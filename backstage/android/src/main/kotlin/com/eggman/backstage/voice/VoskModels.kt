package com.eggman.backstage.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** An official small Vosk model (Apache-2.0), downloaded only after the user confirms. */
data class VoskModel(
    val id: String,
    val language: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val unpackedBytes: Long,
    val license: String = "Apache-2.0",
) {
    val sizeLabel: String get() = "%.0f MB".format(sizeBytes / 1_000_000.0)
}

object VoskModelCatalog {
    private const val BASE = "https://alphacephei.com/vosk/models/"

    /** Sizes and SHA-256 measured from the official files (2026-11-01). */
    val models = listOf(
        VoskModel("vosk-model-small-en-us-0.15", "English (US)", BASE + "vosk-model-small-en-us-0.15.zip",
            41_205_931, "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", 70_898_967),
        VoskModel("vosk-model-small-de-0.15", "Deutsch", BASE + "vosk-model-small-de-0.15.zip",
            46_499_967, "b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43", 95_621_761),
        VoskModel("vosk-model-small-fr-0.22", "Français", BASE + "vosk-model-small-fr-0.22.zip",
            42_233_323, "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad", 68_483_807),
        VoskModel("vosk-model-small-es-0.42", "Español", BASE + "vosk-model-small-es-0.42.zip",
            39_817_833, "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f", 60_286_598),
        VoskModel("vosk-model-small-it-0.22", "Italiano", BASE + "vosk-model-small-it-0.22.zip",
            49_665_141, "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267", 91_299_150),
    )

    fun byId(id: String?): VoskModel? = models.firstOrNull { it.id == id }

    /** Best default for a BCP-47 language tag. */
    fun forLanguage(languageTag: String): VoskModel {
        val lang = languageTag.substringBefore('-').lowercase()
        return models.firstOrNull { it.id.contains("-small-$lang-") } ?: models.first()
    }
}

/**
 * Downloads, verifies (exact size + SHA-256) and unpacks a model into `<root>/<id>/` atomically.
 * Zip entries are confined to the target directory and the unpacked size is capped.
 */
class VoskModelInstaller(
    private val root: File,
    private val client: OkHttpClient = OkHttpClient(),
) {
    class VerificationException(message: String) : IOException(message)

    fun dir(model: VoskModel) = File(root, model.id)
    private fun marker(model: VoskModel) = File(dir(model), ".backstage-verified")

    fun isInstalled(model: VoskModel): Boolean =
        marker(model).isFile && File(dir(model), "am").isDirectory && File(dir(model), "conf").isDirectory

    fun delete(model: VoskModel) {
        dir(model).deleteRecursively()
    }

    suspend fun install(model: VoskModel, onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            if (isInstalled(model)) return@withContext dir(model)
            root.mkdirs()
            val part = File(root, "${model.id}.zip.part")
            val tmp = File(root, "${model.id}.tmp")
            try {
                download(model, part, onProgress)
                tmp.deleteRecursively()
                unzip(part, tmp, model)
                if (!File(tmp, "am").isDirectory || !File(tmp, "conf").isDirectory) {
                    throw VerificationException("Archive is not a Vosk model")
                }
                File(tmp, ".backstage-verified").writeText(model.sha256)
                dir(model).deleteRecursively()
                if (!tmp.renameTo(dir(model))) throw IOException("Could not move model into place")
                dir(model)
            } finally {
                part.delete()
                tmp.deleteRecursively()
            }
        }

    private suspend fun download(model: VoskModel, dest: File, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(model.url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            val declared = body.contentLength()
            if (declared >= 0 && declared != model.sizeBytes) {
                throw VerificationException("Unexpected size $declared (expected ${model.sizeBytes})")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > model.sizeBytes) throw VerificationException("Download larger than expected")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        onProgress(total, model.sizeBytes)
                    }
                }
            }
            if (total != model.sizeBytes) throw VerificationException("Incomplete download ($total of ${model.sizeBytes} bytes)")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(model.sha256, ignoreCase = true)) throw VerificationException("Checksum mismatch")
        }
    }

    private suspend fun unzip(zip: File, target: File, model: VoskModel) {
        target.mkdirs()
        val canonicalRoot = target.canonicalPath + File.separator
        val limit = (model.unpackedBytes * 1.1).toLong() + 1_000_000
        var written = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zis.nextEntry ?: break
                // Strip the archive's top-level folder (e.g. "vosk-model-small-en-us-0.15/").
                val name = entry.name.replace('\\', '/').let { n ->
                    val first = n.substringBefore('/')
                    if (first == model.id || first.startsWith("vosk-model")) n.substringAfter('/', "") else n
                }
                if (name.isEmpty()) continue
                val out = File(target, name)
                if (!out.canonicalPath.startsWith(canonicalRoot)) throw VerificationException("Unsafe path in archive: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > limit) throw VerificationException("Archive expands beyond the expected size")
                        os.write(buf, 0, n)
                    }
                }
            }
        }
    }
}
