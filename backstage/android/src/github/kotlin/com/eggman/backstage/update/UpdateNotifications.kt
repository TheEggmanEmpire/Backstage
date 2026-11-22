package com.eggman.backstage.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.eggman.backstage.R

/** Same framework notification pattern and icon as notify/Notifications; GitHub-only channel and entry point. */
internal object UpdateNotifications {
    const val CHANNEL = "backstage_updates"
    const val AVAILABLE = 0x4253_0008
    const val PROGRESS = 0x4253_0009
    const val INSTALL = 0x4253_000A

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Backstage releases, download progress and installation results" })
    }
    fun allowed(context: Context): Boolean = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        context.getSystemService(NotificationManager::class.java)?.let {
            it.areNotificationsEnabled() && it.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        } == true

    fun open(context: Context): PendingIntent = PendingIntent.getActivity(context, AVAILABLE,
        Intent(context, UpdateOpenActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun builder(context: Context, title: String, text: String) = Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.bs_ic_agent).setContentTitle(title).setContentText(text)
        .setContentIntent(open(context)).setOnlyAlertOnce(true)

    @Suppress("MissingPermission") // All ordinary notifications go through allowed; FGS notification is mandatory even if permission is denied.
    fun available(context: Context, version: String): Boolean {
        if (!allowed(context)) return false
        return runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(AVAILABLE,
                builder(context, "Backstage $version is available", "Tap to update").setAutoCancel(true).build())
        }.isSuccess
    }

    fun progress(context: Context, state: UpdateState): Notification {
        val cancel = PendingIntent.getBroadcast(context, PROGRESS, Intent(context, UpdateCancelReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val progress = if (state.total > 0) ((state.bytes * 100) / state.total).toInt().coerceIn(0, 100) else 0
        return builder(context, if (state.phase == UpdatePhase.INSTALLING) "Preparing installation" else "Downloading Backstage update",
            if (state.phase == UpdatePhase.VERIFYING) "Verifying APK" else "$progress%")
            .setCategory(Notification.CATEGORY_PROGRESS).setOngoing(true)
            .setProgress(100, progress, state.phase != UpdatePhase.DOWNLOADING)
            .apply { if (state.phase != UpdatePhase.INSTALLING) addAction(Notification.Action.Builder(null, "Cancel", cancel).build()) }.build()
    }

    @Suppress("MissingPermission")
    fun notice(context: Context, message: String, confirmation: PendingIntent? = null) {
        if (!allowed(context)) return
        context.getSystemService(NotificationManager::class.java)?.notify(INSTALL,
            builder(context, "Backstage update", message).setContentIntent(confirmation ?: open(context)).setAutoCancel(true).build())
    }
    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.apply { cancel(AVAILABLE); cancel(INSTALL) }
    }
}
