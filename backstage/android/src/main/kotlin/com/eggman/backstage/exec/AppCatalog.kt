package com.eggman.backstage.exec

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class AppEntry(val label: String, val packageName: String)

/** Installed launchable apps and fuzzy name resolution (the matching itself is pure and tested). */
object AppCatalog {
    @Volatile private var cache: Pair<Long, List<AppEntry>>? = null

    /**
     * kept for hours; package added/removed/changed broadcasts drop it ([DefaultAppsCache.watchPackages]),
     * so repeated task lookups use the cached package list.
   */
    const val MAX_AGE_MS = 6 * 60 * 60_000L

    fun installedApps(context: Context, maxAgeMs: Long = MAX_AGE_MS): List<AppEntry> {
        DefaultAppsCache.watchPackages(context)
        cache?.let { (at, list) -> if (System.currentTimeMillis() - at < maxAgeMs && list.isNotEmpty()) return list }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
        cache = System.currentTimeMillis() to list
        return list
    }

    /** Drops the cached list (a package is added, removed or changed). */
    fun invalidate() { cache = null }

    fun resolve(context: Context, query: String): AppEntry? = match(installedApps(context), query).firstOrNull()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Ranked candidates: exact package, exact label, label prefix, word prefix, substring, fuzzy. */
    fun match(apps: List<AppEntry>, query: String): List<AppEntry> {
        val q = norm(query)
        val raw = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        fun score(a: AppEntry): Int {
            val label = norm(a.label)
            val pkg = a.packageName.lowercase()
            return when {
                pkg == raw -> 100
                label == q -> 90
                label.startsWith(q) -> 80
                label.split(' ').any { it.startsWith(q) } -> 70
                label.contains(q) -> 60
                pkg.split('.').any { it == q.replace(" ", "") } -> 55
                pkg.contains(q.replace(" ", "")) -> 50
                q.length >= 4 && editDistance(label, q) <= maxOf(1, q.length / 4) -> 40
                else -> 0
            }
        }
        return apps.map { it to score(it) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<AppEntry, Int>> { it.second }.thenBy { it.first.label.length })
            .map { it.first }
    }

    fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
