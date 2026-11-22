package com.eggman.backstage.tools

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.eggman.backstage.R
import com.eggman.backstage.notify.Notifications
import com.eggman.backstage.notify.NotifyChannels
import com.eggman.backstage.exec.OverlayHooks

/** startActivity returning normally is not proof that Android allowed a background start. */
internal object SmsDraftPolicy {
    private const val NOTIFICATION_ID = 0x4253_0006

    fun canOpen(foreground: Boolean, overlayGranted: Boolean, overlayVisible: Boolean): Boolean =
        foreground || (overlayGranted && overlayVisible)

    fun canOpen(context: Context): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return canOpen(com.eggman.backstage.shell.ShellLifecycle.visible.value &&
            state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            Settings.canDrawOverlays(context), runCatching { OverlayHooks.controller?.bounds() != null }.getOrDefault(false))
    }

    fun notify(context: Context, draft: Intent): Boolean = runCatching {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        Notifications.ensureChannels(app)
        if (nm.getNotificationChannel(NotifyChannels.QUESTIONS)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val review = PendingIntent.getActivity(app, NOTIFICATION_ID, draft,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIFICATION_ID, Notification.Builder(app, NotifyChannels.QUESTIONS)
            .setSmallIcon(R.drawable.bs_ic_agent)
            .setContentTitle("Review SMS draft")
            .setContentText("Tap to review the draft in your SMS app. You choose whether to send it.")
            .setContentIntent(review).setAutoCancel(true).build())
        true
    }.getOrDefault(false)
}
