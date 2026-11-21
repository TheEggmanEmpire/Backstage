package com.eggman.backstage.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * How each permission-gated device tool gets its access: the runtime permissions to ask for in a
 * system dialog, or the special-access settings page. The onboarding checklist offers one optional item per [Kind];
 * the registry re-reads the grants for every task ([ToolRegistry.Availability.of]), so a grant takes effect with
 * the next task, without a restart or a service reconnect.
 */
object DeviceAccess {
    enum class Kind(
        val title: String,
        val why: String,
        /** [DeviceControl] capabilities (and with them the tools in [DeviceControlTools.capabilityOf]) it unlocks. */
        val capabilities: Set<String>,
        /** Runtime permissions requested together (a system dialog); empty for special access. */
        val permissions: List<String>,
        /** Special-access settings page ([permissions] is empty then). */
        val settingsAction: String? = null,
        /** Personal data stays a choice the user makes in the system dialog: never granted through the shell. */
        val shellGrantable: Boolean = false,
    ) {
        CONTACTS("Contacts", "Look up a contact's number or email directly (contacts_search).",
            setOf("contacts"), listOf(Manifest.permission.READ_CONTACTS)),
        SMS("Send SMS", "Send a text directly (sms_send). You approve every message before it is sent.",
            setOf("sms"), listOf(Manifest.permission.SEND_SMS)),
        PHONE("Phone calls", "Start a call directly (phone_call). You approve every call before it is placed.",
            setOf("call"), listOf(Manifest.permission.CALL_PHONE)),
        LOCATION("Location", "Answer \"where am I?\" and location-based questions (location_get).",
            setOf("location"), listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
        SYSTEM_SETTINGS("Modify system settings", "Change brightness, screen timeout and auto-rotate without opening Settings.",
            setOf("brightness", "screen_timeout", "auto_rotate"), emptyList(), Settings.ACTION_MANAGE_WRITE_SETTINGS, shellGrantable = true),
        DND("Do Not Disturb access", "Read and switch Do Not Disturb.",
            setOf("dnd"), emptyList(), Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, shellGrantable = true),
    }

    fun kindOf(capability: String): Kind? = Kind.entries.firstOrNull { capability in it.capabilities }

    /** The special-access page for [kind] (null for runtime permissions, which are requested with a dialog). */
    fun settingsIntent(context: Context, kind: Kind): Intent? = kind.settingsAction?.let { action ->
        Intent(action).apply {
            // The write-settings page can open on this app directly; the DND page lists all apps.
            if (kind == Kind.SYSTEM_SETTINGS) data = Uri.fromParts("package", context.packageName, null)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Kinds this device can use at all (no SMS or calls without telephony). */
    fun supported(context: Context): Set<Kind> {
        val pm = context.packageManager
        return Kind.entries.filter { k ->
            when (k) {
                Kind.SMS -> runCatching { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) }.getOrDefault(false)
                Kind.PHONE -> runCatching { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING) }.getOrDefault(false)
                else -> true
            }
        }.toSet()
    }

    /** Kinds whose tools are usable: exactly what the registry will offer the model. */
    fun granted(context: Context): Set<Kind> = granted(AndroidDeviceControl(context))

    fun granted(control: DeviceControl): Set<Kind> =
        Kind.entries.filter { k -> k.capabilities.all { runCatching { control.has(it) }.getOrDefault(false) } }.toSet()

    /** Shell commands (uid 2000, e.g. through Shizuku) that grant [kind]; empty for personal data ([Kind.shellGrantable]). */
    fun shellGrant(pkg: String, kind: Kind): List<List<String>> = when {
        !kind.shellGrantable -> emptyList()
        kind == Kind.SYSTEM_SETTINGS -> listOf(listOf("appops", "set", pkg, "WRITE_SETTINGS", "allow"))
        kind == Kind.DND -> listOf(listOf("cmd", "notification", "allow_dnd", pkg))
        else -> emptyList()
    }
}
