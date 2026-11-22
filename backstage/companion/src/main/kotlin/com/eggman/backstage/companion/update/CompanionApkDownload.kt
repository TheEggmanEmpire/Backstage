package com.eggman.backstage.companion.update

import java.io.File
import java.security.MessageDigest

/**
 * Fetches SHA256SUMS first, then streams the APK into a private file while hashing it. The file is kept only when its
 * byte count equals the published size and its SHA-256 equals the SHA256SUMS line; every failure deletes it.
 */
internal class CompanionApkDownload(
    private val transport: UpdateTransport,
    private val cancelled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun fetch(release: CompanionRelease, file: File, freeBytes: Long, progress: (Long) -> Unit): String {
        val apk = release.apk
        if (freeBytes < CompanionUpdatePolicy.requiredSpace(apk.size)) throw CompanionUpdateException(UpdateFailure.LOW_STORAGE)
        val expected = transport.get(release.sums.url).use { response ->
            if (response.code != 200) throw CompanionReleaseClient.httpFailure(response)
            CompanionUpdatePolicy.checksum(response.boundedText(CompanionUpdatePolicy.MAX_SUMS), apk.name)
        }
        try {
            // Download into an empty file so the checksum verifies the entire response.
            if (file.exists() && !file.delete()) throw CompanionUpdateException(UpdateFailure.INSTALLER, "Cannot clear previous download")
            val deadline = clock() + DOWNLOAD_DEADLINE
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            transport.get(apk.url).use { response ->
                if (response.code != 200) throw CompanionReleaseClient.httpFailure(response)
                if (response.contentLength != -1L && response.contentLength != apk.size) throw CompanionUpdateException(UpdateFailure.SIZE_MISMATCH)
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw CompanionUpdateException(UpdateFailure.CANCELLED)
                        if (clock() > deadline) throw CompanionUpdateException(UpdateFailure.NETWORK, "Download took too long")
                        val read = response.body.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > apk.size) throw CompanionUpdateException(UpdateFailure.SIZE_MISMATCH)
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        progress(count)
                    }
                    output.fd.sync()
                }
            }
            if (count != apk.size) throw CompanionUpdateException(UpdateFailure.SIZE_MISMATCH)
            if (!CompanionUpdatePolicy.hashMatches(expected, digest.digest().hex())) throw CompanionUpdateException(UpdateFailure.CHECKSUM_MISMATCH)
            if (!file.setReadOnly()) throw CompanionUpdateException(UpdateFailure.INSTALLER, "Cannot protect the downloaded APK")
            return expected
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private companion object { const val DOWNLOAD_DEADLINE = 10 * 60_000L }
}
