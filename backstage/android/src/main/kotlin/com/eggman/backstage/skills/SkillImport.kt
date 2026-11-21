package com.eggman.backstage.skills

import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream

/** Shares exported skill zips (`<cache>/skill-share/`) with the app the user picks. */
class SkillShareProvider : FileProvider()

/** One line of the import preview. */
data class ImportRow(
    val label: String,
    val description: String,
    val ok: Boolean,
    val duplicate: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
    val files: Int,
    val bytes: Long,
)

/** The import flow's pure parts: preview rows, installing what the user confirmed, and the result line. */
object SkillImportLogic {
    fun rows(scan: ArchiveScan, store: SkillStore): List<ImportRow> = scan.candidates.map { c ->
        ImportRow(
            label = c.label,
            description = c.parse.manifest?.description.orEmpty(),
            ok = c.ok,
            duplicate = c.name?.let(store::exists) == true,
            errors = c.allErrors,
            warnings = c.allWarnings,
            files = c.files.size,
            bytes = c.totalBytes,
        )
    }

    /**
     * Installs every valid candidate of [scan] (switched off) with [source] recorded. Names that exist already are
     * replaced only with [replace]; two candidates with the same name in one archive: the first wins.
   */
    fun install(scan: ArchiveScan, store: SkillStore, source: String, replace: Boolean): List<InstallOutcome> {
        val seen = HashSet<String>()
        return scan.candidates.map { c ->
            val n = c.name
            if (n != null && !seen.add(n)) InstallOutcome.Failed(n, "the archive holds this name twice; the first one was used")
            else store.install(c, source, replace)
        }
    }

    fun summary(outcomes: List<InstallOutcome>): String {
        val installed = outcomes.filterIsInstance<InstallOutcome.Installed>()
        val dup = outcomes.filterIsInstance<InstallOutcome.Duplicate>()
        val failed = outcomes.filterIsInstance<InstallOutcome.Failed>()
        return buildList {
            if (installed.isNotEmpty()) add("Installed ${installed.size} (switched off until you enable them): " + installed.joinToString(", ") { it.name + if (it.replaced) " (replaced)" else "" } + ".")
            if (dup.isNotEmpty()) add("Already installed, kept: " + dup.joinToString(", ") { it.name } + ".")
            if (failed.isNotEmpty()) add("Not installed: " + failed.joinToString("; ") { "${it.name}: ${it.reason}" } + ".")
            if (isEmpty()) add("Nothing to install.")
        }.joinToString(" ")
    }

    /** Copies [input] to [dest], failing once more than [max] bytes arrive (a picked file or a download). */
    fun copyBounded(input: InputStream, dest: File, max: Long, progress: ((Long) -> Unit)? = null) {
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out ->
            val buf = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw java.io.IOException("larger than ${SkillArchive.mb(max)}")
                out.write(buf, 0, n)
                progress?.invoke(total)
            }
        }
    }

    /** Source line for a picked file. */
    fun fileSource(displayName: String?): String = "file: " + (displayName?.takeIf { it.isNotBlank() } ?: "picked file")
}

/** Downloads skill archives over https with the size cap (OkHttp; follows GitHub's redirect to its archive host). */
object SkillDownloader {
    suspend fun download(d: SkillDownload, dest: File, max: Long): Unit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val client = com.eggman.backstage.llm.LlmHttp.sharedClient.newBuilder()
            .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(false)
            .build()
        val req = okhttp3.Request.Builder().url(d.archiveUrl)
            .header("User-Agent", "Backstage-skills")
            .apply { if (d.github) header("Accept", "application/vnd.github+json") }
            .get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException(when (r.code) {
                404 -> "not found (404): check the link; private repositories cannot be downloaded"
                403, 429 -> "the server refused the download (${r.code}); GitHub limits downloads without an account, try again later"
                else -> "download failed (HTTP ${r.code})"
            })
            if (!r.request.url.isHttps) throw java.io.IOException("the download was redirected to a non-https address")
            saveArchive(r, dest, max)
        }
    }

    /** Shared cap for manual imports and authenticated GitHub mirror downloads. */
    internal fun saveArchive(response: okhttp3.Response, dest: File, max: Long, progress: ((Long, Long) -> Unit)? = null) {
        val body = response.body ?: throw java.io.IOException("the server sent nothing")
        val total = body.contentLength()
        if (total > max) throw java.io.IOException("the archive is larger than ${SkillArchive.mb(max)}")
        body.byteStream().use { SkillImportLogic.copyBounded(it, dest, max) { bytes -> progress?.invoke(bytes, total) } }
    }
}
