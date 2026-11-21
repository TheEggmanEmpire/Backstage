package com.eggman.backstage.privileged

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.eggman.backstage.a11y.AccessibilityAutoEnable
import com.eggman.backstage.a11y.AccessibilityEnabler
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.host.extras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared Stellar/Shizuku connection state. The legacy type name keeps existing probe/disclosure contracts stable. */
enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, PERMISSION_NEEDED, READY }

object ShizukuSetup {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val DOWNLOAD_URL = PrivilegeProviders.STELLAR_DOWNLOAD

    /** Pure mapping. A live binder without the app (Sui on rooted devices) counts as installed. */
    fun state(installed: Boolean, binderAlive: Boolean, granted: Boolean): ShizukuState = when {
        binderAlive && granted -> ShizukuState.READY
        binderAlive -> ShizukuState.PERMISSION_NEEDED
        installed -> ShizukuState.NOT_RUNNING
        else -> ShizukuState.NOT_INSTALLED
    }

    fun installedPackages(context: Context): List<String> {
        // Dextop also accepts Shizuku-compatible managers outside the two named apps. Keep a user's
        // explicit installed manager rather than replacing it merely because it is not in our recommendations.
        val saved = context.getSharedPreferences(PrivilegeProviders.PREFERENCES, Context.MODE_PRIVATE).getString("selected", null)
        val candidates = PrivilegeProviders.packages + listOfNotNull(saved?.takeUnless {
            it in setOf(PrivilegeProviders.PREFERRED, PrivilegeProviders.STELLAR, PrivilegeProviders.SHIZUKU)
        })
        return candidates.distinct().filter { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }
    }

    fun preferredPackage(context: Context): String? = PrivilegeProviders.select(installedPackages(context),
        context.getSharedPreferences(PrivilegeProviders.PREFERENCES, Context.MODE_PRIVATE).getString("selected", null))

    fun installed(context: Context): Boolean = installedPackages(context).isNotEmpty()

    fun current(context: Context): ShizukuState =
        state(installed(context), Privileged.binderAlive(), Privileged.shizukuGranted())

    /** Shows the connected provider's dialog, including Stellar through its compatibility layer. */
    fun requestPermission() = Privileged.requestPermission()

    fun openShizuku(context: Context): Intent? =
        preferredPackage(context)?.let { context.packageManager.getLaunchIntentForPackage(it) }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun download(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * One-shot device setup through the privileged shell (Stellar first): selects Dextop's preferred privilege
 * provider, switches on the app's ONE accessibility service (Dextop's MirrorService: the desktop engine and the
 * agent's eyes and hands) by appending to the secure list (never dropping other services; only the retired agent
 * service of Backstage is removed) and grants what the shell can grant.
 * Personal-data permissions (microphone, calendar, contacts, SMS, location) are NOT granted here: the user
 * grants those in the checklist.
 */
object PrivilegedSetup {
    private const val TAG = "BackstageSetup"
    const val ACCESSIBILITY = "Accessibility service"

    data class Step(val label: String, val argv: List<String>)

    data class Report(val done: List<String>, val failed: List<String>) {
        val ok: Boolean get() = failed.isEmpty()
        /** The part that matters for running at all: the accessibility service is switched on. */
        val accessibilityDone: Boolean get() = ACCESSIBILITY in done
        fun summary(): String = buildString {
            append("Set up ").append(done.size).append(" of ").append(done.size + failed.size).append(" items")
            if (failed.isNotEmpty()) append("; not possible: ").append(failed.joinToString(", "))
            append('.')
        }
    }

    /** Shell grants (uid 2000 may grant development permissions and set app-ops). */
    fun grantSteps(pkg: String): List<Step> = listOf(
        Step("Restricted settings", listOf("appops", "set", pkg, "ACCESS_RESTRICTED_SETTINGS", "allow")),
        Step("Secure settings", listOf("pm", "grant", pkg, "android.permission.WRITE_SECURE_SETTINGS")),
        Step("Notifications", listOf("pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS")),
        Step("Display over other apps", listOf("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")),
        Step("Exact alarms", listOf("appops", "set", pkg, "SCHEDULE_EXACT_ALARM", "allow")),
        Step("Usage access", listOf("appops", "set", pkg, "GET_USAGE_STATS", "allow")),
        Step("Unrestricted battery", listOf("dumpsys", "deviceidle", "whitelist", "+$pkg")),
    )

    /**
     * The secure list with every component in [components] appended once and every [retired] one removed; other services
     * keep their order.
   */
    fun accessibilityValue(current: String?, components: List<String>, retired: List<String> = emptyList()): String =
        components.fold(retired.fold(AccessibilityEnabler.parse(current).joinToString(":")) { acc, r -> AccessibilityEnabler.remove(acc, r) }) { acc, c ->
            AccessibilityEnabler.merge(acc, c)
        }

    /** Runs the grants and the accessibility merge through [shell]; pure apart from [shell]. */
    suspend fun run(shell: AccessibilityEnabler.Shell, pkg: String, a11yComponents: List<String>, retired: List<String> = emptyList()): Report {
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (step in grantSteps(pkg)) {
            val (code, _) = runCatching { shell.run(*step.argv.toTypedArray()) }.getOrDefault(-1 to "")
            if (code == 0) done += step.label else failed += step.label
        }
        val (readCode, current) = runCatching { shell.run("settings", "get", "secure", AccessibilityEnabler.KEY_ENABLED_SERVICES) }
            .getOrDefault(-1 to "")
        if (readCode != 0 || a11yComponents.isEmpty()) {
            failed += ACCESSIBILITY
            return Report(done, failed)
        }
        val merged = accessibilityValue(current, a11yComponents, retired)
        if (merged != AccessibilityEnabler.parse(current).joinToString(":")) {
            shell.run("settings", "put", "secure", AccessibilityEnabler.KEY_ENABLED_SERVICES, merged)
        }
        shell.run("settings", "put", "secure", AccessibilityEnabler.KEY_ACCESSIBILITY_ENABLED, "1")
        val (_, verify) = shell.run("settings", "get", "secure", AccessibilityEnabler.KEY_ENABLED_SERVICES)
        if (a11yComponents.all { AccessibilityEnabler.contains(verify, it) }) done += ACCESSIBILITY
        else failed += ACCESSIBILITY
        return Report(done, failed)
    }

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Full setup on the device: records Stellar first (Shizuku otherwise), then [run] through the
     * privileged shell. Returns null without any privileged access.
   */
    suspend fun applyAll(context: Context): Report? = lock.withLock {
        val app = context.applicationContext ?: context
        val extras = DesktopHosts.extras()
        // Reuse Dextop's connection and saved choice. Installed-but-stopped providers leave the fallback on.
        runCatching { extras?.usePrivilegeProvider(PrivilegeProviders.PREFERRED) }
        if (!Privileged.available()) return@withLock null
        val components = listOfNotNull(AccessibilityAutoEnable.component(app))
        run({ argv -> Privileged.exec(*argv).let { it.code to it.out } }, app.packageName, components,
            retired = listOf(AccessibilityAutoEnable.retiredComponent(app)))
            .also { Log.i(TAG, it.summary()) }
    }

    /**
     * Runs [applyAll] once automatically when Stellar/Shizuku permission becomes available (onboarding, or the user
     * granted it from the provider later). [shouldRun] gates it (setting + not done yet); [onDone] records it.
   */
    fun installAutoSetup(context: Context, shouldRun: () -> Boolean, onDone: (Report) -> Unit) {
        val app = context.applicationContext ?: context
        Privileged.addPermissionListener {
            if (Privileged.shizukuGranted() && shouldRun()) scope.launch {
                applyAll(app)?.let(onDone)
            }
        }
    }

    /** Whether a package declares the permission (development permissions show up as granted after `pm grant`). */
    fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
