package com.eggman.backstage.companion

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import com.eggman.backstage.a11y.LocalAccessibilityBackend
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.companion.privilege.CompanionPrivilege
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Process-local state shared by the two services; the binder service never instantiates an accessibility service. */
internal object CompanionRuntime {
    private const val PREFS = "companion_consent"
    private const val CONSENT = "screen_and_notifications_v1"
    private const val ACCESSIBILITY_PREFS = "companion_accessibility"
    private const val KEY_AUTO_ENABLE = "auto_enable"
    private const val KEY_PAUSE_DETECTOR = "pause_detector"
    private const val KEY_RECHECK_SECONDS = "recheck_seconds"
    private const val KEY_DETECTOR_PACKAGES = "detector_packages"
    private const val KEY_PAUSED_SINCE = "paused_since"
    private const val MAX_PAUSE_MS = 60_000L
    private val a11yExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Backstage-companion-accessibility").apply { isDaemon = true }
    }
    @Volatile private var recheck: ScheduledFuture<*>? = null
    @Volatile private var pausedSinceMs: Long? = null

    // The system owns the connected service; disconnected/revoked callbacks explicitly clear this reference.
    @SuppressLint("StaticFieldLeak") @Volatile var backend: LocalAccessibilityBackend? = null
        private set
    @SuppressLint("StaticFieldLeak") @Volatile private var connectedService: AgentAccessibilityService? = null
    private val visibleScreens = AtomicInteger()
    val protectCompanionUi: Boolean get() = visibleScreens.get() != 0

    fun uiStarted() { visibleScreens.incrementAndGet(); stateListeners.forEach { it() } }
    fun uiStopped() { visibleScreens.decrementAndGet(); stateListeners.forEach { it() } }

    val stateListeners = CopyOnWriteArraySet<() -> Unit>()
    val clientListeners = CopyOnWriteArraySet<() -> Unit>()
    val eventListeners = CopyOnWriteArraySet<(CompanionEvent) -> Unit>()
    private val clients = CopyOnWriteArraySet<Any>()
    val hasBackstageClient: Boolean get() = clients.isNotEmpty()

    fun clientConnected(session: Any) {
        if (clients.add(session)) clientListeners.forEach { it() }
    }

    fun clientDisconnected(session: Any) {
        if (clients.remove(session)) clientListeners.forEach { it() }
    }

    fun consent(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONSENT, false)
    @SuppressLint("ApplySharedPref") // Durably record the user's decision before handing them to Android Settings.
    fun setConsent(context: Context, accepted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CONSENT, accepted).commit()
        if (!accepted) {
            connectedService?.clearPendingEvents()
            a11yExecutor.execute {
                recheck?.cancel(false)
                recheck = null
                pausedSinceMs = null
                accessibilityPrefs(context).edit().remove(KEY_PAUSED_SINCE).commit()
            }
        }
        updateBackend(context)
        if (accepted && autoEnable(context) && connectedService == null) scheduleAutoEnable(context)
    }

    fun status(context: Context): Int = when {
        connectedService == null -> CompanionProtocol.OFF
        !consent(context) -> CompanionProtocol.CONSENT_NEEDED
        backend == null -> CompanionProtocol.OFF
        else -> CompanionProtocol.READY
    }

    fun restoreAccessibilitySettings(context: Context) {
        a11yExecutor.execute {
            if (!consent(context)) return@execute
            recoverPause(context)
            if (autoEnable(context)) scheduleAutoEnable(context)
        }
    }

    /** Applies only settings supplied by the authenticated companion client. */
    fun applyAccessibilitySettings(context: Context, settings: Bundle): Boolean {
        val parsed = CompanionAccessibilitySettings.from(settings) ?: return false
        val prefs = accessibilityPrefs(context)
        val committed = prefs.edit()
            .putBoolean(KEY_AUTO_ENABLE, parsed.autoEnable)
            .putBoolean(KEY_PAUSE_DETECTOR, parsed.pauseForDetector)
            .putInt(KEY_RECHECK_SECONDS, parsed.recheckSeconds)
            .putString(KEY_DETECTOR_PACKAGES, parsed.detectorPackages.joinToString(":"))
            .commit()
        if (!committed) return false
        a11yExecutor.execute {
            if (!consent(context)) {
                recheck?.cancel(false)
                recheck = null
                return@execute
            }
            if (parsed.pauseForDetector) recoverPause(context) else resumeAfterPause(context)
            if (parsed.autoEnable && connectedService == null) scheduleAutoEnable(context)
        }
        return true
    }

    fun connected(service: AgentAccessibilityService) {
        connectedService = service
        updateBackend(service)
        a11yExecutor.execute {
            val since = accessibilityPrefs(service).getLong(KEY_PAUSED_SINCE, 0L).takeIf { it > 0L }
            if (since != null) {
                pausedSinceMs = null
                accessibilityPrefs(service).edit().remove(KEY_PAUSED_SINCE).commit()
                recheck?.cancel(false)
                recheck = null
            }
        }
    }

    fun disconnected(service: AgentAccessibilityService) {
        if (connectedService !== service) return
        connectedService = null
        backend = null
        stateListeners.forEach { it() }
        if (consent(service) && autoEnable(service)) scheduleAutoEnable(service)
    }

    private fun updateBackend(context: Context) {
        val service = connectedService?.takeIf { consent(context) }
        if (backend?.service !== service) backend = service?.let(::LocalAccessibilityBackend)
        stateListeners.forEach { it() }
    }

    fun event(event: CompanionEvent) { if (backend != null) eventListeners.forEach { it(event) } }

    fun onForeground(context: Context, packageName: String?) {
        if (packageName.isNullOrBlank() || !consent(context) || !pauseForDetector(context)) return
        if (packageName !in detectorPackages(context)) return
        a11yExecutor.execute { beginPause(context, packageName) }
    }

    private fun beginPause(context: Context, reportedPackage: String) {
        val configured = detectorPackages(context)
        if (pausedSinceMs != null || !consent(context) || !pauseForDetector(context) || reportedPackage !in configured) return
        val foreground = foregroundPackage(context) ?: reportedPackage
        if (foreground !in configured) return
        val component = ownAccessibilityComponent(context)
        val current = readEnabledServices(context) ?: return
        if (!containsComponent(current, component)) return
        val startedAt = System.currentTimeMillis()
        if (!accessibilityPrefs(context).edit().putLong(KEY_PAUSED_SINCE, startedAt).commit()) return
        pausedSinceMs = startedAt
        CompanionPrivilege.setOwnAccessibilityEnabled(context, false) { disabled ->
            a11yExecutor.execute {
                if (!disabled) {
                    pausedSinceMs = null
                    accessibilityPrefs(context).edit().remove(KEY_PAUSED_SINCE).commit()
                } else startRecheck(context)
            }
        }
    }

    private fun startRecheck(context: Context) {
        recheck?.cancel(false)
        recheck = a11yExecutor.scheduleWithFixedDelay({
            val since = pausedSinceMs ?: accessibilityPrefs(context).getLong(KEY_PAUSED_SINCE, 0L).takeIf { it > 0L }
            if (since == null) {
                recheck?.cancel(false)
                recheck = null
            } else if (!consent(context)) {
                pausedSinceMs = null
                accessibilityPrefs(context).edit().remove(KEY_PAUSED_SINCE).commit()
                recheck?.cancel(false)
                recheck = null
            } else {
                val foreground = foregroundPackage(context)
                if (System.currentTimeMillis() - since >= MAX_PAUSE_MS ||
                    (foreground != null && foreground !in detectorPackages(context))) resumeAfterPause(context)
            }
        }, recheckSeconds(context).toLong(), recheckSeconds(context).toLong(), TimeUnit.SECONDS)
    }

    private fun resumeAfterPause(context: Context) {
        if (pausedSinceMs == null && accessibilityPrefs(context).getLong(KEY_PAUSED_SINCE, 0L) <= 0L) return
        recheck?.cancel(false)
        recheck = null
        if (!consent(context)) {
            pausedSinceMs = null
            accessibilityPrefs(context).edit().remove(KEY_PAUSED_SINCE).commit()
            return
        }
        CompanionPrivilege.setOwnAccessibilityEnabled(context, true) { enabled ->
            a11yExecutor.execute {
                if (enabled) {
                    pausedSinceMs = null
                    accessibilityPrefs(context).edit().remove(KEY_PAUSED_SINCE).commit()
                } else {
                    startRecheck(context)
                }
            }
        }
    }

    private fun recoverPause(context: Context) {
        val since = accessibilityPrefs(context).getLong(KEY_PAUSED_SINCE, 0L).takeIf { it > 0L } ?: return
        if (!consent(context)) {
            recheck?.cancel(false)
            recheck = null
            pausedSinceMs = null
            accessibilityPrefs(context).edit().remove(KEY_PAUSED_SINCE).commit()
            return
        }
        pausedSinceMs = since
        if (!pauseForDetector(context) || System.currentTimeMillis() - since >= MAX_PAUSE_MS) {
            resumeAfterPause(context)
        } else startRecheck(context)
    }

    private fun scheduleAutoEnable(context: Context) {
        a11yExecutor.schedule({
            if (!consent(context) || !autoEnable(context) || pausedSinceMs != null || connectedService != null) return@schedule
            val current = readEnabledServices(context) ?: return@schedule
            if (!containsComponent(current, ownAccessibilityComponent(context)) ||
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 0) {
                CompanionPrivilege.setOwnAccessibilityEnabled(context, true) { }
            }
        }, 2, TimeUnit.SECONDS)
    }

    private fun readEnabledServices(context: Context): String? {
        val (code, stdout, _) = runCatching {
            CompanionPrivilege.runSystemCommand(context, listOf("settings", "get", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), 5_000)
        }.getOrNull() ?: return null
        if (code != 0) return null
        return stdout.trim().takeUnless { it == "null" }.orEmpty()
    }

    private fun foregroundPackage(context: Context): String? {
        val shellOutput = runCatching {
            CompanionPrivilege.runSystemCommand(context, listOf("dumpsys", "window", "displays"), 5_000).let { (code, out, _) -> if (code == 0) out else "" }
        }.getOrDefault("")
        val focused = Regex("(?:mCurrentFocus|mFocusedApp)[^\\n]*?\\s([A-Za-z][A-Za-z0-9_.]+)/(?:[A-Za-z][A-Za-z0-9_.$]*)")
            .find(shellOutput)?.groupValues?.getOrNull(1)
        if (!focused.isNullOrBlank()) return focused
        return runCatching {
            val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            val events = manager.queryEvents(now - 60_000L, now)
            var latest: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = event.packageName
            }
            latest
        }.getOrNull()
    }

    private fun ownAccessibilityComponent(context: Context) = "${context.packageName}/${AgentAccessibilityService::class.java.name}"
    private fun containsComponent(list: String, component: String): Boolean {
        val expected = ComponentName.unflattenFromString(component) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it.trim()) == expected }
    }
    private fun accessibilityPrefs(context: Context) = context.applicationContext.getSharedPreferences(ACCESSIBILITY_PREFS, Context.MODE_PRIVATE)
    private fun autoEnable(context: Context) = accessibilityPrefs(context).getBoolean(KEY_AUTO_ENABLE, false)
    private fun pauseForDetector(context: Context) = accessibilityPrefs(context).getBoolean(KEY_PAUSE_DETECTOR, false)
    private fun recheckSeconds(context: Context) = accessibilityPrefs(context).getInt(KEY_RECHECK_SECONDS, 5).coerceIn(1, 60)
    private fun detectorPackages(context: Context) = accessibilityPrefs(context).getString(KEY_DETECTOR_PACKAGES, "").orEmpty()
        .split(':', ',', '\n').map(String::trim).filter(String::isNotEmpty).toSet()
}
