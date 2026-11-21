package com.eggman.backstage.a11y

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.provider.Settings as AndroidSettings
import android.util.Log
import com.eggman.backstage.agent.AgentService
import com.eggman.backstage.data.Settings
import com.eggman.backstage.privileged.Privileged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Switches the app's one accessibility service off while a detector app (one that warns about enabled accessibility
 * services) is in front, and back on after it left.
 *
 * The first such app's warning survived every configuration change that kept the functionality (isAccessibilityTool, dropping key
 * filtering), so the service is taken out of the enabled list instead: a detector that reads the list cannot see it.
 *
 * The re-enable is not a blind timer: at every [Settings.detectorPauseDelayMs] tick the foreground app is asked again and
 * the pause is postponed while a detector is still in front, up to [MAX_PAUSE_MS]. A fixed delay would put the service
 * back while the user is still inside the app (a 3-D Secure hop and back takes longer than any short delay). Leaving the
 * detector, or the cap, re-enables at once.
 *
 * A running task is never interrupted: the agent needs the service it is driving. A task that needs the service while a
 * pause runs ends it ([release], from [AccessibilityAutoEnable.ensureConnected]). Nothing happens without the user's
 * accessibility consent, and a pause is persisted, so a process death while paused is recovered at the next start
 * ([recover]) instead of leaving the service off.
 */
object DetectorPause {
    private const val TAG = "BackstageA11y"

    /** Hard cap on a pause, so a detector that stays in front cannot keep the service off forever. */
    const val MAX_PAUSE_MS = 60_000L

    /** What a tick decides. Pure, so it is unit-tested without Android. */
    enum class Next { ENTER, POSTPONE, EXIT, NONE }

    /**
     * [pausedSinceMs] null = not paused: enter only for a detector. Paused: exit when the foreground app is not a
     * detector or the cap is reached, otherwise postpone (the caller ticks again after the delay).
   */
    fun decide(foreground: String?, pausedSinceMs: Long?, nowMs: Long, detectors: Set<String>): Next = when {
        pausedSinceMs == null -> if (foreground != null && foreground in detectors) Next.ENTER else Next.NONE
        nowMs - pausedSinceMs >= MAX_PAUSE_MS -> Next.EXIT
        // Unknown foreground (no usage access, no recent event): keep the pause, the cap ends it.
        foreground == null || foreground in detectors -> Next.POSTPONE
        else -> Next.EXIT
    }

    /** How long the tick loop sleeps: the delay, but never past the cap. */
    fun nextDelay(delayMs: Long, pausedSinceMs: Long, nowMs: Long): Long =
        delayMs.coerceAtMost(MAX_PAUSE_MS - (nowMs - pausedSinceMs)).coerceAtLeast(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var tick: Job? = null
    @Volatile private var pausedSinceMs: Long? = null

    val paused: Boolean get() = pausedSinceMs != null

    private const val PREFS = "backstage_a11y"
    private const val KEY_PAUSED_SINCE = "detector_paused_since"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun persist(context: Context, since: Long?) {
        runCatching { prefs(context).edit().apply { if (since == null) remove(KEY_PAUSED_SINCE) else putLong(KEY_PAUSED_SINCE, since) }.commit() }
    }

    /** A window became frontmost ([AccessibilityBridge.onEvent]); cheap on the hot path. */
    fun onForeground(context: Context, pkg: String?) {
        if (pkg == null || paused) return
        val settings = settings(context) ?: return
        if (!settings.accessibilityConsent || !settings.detectorPause || pkg !in settings.detectorPackageSet()) return
        if (runningTask()) return
        scope.launch { enter(context, settings) }
    }

    private suspend fun enter(context: Context, settings: Settings) = lock.withLock {
        if (pausedSinceMs != null) return@withLock
        // Re-checked under the lock: a task may have started since onForeground.
        if (runningTask()) return@withLock
        val component = AccessibilityAutoEnable.component(context) ?: return@withLock
        if (!AccessibilityAutoEnable.enabledInSettings(context)) return@withLock
        val now = System.currentTimeMillis()
        pausedSinceMs = now
        persist(context, now)
        AccessibilityAutoEnable.markSelfWrite()
        if (!disableDirect(context, component) && !disablePrivileged(component)) {
            // Could not take the service out: nothing to undo.
            pausedSinceMs = null
            persist(context, null)
            return@withLock
        }
        // The pause outlives the unbind: hold the foreground service so the process survives for the tick loop.
        AgentService.paused = true
        runCatching { AgentService.refresh(context) }
        Log.i(TAG, "accessibility paused for a detector")
        startTicking(context, settings)
    }

    private fun startTicking(context: Context, settings: Settings) {
        tick?.cancel()
        tick = scope.launch {
            // Already clamped by the getter; the cap only needs to hold for the loop's exit test.
            val delayMs = settings.detectorPauseDelayMs.toLong().coerceAtMost(MAX_PAUSE_MS)
            while (true) {
                delay(nextDelay(delayMs, pausedSinceMs ?: break, System.currentTimeMillis()))
                val since = pausedSinceMs ?: break
                when (decide(foregroundPackage(context), since, System.currentTimeMillis(), settings.detectorPackageSet())) {
                    Next.EXIT -> {
                        exit(context)
                        break
                    }

                    Next.POSTPONE -> Unit
                    else -> break
                }
            }
        }
    }

    /** Re-enables at once (the detector left, or the cap is reached). */
    fun exit(context: Context) {
        scope.launch {
            lock.withLock {
                if (pausedSinceMs == null) return@withLock
                pausedSinceMs = null
                tick?.cancel()
                tick = null
                Log.i(TAG, "accessibility resumed")
                reEnable(context)
                AgentService.paused = false
                runCatching { AgentService.refresh(context) }
            }
        }
    }

    /**
     * A task needs the service ([AccessibilityAutoEnable.ensureConnected]): end the pause without re-enabling (the
     * caller does that) and keep the persisted marker until the service is back.
   */
    fun release(context: Context) {
        if (pausedSinceMs == null) return
        pausedSinceMs = null
        tick?.cancel()
        tick = null
        persist(context, null)
        AgentService.paused = false
        runCatching { AgentService.refresh(context) }
        Log.i(TAG, "accessibility pause ended for a task")
    }

    /** Process start: a pause that a process death interrupted is ended, so the service does not stay off. */
    fun recover(context: Context) {
        val since = runCatching { prefs(context).getLong(KEY_PAUSED_SINCE, 0L) }.getOrDefault(0L)
        if (since == 0L || paused) return
        scope.launch { lock.withLock { if (!paused) { Log.i(TAG, "recovering an interrupted accessibility pause"); reEnable(context) } } }
    }

    /**
     * Puts the service back (only with the user's consent: withdrawn meanwhile, it stays off). The persisted marker is
     * cleared once the service is listed again; a failure keeps it, so the next start retries ([recover]).
   */
    private suspend fun reEnable(context: Context) {
        val consent = settings(context)?.accessibilityConsent ?: false
        if (!consent) { persist(context, null); return }
        repeat(3) { attempt ->
            runCatching { AccessibilityAutoEnable.ensure(context) }
            if (AccessibilityAutoEnable.enabledInSettings(context)) { persist(context, null); return }
            if (attempt < 2) delay(2_000)
        }
        Log.w(TAG, "re-enabling the accessibility service failed; retried at the next start")
    }

    private fun disableDirect(context: Context, component: String): Boolean = runCatching {
        val cr = context.contentResolver
        val current = AndroidSettings.Secure.getString(cr, AccessibilityEnabler.KEY_ENABLED_SERVICES)
        if (!AccessibilityEnabler.contains(current, component)) return false
        AndroidSettings.Secure.putString(cr, AccessibilityEnabler.KEY_ENABLED_SERVICES, AccessibilityEnabler.remove(current, component))
        true
    }.getOrDefault(false)

    private suspend fun disablePrivileged(component: String): Boolean {
        if (!Privileged.available()) return false
        return runCatching {
            val read = Privileged.exec("settings", "get", "secure", AccessibilityEnabler.KEY_ENABLED_SERVICES)
            // A failed or empty read must never be written back: it would clear every other enabled service.
            if (read.code != 0 || !AccessibilityEnabler.contains(read.out, component)) return false
            val next = AccessibilityEnabler.remove(read.out, component)
            Privileged.exec("settings", "put", "secure", AccessibilityEnabler.KEY_ENABLED_SERVICES, next).code == 0
        }.getOrDefault(false)
    }

    /** Foreground package from usage stats; null without the app-op or a recent event (then the cap re-enables). */
    private fun foregroundPackage(context: Context): String? = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return@runCatching null
        val now = System.currentTimeMillis()
        // UsageEvents is not Closeable on every level we run on; it holds no fd worth releasing.
        val events = usm.queryEvents(now - 60_000, now)
        var pkg: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) pkg = event.packageName
        }
        pkg
    }.getOrNull()

    private fun runningTask(): Boolean =
        runCatching { com.eggman.backstage.init.Backstage.controller.state.value.busy }.getOrDefault(false)

    private fun settings(context: Context): Settings? =
        runCatching { com.eggman.backstage.init.Backstage.environment.settings }.getOrNull()
}
