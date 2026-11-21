package com.eggman.backstage.notify

import android.content.Context
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.SurfaceScope
import com.eggman.backstage.notify.TaskNotificationModel.Companion.terminal
import com.eggman.backstage.ui.settings.SettingsStores
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Mirrors the agent state into notifications: questions with Approve/Deny/Reply (RemoteInput), and results for tasks
 * the user did not watch in the app. What the agent is doing is the session notification's job.
 */
object TaskNotifier {
    /** Set by the app UI while the chat screen is visible (results are then shown in-app only). */
    @Volatile
    var appVisible: Boolean = false

    private var job: Job? = null

    /** Pure decision used by [start]; exposed for tests. */
    fun shouldPostResult(s: TaskSnapshot, appVisible: Boolean, notifyResults: Boolean): Boolean {
        if (!s.status.terminal || !notifyResults) return false
        // Scheduled runs post their own result (with "Run again"); one notification per run is enough.
        if (s.request.source == Source.SCHEDULE) return false
        if (s.status == TaskStatus.CANCELLED && s.request.source == Source.CHAT) return false
        return !(appVisible && s.request.source == Source.CHAT)
    }

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = SurfaceScope.launch {
            // The core may install the controller after us; wait for it.
            while (AgentAccess.controller() == null) delay(500)
            var lastTerminalId: String? = null
            var lastPosted: Pair<String, TaskNotificationModel>? = null
            AgentAccess.state().map { it.current }.distinctUntilChanged().collect { s ->
                if (s == null) {
                    lastPosted = null
                    Notifications.clearTaskStatus(app)
                    return@collect
                }
                if (s.status.terminal) {
                    lastPosted = null
                    Notifications.clearTaskStatus(app)
                    if (s.id != lastTerminalId) {
                        lastTerminalId = s.id
                        if (shouldPostResult(s, appVisible, SettingsStores.get(app).notifyResults)) Notifications.notifyTask(app, s)
                    }
                } else if (s.status == TaskStatus.WAITING_FOR_USER) {
                    // Questions and approvals still ring on their own (heads-up); progress is the session notification's.
                    val key = s.id to TaskNotificationModel.from(s)
                    if (key != lastPosted) {
                        lastPosted = key
                        Notifications.notifyTask(app, s)
                    }
                } else {
                    lastPosted = null
                    Notifications.clearTaskStatus(app)
                }
            }
        }
    }
}
