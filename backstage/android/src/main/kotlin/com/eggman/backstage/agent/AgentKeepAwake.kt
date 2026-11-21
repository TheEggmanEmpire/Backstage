package com.eggman.backstage.agent

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopHosts
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The phone's own screen: the default display's state. PowerManager.isInteractive stays true while any display
 *  group is awake (a desktop with its own group keeps running while the phone is locked), so it cannot tell. */
object PhoneScreen {
    fun on(context: Context): Boolean = runCatching {
        stateOn(context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).state)
    }.getOrDefault(true)

    /** Display.STATE_ON, STATE_VR and STATE_ON_SUSPEND are on; OFF and the doze states are off (pure). */
    fun stateOn(state: Int): Boolean = state == Display.STATE_ON || state == Display.STATE_VR || state == Display.STATE_ON_SUSPEND
}

/**
 * Keeps a task running while the phone is locked:
 * - a PARTIAL_WAKE_LOCK while the agent is busy (CPU and network calls continue with the screen off; released when the
 *   queue empties, after a cancel or failure, and by the system when the process dies; [TASK_LOCK_TIMEOUT_MS] caps it);
 * - the desktop's own display group awake ([DesktopAwakePolicy]) through the host's per-display wake lock, which also
 *   wakes that group (only that one, never the phone's screen) when it went to sleep since the last task.
 * The foreground service ([AgentService]) keeps the process at foreground priority meanwhile (network in Doze).
 * All work runs on one background thread.
 */
object AgentKeepAwake {
    private const val TAG = "AgentKeepAwake"
    const val TASK_LOCK_TIMEOUT_MS = 60 * 60_000L
    private const val DESKTOP_HOLD_MS = 10 * 60_000L
    private const val RENEW_MS = 4 * 60_000L
    /** Hard cap for one busy stretch: the longest task limit (120 min) plus slack; renewal stops after it. */
    const val MAX_BUSY_HOLD_MS = 135 * 60_000L

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Backstage-keep-awake").apply { isDaemon = true } }
    @Volatile private var app: Context? = null
    private var busy = false
    private var busySinceMs = 0L
    private var partial: PowerManager.WakeLock? = null
    private var desktopHold: AutoCloseable? = null
    private var heldDisplay = -1
    private var heldSinceMs = 0L
    private var renewal: ScheduledFuture<*>? = null
    private var installed = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = reevaluate()
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) reevaluate()
        }
    }
    private val hostListener = object : DesktopHost.Listener {
        override fun onDesktopChanged(geometry: DesktopHost.Geometry?, presentation: DesktopHost.Presentation) = reevaluate()
    }

    /** Process start; idempotent. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        app = context.applicationContext ?: context
        runCatching {
            context.getSystemService(DisplayManager::class.java)
                .registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        }.onFailure { Log.w(TAG, "display listener unavailable", it) }
        runCatching { DesktopHosts.current?.addListener(hostListener) }
    }

    fun setBusy(context: Context, value: Boolean) {
        if (app == null) app = context.applicationContext ?: context
        worker.execute {
            if (value && !busy) busySinceMs = android.os.SystemClock.elapsedRealtime()
            busy = value
            applyPartial()
            evaluate()
        }
    }

    /** The desktop executor is about to use the desktop (it may just have started or restarted). */
    fun desktopInUse(context: Context) {
        if (app == null) app = context.applicationContext ?: context
        worker.execute { evaluate(force = true) }
    }

    fun reevaluate() {
        worker.execute { evaluate() }
    }

    private fun applyPartial() {
        val context = app ?: return
        val overCap = busy && android.os.SystemClock.elapsedRealtime() - busySinceMs > MAX_BUSY_HOLD_MS
        if (overCap) Log.w(TAG, "agent busy for over ${MAX_BUSY_HOLD_MS / 60_000} min: task wake lock no longer renewed")
        if (busy && !overCap) {
            val lock = partial ?: runCatching {
                context.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "backstage:agent-task")
                    .apply { setReferenceCounted(false) }
            }.getOrNull()?.also { partial = it }
            // Re-acquiring a non-counted lock renews its timeout.
            runCatching { lock?.acquire(TASK_LOCK_TIMEOUT_MS) }.onFailure { Log.w(TAG, "task wake lock refused", it) }
        } else {
            runCatching { partial?.takeIf { it.isHeld }?.release() }
        }
    }

    private fun evaluate(force: Boolean = false) {
        val context = app ?: return
        val host = DesktopHosts.current
        val extras = host as? DesktopHostExtras
        val status = runCatching { extras?.lockedModeStatus() }.getOrNull()
        val displayId = runCatching { host?.geometry()?.displayId }.getOrNull() ?: -1
        val want = displayId >= 0 && DesktopAwakePolicy.hold(status, busy, PhoneScreen.on(context))
        if (!want || extras == null) {
            releaseDesktop()
        } else {
            val now = System.currentTimeMillis()
            val due = desktopHold == null || heldDisplay != displayId || force || now - heldSinceMs >= RENEW_MS
            if (due) {
                if (busy && status?.wakeAllowed == false) runCatching { extras.ensureDesktopWakePermission() }
                // The new hold first, then the old one goes: the group never drops between the two.
                val next = runCatching { extras.holdDesktopAwake(displayId, DESKTOP_HOLD_MS) }.getOrNull()
                runCatching { desktopHold?.close() }
                desktopHold = next
                heldDisplay = if (next != null) displayId else -1
                heldSinceMs = now
                if (next == null && busy) LockedModeLog.record("the desktop display could not be kept awake")
            }
        }
        renewal?.cancel(false)
        renewal = if (busy || desktopHold != null) worker.schedule({
            applyPartial() // Renew the CPU lock too, including tasks longer than its one-hour timeout.
            evaluate()
        }, RENEW_MS, TimeUnit.MILLISECONDS) else null
    }

    private fun releaseDesktop() {
        runCatching { desktopHold?.close() }
        desktopHold = null
        heldDisplay = -1
    }
}
