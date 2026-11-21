package com.eggman.backstage.overlay

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.ui.settings.SettingsStores

/** Overlay permission helpers + bubble start/stop. */
object Overlay {

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Grant SYSTEM_ALERT_WINDOW through the privileged runtime (Dextop's wireless-ADB / Shizuku
     * shell) when available. Returns true when the op reports success.
   */
    suspend fun grantPrivileged(context: Context): Boolean {
        val host = AgentAccess.host() ?: return false
        if (!host.privilegedAvailable()) return false
        val r = runCatching { host.shell("appops", "set", context.packageName, "SYSTEM_ALERT_WINDOW", "allow") }.getOrNull()
        return r?.ok == true && (canDraw(context) || appOpAllowed(context))
    }

    private fun appOpAllowed(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, android.os.Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * Show/hide the bubble to match the setting (needs the overlay permission). From the background Android 15+
     * refuses the start until a Backstage window is visible; then [fromBackground] posts a one-tap notification.
   */
    fun refresh(context: Context, fromBackground: Boolean = false) {
        val app = context.applicationContext
        // Dragged onto the remove target: stays away (also across restarts) until it is shown again on purpose.
        val want = BubbleModel.wanted(SettingsStores.get(app).bubbleEnabled, dismissed(app)) && canDraw(app)
        if (!want) {
            app.stopService(Intent(app, BubbleService::class.java))
            return
        }
        val started = runCatching { app.startForegroundService(Intent(app, BubbleService::class.java)) }.isSuccess
        if (!started && fromBackground) notifyBubbleParked(app)
    }

    /** The bubble's own preferences (position, dismissed). */
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The user dragged the bubble onto the remove target and has not shown it again since. */
    fun dismissed(context: Context): Boolean = runCatching { prefs(context).getBoolean(KEY_DISMISSED, false) }.getOrDefault(false)

    fun setDismissed(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISMISSED, value).apply()
    }

    /** Settings' "show the bubble": forget a drag onto the remove target and show it (with the overlay permission). */
    fun show(context: Context) {
        setDismissed(context, false)
        refresh(context)
    }

    private fun notifyBubbleParked(app: Context) =
        notifyShowAgain(app, "Floating bubble is off after a restart", "Tap to show it again.")

    /** After a drag onto the remove target: one tap shows the bubble again. */
    fun notifyBubbleHidden(context: Context) =
        notifyShowAgain(context.applicationContext, BubbleModel.HIDDEN_TITLE, BubbleModel.HIDDEN_TEXT)

    private fun notifyShowAgain(app: Context, title: String, text: String) {
        val show = android.app.PendingIntent.getForegroundService(
            app, 12, Intent(app, BubbleService::class.java).setAction(BubbleService.ACTION_SHOW),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            app.getSystemService(android.app.NotificationManager::class.java)?.notify(
                com.eggman.backstage.notify.Notifications.ID_BUBBLE,
                android.app.Notification.Builder(app, com.eggman.backstage.notify.NotifyChannels.BUBBLE)
                    .setSmallIcon(com.eggman.backstage.R.drawable.bs_ic_agent)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(show)
                    .build(),
            )
        }
    }

    private const val PREFS = "backstage_bubble"
    private const val KEY_DISMISSED = "dismissed"
}
