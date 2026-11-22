package com.eggman.backstage.a11y

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Agent accessibility façade backed by the connected host service. Screen operations use this API. */
class AgentAccessibility internal constructor(
    val service: AccessibilityService,
    private val backend: AccessibilityBackend,
) : AccessibilityBackend by backend {
    internal constructor(service: AccessibilityService) : this(service, LocalAccessibilityBackend(service))
    private val settle = PerDisplaySettle()
    fun settleFor(displayId: Int): UiSettleDetector = settle.forDisplay(displayId)

    /** Every event the host service receives ([AccessibilityBridge.onEvent]); only the agent's types matter here. */
    internal fun onEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            -> if (!ownPhoneEvent(event)) settle.onEvent(event.displayId, isWindowChange(event.eventType), "${event.packageName}/${event.windowId}/${event.className}")
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> recordNotification(event)
            else -> Unit // the host's own event types (clicks, focus, selection): not the agent's concern
        }
    }

    /**
     * Backstage's own windows on the phone display (bubble, agent cursor, mini desktop tile, chat) change while
     * the agent works and must not keep the phone's settle window open. On the desktop display our package also draws
     * the desktop itself (taskbar, start menu), so its events still count there.
   */
    private fun isWindowChange(type: Int) = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED

    private fun ownPhoneEvent(event: AccessibilityEvent): Boolean =
        event.displayId == android.view.Display.DEFAULT_DISPLAY && event.packageName?.toString() == service.packageName

    internal fun observeWindowEvent(displayId: Int, type: Int, packageName: String) {
        settle.onEvent(displayId, isWindowChange(type), packageName)
    }

    private fun recordNotification(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg == service.packageName) return
        val n = event.parcelableData as? android.app.Notification
        val title = n?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = n?.extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
            ?: n?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
            ?: event.text.joinToString(" ")
        val text = listOf(title, body).filter { it.isNotBlank() }.distinct().joinToString(": ")
        val pm = service.packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        NotificationFeed.add(com.eggman.backstage.tools.NotificationEntry(pkg, label, text, System.currentTimeMillis()))
    }


    suspend fun tap(displayId: Int, x: Float, y: Float) = gesture(displayId, Path().apply { moveTo(x, y) }, 60)
    suspend fun longPress(displayId: Int, x: Float, y: Float, ms: Long = 700) = gesture(displayId, Path().apply { moveTo(x, y) }, ms)
    suspend fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        gesture(displayId, Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile var instance: AgentAccessibility? = null
            internal set
        internal val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected
        fun kindOf(type: Int) = LocalAccessibilityBackend.kindOf(type)
    }
}
