package moe.n4tsu.dextop

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.eggman.backstage.host.AgentActivityLauncher
import com.eggman.backstage.host.DesktopFocusPolicy
import com.eggman.backstage.host.DesktopCapabilities
import com.eggman.backstage.host.DesktopDisplayPolicy
import com.eggman.backstage.host.DesktopGlobalActions
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostInput
import com.eggman.backstage.privileged.BoundedShell
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.host.DesktopInputMath
import com.eggman.backstage.host.DesktopIntentPolicy
import com.eggman.backstage.host.DesktopLaunchPolicy
import com.eggman.backstage.host.DesktopStartProfile
import com.eggman.backstage.host.DesktopTyping
import com.eggman.backstage.host.FrameHandoff
import com.eggman.backstage.host.LockedModeStatus
import com.eggman.backstage.host.PresentationNames
import com.eggman.backstage.host.Reconnecting
import com.eggman.backstage.host.ShellPayloads
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.n4tsu.dextop.plasma.TaskBridge
import moe.n4tsu.dextop.plasma.TaskSnapshot
import moe.n4tsu.dextop.privilege.BackstagePrivilegeFallback
import moe.n4tsu.dextop.privilege.DistributionPrivilegeRuntime
import moe.n4tsu.dextop.privilege.EmbeddedPrivilegeKeeper
import rikka.shizuku.Shizuku
import com.eggman.backstage.privileged.ProviderSnapshotCache
import com.eggman.backstage.privileged.PrivilegeProviders
import com.eggman.backstage.privileged.ShizukuSetup

/**
 * Backstage glue compiled INTO Dextop's:app module (sees `internal` Dextop classes). Implements the library's
 * [DesktopHost] contract on top of PrivilegedAccess, InputDispatcher, the MirrorService companion and the
 * MirrorService.Backstage presentation API added by patches/0001.
 *
 * Registered by the library's BackstageInitProvider, which calls [install] reflectively at process start.
 */
object BackstageDesktopBridge : DesktopHost, DesktopHostExtras {
    private const val TAG = "BackstageBridge"
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val PRESENTATION_WAIT_MS = 3_000L
    private const val EXTRA_CLOSE_AFTER_START = "app.freedextop.extra.CLOSE_AFTER_START"
    private const val PRIVILEGE_PREFS = PrivilegeProviders.PREFERENCES
    private const val RICH_PROFILE = "rich"
    private const val NONE_PROFILE = "none"
    private const val RESTART_STOP_WAIT_MS = 8_000L
    private const val DESKTOP_WAKE_TAG = "backstage:desktop-awake"
    private const val TURN_SCREEN_ON_OP = "android:turn_screen_on"
    private const val TURN_SCREEN_ON_PERMISSION = "android.permission.TURN_SCREEN_ON"
    private const val WINDOWING_MODE_PINNED = 2
    private data class ProviderSnapshot(val packages: List<String>, val saved: String?, val lastMode: String?)
    private val providerSnapshots = ProviderSnapshotCache<ProviderSnapshot>()
    private var lastBinderSnapshot: ProviderSnapshot? = null
    private fun providerSnapshot(context: Context): ProviderSnapshot = providerSnapshots.get {
        val prefs = context.getSharedPreferences(PRIVILEGE_PREFS, Context.MODE_PRIVATE)
        ProviderSnapshot(ShizukuSetup.installedPackages(context), prefs.getString("selected", null), prefs.getString("last_mode", null))
    }
    private val stellarBinderRetry = StellarBinderRetryGate()
    @Volatile private var stellarBinderRequested = false
    /** When the phone gets its focus back after desktop input: the platform moves the focus asynchronously. */
    private val POINTER_FOCUS_RETURN_MS = longArrayOf(120L, 450L)
    private val LAUNCH_FOCUS_RETURN_MS = longArrayOf(250L, 900L, 2_000L)
    /** A SHARED-focus desktop needs the top focus before key events; the switch is asynchronous. */
    private const val DESKTOP_FOCUS_SETTLE_MS = 80L

    @Volatile private var appContext: Context? = null
    private val installed = AtomicBoolean(false)
    /**
     * Hidden-API exemption (InputEvent.setDisplayId, IInputManager, IActivityTaskManager...). MirrorService only
     * adds it in onServiceConnected; the agent may run before any desktop session. Process-wide and idempotent.
   */
    private val access by lazy {
        runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("") }
        PrivilegedAccess(TAG)
    }
    /**
     * InputDispatcher caches its IInputManager proxy, which with Dextop's embedded runtime is bound to one runtime
     * binder: after the runtime reconnects (Wi-Fi change, adbd restart) that proxy fails for good. A send that FAILED
     * (exception, not a plain refusal) rebuilds the dispatcher once and is retried.
   */
    private val lastInjectFailure = ThreadLocal<Throwable?>()
    private val dispatchers = Reconnecting {
        InputDispatcher(access) { _, _, _, failure -> lastInjectFailure.set(failure) }
    }
    private val taskBridge by lazy { TaskBridge({ name, iface -> access.service(name, iface) }, TAG) }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Gives the phone its focus back shortly after desktop input, off the input lane. */
    private val focusExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Backstage-phone-focus").apply { isDaemon = true }
    }
    private val shizukuListenersInstalled = AtomicBoolean(false)
    @Volatile private var desktopCompatibilityFallback: String? = null
    @Volatile private var desktopProfileFallback: String? = null

    /** One ordered lane for every injected event: streams (swipes, chords) never interleave. */
    internal val inputExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Backstage-desktop-input").apply { isDaemon = true }
    }
    private val inputLane = inputExecutor.asCoroutineDispatcher()
    private val ioExecutor = Executors.newCachedThreadPool { task ->
        Thread(task, "Backstage-desktop-io").apply { isDaemon = true }
    }
    private val ioLane = ioExecutor.asCoroutineDispatcher()

    private val listeners = CopyOnWriteArrayList<DesktopHost.Listener>()
    private val sessionListenerRegistered = AtomicBoolean(false)
    private val sessionListener = MirrorService.Backstage.SessionListener { displayId, width, height, dpi, presentation ->
        val geometry = DesktopDisplayPolicy.geometry(displayId, width, height, dpi)
        val mapped = if (geometry == null) DesktopHost.Presentation.NONE else PresentationNames.parse(presentation)
        listeners.forEach { listener ->
            runCatching { listener.onDesktopChanged(geometry, mapped) }
                .onFailure { Log.w(TAG, "desktop listener failed", it) }
        }
    }

    // ---- registration ------------------------------------------------------------------------------------------

    /**
     * Called reflectively by the library's init provider. Idempotent and cheap: no binder calls, no threads started.
     * Turns on the direct owned desktop display for Dextop sessions of this (Backstage) build.
   */
    @JvmStatic
    fun install(context: Context) {
        appContext = context.applicationContext ?: context
        if (!installed.compareAndSet(false, true)) return
        MirrorService.Backstage.directDesktopDefault = true
        // Family is an ordering hint. Refusals and the created Display.flags remain authoritative on every vendor.
        MirrorService.Backstage.displayFlagPolicy = { bases, extras ->
            desktopCompatibilityFallback = null
            val profile = compatibilityProfile()
            Log.i(TAG, "desktop compatibility: ${profile.description}")
            DesktopCapabilities.flagLadder(profile, bases, extras)
        }
        MirrorService.Backstage.displayFlagRejected = { flags, error ->
            val cause = generateSequence(error) { it.cause }.last()
            desktopCompatibilityFallback = "Direct display flags 0x${flags.toString(16)} refused: " +
                (cause.message ?: cause.javaClass.simpleName).take(200)
        }
        // Stellar first, then Shizuku; the stored embedded pairing serves while neither is usable (patches/0003).
        BackstagePrivilegeFallback.active = true
        // Home's AI destination and live desktop player (patches/0005).
        BackstageShell.install()
        // The popup's chat button opens the library's chat panel (the popup stays; ShellLifecycle ignores the panel).
        val app = appContext
        MirrorService.Backstage.setMiniChatHandler {
            app?.let { com.eggman.backstage.ui.minichat.MiniChatActivity.open(it) }
        }
        // The popup's "Open Backstage": Home on the phone, the desktop in the background (not pulled in).
        MirrorService.Backstage.setMiniAppHandler {
            app?.let { com.eggman.backstage.shell.ShellLifecycle.openAppKeepingBackground(it) }
        }
        // The popup's X stops the agent first, then the desktop, preventing the task from restarting it.
        MirrorService.Backstage.setMiniStopHandler {
            com.eggman.backstage.agent.SessionStop.stopAgentAndDesktop(
                com.eggman.backstage.entry.AgentAccess.controller(), this,
            )
        }
        // The app's ONE accessibility service: MirrorService also serves the agent (patches/0001 hands over its
        // connection, every event and its disconnection; the library's AccessibilityBridge does the rest).
        if (BuildConfig.DISTRIBUTION_CHANNEL == "github") BackstageAccessibilityClient.register(context)
        DesktopHosts.current = this
    }

    // ---- privilege ---------------------------------------------------------------------------------------------

    /** The shared external binder needs permission; installation alone never grants shell access. */
    private fun privileged(embedded: Boolean = DistributionPrivilegeRuntime.available): Boolean =
        if (embedded) true else PrivilegeProviders.usable(false,
            runCatching { Shizuku.pingBinder() }.getOrDefault(false),
            runCatching { !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false))

    private fun externalUsable(): Boolean = privileged(embedded = false)

    override fun privilegedAvailable(): Boolean {
        installShizukuListeners()
        requestPreferredBinder()
        return privileged()
    }

    /**
     * Uses Dextop's existing Stellar recovery broadcast and retry gate. Stellar still needs its Shizuku
     * compatibility layer: no native Stellar client or extra provider is added to either APK flavor.
   */
    @Synchronized private fun requestPreferredBinder() {
        val context = appContext ?: return
        val snapshot = providerSnapshot(context)
        if (snapshot === lastBinderSnapshot) return
        lastBinderSnapshot = snapshot
        val selected = PrivilegeProviders.choice(snapshot.packages, PrivilegeProviders.PREFERRED,
            snapshot.saved, snapshot.lastMode, false, false).selected
        if (selected != PrivilegeProviders.STELLAR_PACKAGE) {
            stellarBinderRequested = false
            return
        }
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!PrivilegeProviders.requestStellar(selected, alive, stellarBinderRequested)) return
        if (stellarBinderRetry.acquire(android.os.SystemClock.elapsedRealtime()) == null) return
        // Do not clear a usable Shizuku binder while waiting: it is the second rung if Stellar is stopped or
        // compatibility is off. A Stellar reply uses the very same provider receiver Dextop already ships.
        stellarBinderRequested = true
        runCatching {
            context.sendBroadcast(Intent(PrivilegeProviders.STELLAR_REQUEST_BINDER).setPackage(selected))
        }.onFailure { Log.w(TAG, "Stellar binder request failed", it) }
    }


    /** Keep a saved choice; fresh setup prefers Stellar, with Shizuku and embedded access as alternatives. */
    private fun installShizukuListeners() {
        if (appContext == null || !shizukuListenersInstalled.compareAndSet(false, true)) return
        val onChange = {
            providerSnapshots.invalidate()
            usePrivilegeProvider(PrivilegeProviders.PREFERRED)
            Unit
        }
        runCatching {
            Shizuku.addRequestPermissionResultListener { _, _ -> onChange() }
            Shizuku.addBinderReceivedListenerSticky { onChange() }
            Shizuku.addBinderDeadListener { onChange() }
        }.onFailure { Log.w(TAG, "Stellar/Shizuku listeners unavailable", it) }
        onChange()
    }

    override suspend fun shell(vararg argv: String): DesktopHost.ShellResult =
        shell(*argv, timeoutMs = 15_000, maxOutputBytes = BoundedShell.DEFAULT_MAX_OUTPUT_BYTES)

    override suspend fun shell(vararg argv: String, timeoutMs: Long, maxOutputBytes: Int): DesktopHost.ShellResult {
        // Upstream transports read stdout, then stderr. This wrapper drains both into capped
        // files concurrently and kills the command's process group at its deadline.
        val script = BoundedShell.script(argv.toList(), timeoutMs, maxOutputBytes)
        // Binder cannot be interrupted in flight: release the coroutine immediately on Stop.
        // The remote wrapper still owns cleanup and its deadline, even after cancellation.
        return suspendCancellableCoroutine { continuation ->
            ioExecutor.execute {
                if (!continuation.isActive) return@execute
                val result = runCatching {
                    val raw = access.execute("sh", "-c", script)
                    DesktopHost.ShellResult(raw.exitCode, raw.output, raw.error)
                }
                continuation.resumeWith(result)
            }
        }
    }

    override fun systemService(name: String, aidlInterface: String): Any? =
        runCatching { access.service(name, aidlInterface) }
            .onFailure { Log.w(TAG, "system service $name unavailable", it) }
            .getOrNull()

    /**
     * Extra (not in the contract): reconnect Dextop's embedded wireless-ADB runtime when it is paired but not
     * running (after a reboot nothing reconnects without UI). [done] runs on the main thread.
     * With Stellar/Shizuku selected but not usable (stopped after a reboot, or not granted) the embedded runtime is the
     * fallback: Dextop switched it off when the external provider is selected, so it is switched back on first.
   */
    fun ensurePrivilege(reason: String = "backstage", done: ((Boolean) -> Unit)? = null) {
        val context = appContext ?: run {
            done?.invoke(privileged())
            return
        }
        if (privileged()) {
            done?.let { callback -> main.post { callback(true) } }
            return
        }
        if (!DistributionPrivilegeRuntime.enabled && BackstagePrivilegeFallback.useEmbedded(context)) {
            DistributionPrivilegeRuntime.enabled = true
            Log.i(TAG, "External provider unusable: embedded wireless-debugging runtime enabled as the fallback")
        }
        EmbeddedPrivilegeKeeper.ensure(context, reason) { outcome ->
            done?.invoke(outcome == EmbeddedPrivilegeKeeper.Outcome.CONNECTED)
        }
    }

    // ---- desktop session ---------------------------------------------------------------------------------------

    override fun sessionActive(): Boolean = geometry() != null

    override fun geometry(): DesktopHost.Geometry? {
        // An Android Auto session drives the car screen, never an agent target.
        if (MirrorService.isAutoOnlySessionActive()) return null
        val raw = MirrorService.activeDisplayGeometry() ?: return null
        // The desktop is always a created display; the phone's own display (0) is never an injection target.
        if (raw.size < 4) return null
        return DesktopDisplayPolicy.geometry(raw[0], raw[1], raw[2], raw[3])
    }

    override fun directDisplay(): Boolean = MirrorService.Backstage.isDirectDisplay()

    override suspend fun startDesktop(presentation: DesktopHost.Presentation, timeoutMs: Long): DesktopHost.Geometry? {
        geometry()?.let { current ->
            if (presentation != DesktopHost.Presentation.NONE && this.presentation() != presentation) {
                setPresentationAwait(presentation)
            }
            return current
        }
        val context = appContext ?: return null
        if (MirrorService.isAutoOnlySessionActive()) return null
        if (!privileged()) awaitPrivilege(minOf(timeoutMs, 10_000L))
        val wanted = presentation.takeUnless { it == DesktopHost.Presentation.NONE } ?: DesktopHost.Presentation.FULL
        MirrorService.Backstage.requestPresentationOnStart(PresentationNames.wireName(wanted))
        val failed = AtomicReference<String?>(null)
        // A background desktop (MINI/HIDDEN) starts headlessly: Dextop's activity never comes to the front, so the app
        // the user is in keeps its focus and keyboard, and patches/0001 leaves the phone's rotation and navigation
        // alone. FULL is the user asking for the desktop on the screen: Dextop's own START_DISPLAY (activity in front).
        // START_DISPLAY toggles: never send it while a session is already starting (active, no display yet).
        val launched = MirrorService.isActive() ||
            if (wanted == DesktopHost.Presentation.FULL) launchStartDisplayIntent(context)
            else launchHeadless(context) { error -> failed.set(error) }
        if (!launched) {
            MirrorService.Backstage.requestPresentationOnStart(null)
            return null
        }
        val started = withTimeoutOrNull(timeoutMs.coerceAtLeast(1_000L)) {
            var current = geometry()
            while (current == null && failed.get() == null) {
                delay(250L)
                current = geometry()
            }
            current
        }
        failed.get()?.let { Log.w(TAG, "background desktop start failed: $it") }
        if (started == null) {
            MirrorService.Backstage.requestPresentationOnStart(null)
            Log.w(TAG, "desktop did not start within ${timeoutMs}ms")
            return null
        }
        if (wanted != DesktopHost.Presentation.FULL) {
            // The pending presentation is applied shortly after the display attaches.
            withTimeoutOrNull(PRESENTATION_WAIT_MS) {
                while (this@BackstageDesktopBridge.presentation() != wanted) delay(100L)
            } ?: setPresentationAwait(wanted)
        }
        return geometry() ?: started
    }

    /**
     * Dextop's own START_DISPLAY shortcut: Flutter starts the session with the user's saved profile.
     * The privileged shell is preferred: a background `startActivity` can be dropped silently by the
     * background-activity-launch rules (no exception), while `am start` as shell is exempt.
   */
    private suspend fun launchStartDisplayIntent(context: Context): Boolean {
        if (privileged()) {
            val result = shell(
                "am", "start",
                "-a", DextopShortcuts.ACTION_START_DISPLAY,
                "-n", "${context.packageName}/${MainActivity::class.java.name}",
                "-f", (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).toString(),
                "--ez", EXTRA_CLOSE_AFTER_START, "true",
            )
            if (result.ok && !result.output.contains("Error", ignoreCase = true)) return true
            Log.w(TAG, "privileged START_DISPLAY failed: ${result.error.ifBlank { result.output }}")
        }
        return runCatching {
            context.startActivity(DextopShortcuts.startDisplayIntent(context, closeAfterStart = true))
        }.onFailure { Log.w(TAG, "START_DISPLAY refused", it) }.isSuccess
    }

    /**
     * Starts a session without any UI (like the Car Companion relay does), with the display Dextop's home screen
     * would create from the user's saved choices (profile, orientation, workspace magnification, secure display).
     * [onFailure] receives Dextop's refusal, if any. False when no valid display can be derived.
   */
    private suspend fun launchHeadless(context: Context, onFailure: (String) -> Unit): Boolean {
        val spec = runCatching { backgroundStartSpec(context) }
            .onFailure { Log.w(TAG, "cannot derive the desktop profile", it) }.getOrNull() ?: return false
        if (!DesktopStartProfile.valid(spec)) {
            Log.w(TAG, "saved desktop profile out of range: $spec")
            return false
        }
        val prefs = context.getSharedPreferences(MirrorService.Backstage.FLUTTER_PREFS, Context.MODE_PRIVATE)
        val secure = runCatching { prefs.getBoolean("flutter.secure_display", false) }.getOrDefault(false)
        val decorations = !android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val posted = CompletableDeferred<Boolean>()
        main.post {
            val accepted = runCatching {
                MirrorService.launch(context, spec.width, spec.height, spec.density, secure, decorations) { outcome ->
                    outcome.onFailure { onFailure(it.message ?: it.javaClass.simpleName) }
                }
            }.onFailure { onFailure(it.message ?: it.javaClass.simpleName) }.isSuccess
            posted.complete(accepted)
        }
        Log.i(TAG, "headless desktop start ${spec.width}x${spec.height}/${spec.density} secure=$secure decorations=$decorations")
        return posted.await()
    }

    /** Port of Dextop's own start computation (DesktopStartProfile) over its Flutter preferences. */
    private fun backgroundStartSpec(context: Context): DesktopStartProfile.Spec {
        desktopProfileFallback = null
        val prefs = context.getSharedPreferences(MirrorService.Backstage.FLUTTER_PREFS, Context.MODE_PRIVATE)
        val all = prefs.all
        val selected = all["flutter.selected_resolution_id"] as? String ?: "device"
        val metrics = android.util.DisplayMetrics()
        val phone = context.getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        phone?.getRealMetrics(metrics)
        val fallback = context.resources.displayMetrics
        val device = DesktopStartProfile.device(
            metrics.widthPixels.takeIf { it > 0 } ?: fallback.widthPixels,
            metrics.heightPixels.takeIf { it > 0 } ?: fallback.heightPixels,
            metrics.density.takeIf { it > 0f } ?: fallback.density,
        )
        val profile = if (selected == "device") device else {
            ResolutionRepository(context, TAG).profiles(device.density)
                .firstOrNull { !it.device && it.id == selected }
                ?.let { DesktopStartProfile.Spec(it.width, it.height, it.density) } ?: device
        }
        val phonePortrait = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val portrait = DesktopStartProfile.portrait(
            all["flutter.home_orientation_mode"] as? String, all["flutter.home_portrait"] as? Boolean, phonePortrait,
        )
        // Flutter stores ints as Long.
        val magnification = (all["flutter.desktop_workspace_magnification_percent"] as? Number)?.toInt() ?: 100
        val magnifyWindows = all["flutter.desktop_magnify_windows_with_display"] as? Boolean ?: false
        if (!DesktopStartProfile.valid(profile)) {
            desktopProfileFallback = "Phone/saved metrics ${profile.width}x${profile.height}/${profile.density} " +
                "are outside desktop bounds; using a bounded desktop profile."
        }
        return DesktopStartProfile.compute(profile, portrait, magnification, magnifyWindows)
    }

    /** Physical external displays only: an agent desktop or recording mirror is not evidence of a monitor. */
    private fun compatibilityProfile(): DesktopCapabilities.Profile {
        val context = appContext
        val external = runCatching {
            context?.getSystemService(DisplayManager::class.java)?.displays?.count { display ->
                display.displayId != Display.DEFAULT_DISPLAY &&
                    Display::class.java.getMethod("getType").invoke(display) == 2 // Display.TYPE_EXTERNAL (@hide)
            } ?: 0
        }.getOrDefault(0)
        return DesktopCapabilities.detect(DesktopCapabilities.Facts(
            android.os.Build.MANUFACTURER, android.os.Build.MODEL, android.os.Build.VERSION.SDK_INT,
            runCatching { context?.packageManager?.hasSystemFeature("android.software.freeform_window_management") == true }
                .getOrDefault(false),
            external,
        ))
    }

    private suspend fun awaitPrivilege(timeoutMs: Long) {
        val outcome = AtomicReference<Boolean?>(null)
        main.post { ensurePrivilege("backstage_start") { outcome.set(it) } }
        withTimeoutOrNull(timeoutMs) {
            while (outcome.get() == null && !privileged()) delay(200L)
        }
    }

    override fun stopDesktop() {
        main.post { MirrorService.stopActive() }
    }

    override fun presentation(): DesktopHost.Presentation =
        if (geometry() == null) DesktopHost.Presentation.NONE
        else PresentationNames.parse(MirrorService.Backstage.presentation())

    override fun setPresentation(presentation: DesktopHost.Presentation): Boolean {
        if (presentation == DesktopHost.Presentation.NONE || geometry() == null) return false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            com.eggman.backstage.shell.ShellLifecycle.homePreview.onPresentationRequested()
        } else main.post { com.eggman.backstage.shell.ShellLifecycle.homePreview.onPresentationRequested() }
        val mode = PresentationNames.wireName(presentation)
        if (Looper.myLooper() == Looper.getMainLooper()) return MirrorService.Backstage.setPresentation(mode)
        val result = AtomicReference(false)
        val latch = CountDownLatch(1)
        val accepted = MirrorService.Backstage.setPresentation(mode) { applied ->
            result.set(applied)
            latch.countDown()
        }
        if (!accepted) return false
        latch.await(PRESENTATION_WAIT_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private suspend fun setPresentationAwait(presentation: DesktopHost.Presentation): Boolean =
        withContext(ioLane) { setPresentation(presentation) }

    override fun addListener(listener: DesktopHost.Listener) {
        listeners.addIfAbsent(listener)
        if (sessionListenerRegistered.compareAndSet(false, true)) {
            MirrorService.Backstage.addSessionListener(sessionListener)
        } else {
            val geometry = geometry()
            main.post { listener.onDesktopChanged(geometry, presentation()) }
        }
    }

    override fun removeListener(listener: DesktopHost.Listener) {
        listeners.remove(listener)
    }

    /** One-line agent status shown as the MINI caption (null hides it). */
    override fun setMiniStatus(text: String?) = MirrorService.Backstage.setMiniStatus(text)

    /** "rounded" | "circle": persisted by MirrorService.Backstage, applied at once when MINI is showing. */
    override fun setMiniShape(shape: String): Boolean {
        val context = appContext ?: return false
        return MirrorService.Backstage.setMiniShape(context, shape)
    }

    /** The popup's chat panel: the MINI tile keeps clear of its top edge (null: closed). */
    override fun setMiniPanelTop(top: Int?) = MirrorService.Backstage.setMiniPanelTop(top)

    /** The agent chat's live player: shares MirrorService's player slot with Home's (BackstageShell.playerOwner). */
    override fun setChatPlayerSurface(surface: android.view.Surface?): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val owner = BackstageShell.playerOwner
        return runCatching {
            if (surface != null) owner.chatAttached(surface) else owner.chat?.let(owner::chatDetached)
            true
        }.getOrDefault(false)
    }

    /** A touch on the agent chat's player, in desktop pixels: forwarded like Home's player touches. */
    override fun playerTouch(action: Int, x: Float, y: Float): Boolean =
        runCatching { BackstageShell.chatTouch(action, x, y) }.getOrDefault(false)

    override fun miniShape(): String =
        appContext?.let { MirrorService.Backstage.miniShape(it) } ?: MirrorService.Backstage.SHAPE_ROUNDED

    /**
     * Records the same package/mode as Dextop's picker. "preferred" preserves an explicit saved choice,
     * otherwise Stellar wins. Installed-but-stopped/denied providers never disable a working embedded runtime.
   */
    @Synchronized override fun usePrivilegeProvider(provider: String): Boolean {
        if (provider !in setOf(PrivilegeProviders.PREFERRED, PrivilegeProviders.STELLAR, PrivilegeProviders.SHIZUKU)) return false
        val context = appContext ?: return false
        // Explicit setup selections see installations immediately; background probes share a five-second snapshot.
        if (provider != PrivilegeProviders.PREFERRED) providerSnapshots.invalidate()
        val snapshot = providerSnapshot(context)
        val choice = PrivilegeProviders.choice(snapshot.packages, provider, snapshot.saved, snapshot.lastMode,
            externalUsable(), MirrorService.isActive())
        if (choice.selected == null) return false
        if (choice.writePreferences) {
            context.getSharedPreferences(PRIVILEGE_PREFS, Context.MODE_PRIVATE).edit()
                .putString("selected", choice.selected).putString("last_mode", "external").apply()
            providerSnapshots.invalidate()
        }
        if (choice.disableEmbedded) DistributionPrivilegeRuntime.enabled = false
        else if (BackstagePrivilegeFallback.useEmbedded(context)) DistributionPrivilegeRuntime.enabled = true
        requestPreferredBinder()
        return true
    }

    /** "quiet" | "own" | "shared" for the running desktop, null without one. */
    override fun desktopFocusMode(): String? =
        MirrorService.Backstage.desktopFocusMode().takeIf { it != MirrorService.Backstage.NONE }

    /**
     * An app the user has open on the phone is never launched on a background (MINI/HIDDEN) desktop: Android would
     * move its task off the phone. Tasks the user is not looking at may move.
   */
    override fun launchRefusal(packageName: String): String? {
        val presentation = presentation()
        if (!DesktopFocusPolicy.background(presentation) || !privileged()) return null
        val tasks = runCatching { taskBridge.tasks(Display.DEFAULT_DISPLAY) }
            .onFailure { Log.w(TAG, "phone tasks unavailable", it) }.getOrNull() ?: return null
        val phone = tasks.map { DesktopLaunchPolicy.PhoneTask(it.packageName, it.visible) }
        val label = appContext?.let { context ->
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull()
        }
        return DesktopLaunchPolicy.phoneConflict(packageName, phone, presentation, label)
    }

    override fun typingRefusal(text: String): String? = DesktopTyping.refusal(text)

    override fun desktopAccessibilityComponent(): String? =
        appContext?.let { android.content.ComponentName(it, MirrorService::class.java).flattenToString() }

    /** The running desktop is accepted with OWN_DISPLAY_GROUP and ALWAYS_UNLOCKED. */
    override fun desktopRunsWhileLocked(): Boolean = MirrorService.Backstage.desktopRunsWhileLocked()

    override fun runWhileLocked(): Boolean =
        appContext?.let { MirrorService.Backstage.displayFlagsProfile(it) == RICH_PROFILE } == true

    /** Display flag profile "rich" (opt-in) or the default "focus"; applies to the next desktop start. */
    override fun setRunWhileLocked(enabled: Boolean): Boolean {
        val context = appContext ?: return false
        MirrorService.Backstage.setDisplayFlagsProfile(context, if (enabled) RICH_PROFILE else null)
        return true
    }

    /**
     * Locked-mode diagnostics from patches/0001 (flags asked for, accepted and really applied; awake; last fallback),
     * plus whether this app may wake the desktop's own display group.
   */
    override fun lockedModeStatus(): LockedModeStatus? {
        val snapshot = runCatching { MirrorService.Backstage.lockedModeSnapshot() }
            .onFailure { Log.w(TAG, "locked-mode snapshot unavailable", it) }.getOrNull() ?: return null
        val session = snapshot.sessionActive && geometry() != null
        return LockedModeStatus(
            sessionActive = session,
            directDisplay = session && snapshot.direct,
            createdWithOptIn = if (session) snapshot.profile == RICH_PROFILE else null,
            requestedFlags = snapshot.requestedFlags,
            acceptedFlags = snapshot.acceptedFlags,
            effectiveFlags = snapshot.effectiveFlags,
            runsWhileLocked = session && snapshot.runsWhileLocked,
            desktopAwake = if (session) snapshot.desktopAwake else null,
            wakeAllowed = desktopWakeAllowed(),
            hostFallback = snapshot.fallback,
            hostFallbackAtMs = snapshot.fallbackAtMs,
            compatibilityProfile = compatibilityProfile().description,
            displayFallback = listOfNotNull(
                desktopProfileFallback, desktopCompatibilityFallback,
                if (session && !snapshot.direct) "Overlay fallback: desktop screen reading is unavailable; phone control is separate." else null,
            ).joinToString("; ").ifBlank { null },
        )
    }

    /** Stop, wait until MirrorService let go of the old session, then a fresh start (the flags apply at creation). */
    override suspend fun restartDesktop(presentation: DesktopHost.Presentation): DesktopHost.Geometry? {
        if (MirrorService.isAutoOnlySessionActive()) return null
        if (MirrorService.isActive()) {
            main.post { MirrorService.stopActive() }
            withTimeoutOrNull(RESTART_STOP_WAIT_MS) {
                while (MirrorService.isActive()) delay(200L)
            } ?: run {
                Log.w(TAG, "desktop restart: the old session did not stop in time")
                return null
            }
        }
        // MirrorService refuses a start while it is still finishing the previous session: one retry.
        repeat(2) { attempt ->
            delay(if (attempt == 0) 400L else 1_500L)
            startDesktop(presentation)?.let { return it }
        }
        return null
    }

    /**
     * A screen wake lock tied to the desktop display (PowerManager.newWakeLock(level, tag, displayId), hidden API) keeps
     * only that display's group awake, and ACQUIRE_CAUSES_WAKEUP wakes only that group. Refused for a display without
     * its own group: there the lock would hold (and turn on) the phone's own screen.
   */
    override fun holdDesktopAwake(displayId: Int, timeoutMs: Long): AutoCloseable? {
        val context = appContext ?: return null
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        if (display.flags and BackstageDisplayFlags.DISPLAY_OWN_DISPLAY_GROUP == 0) return null
        access // hidden-API exemption
        val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val level = android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
        val lock = runCatching {
            android.os.PowerManager::class.java
                .getMethod("newWakeLock", Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                .invoke(pm, level, DESKTOP_WAKE_TAG, displayId) as android.os.PowerManager.WakeLock
        }.onFailure { Log.w(TAG, "per-display wake lock unavailable", it) }.getOrNull() ?: return null
        lock.setReferenceCounted(false)
        if (runCatching { lock.acquire(timeoutMs.coerceAtLeast(1_000L)) }.onFailure { Log.w(TAG, "desktop wake lock refused", it) }.isFailure) {
            return null
        }
        return AutoCloseable { runCatching { if (lock.isHeld) lock.release() } }
    }

    override fun ensureDesktopWakePermission(): Boolean {
        val context = appContext ?: return false
        if (desktopWakeAllowed() == true) return true
        if (!privileged()) return false
        val result = access.execute("appops", "set", context.packageName, "TURN_SCREEN_ON", "allow")
        if (!result.succeeded) Log.w(TAG, "TURN_SCREEN_ON grant failed: ${result.error.ifBlank { result.output }}")
        return desktopWakeAllowed() == true
    }

    /** ACQUIRE_CAUSES_WAKEUP needs TURN_SCREEN_ON (app-op) for apps targeting Android 14+ on Android 14+. */
    private fun desktopWakeAllowed(): Boolean? {
        val context = appContext ?: return null
        if (android.os.Build.VERSION.SDK_INT < 34) return true
        val ops = context.getSystemService(android.app.AppOpsManager::class.java) ?: return null
        val mode = runCatching {
            ops.unsafeCheckOpNoThrow(TURN_SCREEN_ON_OP, android.os.Process.myUid(), context.packageName)
        }.getOrNull() ?: return null
        return mode == android.app.AppOpsManager.MODE_ALLOWED || (mode == android.app.AppOpsManager.MODE_DEFAULT &&
            context.checkSelfPermission(TURN_SCREEN_ON_PERMISSION) == PackageManager.PERMISSION_GRANTED)
    }

    /** Display flag profile "none" = shared focus; "focus" (default) and "rich" have an own focus. */
    override fun focusModeSetting(): String? = appContext?.let {
        if (MirrorService.Backstage.displayFlagsProfile(it) == NONE_PROFILE) DesktopFocusPolicy.SHARED else DesktopFocusPolicy.OWN
    }

    override fun setFocusModeSetting(mode: String): Boolean {
        val context = appContext ?: return false
        when (mode) {
            DesktopFocusPolicy.SHARED -> MirrorService.Backstage.setDisplayFlagsProfile(context, NONE_PROFILE)
            // Back to the default "focus" profile, unless the user's "rich" (keep running while locked) is already set.
            DesktopFocusPolicy.OWN -> if (MirrorService.Backstage.displayFlagsProfile(context) == NONE_PROFILE) {
                MirrorService.Backstage.setDisplayFlagsProfile(context, null)
            }
            else -> return false
        }
        return true
    }

    /** Extra: direct owned display for future sessions (default on in Backstage builds). */
    fun setDirectDesktopEnabled(enabled: Boolean) {
        appContext?.let { MirrorService.Backstage.setDirectDesktopEnabled(it, enabled) }
    }

    /**
     * Extra: VirtualDisplay flag profile for future sessions: "focus" (default), "none", or "rich" (opt-in only: keeps
     * the desktop unlocked while the phone is locked); null = default.
   */
    fun setDisplayFlagsProfile(profile: String?) {
        appContext?.let { MirrorService.Backstage.setDisplayFlagsProfile(it, profile) }
    }

    // ---- perception --------------------------------------------------------------------------------------------

    /**
     * Desktop frame at desktop resolution: (1) MirrorService.Backstage.latestFrame (background sink in HIDDEN,
     * PixelCopy of the host SurfaceView in FULL/MINI), (2) `screencap -d` through the privileged shell,
     * (3) a one-shot recording mirror of the display into an ImageReader.
   */
    override suspend fun captureFrame(): Bitmap? = withContext(ioLane) {
        val geometry = geometry() ?: return@withContext null
        MirrorService.Backstage.latestFrame()?.let { return@withContext it }
        screencap(geometry)?.let { return@withContext it }
        mirrorOnce(geometry)
    }

    private fun screencap(geometry: DesktopHost.Geometry): Bitmap? {
        if (!privileged()) return null
        val result = access.execute(*ShellPayloads.screencapCommand(geometry.displayId))
        if (!result.succeeded) return null
        val bytes = ShellPayloads.decodeBase64(result.output)?.takeIf(ShellPayloads::isPng) ?: return null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
        // screencap of the wrong display (vendor id semantics) shows up as a size mismatch.
        if (bitmap.width != geometry.width || bitmap.height != geometry.height) {
            Log.w(TAG, "screencap size ${bitmap.width}x${bitmap.height} != desktop ${geometry.width}x${geometry.height}")
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun mirrorOnce(geometry: DesktopHost.Geometry, timeoutMs: Long = 1_200L): Bitmap? {
        val context = appContext ?: return null
        if (!privileged()) return null
        val thread = HandlerThread("Backstage-frame-tap").apply { start() }
        val reader = ImageReader.newInstance(geometry.width, geometry.height, PixelFormat.RGBA_8888, 2)
        val backend = DisplayMirrorBackend(
            context,
            context.contentResolver,
            context.getSystemService(DisplayManager::class.java),
            access,
            DesktopEnvironmentRegistry.current(),
        )
        // The copy (frame-tap thread) and reader.close (this thread, after a timeout) never overlap: closing the
        // reader frees the buffer a copy reads. A frame copied after the timeout is recycled.
        val handoff = FrameHandoff<Bitmap> { it.recycle() }
        reader.setOnImageAvailableListener({ source ->
            try {
                handoff.offer {
                    val image = source.acquireLatestImage() ?: return@offer null
                    try {
                        val plane = image.planes[0]
                        val rowPadding = plane.rowStride - plane.pixelStride * image.width
                        val padded = Bitmap.createBitmap(
                            image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888
                        )
                        padded.copyPixelsFromBuffer(plane.buffer.duplicate().apply { rewind() })
                        if (rowPadding == 0) padded
                        else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
                    } finally {
                        image.close()
                    }
                }
            } catch (error: Throwable) {
                // Never let an exception escape on the HandlerThread: it would kill the process (and the desktop).
                Log.w(TAG, "one-shot desktop frame copy failed", error)
            }
        }, Handler(thread.looper))
        return try {
            backend.attachSurface(
                geometry.displayId, reader.surface,
                geometry.width, geometry.height, geometry.width, geometry.height, geometry.densityDpi,
            )
            handoff.take(timeoutMs)
        } catch (error: Throwable) {
            Log.w(TAG, "one-shot desktop mirror failed", error)
            null
        } finally {
            runCatching { backend.releaseLayer() }
            handoff.close { runCatching { reader.close() } }
            thread.quitSafely()
        }
    }

    // ---- input -------------------------------------------------------------------------------------------------

    override suspend fun tap(x: Float, y: Float): Boolean = withContext(inputLane) {
        val (displayId, point) = target(x, y) ?: return@withContext false
        val down = SystemClock.uptimeMillis()
        val pressed = touch(displayId, down, down, MotionEvent.ACTION_DOWN, point.first, point.second)
        Thread.sleep(40L)
        // Always release, even after a rejected DOWN, so no pointer can stay logically held.
        val released = touch(displayId, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point.first, point.second)
        returnPhoneFocusSoon(POINTER_FOCUS_RETURN_MS)
        pressed && released
    }

    override suspend fun longPress(x: Float, y: Float, durationMs: Long): Boolean = withContext(inputLane) {
        val (displayId, point) = target(x, y) ?: return@withContext false
        val duration = DesktopInputMath.longPressDuration(durationMs)
        val down = SystemClock.uptimeMillis()
        val pressed = touch(displayId, down, down, MotionEvent.ACTION_DOWN, point.first, point.second)
        // A stationary MOVE midway keeps vendor long-press detectors (and drag-and-drop) alive.
        Thread.sleep(duration / 2)
        touch(displayId, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, point.first, point.second)
        Thread.sleep(duration - duration / 2)
        val released = touch(displayId, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point.first, point.second)
        returnPhoneFocusSoon(POINTER_FOCUS_RETURN_MS)
        pressed && released
    }

    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        withContext(inputLane) {
            val (displayId, start) = target(x1, y1) ?: return@withContext false
            val geometry = geometry() ?: return@withContext false
            val end = DesktopInputMath.clampPoint(x2, y2, geometry.width, geometry.height) ?: return@withContext false
            val plan = DesktopInputMath.swipePlan(
                start.first, start.second, end.first, end.second, DesktopInputMath.swipeDuration(durationMs)
            )
            val down = SystemClock.uptimeMillis()
            var ok = touch(displayId, down, down, MotionEvent.ACTION_DOWN, start.first, start.second)
            for (step in plan) {
                val due = down + step.offsetMs
                val wait = due - SystemClock.uptimeMillis()
                if (wait > 0) Thread.sleep(wait)
                // Interpolated, monotonically spaced eventTimes (never in the future) keep the fling velocity.
                val eventTime = minOf(due, SystemClock.uptimeMillis())
                ok = touch(displayId, down, eventTime, MotionEvent.ACTION_MOVE, step.x, step.y) && ok
            }
            val last = plan.last()
            ok = touch(displayId, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, last.x, last.y) && ok
            returnPhoneFocusSoon(POINTER_FOCUS_RETURN_MS)
            ok
        }

    override suspend fun key(keyCode: Int, metaState: Int): Boolean {
        // HOME is never injected (PhoneWindowManager would handle it for the whole phone): desktop launcher instead.
        if (DesktopGlobalActions.keyIsDesktopHome(keyCode)) return withContext(ioLane) { globalAction("home") }
        return withContext(inputLane) {
            val displayId = geometry()?.displayId ?: return@withContext false
            withKeyFocus(displayId) { chord(displayId, keyCode, metaState) }
        }
    }

    /**
     * Key events only (virtual keyboard map: printable ASCII, newline, tab). Anything else is refused: the phone's
     * clipboard belongs to the user and is never read, written or cleared for typing ; the agent gets the
     * reason through [typingRefusal] and uses accessibility text input or asks the user.
   */
    override suspend fun typeText(text: String): Boolean = withContext(inputLane) {
        val displayId = geometry()?.displayId ?: return@withContext false
        if (text.isEmpty()) return@withContext true
        if (!DesktopTyping.keyTypeable(text)) {
            Log.i(TAG, "text refused: not typeable as key events (the clipboard is never used)")
            return@withContext false
        }
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        withKeyFocus(displayId) { typeKeys(map, displayId, text) }
    }

    private fun typeKeys(map: KeyCharacterMap, displayId: Int, text: String): Boolean {
        for (chunk in DesktopInputMath.chunks(text)) {
            val events = map.getEvents(chunk.toCharArray()) ?: return false
            for (source in events) {
                val now = SystemClock.uptimeMillis()
                val event = KeyEvent(
                    now, now, source.action, source.keyCode, source.repeatCount, source.metaState,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
                )
                if (!inject(event, displayId)) {
                    Log.w(TAG, "text key rejected display=$displayId code=${source.keyCode}")
                    return false
                }
            }
        }
        return true
    }

    /**
     * A key combination as a hardware keyboard sends it ([DesktopInputMath.chordStrokes]): each modifier (Ctrl, Shift,
     * Alt, Meta) goes down as a real key event carrying the meta state held so far, then the key down/up with all of
     * them, then the modifiers up in reverse, each with the state that remains. Every event carries the desktop's
     * display id (inject); each key's down time is its own press. Every up is sent even after a rejected down, so no
     * key or modifier can stay logically held.
   */
    private fun chord(displayId: Int, keyCode: Int, metaState: Int): Boolean {
        val pressedAt = HashMap<Int, Long>()
        var ok = true
        for (stroke in DesktopInputMath.chordStrokes(keyCode, metaState)) {
            val now = SystemClock.uptimeMillis()
            val downTime = if (stroke.down) now.also { pressedAt[stroke.keyCode] = it } else pressedAt[stroke.keyCode] ?: now
            val action = if (stroke.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            ok = sendKey(displayId, downTime, action, stroke.keyCode, stroke.metaState) && ok
        }
        return ok
    }

    private fun sendKey(displayId: Int, downTime: Long, action: Int, keyCode: Int, meta: Int): Boolean {
        val event = KeyEvent(
            downTime, SystemClock.uptimeMillis(), action, keyCode, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        return inject(event, displayId)
    }

    /**
     * One injection. A send that failed with an exception (dead runtime binder) rebuilds the dispatcher once and
     * retries; a plain refusal (false without an exception) is final.
   */
    private fun inject(event: InputEvent, displayId: Int): Boolean {
        // A queued event may outlive its desktop (unplug/stop/reconfigure). Never stamp it with a stale/default id.
        if (!DesktopDisplayPolicy.inputTarget(displayId, geometry()?.displayId)) return false
        var reconnected = false
        val accepted = dispatchers.call(broken = { ok: Boolean ->
            val failed = !ok && lastInjectFailure.get() != null && privileged()
            if (failed) {
                reconnected = true
                Log.w(TAG, "input injection failed (${lastInjectFailure.get()?.javaClass?.simpleName}); reconnecting")
            }
            failed
        }) { dispatcher ->
            lastInjectFailure.set(null)
            dispatcher.send(event, displayId)
        }
        // Task proxies of the old runtime are stale too.
        if (reconnected) runCatching { taskBridge.reset() }
        return accepted
    }

    // ---- phone focus -------------------------------------------------------------------------------------------

    /**
     * Key events need a focused window on the desktop. A desktop without its own focus ("shared": Android 13, or
     * profile "none") has one only while it is the top-focused display, so it is focused first and the phone gets its
     * focus back afterwards. A desktop with its own focus keeps it while the phone app stays focused.
   */
    private fun withKeyFocus(displayId: Int, block: () -> Boolean): Boolean {
        val requiresFocus = DesktopFocusPolicy.focusDesktopForKeys(desktopFocusMode())
        val focused = requiresFocus && privileged() && runCatching {
            val task = taskBridge.tasks(displayId).firstOrNull { it.visible } ?: return@runCatching false
            taskBridge.focus(task).also { if (it) Thread.sleep(DESKTOP_FOCUS_SETTLE_MS) }
        }.onFailure { Log.w(TAG, "focusing the desktop for key input failed", it) }.getOrDefault(false)
        // Refused shared-focus activation means there is no proven desktop key target. Do not type into the phone.
        if (requiresFocus && !focused) return false
        return try {
            block()
        } finally {
            // Key events never move the focus by themselves; only the explicit desktop focus above is undone.
            if (focused) returnPhoneFocusSoon(POINTER_FOCUS_RETURN_MS)
        }
    }

    /**
     * After input or a launch on a background (MINI/HIDDEN) desktop that is not focus-safe, the phone's front task gets
     * the focus back, so the app the user is in keeps working. The platform moves the focus
     * asynchronously, hence a few checks; the front task is read each time (the user may have switched apps).
   */
    private fun returnPhoneFocusSoon(delaysMs: LongArray) {
        if (!DesktopFocusPolicy.returnFocusToPhone(desktopFocusMode(), presentation())) return
        delaysMs.forEach { delay ->
            runCatching { focusExecutor.schedule({ returnPhoneFocus() }, delay, TimeUnit.MILLISECONDS) }
        }
    }

    private fun returnPhoneFocus() {
        if (!DesktopFocusPolicy.returnFocusToPhone(desktopFocusMode(), presentation()) || !privileged()) return
        runCatching { phoneFrontTask()?.let(taskBridge::focus) }
            .onFailure { Log.w(TAG, "returning the focus to the phone failed", it) }
    }

    /** The task the user sees in front on the phone (not picture-in-picture), or null. */
    private fun phoneFrontTask(): TaskSnapshot? =
        taskBridge.tasks(Display.DEFAULT_DISPLAY).firstOrNull { it.visible && it.windowingMode != WINDOWING_MODE_PINNED }

    override suspend fun rightClick(x: Float, y: Float): Boolean = withContext(inputLane) {
        val (displayId, point) = target(x, y) ?: return@withContext false
        val down = SystemClock.uptimeMillis()
        var ok = true
        // Same sequence as Dextop's own right click (SOURCE_MOUSE, BUTTON_SECONDARY, deviceId 0).
        listOf(
            MotionEvent.ACTION_DOWN to MotionEvent.BUTTON_SECONDARY,
            MotionEvent.ACTION_BUTTON_PRESS to MotionEvent.BUTTON_SECONDARY,
            MotionEvent.ACTION_BUTTON_RELEASE to 0,
            MotionEvent.ACTION_UP to 0,
        ).forEach { (action, buttons) ->
            val event = mouseEvent(down, action, point.first, point.second, buttons)
            runCatching {
                MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
                    .invoke(event, MotionEvent.BUTTON_SECONDARY)
            }
            try {
                ok = inject(event, displayId) && ok
            } finally {
                event.recycle()
            }
        }
        returnPhoneFocusSoon(POINTER_FOCUS_RETURN_MS)
        ok
    }

    override suspend fun scrollWheel(x: Float, y: Float, vertical: Float, horizontal: Float): Boolean =
        withContext(inputLane) {
            val (displayId, point) = target(x, y) ?: return@withContext false
            val steps = DesktopInputMath.scrollSteps(vertical, horizontal)
            if (steps.isEmpty()) return@withContext true
            var ok = true
            val hover = mouseEvent(SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE, point.first, point.second, 0)
            try {
                inject(hover, displayId)
            } finally {
                hover.recycle()
            }
            steps.forEach { (vscroll, hscroll) ->
                val now = SystemClock.uptimeMillis()
                val properties = MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_MOUSE
                }
                val coordinates = MotionEvent.PointerCoords().apply {
                    this.x = point.first
                    this.y = point.second
                    setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
                    setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll)
                }
                val event = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties), arrayOf(coordinates),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
                )
                try {
                    ok = inject(event, displayId) && ok
                } finally {
                    event.recycle()
                }
                Thread.sleep(16L)
            }
            ok
        }

    private fun target(x: Float, y: Float): Pair<Int, Pair<Float, Float>>? {
        val geometry = geometry() ?: return null
        val point = DesktopInputMath.clampPoint(x, y, geometry.width, geometry.height) ?: run {
            Log.w(TAG, "point ($x, $y) outside desktop ${geometry.width}x${geometry.height}")
            return null
        }
        return geometry.displayId to point
    }

    /** SOURCE_TOUCHSCREEN, TOOL_TYPE_FINGER, deviceId 0 (Samsung rejects the phone panel's id on other displays). */
    internal fun touch(displayId: Int, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): Boolean {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coordinates),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        return try {
            inject(event, displayId)
        } finally {
            event.recycle()
        }
    }

    private fun mouseEvent(downTime: Long, action: Int, x: Float, y: Float, buttons: Int): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (buttons != 0) 1f else 0f
            size = 1f
        }
        return MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coordinates),
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
    }

    // ---- apps / windows ----------------------------------------------------------------------------------------

    /**
     * Launches on the desktop display. When [bounds] are given and the app already has a task there, the task is
     * re-placed with TaskBridge (launchBounds are ignored for an existing task) and focused instead of relaunched.
   */
    override fun launchPackage(packageName: String, bounds: Rect?): Boolean {
        val geometry = geometry() ?: return false
        if (bounds != null && replaceExistingTask(geometry.displayId, packageName, bounds)) return true
        if (refusedForPhone(packageName)) return false
        val launched = MirrorService.launchPackage(packageName, bounds)
        return launched.also { if (it) returnPhoneFocusSoon(LAUNCH_FOCUS_RETURN_MS) }
    }

    /** [launchRefusal] applies: the app is open on the phone (logged; the agent reads the reason from launchRefusal). */
    private fun refusedForPhone(packageName: String?): Boolean {
        val reason = packageName?.let(::launchRefusal) ?: return false
        Log.w(TAG, "desktop launch refused: $packageName is open on the phone")
        return reason.isNotEmpty()
    }

    /**
     * The launch runs with Backstage's identity: whatever the caller built, URI grants, clip data and the selector are
     * dropped and Backstage's own components are refused (intent redirection), see [DesktopIntentPolicy].
   */
    override fun launchIntent(intent: Intent, bounds: Rect?): Boolean {
        if (geometry() == null) return false
        val ownPackage = appContext?.packageName ?: return false
        val selector = intent.selector
        if (!DesktopIntentPolicy.targetAllowed(
                intent.component?.packageName, intent.`package`,
                selector?.component?.packageName ?: selector?.`package`, ownPackage,
            )
        ) {
            Log.w(TAG, "refusing a desktop launch aimed at $ownPackage itself")
            return false
        }
        val safe = Intent(intent).apply {
            flags = DesktopIntentPolicy.sanitizedFlags(flags)
            clipData = null
            this.selector = null
        }
        if (refusedForPhone(targetPackage(safe))) return false
        val launched = MirrorService.Backstage.launchIntent(safe, bounds)
        return launched.also { if (it) returnPhoneFocusSoon(LAUNCH_FOCUS_RETURN_MS) }
    }

    /** The app an Intent starts: explicit component/package, else what it resolves to (null when ambiguous). */
    private fun targetPackage(intent: Intent): String? {
        intent.component?.packageName?.let { return it }
        intent.`package`?.let { return it }
        val resolved = appContext?.let { context ->
            runCatching { context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName }.getOrNull()
        }
        // The chooser ("android") is not an app the user has open.
        return resolved?.takeIf { it != "android" }
    }

    override fun launchPackageAt(packageName: String, slot: String): Boolean {
        val geometry = geometry() ?: return false
        if (refusedForPhone(packageName)) return false
        return MirrorService.launchPackageAt(packageName, slot).also { if (it) returnPhoneFocusSoon(LAUNCH_FOCUS_RETURN_MS) }
    }

    /**
     * Always aimed at the desktop display, never at the phone ([DesktopGlobalActions]): BACK is a key event with the
     * desktop's display id (an accessibility global action would hit the top-focused display, i.e. the phone in
     * MINI/HIDDEN); HOME starts the desktop's launcher on the desktop display (an injected KEYCODE_HOME is handled by
     * PhoneWindowManager for the whole phone: it closes the phone's recents, shade and dialogs, );
     * "recents" uses Dextop's own display-targeted path.
   */
    override fun globalAction(action: String): Boolean {
        val displayId = geometry()?.displayId ?: return false
        return when (DesktopGlobalActions.route(action)) {
            DesktopGlobalActions.Route.BACK_KEY_ON_DESKTOP ->
                runBlockingOnInputLane { withKeyFocus(displayId) { chord(displayId, KeyEvent.KEYCODE_BACK, 0) } }
            DesktopGlobalActions.Route.HOME_ACTIVITY_ON_DESKTOP ->
                MirrorService.Backstage.launchDesktopHome()
                    .also { if (it) returnPhoneFocusSoon(LAUNCH_FOCUS_RETURN_MS) }
            DesktopGlobalActions.Route.DESKTOP_RECENTS -> MirrorService.performCardexAction("recents")
            DesktopGlobalActions.Route.UNSUPPORTED -> false
        }
    }

    private fun runBlockingOnInputLane(block: () -> Boolean): Boolean =
        DesktopHostInput.run(inputExecutor, block = block)

    // ---- desktop windows: listed and closed on the desktop display only -----------------------------------------

    /**
     * The tasks of the desktop display, top-most first (TaskBridge reads them per display and drops any other display's
     * task). The phone's display is never listed: "close all" on the desktop must never reach the phone's apps.
   */
    override fun desktopTasks(): List<com.eggman.backstage.host.DesktopTask>? {
        val displayId = geometry()?.displayId ?: return null
        if (!privileged()) return null
        return runCatching { taskBridge.tasks(displayId).filter { it.displayId == displayId }.map(::desktopTask) }
            .onFailure { Log.w(TAG, "desktop tasks unavailable", it) }.getOrNull()
    }

    /**
     * removeTask for one task of the desktop display, re-checked right before: it must still be on the desktop display
     * and be an ordinary app task that is not Backstage/Dextop itself (DesktopTaskPolicy). Never force-stop; Android keeps
     * the process while the app still has a task elsewhere (e.g. on the phone). Without removeTask (refused, or missing on
     * this release) the window gets Alt+F4 instead, but only while the desktop is shown FULL and after focusing that task:
     * in the background the system could apply Alt+F4 to the phone's focused app.
   */
    override fun closeDesktopTask(taskId: Int): Boolean {
        val displayId = geometry()?.displayId ?: return false
        if (!privileged()) return false
        val own = setOfNotNull(appContext?.packageName)
        val task = runCatching { taskBridge.tasks(displayId) }.onFailure { Log.w(TAG, "desktop tasks unavailable", it) }.getOrNull()
            ?.firstOrNull { it.taskId == taskId && com.eggman.backstage.host.DesktopTaskPolicy.closable(desktopTask(it), displayId, own) }
            ?: return false
        if (taskBridge.close(task) && awaitTaskGone(displayId, taskId)) return true
        if (presentation() != DesktopHost.Presentation.FULL) {
            Log.w(TAG, "removeTask failed for desktop task $taskId; not sending Alt+F4 to a background desktop")
            return false
        }
        val sent = runBlockingOnInputLane {
            taskBridge.focus(task) && chord(displayId, KeyEvent.KEYCODE_F4, KeyEvent.META_ALT_ON)
        }
        return sent && awaitTaskGone(displayId, taskId)
    }

    /** The task left the desktop display's task list within [timeoutMs]. */
    private fun awaitTaskGone(displayId: Int, taskId: Int, timeoutMs: Long = 1_500L): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val present = runCatching { taskBridge.tasks(displayId).any { it.taskId == taskId } }.getOrDefault(true)
            if (!present) return true
            if (SystemClock.uptimeMillis() >= deadline) return false
            Thread.sleep(100L)
        }
    }

    private fun desktopTask(task: TaskSnapshot) = com.eggman.backstage.host.DesktopTask(
        taskId = task.taskId,
        packageName = task.packageName,
        label = task.label,
        displayId = task.displayId,
        activityType = task.activityType,
        visible = task.visible,
        focused = task.focused,
        bounds = task.bounds.takeUnless { it.isEmpty }?.let { com.eggman.backstage.host.TaskBounds(it.left, it.top, it.right, it.bottom) },
        windowingMode = task.windowingMode,
    )

    // ---- window control on both screens (window_control, launch repair) ------------------------------------------

    /** The phone's app tasks (display 0) with their window mode and bounds; read-only. Needs privilege, no session. */
    override fun phoneTasks(): List<com.eggman.backstage.host.DesktopTask>? {
        if (!privileged()) return null
        return runCatching {
            taskBridge.tasks(Display.DEFAULT_DISPLAY).filter { it.displayId == Display.DEFAULT_DISPLAY }.map(::desktopTask)
        }.onFailure { Log.w(TAG, "phone tasks unavailable", it) }.getOrNull()
    }

    /**
     * One task of the desktop display or the phone's, re-checked right before acting (DesktopTaskPolicy.controllable):
     * still on [displayId], an ordinary app task, not Backstage itself. Nothing is force-stopped; the focus is moved to
     * a desktop task only while the desktop is shown FULL.
   */
    override fun controlTask(
        taskId: Int,
        displayId: Int,
        op: com.eggman.backstage.host.TaskOp,
        bounds: com.eggman.backstage.host.TaskBounds?,
    ): Boolean {
        if (!privileged()) return false
        val desktopId = geometry()?.displayId
        if (displayId != Display.DEFAULT_DISPLAY && displayId != desktopId) return false
        val own = setOfNotNull(appContext?.packageName)
        val task = runCatching { taskBridge.tasks(displayId) }.onFailure { Log.w(TAG, "tasks of display $displayId unavailable", it) }
            .getOrNull()?.firstOrNull {
                it.taskId == taskId && com.eggman.backstage.host.DesktopTaskPolicy.controllable(desktopTask(it), displayId, own)
            } ?: return false
        val rect = bounds?.let { Rect(it.left, it.top, it.right, it.bottom) }
        return runCatching {
            when (op) {
                com.eggman.backstage.host.TaskOp.FULLSCREEN -> taskBridge.setWindowingMode(task, TaskBridge.WINDOWING_MODE_FULLSCREEN)
                    .also { if (it && displayId == desktopId) focusIfShown(task) }
                com.eggman.backstage.host.TaskOp.FREEFORM -> {
                    val r = rect ?: return false
                    if (task.windowingMode != WINDOWING_MODE_FREEFORM &&
                        !taskBridge.setWindowingMode(task, WINDOWING_MODE_FREEFORM)) {
                        desktopCompatibilityFallback = "Freeform mode refused on display $displayId; use fullscreen. " +
                            "Desktop compatibility settings are managed and restored by Dextop."
                        return false
                    }
                    taskBridge.setBounds(task, r).also { if (it && displayId == desktopId) focusIfShown(task) }
                }
                com.eggman.backstage.host.TaskOp.MINIMIZE -> taskBridge.sendToBack(task)
                com.eggman.backstage.host.TaskOp.CLOSE -> taskBridge.close(task)
                com.eggman.backstage.host.TaskOp.MOVE_TO_DESKTOP -> {
                    val target = desktopId ?: return false
                    if (displayId != Display.DEFAULT_DISPLAY) return false
                    val moved = taskBridge.moveTaskToDisplay(task, target) || taskBridge.extractToDisplay(task, target)
                    if (moved) {
                        taskBridge.tasks(target).firstOrNull { it.taskId == taskId }?.let { onDesktop ->
                            if (onDesktop.windowingMode != WINDOWING_MODE_FREEFORM) taskBridge.setWindowingMode(onDesktop, WINDOWING_MODE_FREEFORM)
                            rect?.let { taskBridge.setBounds(onDesktop, it) }
                        }
                        returnPhoneFocusSoon(LAUNCH_FOCUS_RETURN_MS)
                    }
                    moved
                }
                com.eggman.backstage.host.TaskOp.MOVE_TO_PHONE -> {
                    if (displayId != desktopId) return false
                    val moved = taskBridge.moveTaskToDisplay(task, Display.DEFAULT_DISPLAY)
                    if (moved) {
                        taskBridge.tasks(Display.DEFAULT_DISPLAY).firstOrNull { it.taskId == taskId }
                            ?.let { taskBridge.setWindowingMode(it, TaskBridge.WINDOWING_MODE_FULLSCREEN) }
                    }
                    moved
                }
            }
        }.onFailure { Log.w(TAG, "task control $op failed for $taskId", it) }.getOrDefault(false)
    }

    /** Focus moves the top focus to the task's display: only while the user looks at the desktop (FULL). */
    private fun focusIfShown(task: TaskSnapshot) {
        if (presentation() == DesktopHost.Presentation.FULL) runCatching { taskBridge.focus(task) }
    }

    /**
     * Which desktop's keyboard shortcuts apply: "dextop_plasma" when one of Dextop's own window managers owns the
     * display, else Dextop's environment id for this device ("samsung_dex", "android_freeform",...).
   */
    override fun desktopEnvironment(): String? {
        val context = appContext ?: return null
        return runCatching {
            if (moe.n4tsu.dextop.plasma.WindowManagerSelection.isPlasma(context)) "dextop_plasma"
            else DesktopEnvironmentRegistry.current().id
        }.onFailure { Log.w(TAG, "desktop environment unknown", it) }.getOrNull()
    }

    private fun replaceExistingTask(displayId: Int, packageName: String, bounds: Rect): Boolean = runCatching {
        if (!privileged()) return false
        val task = taskBridge.tasks(displayId).firstOrNull { it.packageName == packageName } ?: return false
        if (task.windowingMode != WINDOWING_MODE_FREEFORM &&
            !taskBridge.setWindowingMode(task, WINDOWING_MODE_FREEFORM)) return false
        val placed = taskBridge.setBounds(task, bounds)
        // Focusing a task moves the top focus to its display: only when the user looks at the desktop (FULL),
        // never while it runs in the background behind the user's phone apps (MINI/HIDDEN).
        if (presentation() == DesktopHost.Presentation.FULL) taskBridge.focus(task)
        placed
    }.onFailure { Log.w(TAG, "re-placing $packageName failed", it) }.getOrDefault(false)
}
