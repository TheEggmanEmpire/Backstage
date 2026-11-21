package com.eggman.backstage.skills

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Empty [ref] means the repository's default branch, resolved by GitHub at each sync. */
data class SkillSyncSource(val id: String, val owner: String, val repo: String, val ref: String = "", val paths: List<String> = listOf(".")) {
    val configKey: String get() = JSONArray(listOf(id, owner, repo, ref, JSONArray(paths))).toString()
    val url: String get() = "https://github.com/$owner/$repo"
    fun toJson(): JSONObject = JSONObject().put("id", id).put("owner", owner).put("repo", repo).put("ref", ref).put("paths", JSONArray(paths))
}

data class SkillSyncConfig(val enabled: Boolean = false, val intervalMinutes: Int = 60, val sources: List<SkillSyncSource> = emptyList()) {
    fun validated(): SkillSyncConfig {
        require(intervalMinutes in 15..10_080) { "The sync interval must be between 15 minutes and 7 days." }
        require(sources.map { it.id }.distinct().size == sources.size) { "Each GitHub source needs a unique id." }
        sources.forEach { s ->
            require(s.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"))) { "Invalid GitHub source id." }
            require(s.owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]*")) && s.repo.matches(Regex("[A-Za-z0-9_.-]+")) && s.repo !in listOf(".", "..")) { "Enter a GitHub repository as owner/repo." }
            require(s.ref.none { it.code < 32 || it.code == 127 } && s.ref == s.ref.trim()) { "Invalid repository ref." }
            require(s.paths.isNotEmpty() && s.paths.all { it == "." || SkillPaths.normalize(it) == it }) { "Use safe relative repository folder paths." }
        }
        return this
    }

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("intervalMinutes", intervalMinutes).put("sources", JSONArray(sources.map { it.toJson() }))

    companion object {
        fun parse(text: String?): SkillSyncConfig {
            if (text.isNullOrBlank()) return SkillSyncConfig()
            val o = JSONObject(text)
            val a = o.optJSONArray("sources") ?: JSONArray()
            return SkillSyncConfig(o.optBoolean("enabled", false), o.optInt("intervalMinutes", 60), (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                val p = s.optJSONArray("paths") ?: JSONArray(listOf("."))
                SkillSyncSource(s.getString("id"), s.getString("owner"), s.getString("repo"), s.optString("ref", ""), (0 until p.length()).map { p.getString(it) })
            }).validated()
        }
    }
}

object SkillSyncInputs {
    /** Reuses the import URL parser for /tree/ref/path and encoded folder handling. */
    fun parseRepository(input: String, takenIds: Collection<String> = emptyList()): SkillSyncSource {
        val text = input.trim().trimEnd('/')
        require(text.isNotEmpty()) { "Enter a GitHub repository, for example owner/repo." }
        val url = when {
            text.startsWith("github.com/", true) || text.startsWith("www.github.com/", true) -> "https://$text"
            text.contains("://") -> text
            text.split('/').size == 2 -> "https://github.com/$text"
            else -> throw IllegalArgumentException("Enter owner/repo or a GitHub repository URL.")
        }
        val uri = URI(url)
        require(uri.scheme.equals("https", true) && uri.host?.lowercase() in listOf("github.com", "www.github.com") && uri.rawUserInfo == null && uri.port in listOf(-1, 443)) { "Use an HTTPS GitHub repository URL." }
        val download = SkillSources.resolve(url)
        require(download.github) { "Use a repository or /tree/ref/path URL." }
        val parts = URI(download.archiveUrl).rawPath.split('/').filter { it.isNotEmpty() }
        fun decode(s: String) = java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        val owner = decode(parts[1]); val repo = decode(parts[2]); val ref = parts.getOrNull(4)?.let(::decode).orEmpty()
        val path = download.subPath ?: "."
        val base = ("$owner-$repo" + if (path != ".") "-$path" else "").lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(80).ifEmpty { "skills" }
        var id = base; var n = 2
        while (id in takenIds) id = "$base-${n++}"
        return SkillSyncSource(id, owner, repo, ref, listOf(path)).also { SkillSyncConfig(sources = listOf(it)).validated() }
    }

    fun checkToken(token: String): String {
        if (token.isEmpty()) return token
        require(token.none { it.isWhitespace() || it.code < 32 || it.code == 127 }) { "The GitHub token contains spaces or line breaks. Paste only the token itself." }
        val prefixes = Regex("github_pat_|gh[pousr]_").findAll(token).toList()
        require(prefixes.size <= 1) { "The GitHub token looks pasted more than once. Clear the field and paste it a single time." }
        require(prefixes.isEmpty() || prefixes.single().range.first == 0) { "The GitHub token has extra text before it. Paste only the token itself." }
        return token
    }

    fun splitInterval(minutes: Int): Pair<String, String> = when {
        minutes % 1440 == 0 -> (minutes / 1440).toString() to "days"
        minutes % 60 == 0 -> (minutes / 60).toString() to "hours"
        else -> minutes.toString() to "minutes"
    }

    fun joinInterval(value: String, unit: String): Int {
        val factor = when (unit) { "minutes" -> 1; "hours" -> 60; "days" -> 1440; else -> 0 }
        val n = value.toDoubleOrNull()
        require(factor > 0 && n != null && n.isFinite() && n > 0) { "Enter how often to sync as a positive number." }
        val minutes = kotlin.math.floor(n * factor + 0.5)
        require(minutes in 15.0..10_080.0) { "The sync interval must be between 15 minutes and 7 days." }
        return minutes.toInt()
    }

    fun redact(message: String, token: String): String = (if (token.isNotEmpty()) message.replace(token, "[redacted]") else message)
        .replace(Regex("(?:github_pat_|gh[pousr]_)[A-Za-z0-9_]+"), "[redacted]").take(1000)
}

/** [notes]: why things are skipped (name clashes, invalid skills, unsafe archive entries), shown with the status. */
data class SkillSyncCounts(val added: Int = 0, val updated: Int = 0, val deleted: Int = 0, val skipped: Int = 0, val notes: List<String> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("added", added).put("updated", updated).put("deleted", deleted).put("skipped", skipped)
        .put("notes", org.json.JSONArray(notes.take(20)))
}

data class SkillSyncStatus(val lastRun: Long, val result: String, val error: String? = null, val commitSha: String = "", val configKey: String = "", val counts: SkillSyncCounts = SkillSyncCounts()) {
    fun toJson(): JSONObject = JSONObject().put("lastRun", lastRun).put("result", result).put("error", error).put("commitSha", commitSha).put("configKey", configKey).put("counts", counts.toJson())
    companion object {
        fun parse(o: JSONObject): SkillSyncStatus {
            val c = o.optJSONObject("counts") ?: JSONObject()
            return SkillSyncStatus(o.optLong("lastRun"), o.optString("result", "error"), o.optString("error").takeIf { it.isNotEmpty() }, o.optString("commitSha"), o.optString("configKey"),
                SkillSyncCounts(c.optInt("added"), c.optInt("updated"), c.optInt("deleted"), c.optInt("skipped"),
                    c.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()))
        }
    }
}

data class SkillSyncProgress(val sourceId: String, val phase: String, val bytes: Long = 0, val total: Long = 0) {
    fun description(): String = "Syncing $sourceId: " + when (phase) {
        "commit" -> "checking the latest commit"
        "download" -> "downloading " + String.format(java.util.Locale.ROOT, "%.1f", bytes / 1_048_576.0) + if (total > 0) " / " + String.format(java.util.Locale.ROOT, "%.1f MB", total / 1_048_576.0) else " MB"
        "save" -> "saving skills"
        else -> "checking skills"
    } + "…"
}
