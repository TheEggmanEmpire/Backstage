package com.eggman.backstage.privileged

/**
 * Provider preference, independent of Android so the picker and glue share one tested policy.
 * A package being installed says nothing about its binder or permission. In particular Stellar's
 * Shizuku compatibility layer can be off: callers must still check the live, granted connection.
 */
object PrivilegeProviders {
    const val PREFERENCES = "dextop_privilege_provider"
    const val PREFERRED = "preferred"
    const val STELLAR = "stellar"
    const val SHIZUKU = "shizuku"
    const val STELLAR_PACKAGE = "roro.stellar.manager"
    const val STELLAR_DOWNLOAD = "https://github.com/roro2239/Stellar/releases"
    const val STELLAR_REQUEST_BINDER = "roro.stellar.intent.action.REQUEST_BINDER"
    val shizukuPackages = listOf("moe.shizuku.privileged.api", "moe.shizuku.manager")
    val packages = listOf(STELLAR_PACKAGE) + shizukuPackages

    /** Keep an explicit choice (package or upstream's legacy kind); defaults prefer Stellar. */
    fun select(installed: List<String>, saved: String? = null): String? {
        val ordered = installed.distinct().sortedWith(compareBy<String> {
            when (it) { STELLAR_PACKAGE -> 0; in shizukuPackages -> 1 + shizukuPackages.indexOf(it); else -> 3 }
        }.thenBy { it })
        return ordered.firstOrNull { it == saved }
            ?: ordered.firstOrNull { saved == STELLAR && it == STELLAR_PACKAGE }
            ?: ordered.firstOrNull { saved == SHIZUKU && it != STELLAR_PACKAGE }
            ?: ordered.firstOrNull()
    }

    data class Choice(val selected: String?, val writePreferences: Boolean, val disableEmbedded: Boolean)

    /** Automatic discovery never ends the user's bundled session or switches a running desktop's runtime. */
    fun choice(
        installed: List<String>, provider: String, saved: String?, lastMode: String?,
        externalUsable: Boolean, sessionRunning: Boolean,
    ): Choice {
        val explicit = provider == STELLAR || provider == SHIZUKU
        if (!explicit && lastMode == "embedded" && saved == null) return Choice(null, false, false)
        val candidates = installed.filter {
            when (provider) { STELLAR -> it == STELLAR_PACKAGE; SHIZUKU -> it in shizukuPackages; else -> true }
        }
        val selected = select(candidates, if (explicit) provider else saved)
        return Choice(selected,
            selected != null && (saved != selected || lastMode != "external"),
            selected != null && externalUsable && (explicit || !sessionRunning))
    }

    /**
     * Ask the selected Stellar manager once even if another manager supplied the live shared binder.
     * Later polls only recover a missing binder (the Android adapter also applies Dextop's retry gate).
   */
    fun requestStellar(selected: String?, binderAlive: Boolean, requested: Boolean): Boolean =
        selected == STELLAR_PACKAGE && (!binderAlive || !requested)

    /** Embedded remains usable while an installed external provider is stopped, denied or incompatible. */
    fun usable(embedded: Boolean, binderAlive: Boolean, granted: Boolean): Boolean =
        embedded || (binderAlive && granted)
}


/** Monotonic, thread-safe snapshot cache. Loading runs once per TTL, including concurrent privilege probes. */
class ProviderSnapshotCache<T>(
    private val ttlMs: Long = 5_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var loaded = false
    private var at = 0L
    private var value: T? = null

    @Synchronized fun get(load: () -> T): T {
        val now = clock()
        if (!loaded || now - at >= ttlMs || now < at) {
            value = load()
            at = now
            loaded = true
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    @Synchronized fun invalidate() { loaded = false }
}
