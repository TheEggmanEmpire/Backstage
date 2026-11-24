package com.eggman.backstage.ui.chat

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.eggman.backstage.a11y.AccessibilityEnabler
import com.eggman.backstage.host.PlayDesktopPrerequisite
import com.eggman.backstage.privileged.ShizukuSetup

/**
 * What stops the agent screen's Start button in the Play build, as a message and the actions that fix it.
 * The desktop's own accessibility service is the Dextop host's MirrorService, named by component here.
 */
internal object DesktopStartGuard {
    const val MIRROR_SERVICE = "moe.n4tsu.dextop.MirrorService"

    data class Action(val label: String, val run: (Context) -> Unit)
    data class Blocker(val title: String, val message: String, val actions: List<Action>)

    const val RESTRICTED_STEPS = "If Android blocks the switch: 1) In Accessibility, tap Backstage-play once. " +
        "2) Open app details, tap ⋮ and choose Allow restricted settings. 3) Return to Accessibility and switch it on."

    fun blocker(context: Context): Blocker? =
        message(PlayDesktopPrerequisite.check(ShizukuSetup.current(context)) { mirrorEnabled(context) })

    fun message(prerequisite: PlayDesktopPrerequisite?): Blocker? = when (prerequisite) {
        null -> null
        PlayDesktopPrerequisite.MANAGER_MISSING -> Blocker("Stellar or Shizuku Plus needed",
            "The desktop needs Stellar or Shizuku Plus. Install one, start it and allow Backstage.",
            listOf(Action("Get Stellar") { open(it, com.eggman.backstage.privileged.PrivilegeProviders.STELLAR_DOWNLOAD) },
                Action("Get Shizuku Plus") { open(it, com.eggman.backstage.privileged.PrivilegeProviders.SHIZUKU_PLUS_DOWNLOAD) }))
        PlayDesktopPrerequisite.MANAGER_STOPPED -> Blocker("Stellar or Shizuku Plus is not running",
            "Start Stellar or Shizuku Plus, then tap Start again.",
            listOf(Action("Open it") { c -> ShizukuSetup.openShizuku(c)?.let { start(c, it) } }))
        PlayDesktopPrerequisite.MANAGER_PERMISSION -> Blocker("Permission needed",
            "Allow Backstage in Stellar or Shizuku Plus, then tap Start again.",
            listOf(Action("Allow") { ShizukuSetup.requestPermission() }))
        PlayDesktopPrerequisite.ACCESSIBILITY_OFF -> Blocker("Desktop accessibility is off",
            "Switch on Backstage-play in Accessibility. It runs the desktop's keyboard, mouse and windows. $RESTRICTED_STEPS",
            listOf(Action("Open Accessibility", ::openAccessibility), Action("Open app details", ::openAppDetails)))
    }

    fun mirrorEnabled(context: Context): Boolean {
        val resolver = context.contentResolver
        val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val component = ComponentName(context.packageName, MIRROR_SERVICE).flattenToString()
        return AccessibilityEnabler.contains(services, component) &&
            Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    }

    fun openAccessibility(context: Context) {
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context.packageName, MIRROR_SERVICE).flattenToString())
        if (!start(context, details)) start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openAppDetails(context: Context) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    private fun open(context: Context, url: String) { start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
}
