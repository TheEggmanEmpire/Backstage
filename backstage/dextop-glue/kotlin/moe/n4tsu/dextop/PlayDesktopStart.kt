package moe.n4tsu.dextop

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.eggman.backstage.a11y.AccessibilityEnabler
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.host.PlayDesktopPrerequisite
import com.eggman.backstage.privileged.PrivilegeProviders
import com.eggman.backstage.privileged.ShizukuSetup
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object PlayDesktopStart {
    private const val TIMEOUT_MS = 10_000L
    private val scope = CoroutineScope(Dispatchers.Main)
    private var starting = false

    fun prerequisite(activity: Activity): Map<String, Any?> {
        BackstageDesktopBridge.privilegedAvailable()
        val blocker = PlayDesktopPrerequisite.check(ShizukuSetup.current(activity)) {
            val resolver = activity.contentResolver
            val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val service = ComponentName(activity, MirrorService::class.java).flattenToString()
            AccessibilityEnabler.contains(services, service) &&
                Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }
        return mapOf("blocker" to blocker?.name, "restrictedSettingsHint" to (Build.VERSION.SDK_INT >= 33))
    }

    fun openManager(activity: Activity): Boolean {
        val intent = ShizukuSetup.openShizuku(activity) ?: error("Could not open Stellar or Shizuku Plus.")
        activity.startActivity(intent)
        return true
    }

    fun openDownload(activity: Activity, manager: String?): Boolean {
        val link = when (manager) {
            "stellar" -> PrivilegeProviders.STELLAR_DOWNLOAD
            "shizukuPlus" -> PrivilegeProviders.SHIZUKU_PLUS_DOWNLOAD
            else -> error("Unknown desktop privilege manager.")
        }
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        return true
    }

    fun openAccessibility(activity: Activity): Boolean {
        val component = ComponentName(activity, MirrorService::class.java).flattenToString()
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        try {
            activity.startActivity(details)
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: SecurityException) {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        return true
    }

    fun launch(presentation: String?, result: MethodChannel.Result) {
        val target = when (presentation) {
            "hidden" -> Presentation.HIDDEN
            "in_app" -> Presentation.IN_APP
            else -> error("Invalid desktop presentation.")
        }
        if (starting) {
            result.error("start_busy", "The desktop is already starting.", null)
            return
        }
        starting = true
        scope.launch {
            try {
                var failure: String? = null
                val geometry = withTimeoutOrNull(TIMEOUT_MS) {
                    withContext(Dispatchers.Default) {
                        BackstageDesktopBridge.startDesktop(target, TIMEOUT_MS) { failure = it }
                    }
                }
                if (geometry != null) {
                    result.success(true)
                } else if (failure != null) {
                    result.error("start_failed", failure, null)
                } else {
                    MirrorService.Backstage.requestPresentationOnStart(null)
                    if (!BackstageDesktopBridge.sessionActive()) MirrorService.stopActive()
                    result.error("start_timeout", "The desktop did not finish starting within 10 seconds.", null)
                }
            } catch (error: Exception) {
                Log.w("BackstageShell", "Play desktop startup failed", error)
                MirrorService.Backstage.requestPresentationOnStart(null)
                result.error("start_failed", error.message ?: "The desktop could not start.", null)
            } finally {
                starting = false
            }
        }
    }
}
