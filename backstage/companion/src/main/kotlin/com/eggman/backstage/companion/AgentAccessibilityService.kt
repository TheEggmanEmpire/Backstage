package com.eggman.backstage.companion

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/** The companion screen service forwards only the event data requested by its connected client. */
class AgentAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val pending = LinkedHashMap<Pair<Int, String>, CompanionEvent>()
    private val flush = Runnable {
        val events = pending.values.toList()
        pending.clear()
        events.forEach(CompanionRuntime::event)
    }
    override fun onServiceConnected() = CompanionRuntime.connected(this)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || CompanionRuntime.backend?.service !== this || !CompanionRuntime.consent(this)) return
        val pkg = event.packageName?.toString().orEmpty().take(255)
        if (pkg == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.displayId == android.view.Display.DEFAULT_DISPLAY) {
            CompanionRuntime.onForeground(applicationContext, pkg)
        }
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val n = event.parcelableData as? Notification
            val title = n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = n?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: event.text.joinToString(" ")
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            CompanionRuntime.event(CompanionEvent(event.eventType, event.displayId, pkg, label.take(256),
                listOf(title, body).filter { it.isNotBlank() }.distinct().joinToString(": ").take(8_000), System.currentTimeMillis()))
        } else when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Coalesce per display AND package, so a Backstage phone overlay cannot overwrite a foreign event
                // that should reset settle detection. No raw accessibility objects or view text leave this callback.
                if (pending.isEmpty()) handler.postDelayed(flush, 50)
                pending[event.displayId to pkg] = CompanionEvent(event.eventType, event.displayId, pkg)
                // Coalesce per display AND package, so a Backstage phone overlay cannot overwrite a foreign event
                // that should reset settle detection. No raw accessibility objects or view text leave this callback.
                if (pending.size > 64) pending.remove(pending.keys.first())
            }
            else -> Unit // No unrelated accessibility event types are forwarded to the agent.
        }
    }
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean {
        clear()
        return super.onUnbind(intent)
    }
    override fun onDestroy() { clear(); super.onDestroy() }
    internal fun clearPendingEvents() {
        handler.removeCallbacks(flush)
        pending.clear()
    }
    private fun clear() {
        clearPendingEvents()
        CompanionRuntime.disconnected(this)
    }
}
