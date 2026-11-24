package com.eggman.backstage.a11y

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.privileged.Privileged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android side of [AccessibilityEnabler]: switches the app's one accessibility service (the host's, Dextop's
 * MirrorService: [AccessibilityBridge.hostComponent]) on with WRITE_SECURE_SETTINGS (held directly when Dextop granted
 * it, else through the privileged shell), re-asserts it after Dextop rewrites the enabled-services list at desktop
 * session start, and drops the retired agent service's stale entry ([AccessibilityBridge.RETIRED_AGENT_SERVICE]).
 */
object AccessibilityAutoEnable {
    private const val TAG = "BackstageA11y"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pending: Job? = null
    /** Last Dextop session change (it rewrites the enabled-services list) and our own last write. */
    @Volatile private var lastDesktopChangeMs = 0L
    @Volatile private var lastSelfWriteMs = 0L
    private const val PREFS = "backstage_a11y"
    private const val KEY_USER_OFF = "user_turned_off"
    /** A service stop this close to a desktop change or to our own write is not the user's doing. */
    const val TRANSITION_WINDOW_MS = 10_000L

    /**
     * Whether the service disappearing from the enabled list is the user's choice: it is still gone after
     * a grace period, and neither a Dextop session change nor our own write happened around that time.
   */
    fun isUserOptOut(stillListed: Boolean, nowMs: Long, lastDesktopChangeMs: Long, lastSelfWriteMs: Long): Boolean =
        !stillListed && nowMs - lastDesktopChangeMs > TRANSITION_WINDOW_MS && nowMs - lastSelfWriteMs > TRANSITION_WINDOW_MS

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun userOptedOut(context: Context): Boolean = runCatching { prefs(context).getBoolean(KEY_USER_OFF, false) }.getOrDefault(false)

    /** The service came up (the user or our setup turned it on): auto-enable may manage it again. */
    fun onServiceConnected(context: Context) {
        runCatching { prefs(context).edit().putBoolean(KEY_USER_OFF, false).apply() }
    }

    /** The service went away: after a short grace period decide whether the user switched it off. */
    fun onServiceGone(context: Context) {
        val app = context.applicationContext ?: context
        scope.launch {
            delay(2_000)
            val now = System.currentTimeMillis()
            if (!DetectorPause.paused && isUserOptOut(enabledInSettings(app), now, lastDesktopChangeMs, lastSelfWriteMs)) {
                Log.i(TAG, "accessibility service turned off by the user; auto-enable paused until it is turned on again")
                runCatching { prefs(app).edit().putBoolean(KEY_USER_OFF, true).apply() }
            }
        }
    }

    /** The one accessibility service to switch on, or null when this build has none (no Dextop host). */
    fun component(context: Context): String? = AccessibilityBridge.hostComponent()

    /** The retired separate agent service of [context]'s package (Backstage ). */
    fun retiredComponent(context: Context): String = "${context.packageName}/${AccessibilityBridge.RETIRED_AGENT_SERVICE}"

    fun enabledInSettings(context: Context): Boolean = runCatching {
        val component = component(context) ?: return false
        AccessibilityEnabler.contains(Settings.Secure.getString(context.contentResolver, AccessibilityEnabler.KEY_ENABLED_SERVICES), component)
    }.getOrDefault(false)

    fun connected(): Boolean = AgentAccessibility.instance != null

    /** Stamps our own write, so the unbind it causes is not mistaken for the user switching the service off. */
    fun markSelfWrite() {
        lastSelfWriteMs = System.currentTimeMillis()
    }

    /** Direct write when the app holds WRITE_SECURE_SETTINGS; avoids a shell round-trip. */
    private fun tryDirect(context: Context, component: String): Boolean = runCatching {
        val cr = context.contentResolver
        val current = Settings.Secure.getString(cr, AccessibilityEnabler.KEY_ENABLED_SERVICES)
        val merged = AccessibilityEnabler.merge(AccessibilityEnabler.remove(current, retiredComponent(context)), component)
        if (merged != current) Settings.Secure.putString(cr, AccessibilityEnabler.KEY_ENABLED_SERVICES, merged)
        Settings.Secure.putInt(cr, AccessibilityEnabler.KEY_ACCESSIBILITY_ENABLED, 1)
        true
    }.getOrDefault(false)

    suspend fun ensure(context: Context): AccessibilityEnabler.Outcome {
        val component = component(context) ?: return AccessibilityEnabler.Outcome.Failed(AccessibilityEnabler.NO_HOST)
        if (PLAY_DISTRIBUTION) return AccessibilityEnabler.Outcome.Failed("Enable desktop accessibility in Android Settings.")
        if (connected() && enabledInSettings(context)) return AccessibilityEnabler.Outcome.AlreadyEnabled
        if (userOptedOut(context)) {
            return AccessibilityEnabler.Outcome.Failed(
                "The Backstage accessibility service was switched off, so Backstage leaves it off. " +
                    AccessibilityEnabler.manualGuidance(appLabel(context), Build.VERSION.SDK_INT),
            )
        }
        lastSelfWriteMs = System.currentTimeMillis()
        if (tryDirect(context, component) && enabledInSettings(context)) return AccessibilityEnabler.Outcome.Enabled
        if (!Privileged.available()) {
            return AccessibilityEnabler.Outcome.Failed(AccessibilityEnabler.manualGuidance(appLabel(context), Build.VERSION.SDK_INT))
        }
        return AccessibilityEnabler.ensureEnabled({ argv ->
            val r = Privileged.exec(*argv)
            r.code to r.out
        }, component, retired = retiredComponent(context)).also { Log.i(TAG, "auto-enable: $it") }
    }

    /** Waits (bounded) until the service is connected after enabling. */
    suspend fun ensureConnected(context: Context, timeoutMs: Long = 4_000): Boolean {
        if (connected()) return true
        if (PLAY_DISTRIBUTION) return false
        // A task needs the service: it takes precedence over a running detector pause.
        if (DetectorPause.paused) DetectorPause.release(context)
        ensure(context)
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (connected()) return true
            delay(150)
        }
        return connected()
    }

    /** Re-assert shortly after Dextop's own remove/re-add cycle (≈180 ms) completes. */
    fun reassertSoon(context: Context, delayMs: Long = 900) {
        if (PLAY_DISTRIBUTION) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            // A detector pause owns the list meanwhile and re-enables itself (DetectorPause).
            if (DetectorPause.paused) return@launch
            if (!enabledInSettings(context) || !connected()) ensure(context)
        }
    }

    fun desktopListener(context: Context, enabled: () -> Boolean): DesktopHost.Listener = object : DesktopHost.Listener {
        override fun onDesktopChanged(geometry: DesktopHost.Geometry?, presentation: DesktopHost.Presentation) {
            lastDesktopChangeMs = System.currentTimeMillis()
            if (geometry != null && enabled()) reassertSoon(context)
        }
    }

    private fun appLabel(context: Context): String =
        runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }.getOrDefault("Backstage")
}
