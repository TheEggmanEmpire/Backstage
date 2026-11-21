package com.eggman.backstage.shell

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.SurfaceScope
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.ui.settings.SettingsStores
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * "Is Backstage on the phone screen?" for the whole process, and the [ShellPresence] rules applied to the desktop host.
 *
 * Counts this app's started activities on the phone's own display (Display.DEFAULT_DISPLAY): Dextop's Home
 * (MainActivity), the Agent tab (AgentScreenActivity) and short-lived helpers. Activities on the desktop display (Dextop's
 * desktop window, PlasmaDesktopActivity) never count, so ProcessLifecycleOwner (which would see them) is not used.
 * Leaving is confirmed only after [LEAVE_DELAY_MS] without a started activity: switching between Home and the Agent tab,
 * the launcher trampoline and configuration changes stop one activity just before another starts.
 * Activities marked [OverThePhone] (the popup's chat panel) are not "the app": they float over whatever the phone shows,
 * so opening or closing them never moves the desktop.
 */
object ShellLifecycle {
    /** Marker: this activity shows over the phone's current app and does not count as Backstage being opened. */
    interface OverThePhone

    private const val TAG = "BackstageShell"
    const val LEAVE_DELAY_MS = 700L
    /** How long after the popup's "Open Backstage" tap the app's arrival still counts as that opening. */
    const val KEEP_ARM_MS = 10_000L

    private val _visible = MutableStateFlow(false)
    /** True while one of this app's activities is visible on the phone display. */
    val visible: StateFlow<Boolean> = _visible

    val homePreview = HomePreviewLease()

    private val started = HashSet<Int>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var app: Context? = null
    private var registeredHost: DesktopHost? = null
    private var lastPresentation: Presentation = Presentation.NONE
    private val leave = Runnable { if (started.isEmpty()) setVisible(false) }

    /** Until when (uptime ms) the next "app visible" keeps the desktop in the background ([openAppKeepingBackground]). */
    @Volatile private var keepArmedUntil = 0L
    private val _keepBackground = MutableStateFlow(false)
    /**
     * True while the app is opened from the popup's "Open Backstage" button and the desktop stays in the background
     *. Home may borrow HIDDEN for a visible preview and restores it afterwards; Chat owns its player. Ends
     * when the user shows the desktop in the app (or full screen) or leaves the app; leaving then follows the normal
     * pop-out rule.
   */
    val keepBackground: StateFlow<Boolean> = _keepBackground

    // ---- Phone mode: the Phone chip or a task working on the phone keeps DeX in the background ----
    @Volatile private var chip: Target = Target.DESKTOP
    @Volatile private var runningTarget: Target? = null
    private var chipKnown = false
    private var stateFollowed = false
    private var stateJob: kotlinx.coroutines.Job? = null
    private val _phoneMode = MutableStateFlow(false)
    /**
     * True while the app does not use DeX ([ShellPresence.phoneMode]): the current target (chip) is Phone, or the running
     * task works on the phone. The desktop stays in the background; the chat hides its player; the popup does not pop out.
   */
    val phoneMode: StateFlow<Boolean> = _phoneMode

    /** A chip is picked (in-app chat, popup chat, Settings' default target: [com.eggman.backstage.entry.CurrentTarget]). */
    fun onTargetChosen(target: Target) {
        runCatching {
            main.post {
                chipKnown = true
                if (chip != target) {
                    chip = target
                    updatePhoneMode(ShellPresence.Cause.CHIP)
                }
            }
        }
    }

    private fun onAgentState(state: AgentState) {
        val now = ShellPresence.runningTarget(state.current)
        main.post {
            if (now == runningTarget) return@post
            runningTarget = now
            updatePhoneMode(if (now == null) ShellPresence.Cause.TASK_ENDED else ShellPresence.Cause.TASK_TARGET)
        }
    }

    /** Main thread. Applies [ShellPresence.onPhoneModeChanged] when phone mode switches. */
    private fun updatePhoneMode(cause: ShellPresence.Cause) {
        val on = ShellPresence.phoneMode(chip, runningTarget)
        if (on == _phoneMode.value) return
        _phoneMode.value = on
        if (on) { keepArmedUntil = 0L; _keepBackground.value = false }
        app?.let { runCatching { com.eggman.backstage.notify.SessionNotification.render(it) } }
        val host = AgentAccess.host() ?: return
        val context = app ?: return
        val settings = runCatching { SettingsStores.get(context) }.getOrNull() ?: return
        val presentation = runCatching { if (host.sessionActive()) host.presentation() else Presentation.NONE }
            .getOrDefault(Presentation.NONE)
        apply(
            ShellPresence.onPhoneModeChanged(presentation, on, cause, _visible.value, settings.popOutOnLeave, sheet),
            "phone_mode_${if (on) "on" else "off"}",
        )
    }

    // ---- The chat sheet: the bubble's chat shows the DeX popup next to it ----
    /** The chat sheet on screen (main thread), and where it shows notices ("DeX desktop not started: …"). */
    private var sheet = ShellPresence.Sheet.NONE
    private var sheetNotice: ((String) -> Unit)? = null

    /** The chat sheet started (bubble or popup chat); [notice] shows a line in it. Main thread. */
    fun onSheetShown(fromBubble: Boolean, notice: (String) -> Unit) {
        sheet = if (fromBubble) ShellPresence.Sheet.BUBBLE else ShellPresence.Sheet.POPUP
        sheetNotice = notice
    }

    /** The chat sheet stopped. Main thread. */
    fun onSheetHidden() {
        sheet = ShellPresence.Sheet.NONE
        sheetNotice = null
    }

    /**
     * The user opened the bubble's chat ([ShellPresence.onBubbleChatOpened]): with a DeX/Auto target the desktop shows as
     * the popup (brought up from the background, or started); with the Phone target nothing happens. Returns the line
     * the sheet shows when DeX cannot come, or null. Main thread.
   */
    fun onBubbleChatOpened(context: Context, peek: Boolean, notice: (String) -> Unit = {}): String? {
        val host = AgentAccess.host() ?: return null
        val target = runCatching { SettingsStores.get(context).defaultTarget }.getOrDefault(chip)
        val presentation = runCatching { if (host.sessionActive()) host.presentation() else Presentation.NONE }
            .getOrDefault(Presentation.NONE)
        val opening = ShellPresence.onBubbleChatOpened(
            presentation,
            phoneMode = ShellPresence.phoneMode(target, runningTarget),
            peek = peek,
            canStart = runCatching { host.privilegedAvailable() }.getOrDefault(false),
            locked = com.eggman.backstage.notify.SessionNotification.deviceLocked(context),
            startBlocked = com.eggman.backstage.agent.SessionStop.autoStartBlocked(),
        )
        apply(opening.decision, "bubble_chat", notice)
        return opening.notice
    }

    /** The user showed the desktop in the app themselves ("Show it here"). */
    fun clearKeepBackground() { keepArmedUntil = 0L; _keepBackground.value = false }

    /**
     * The popup's "Open Backstage" button (registered by the glue): the desktop goes to the background and Backstage's
     * Home opens on the phone. Started from the accessibility service (whose overlay is just tapped), so Android allows
     * the activity start from the background.
   */
    fun openAppKeepingBackground(context: Context): Boolean {
        keepArmedUntil = android.os.SystemClock.uptimeMillis() + KEEP_ARM_MS
        AgentAccess.host()?.let { host ->
            SurfaceScope.launch { runCatching { if (host.sessionActive()) host.setPresentation(Presentation.HIDDEN) } }
        }
        val starter: Context = runCatching { com.eggman.backstage.a11y.AgentAccessibility.instance?.service }.getOrNull() ?: context
        val launch = starter.packageManager.getLaunchIntentForPackage(starter.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: return false
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        return runCatching { starter.startActivity(launch, options.toBundle()) }
            .onFailure { Log.w(TAG, "opening Backstage from the popup failed", it) }.isSuccess
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (!countsAsApp(activity)) return
            started += System.identityHashCode(activity)
            main.removeCallbacks(leave)
            setVisible(true)
        }

        override fun onActivityStopped(activity: Activity) {
            if (!started.remove(System.identityHashCode(activity)) || started.isNotEmpty()) return
            main.removeCallbacks(leave)
            main.postDelayed(leave, LEAVE_DELAY_MS)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (started.remove(System.identityHashCode(activity)) && started.isEmpty()) {
                main.removeCallbacks(leave)
                main.postDelayed(leave, LEAVE_DELAY_MS)
            }
        }
    }

    private val hostListener = object : DesktopHost.Listener {
        override fun onDesktopChanged(geometry: DesktopHost.Geometry?, presentation: Presentation) {
            val now = if (geometry == null) Presentation.NONE else presentation
            homePreview.onPresentationChanged(now)
            val previous = lastPresentation
            lastPresentation = now
            // Shown in the app or full screen by the user: the one-shot background rule is over.
            if (now == Presentation.IN_APP || now == Presentation.FULL) { keepArmedUntil = 0L; _keepBackground.value = false }
            if (previous == Presentation.NONE && now != Presentation.NONE) {
                apply(ShellPresence.onSessionStarted(now, _visible.value, _phoneMode.value), "session_started")
            }
        }
    }

    /** Tests: forget the Application and host of an earlier test (Robolectric keeps this object across tests). */
    @Synchronized
    internal fun resetForTests() {
        (app as? Application)?.unregisterActivityLifecycleCallbacks(callbacks)
        app = null
        registeredHost?.removeListener(hostListener)
        registeredHost = null
        started.clear()
        homePreview.onPresentationRequested()
        main.removeCallbacks(leave)
        _visible.value = false
        lastPresentation = Presentation.NONE
        keepArmedUntil = 0L
        _keepBackground.value = false
        chip = Target.DESKTOP
        runningTarget = null
        chipKnown = false
        _phoneMode.value = false
        sheet = ShellPresence.Sheet.NONE
        sheetNotice = null
        // Follow the next test's controller, not the first one's.
        stateJob?.cancel()
        stateJob = null
        stateFollowed = false
    }

    /** Process start (SurfacesInitProvider). Idempotent. */
    @Synchronized
    fun start(context: Context) {
        val application = context.applicationContext as? Application ?: return
        if (app == null) {
            app = application
            application.registerActivityLifecycleCallbacks(callbacks)
        }
        if (!chipKnown) {
            chip = runCatching { SettingsStores.get(application).defaultTarget }.getOrDefault(Target.DESKTOP)
            _phoneMode.value = ShellPresence.phoneMode(chip, runningTarget)
        }
        if (!stateFollowed) {
            stateFollowed = true
            stateJob = SurfaceScope.launch {
                while (AgentAccess.controller() == null) delay(500)
                AgentAccess.state().collect(::onAgentState)
            }
        }
        val host = AgentAccess.host() ?: return
        if (registeredHost === host) return
        registeredHost = host
        lastPresentation = runCatching { if (host.sessionActive()) host.presentation() else Presentation.NONE }
            .getOrDefault(Presentation.NONE)
        host.addListener(hostListener)
    }

    /** Started activities that make Backstage "visible": this app's own screens on the phone display. */
    fun countsAsApp(activity: Activity): Boolean = activity !is OverThePhone && onPhoneDisplay(activity)

    private fun onPhoneDisplay(activity: Activity): Boolean =
        runCatching { activity.display?.displayId ?: Display.DEFAULT_DISPLAY }.getOrDefault(Display.DEFAULT_DISPLAY) ==
            Display.DEFAULT_DISPLAY

    private fun setVisible(value: Boolean) {
        if (_visible.value == value) return
        _visible.value = value
        val context = app ?: return
        val host = AgentAccess.host() ?: return
        val settings = runCatching { SettingsStores.get(context) }.getOrNull() ?: return
        val presentation = runCatching { if (host.sessionActive()) host.presentation() else Presentation.NONE }
            .getOrDefault(Presentation.NONE)
        val decision = if (value) {
            if (android.os.SystemClock.uptimeMillis() < keepArmedUntil) { keepArmedUntil = 0L; _keepBackground.value = true }
            ShellPresence.onAppVisible(presentation, settings.startDesktopOnOpen, canStart = true,
                keepBackground = _keepBackground.value, phoneMode = _phoneMode.value)
        } else {
            _keepBackground.value = false
            ShellPresence.onAppLeft(presentation, settings.popOutOnLeave, phoneMode = _phoneMode.value,
                keepHidden = homePreview.keepHiddenOnLeave)
        }
        apply(decision, if (value) "app_visible" else "app_left")
    }

    private fun apply(decision: ShellPresence.Decision, reason: String, notice: ((String) -> Unit)? = sheetNotice) {
        val host = AgentAccess.host() ?: return
        when (decision) {
            ShellPresence.Decision.Nothing -> Unit
            // Off the main thread: the host waits for MirrorService (main thread) to apply it.
            is ShellPresence.Decision.Present -> SurfaceScope.launch {
                val ok = runCatching { host.sessionActive() && host.setPresentation(decision.presentation) }.getOrDefault(false)
                Log.i(TAG, "$reason: desktop -> ${decision.presentation} applied=$ok")
            }
            ShellPresence.Decision.StartInApp -> SurfaceScope.launch {
                // Only with working shell access: without it the start would just fail (Dextop's setup comes first).
                if (!runCatching { host.privilegedAvailable() }.getOrDefault(false) || host.sessionActive()) return@launch
                val geometry = runCatching { host.startDesktop(Presentation.IN_APP) }.getOrNull()
                Log.i(TAG, "$reason: desktop start in the app -> ${geometry != null}")
            }
            ShellPresence.Decision.StartMini -> {
                val context = app
                val locked = context != null && com.eggman.backstage.notify.SessionNotification.deviceLocked(context)
                val privileged = runCatching { host.privilegedAvailable() }.getOrDefault(false)
                when {
                    com.eggman.backstage.agent.SessionStop.autoStartBlocked() -> Unit
                    locked -> notice?.invoke(ShellPresence.SHEET_LOCKED)
                    !privileged -> notice?.invoke(ShellPresence.SHEET_NO_ACCESS)
                    else -> SurfaceScope.launch {
                        if (host.sessionActive()) return@launch
                        val geometry = runCatching { host.startDesktop(Presentation.MINI) }.getOrNull()
                        Log.i(TAG, "$reason: desktop start as the popup -> ${geometry != null}")
                        if (geometry == null) main.post { notice?.invoke(ShellPresence.SHEET_START_FAILED) }
                        else if (context != null && runCatching { com.eggman.backstage.data.Settings(context).mayAutoEnableAccessibility }.getOrDefault(false)) {
                            // Dextop may rewrite the accessibility list while starting (as for the agent's own starts).
                            com.eggman.backstage.a11y.AccessibilityAutoEnable.reassertSoon(context, delayMs = 600)
                        }
                    }
                }
            }
        }
    }
}
