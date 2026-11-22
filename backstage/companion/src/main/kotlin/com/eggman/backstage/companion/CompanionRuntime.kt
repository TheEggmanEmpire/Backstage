package com.eggman.backstage.companion

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import com.eggman.backstage.a11y.LocalAccessibilityBackend
import com.eggman.backstage.a11y.companion.CompanionProtocol
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/** Process-local state shared by the two services; the binder service never instantiates an accessibility service. */
internal object CompanionRuntime {
    private const val PREFS = "companion_consent"
    private const val CONSENT = "screen_and_notifications_v1"
    // The system owns the connected service; disconnected/revoked callbacks explicitly clear this reference.
    @SuppressLint("StaticFieldLeak") @Volatile var backend: LocalAccessibilityBackend? = null
        private set
    private val visibleScreens = AtomicInteger()
    val protectCompanionUi: Boolean get() = visibleScreens.get() != 0

    fun uiStarted() { visibleScreens.incrementAndGet(); stateListeners.forEach { it() } }
    fun uiStopped() { visibleScreens.decrementAndGet(); stateListeners.forEach { it() } }

    val stateListeners = CopyOnWriteArraySet<() -> Unit>()
    val eventListeners = CopyOnWriteArraySet<(CompanionEvent) -> Unit>()

    fun consent(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONSENT, false)
    @SuppressLint("ApplySharedPref") // Durably record the user's decision before handing them to Android Settings.
    fun setConsent(context: Context, accepted: Boolean) {
        // Commit before opening Android's service picker, so the other component always sees the decision.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CONSENT, accepted).commit()
        if (!accepted) {
            val service = backend?.service
            backend = null
            stateListeners.forEach { it() }
            service?.disableSelf()
        } else stateListeners.forEach { it() }
    }

    fun status(context: Context): Int = when {
        !consent(context) -> CompanionProtocol.CONSENT_NEEDED
        backend == null -> CompanionProtocol.OFF
        else -> CompanionProtocol.READY
    }

    fun connected(service: AccessibilityService) {
        if (!consent(service)) { service.disableSelf(); return }
        backend = LocalAccessibilityBackend(service)
        stateListeners.forEach { it() }
    }
    fun disconnected(service: AccessibilityService) {
        if (backend?.service !== service) return
        backend = null
        stateListeners.forEach { it() }
    }
    fun event(event: CompanionEvent) { if (backend != null) eventListeners.forEach { it(event) } }
}
