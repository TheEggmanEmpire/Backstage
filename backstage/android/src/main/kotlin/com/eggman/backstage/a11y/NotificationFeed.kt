package com.eggman.backstage.a11y

import com.eggman.backstage.tools.NotificationEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Recent notifications as reported to our accessibility service (TYPE_NOTIFICATION_STATE_CHANGED),
 * so the agent can answer "what did X just write?" without a separate notification-listener
 * permission. In-memory ring buffer only (privacy: nothing is persisted).
 */
object NotificationFeed {
    private const val CAPACITY = 60
    private val buffer = ArrayDeque<NotificationEntry>()
    private val _events = MutableSharedFlow<NotificationEntry>(extraBufferCapacity = 16)

    /** New notifications as they arrive ( may use this for notification-triggered tasks). */
    val events: SharedFlow<NotificationEntry> = _events.asSharedFlow()

    /** Packages whose notifications are never recorded (our own, system UI noise). */
    @Volatile var ignoredPackages: Set<String> = setOf("android", "com.android.systemui")

    fun add(entry: NotificationEntry) {
        if (entry.packageName in ignoredPackages || entry.text.isBlank()) return
        synchronized(buffer) {
            // Collapse exact repeats (progress notifications re-post constantly).
            val last = buffer.lastOrNull()
            if (last != null && last.packageName == entry.packageName && last.text == entry.text) {
                buffer.removeLast()
            }
            buffer.addLast(entry)
            while (buffer.size > CAPACITY) buffer.removeFirst()
        }
        _events.tryEmit(entry)
    }

    fun recent(): List<NotificationEntry> = synchronized(buffer) { buffer.toList().asReversed() }

    fun clear() = synchronized(buffer) { buffer.clear() }
}
