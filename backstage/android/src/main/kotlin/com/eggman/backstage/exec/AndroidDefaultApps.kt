package com.eggman.backstage.exec

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/** [DefaultAppsPlatform] over PackageManager, TelecomManager, the SMS default and the assistant setting. */
class AndroidDefaultApps(private val context: Context) : DefaultAppsPlatform {
    private val pm: PackageManager = context.packageManager
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override val manufacturer: String get() = Build.MANUFACTURER.orEmpty()

    override fun roleHolder(role: AppRole): String? = runCatching {
        when (role) {
            AppRole.DIALER -> context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage
            AppRole.SMS -> android.provider.Telephony.Sms.getDefaultSmsPackage(context)
            // Not every Android version lets apps read this setting; the ASSIST probe is the fallback.
            AppRole.ASSISTANT -> android.provider.Settings.Secure.getString(context.contentResolver, "assistant")
                ?.takeIf { it.isNotBlank() }?.let { ComponentName.unflattenFromString(it)?.packageName }
            else -> null
        }
    }.getOrNull()

    override fun resolvedDefault(probe: IntentProbe): String? = runCatching {
        pm.resolveActivity(intent(probe), if (probe.defaultOnly) PackageManager.MATCH_DEFAULT_ONLY else 0)?.activityInfo?.packageName
    }.getOrNull()

    override fun handlers(probe: IntentProbe): List<String> = runCatching {
        pm.queryIntentActivities(intent(probe), if (probe.defaultOnly) PackageManager.MATCH_DEFAULT_ONLY else 0)
            .mapNotNull { it.activityInfo?.packageName }.distinct()
    }.getOrDefault(emptyList())

    override fun label(packageName: String): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    override fun isSystem(packageName: String): Boolean = runCatching {
        val flags = pm.getApplicationInfo(packageName, 0).flags
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)

    /** Handlers of a concrete Intent (open_url's link) and what Android would start for it. */
    fun linkHandlers(intent: Intent): Pair<String?, List<String>> {
        val probe = Intent(intent).setPackage(null)
        val resolved = runCatching { pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        val all = runCatching { pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { it.activityInfo?.packageName }.distinct() }
            .getOrDefault(emptyList())
        return resolved to all
    }

    companion object {
        fun intent(probe: IntentProbe): Intent = Intent(probe.action).apply {
            val uri = probe.data?.let(Uri::parse)
            when {
                uri != null && probe.type != null -> setDataAndType(uri, probe.type)
                uri != null -> data = uri
                probe.type != null -> type = probe.type
            }
            probe.categories.forEach { addCategory(it) }
        }
    }
}

/**
 * The resolved default apps, cached for ten minutes and dropped whenever a package is added, removed or changed (a
 * changed default without a package change is picked up within ten minutes).
 */
object DefaultAppsCache {
    /**
     * Ten-minute cache lifetime. Package changes drop it at once; a default changed in Android's settings (no
     * broadcast) shows up within this time.
   */
    const val MAX_AGE_MS = 10 * 60_000L

    /** Fills both caches in the background (agent start), so the first task does not wait for the package manager. */
    fun warm(context: Context) {
        Thread({ runCatching { current(context); AppCatalog.installedApps(context) } }, "backstage-apps-warm").apply { isDaemon = true }.start()
    }
    @Volatile private var cache: Pair<Long, List<DefaultApp>>? = null
    private val registered = AtomicBoolean(false)

    fun invalidate() {
        cache = null
        AppCatalog.invalidate()
    }

    fun current(context: Context, ownPackages: Set<String> = setOf(context.packageName)): List<DefaultApp> {
        watchPackages(context)
        cache?.let { (at, list) -> if (System.currentTimeMillis() - at < MAX_AGE_MS) return list }
        val list = DefaultAppsResolver.resolve(AndroidDefaultApps(context), ownPackages)
        cache = System.currentTimeMillis() to list
        return list
    }

    /**
     * open_url's package when Android has no default for [intent] (it would show a chooser, also on the desktop): the
     * role's app for the link's scheme, else the best handler. null = let Android decide.
   */
    fun pinForLink(context: Context, intent: Intent, ownPackages: Set<String>): String? = runCatching {
        if (intent.`package` != null || intent.component != null) return null
        val scheme = intent.data?.scheme ?: return null
        val platform = AndroidDefaultApps(context)
        val (resolved, handlers) = platform.linkHandlers(intent)
        DefaultAppsResolver.pinForLink(scheme, resolved, handlers, current(context, ownPackages), platform, ownPackages)
    }.getOrNull()

    /** " in <app> (no default is set for such links)" when [intent] is pinned by [pinForLink], else "". */
    fun pinnedNote(context: Context, intent: Intent, uri: String): String {
        // An intent: URI may name its own package; only a plain link gets one from pinForLink.
        if (uri.trim().startsWith("intent:", ignoreCase = true)) return ""
        val p = intent.`package` ?: return ""
        val label = runCatching { context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } }.getOrNull() ?: p
        return " in $label ($p; no default app is set for such links, so the best match was used)"
    }

    internal fun watchPackages(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = invalidate()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching {
            val app = context.applicationContext ?: context
            // Package broadcasts come from the system, which reaches non-exported receivers too.
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
        }.onFailure { registered.set(false) }
    }
}
