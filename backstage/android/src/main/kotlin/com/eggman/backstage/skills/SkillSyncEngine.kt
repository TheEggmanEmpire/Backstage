package com.eggman.backstage.skills

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One source's network staging + atomic store transaction. The coordinator serializes invocations. */
class SkillSyncEngine(
    private val store: SkillStore,
    private val cacheDir: File,
    baseUrl: String = "https://api.github.com/",
    client: OkHttpClient = com.eggman.backstage.llm.LlmHttp.sharedClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sourceIsCurrent: (SkillSyncSource) -> Boolean = { true },
) {
    private val base = baseUrl.toHttpUrl()
    init { require(base.isHttps || base.host in setOf("localhost", "127.0.0.1", "::1")) { "GitHub requests require HTTPS." } }
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    suspend fun sync(source: SkillSyncSource, token: String = "", previous: SkillSyncStatus? = null,
        progress: (SkillSyncProgress) -> Unit = {}): SkillSyncStatus {
        try {
            SkillSyncConfig(sources = listOf(source)).validated()
            SkillSyncInputs.checkToken(token)
            progress(SkillSyncProgress(source.id, "commit"))
            val repo = base.newBuilder().addPathSegment("repos").addPathSegment(source.owner).addPathSegment(source.repo).build()
            val ref = if (source.ref.isEmpty()) json(repo, token).optString("default_branch").also {
                require(it.isNotBlank()) { "GitHub did not return the repository's default branch." }
            } else source.ref
            // Branch names may contain "/" (feature/x): GitHub expects them as plain path segments, not %2F.
            require(ref.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid repository ref." }
            val commit = json(repo.newBuilder().addPathSegment("commits").addPathSegments(ref).build(), token).optString("sha")
            require(commit.matches(Regex("[a-fA-F0-9]{7,64}"))) { "GitHub did not return a valid commit SHA." }
            if (previous?.result == "ok" && previous.commitSha == commit && previous.configKey == source.configKey && !store.hasMissingMirrors(source.id)) {
                return SkillSyncStatus(clock(), "ok", commitSha = commit, configKey = source.configKey)
            }
            cacheDir.mkdirs()
            val zip = File.createTempFile("skill-sync-", ".zip", cacheDir)
            try {
                progress(SkillSyncProgress(source.id, "download"))
                runInterruptible(Dispatchers.IO) {
                    request(repo.newBuilder().addPathSegment("zipball").addPathSegment(commit).build(), token).use { response ->
                        SkillDownloader.saveArchive(response, zip, store.limits.maxArchiveBytes) { bytes, total ->
                            progress(SkillSyncProgress(source.id, "download", bytes, total))
                        }
                    }
                }
                progress(SkillSyncProgress(source.id, "validate"))
                val scan = runInterruptible(Dispatchers.IO) { SkillArchive.scanZip(zip, limits = store.limits, paths = source.paths) }
                // No markers in a valid zip means upstream intentionally removed all skills. Any malformed or
                // truncated scan is an error, so an incomplete snapshot can never delete installed mirrors.
                // Allowlist, not denylist: only known per-entry warnings may pass. Anything else (unreadable archive,
                // truncation, limits, unknown messages) means an incomplete scan, which must never delete mirrors.
                val harmless = { p: String -> p.startsWith("skipped an unsafe path: ") || p.startsWith("skipped a duplicate entry: ") ||
                    p.startsWith("the skill file is named ") }
                val fatal = scan.problems.filterNot { it == "no SKILL.md in the archive" || harmless(it) }
                require(fatal.isEmpty()) { "The repository archive could not be fully scanned: ${fatal.first()}" }
                val scanNotes = scan.problems.filter(harmless)
                currentCoroutineContext().ensureActive()
                progress(SkillSyncProgress(source.id, "save"))
                val counts = runInterruptible(Dispatchers.IO) {
                    SkillStore.withFilesLock(store.root) {
                        check(sourceIsCurrent(source)) { "The GitHub source changed during sync. Run Sync now again." }
                        store.mirror(source.id, scan.candidates, source.url)
                    }
                }
                val all = counts.copy(notes = scanNotes + counts.notes)
                return SkillSyncStatus(clock(), if (all.skipped > 0 || scanNotes.isNotEmpty()) "partial" else "ok", commitSha = commit, configKey = source.configKey, counts = all)
            } finally { zip.delete() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            return SkillSyncStatus(clock(), "error", SkillSyncInputs.redact(e.message ?: "GitHub sync failed.", token),
                previous?.commitSha.orEmpty(), previous?.configKey.orEmpty())
        }
    }

    private suspend fun json(url: HttpUrl, token: String): JSONObject = runInterruptible(Dispatchers.IO) {
        request(url, token).use { r ->
            val body = r.body ?: throw IOException("GitHub sent an empty response.")
            val bytes = body.byteStream().use { it.readNBytes(1_048_577) }
            require(bytes.size <= 1_048_576) { "GitHub sent an oversized response." }
            try { JSONObject(bytes.decodeToString()) } catch (_: Exception) { throw IOException("GitHub sent an invalid response.") }
        }
    }

    private fun request(initial: HttpUrl, token: String): Response {
        var url = initial
        repeat(6) {
            val sameOrigin = url.scheme == base.scheme && url.host == base.host && url.port == base.port
            require(url.username.isEmpty() && url.password.isEmpty() && (sameOrigin || url.isHttps && url.host in setOf("api.github.com", "codeload.github.com"))) { "GitHub returned an unexpected download host." }
            val request = Request.Builder().url(url).header("User-Agent", "Backstage-skills")
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
                .apply { if (token.isNotEmpty() && sameOrigin) header("Authorization", "Bearer $token") }.build()
            val response = http.newCall(request).execute()
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                val next = response.header("Location")?.let { url.resolve(it) }
                response.close()
                url = next ?: throw IOException("GitHub returned an invalid redirect.")
            } else if (response.isSuccessful) return response
            else {
                val code = response.code
                val rate = code == 429 || code == 403 && response.header("X-RateLimit-Remaining") == "0"
                response.close()
                throw IOException(when {
                    rate -> "GitHub rate limit reached. Try again later or add a source token."
                    code == 401 -> "GitHub authentication failed. Check the source token."
                    code == 403 -> "GitHub denied access. Check the token permissions or try again later."
                    code == 404 -> "The GitHub repository or ref was not found, or the token cannot access it."
                    else -> "GitHub could not complete the request (HTTP $code)."
                })
            }
        }
        throw IOException("GitHub returned too many redirects.")
    }
}
