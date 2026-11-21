package com.eggman.backstage.ui.onboarding

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.overlay.Overlay
import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.privileged.ShizukuSetup
import com.eggman.backstage.privileged.ShizukuState
import com.eggman.backstage.ui.settings.ProviderOptions
import com.eggman.backstage.ui.settings.SettingsStores

/** Reads the real device state for the onboarding checklist. */
object AndroidPermissionProbe {

    fun snapshot(context: Context): ProbeSnapshot {
        val s = SettingsStores.get(context)
        val host = AgentAccess.host()
        val readiness = runCatching { AgentAccess.controller()?.readiness() }.getOrNull()
        val opt = ProviderOptions.byId(s.providerId)
        val providerOk = readiness?.providerConfigured
            ?: (opt != null && ProviderOptions.validate(opt, s.apiKey(), s.model(), s.baseUrl()).isEmpty())
        return ProbeSnapshot(
            providerConfigured = providerOk,
            hostAvailable = host != null,
            privileged = runCatching { Privileged.available() }.getOrDefault(false),
            accessibility = readiness?.accessibilityEnabled ?: accessibilityListed(context),
            overlay = Overlay.canDraw(context),
            notifications = granted(context, Manifest.permission.POST_NOTIFICATIONS),
            microphone = granted(context, Manifest.permission.RECORD_AUDIO),
            calendar = granted(context, Manifest.permission.READ_CALENDAR) && granted(context, Manifest.permission.WRITE_CALENDAR),
            exactAlarms = context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true,
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
            sideloaded = sideloaded(context),
            wakeWordWanted = s.wakeWordEnabled,
            bubbleWanted = s.bubbleEnabled,
            shizuku = runCatching { ShizukuSetup.current(context) }.getOrDefault(ShizukuState.NOT_INSTALLED),
            defaultAssistant = runCatching {
                context.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
            }.getOrDefault(false),
            deviceAccess = runCatching { com.eggman.backstage.tools.DeviceAccess.granted(context) }.getOrDefault(emptySet()),
            deviceAccessSupported = com.eggman.backstage.tools.DeviceAccess.supported(context),
        )
    }

    /** The "Default digital assistant app" picker (the assistant role cannot be requested with a dialog). */
    fun assistantSettings(): Intent =
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun defaultAppsSettings(): Intent =
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Fallback when the core is not installed: is our service listed as enabled (the retired agent service does not count)? */
    private fun accessibilityListed(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any {
            it.startsWith(context.packageName + "/") && !it.endsWith(com.eggman.backstage.a11y.AccessibilityBridge.RETIRED_AGENT_SERVICE)
        }
    }

    private fun sideloaded(context: Context): Boolean = runCatching {
        val installer = context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        installer == null || installer !in setOf("com.android.vending", "org.fdroid.fdroid", "com.aurora.store")
    }.getOrDefault(true)

    fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun exactAlarmSettings(context: Context) =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @android.annotation.SuppressLint("BatteryLife")
    fun batterySettings(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationSettings(context: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Grant everything grantable through the privileged shell (Shizuku or Dextop's runtime); (succeeded, total). */
    suspend fun grantAllPrivileged(context: Context, items: List<ChecklistItem>): Pair<Int, Int> {
        if (!Privileged.available()) return 0 to 0
        val cmds = PermissionChecklist.privilegedCommands(context.packageName, items)
        var ok = 0
        cmds.forEach { argv -> if (runCatching { Privileged.exec(*argv.toTypedArray()) }.getOrNull()?.ok == true) ok++ }
        return ok to cmds.size
    }
}
