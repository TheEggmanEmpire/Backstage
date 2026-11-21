package com.eggman.backstage.entry

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.eggman.backstage.notify.Notifications
import com.eggman.backstage.notify.TaskNotifier
import com.eggman.backstage.overlay.Overlay
import com.eggman.backstage.schedule.Schedules
import kotlinx.coroutines.launch

/**
 * Process-start hook for the  surfaces (lower initOrder than the core's BackstageInitProvider,
 * so the agent controller is normally installed first; everything here tolerates its absence).
 */
class SurfacesInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return true
        runCatching { Notifications.ensureChannels(ctx) }
        runCatching { TaskNotifier.start(ctx) }
        runCatching { com.eggman.backstage.update.AppUpdates.start(ctx) }
        // The one session notification (desktop running / agent working), with "Message the agent", Stop and Popup.
        runCatching { com.eggman.backstage.notify.SessionNotification.start(ctx) }
        // Popup <-> in-app player as Backstage's own screens come and go.
        runCatching { com.eggman.backstage.shell.ShellLifecycle.start(ctx) }
        // Spoken replies for voice tasks, also while no screen of the app is open.
        runCatching { com.eggman.backstage.voice.ReplySpeaker.start(ctx) }
        // The agent's create/list/update/delete_scheduled_task tools use the same store and alarms as the UI.
        runCatching { com.eggman.backstage.tools.AgentExtensions.scheduler = com.eggman.backstage.schedule.AgentScheduledTasks(ctx) }
        SurfaceScope.launch {
            // Re-arms alarms and runs anything missed while the process is dead (deduplicated).
            runCatching { Schedules.reschedule(ctx) }
            // With the overlay permission the bubble may come back from the background; where Android refuses that
            // (15+, no visible window yet) a one-tap notification brings it back.
            runCatching { Overlay.refresh(ctx, fromBackground = true) }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
