package moe.n4tsu.dextop

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import com.eggman.backstage.entry.EntryRouter
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.PreviewHealth
import com.eggman.backstage.host.PresentationNames
import com.eggman.backstage.ui.AgentScreenActivity
import com.eggman.backstage.shell.ShellPage
import com.eggman.backstage.shell.ShellLifecycle
import com.eggman.backstage.ui.settings.SettingsStores
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Native side of Home's Backstage shell (patches/0005, lib/backstage_shell.dart), one per Flutter engine of Dextop's
 * activities: the "Agent" destination opens the agent screen in the same task without a transition; the live player
 * hands a Flutter texture (TextureRegistry.SurfaceProducer) to MirrorService as the IN_APP target and forwards the
 * player's touches to the desktop display.
 *
 * Surface rule (as everywhere in patches/0001): the display is moved off the player BEFORE the producer is released.
 * Only the phone's own UI (display 0) gets a player; Dextop's UI inside the desktop window does not.
 */
internal object BackstageShell {
    private const val TAG = "BackstageShell"
    const val CHANNEL = "com.eggman.backstage/shell"
    private const val ACTION_DOWN = 0
    private const val ACTION_MOVE = 1
    private const val ACTION_UP = 2

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Backstage-shell").apply { isDaemon = true } }
    private val sessions = HashMap<FlutterEngine, Session>()
    /** At most one automatic desktop restart for a black preview per [PREVIEW_RESTART_GAP_MS]. */
    private var lastPreviewRestart = 0L
    private const val PREVIEW_RESTART_GAP_MS = 10 * 60_000L
    private val previewWatch = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Backstage-preview").apply { isDaemon = true }
    }

    /**
     * Home's player and the agent chat's player share MirrorService's one player slot (PlayerSurfaceOwner): the
     * chat's wins while it is on screen, Home's comes back after it. Main thread.
   */
    val playerOwner = com.eggman.backstage.host.PlayerSurfaceOwner<android.view.Surface>(
        bind = { s -> MirrorService.Backstage.setPlayerSurface(s) },
        bound = { MirrorService.Backstage.playerSurface },
    )

    private class Session(val activity: Activity, val engine: FlutterEngine, val channel: MethodChannel) {
        var producer: TextureRegistry.SurfaceProducer? = null
        var size: Pair<Int, Int>? = null
        var pendingPage: String? = null
        var resumed = false
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { if (a === activity) resumed = true }
            override fun onActivityPaused(a: Activity) {
                if (a === activity) { resumed = false; restorePreview(this@Session) }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        }
        var downTime = 0L
        /** Preview watchdog: a newer check supersedes older ones. */
        var previewCheck = 0
        val listener = object : DesktopHost.Listener {
            override fun onDesktopChanged(geometry: DesktopHost.Geometry?, presentation: DesktopHost.Presentation) {
                main.post {
                    if (geometry != null) resizePlayer(this@Session, geometry.width, geometry.height)
                    if (presentation == DesktopHost.Presentation.IN_APP && producer != null) watchPreview(this@Session)
                    runCatching { channel.invokeMethod("changed", null) }
                }
            }
        }
        val phoneUi: Boolean
            get() = runCatching { activity.display?.displayId == Display.DEFAULT_DISPLAY }.getOrDefault(false)
    }

    fun install() {
        BackstageShellHooks.onIntent = ::onIntent
        BackstageShellHooks.onEngine = ::attach
        BackstageShellHooks.onEngineCleanup = ::detach
    }

    private fun attach(activity: Activity, engine: FlutterEngine) {
        detach(activity, engine)
        val channel = MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL)
        val session = Session(activity, engine, channel)
        sessions[engine] = session
        activity.application.registerActivityLifecycleCallbacks(session.lifecycle)
        onIntent(activity, activity.intent)
        channel.setMethodCallHandler { call, result ->
            runCatching {
                when (call.method) {
                    "takeShellPage" -> result.success(session.pendingPage.also { session.pendingPage = null })
                    "previewShow" -> result.success(session.phoneUi && session.resumed &&
                        ShellLifecycle.homePreview.show(BackstageDesktopBridge.presentation(), BackstageDesktopBridge::setPresentation))
                    "previewRestore" -> result.success(restorePreview(session))
                    "state" -> result.success(state(session))
                    "homeShown" -> {
                        if (session.phoneUi && session.resumed) ShellLifecycle.onMainScreenShown(activity)
                        result.success(null)
                    }
                    "checkUpdates" -> {
                        val manual = call.argument<Boolean>("manual") == true
                        worker.execute {
                            val status = runCatching { com.eggman.backstage.update.AppUpdates.checkStatus(activity.applicationContext, manual) }
                            main.post { status.fold({ result.success(it) }, { result.error("backstage_update", it.message, null) }) }
                        }
                    }
                    "openUpdates" -> {
                        restorePreview(session)
                        com.eggman.backstage.update.AppUpdates.open(activity)
                        result.success(true)
                    }
                    "openAgent" -> { restorePreview(session); result.success(openAgent(activity, call.argument<String>("screen"))) }
                    "onboardingDone" -> result.success(SettingsStores.get(activity).onboardingDone)
                    "playerAttach" -> result.success(playerAttach(session))
                    "playerDetach" -> result.success(releasePlayer(session).let { true })
                    "touch" -> result.success(touch(session, call.argument<Int>("action") ?: -1,
                        (call.argument<Number>("x") ?: 0).toFloat(), (call.argument<Number>("y") ?: 0).toFloat()))
                    "start" -> result.success(SettingsStores.get(activity).onboardingDone && start(call.argument<String>("presentation")))
                    "desktopStartPrerequisite", "openDesktopManager", "openDesktopManagerDownload",
                    "openDesktopAccessibility", "startPlayDesktop" -> {
                        if (BuildConfig.DISTRIBUTION_CHANNEL != "play" || !session.phoneUi) result.notImplemented()
                        else when (call.method) {
                            "desktopStartPrerequisite" -> result.success(PlayDesktopStart.prerequisite(activity))
                            "openDesktopManager" -> result.success(PlayDesktopStart.openManager(activity))
                            "openDesktopManagerDownload" -> result.success(PlayDesktopStart.openDownload(activity, call.argument<String>("manager")))
                            "openDesktopAccessibility" -> result.success(PlayDesktopStart.openAccessibility(activity))
                            else -> PlayDesktopStart.launch(call.argument<String>("presentation"), result)
                        }
                    }
                    "present" -> result.success(present(call.argument<String>("presentation")))
                    "stop" -> result.success(true.also { BackstageDesktopBridge.stopDesktop() })
                    else -> result.notImplemented()
                }
            }.onFailure { error ->
                Log.w(TAG, "shell call ${call.method} failed", error)
                result.error("backstage_shell", error.message, null)
            }
        }
        BackstageDesktopBridge.addListener(session.listener)
    }

    private fun detach(@Suppress("UNUSED_PARAMETER") activity: Activity, engine: FlutterEngine) {
        val session = sessions.remove(engine) ?: return
        BackstageDesktopBridge.removeListener(session.listener)
        session.activity.application.unregisterActivityLifecycleCallbacks(session.lifecycle)
        restorePreview(session)
        session.channel.setMethodCallHandler(null)
        releasePlayer(session)
    }

    private fun restorePreview(session: Session): Boolean = session.phoneUi &&
        ShellLifecycle.homePreview.restore(BackstageDesktopBridge.presentation(), BackstageDesktopBridge::setPresentation)

    /** Keep cold intents until Dart subscribes; acknowledge warm delivery before consuming it. */
    private fun onIntent(activity: Activity, intent: Intent) {
        val page = ShellPage.parse(intent.getStringExtra(ShellPage.EXTRA)) ?: return
        val session = sessions.values.firstOrNull { it.activity === activity && it.phoneUi } ?: return
        intent.removeExtra(ShellPage.EXTRA)
        session.pendingPage = page.wireName
        session.channel.invokeMethod("selectPage", page.wireName, object : MethodChannel.Result {
            override fun success(result: Any?) {
                if (result == true && session.pendingPage == page.wireName) session.pendingPage = null
            }
            override fun error(code: String, message: String?, details: Any?) = Unit
            override fun notImplemented() = Unit
        })
    }

    private fun state(session: Session): Map<String, Any?> {
        val geometry = BackstageDesktopBridge.geometry()
        return mapOf(
            "playerAllowed" to session.phoneUi,
            "onboardingDone" to SettingsStores.get(session.activity).onboardingDone,
            "running" to (geometry != null),
            "presentation" to PresentationNames.wireName(BackstageDesktopBridge.presentation()),
            "width" to (geometry?.width ?: 0),
            "height" to (geometry?.height ?: 0),
            "privileged" to BackstageDesktopBridge.privilegedAvailable(),
            "textureId" to session.producer?.id(),
        )
    }

    /** The Agent tab: the agent screen in the same task (shared affinity), no transition, never a second recents card. */
    private fun openAgent(activity: Activity, screen: String?): Boolean = runCatching {
        activity.startActivity(
            Intent(activity, AgentScreenActivity::class.java)
                .putExtra(EntryRouter.EXTRA_TRUST, EntryRouter.trustToken)
                .putExtra(EntryRouter.EXTRA_SCREEN, screen?.takeIf { it == "settings" })
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }.onFailure { Log.w(TAG, "agent screen refused", it) }.isSuccess

    private fun playerAttach(session: Session): Map<String, Any>? {
        if (!session.phoneUi) return null
        val geometry = BackstageDesktopBridge.geometry() ?: return null
        val producer = session.producer ?: session.engine.renderer.createSurfaceProducer().also { producer ->
            session.producer = producer
            producer.setCallback(object : TextureRegistry.SurfaceProducer.Callback {
                override fun onSurfaceAvailable() {
                    if (session.producer === producer) bind(producer)
                }

                override fun onSurfaceCleanup() {
                    // Before the Surface becomes invalid: the display moves to the sink first.
                    playerOwner.homeDetached(null)
                }
            })
        }
        resizePlayer(session, geometry.width, geometry.height)
        bind(producer)
        watchPreview(session)
        return mapOf("textureId" to producer.id(), "width" to geometry.width, "height" to geometry.height)
    }

    private fun bind(producer: TextureRegistry.SurfaceProducer) {
        runCatching { playerOwner.homeAttached(producer.surface) }
            .onFailure { Log.w(TAG, "binding the player failed", it) }
    }

    /** A new desktop size gives the producer a new Surface (setSize): bind that one. */
    private fun resizePlayer(session: Session, width: Int, height: Int) {
        val producer = session.producer ?: return
        if (session.size == width to height || width <= 0 || height <= 0) return
        session.size = width to height
        producer.setSize(width, height)
        if (playerOwner.home != null) bind(producer)
    }

    private fun releasePlayer(session: Session) {
        val producer = session.producer ?: return
        session.producer = null
        session.previewCheck++
        session.size = null
        // Display off the player first (to the sink), then the texture goes.
        runCatching { playerOwner.homeDetached(null) }
        runCatching { producer.release() }
    }

    /**
     * Home's preview sometimes comes up black although the desktop runs (stop + start fixed it by hand). A few seconds
     * after the player attaches (or the desktop changes), check the frame the player received; if it is black, rebind
     * the player, and if that does not help, restart the desktop once (never while the agent works on it). Main thread.
   */
    private fun watchPreview(session: Session, attempt: Int = 0) {
        val check = ++session.previewCheck
        previewWatch.schedule({
            // Off the main thread: latestFrame waits for PixelCopy on the main looper.
            val blank = PreviewHealth.blank(runCatching { MirrorService.Backstage.latestFrame() }.getOrNull())
            main.post { onPreviewChecked(session, check, attempt, blank) }
        }, PreviewHealth.CHECK_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun onPreviewChecked(session: Session, check: Int, attempt: Int, blank: Boolean) {
        val producer = session.producer
        val showing = sessions[session.engine] === session && check == session.previewCheck && producer != null &&
            session.resumed && playerOwner.home != null && playerOwner.chat == null &&
            BackstageDesktopBridge.presentation() == DesktopHost.Presentation.IN_APP
        if (!showing || producer == null) return
        val busy = runCatching {
            com.eggman.backstage.agent.SessionStop.agentActive(com.eggman.backstage.init.Backstage.controller.state.value)
        }.getOrDefault(true)
        // The player is released and attached again around a restart: the cap lives here, not in the session.
        val restarted = lastPreviewRestart != 0L && SystemClock.elapsedRealtime() - lastPreviewRestart < PREVIEW_RESTART_GAP_MS
        when (PreviewHealth.next(blank, attempt, restarted, busy)) {
            PreviewHealth.Action.NONE -> Unit
            PreviewHealth.Action.REBIND -> {
                Log.w(TAG, "Home preview is black: rebinding the player")
                runCatching { playerOwner.homeDetached(null) }
                bind(producer)
                watchPreview(session, attempt + 1)
            }
            PreviewHealth.Action.RESTART -> {
                Log.w(TAG, "Home preview is still black: restarting the desktop")
                lastPreviewRestart = SystemClock.elapsedRealtime()
                val restartCheck = session.previewCheck
                worker.execute {
                    runCatching { runBlocking { BackstageDesktopBridge.restartDesktop(DesktopHost.Presentation.IN_APP) } }
                        .onFailure { Log.w(TAG, "desktop restart for a black preview failed", it) }
                    main.post {
                        // The new session notifies "changed" (Home reattaches); one more rebind-only check after it.
                        if (restartCheck == session.previewCheck) watchPreview(session, attempt + 1)
                        runCatching { session.channel.invokeMethod("changed", null) }
                    }
                }
            }
            PreviewHealth.Action.GIVE_UP -> Log.w(TAG, "Home preview stays black (agent busy or already restarted)")
        }
    }

    private fun touch(session: Session, action: Int, x: Float, y: Float): Boolean =
        forwardTouch(action, x, y, { session.downTime }, { session.downTime = it })

    /** The agent chat's player: touches already mapped to desktop pixels through its fitted picture. */
    private var chatDownTime = 0L
    fun chatTouch(action: Int, x: Float, y: Float): Boolean = forwardTouch(action, x, y, { chatDownTime }, { chatDownTime = it })

    /** One player touch in desktop display pixels ([ACTION_DOWN]..cancel, the same codes as PlayerFit's). */
    private fun forwardTouch(action: Int, x: Float, y: Float, down: () -> Long, setDown: (Long) -> Unit): Boolean {
        val geometry = BackstageDesktopBridge.geometry() ?: return false
        val motion = when (action) {
            ACTION_DOWN -> MotionEvent.ACTION_DOWN.also { setDown(SystemClock.uptimeMillis()) }
            ACTION_MOVE -> MotionEvent.ACTION_MOVE
            ACTION_UP -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_CANCEL
        }
        val downTime = down()
        val px = x.coerceIn(0f, geometry.width - 1f)
        val py = y.coerceIn(0f, geometry.height - 1f)
        // One ordered lane with the agent's input: a stream never interleaves with another.
        BackstageDesktopBridge.inputExecutor.execute {
            runCatching { BackstageDesktopBridge.touch(geometry.displayId, downTime, SystemClock.uptimeMillis(), motion, px, py) }
                .onFailure { Log.w(TAG, "player touch failed", it) }
        }
        return true
    }

    /** [name]: "hidden" for Home's "Start Backstage (background)"; the in-app player otherwise. */
    private fun start(name: String? = null): Boolean {
        val target = PresentationNames.parse(name).takeIf { it != DesktopHost.Presentation.NONE } ?: DesktopHost.Presentation.IN_APP
        worker.execute {
            runCatching { runBlocking { BackstageDesktopBridge.startDesktop(target, 20_000L) } }
                .onFailure { Log.w(TAG, "desktop start from Home failed", it) }
        }
        return true
    }

    private fun present(name: String?): Boolean {
        val target = PresentationNames.parse(name).takeIf { it != DesktopHost.Presentation.NONE } ?: return false
        worker.execute { runCatching { BackstageDesktopBridge.setPresentation(target) } }
        return true
    }
}
