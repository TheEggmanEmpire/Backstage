package com.eggman.backstage.companion.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal class TransportResponse(
    val code: Int,
    val contentLength: Long,
    private val headers: (String) -> String?,
    val body: InputStream,
    private val release: () -> Unit,
) : Closeable {
    fun header(name: String): String? = headers(name)
    override fun close() { runCatching { body.close() }; release() }

    fun boundedText(maxBytes: Long): String {
        if (contentLength > maxBytes) throw CompanionUpdateException(UpdateFailure.TOO_LARGE, "Response exceeds $maxBytes bytes")
        val bytes = body.readNBytes((maxBytes + 1).toInt())
        if (bytes.size > maxBytes) throw CompanionUpdateException(UpdateFailure.TOO_LARGE, "Response exceeds $maxBytes bytes")
        return bytes.toString(Charsets.UTF_8)
    }
}

/** A GET whose every hop is checked by [CompanionUpdatePolicy.trustedTransportUrl]; tests supply an in-memory one. */
internal interface UpdateTransport {
    fun get(url: String, headers: Map<String, String> = emptyMap()): TransportResponse
    fun cancel() = Unit
}

/** HTTPS only, redirects followed by hand so each Location is validated before it is contacted. */
internal class HttpsUpdateTransport(private val userAgent: String) : UpdateTransport {
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var cancelled = false

    override fun get(url: String, headers: Map<String, String>): TransportResponse {
        var target = runCatching { URI(url) }.getOrElse { throw CompanionUpdateException(UpdateFailure.UNTRUSTED_URL, url) }
        if (!CompanionUpdatePolicy.trustedTransportUrl(target)) throw CompanionUpdateException(UpdateFailure.UNTRUSTED_URL, url)
        repeat(MAX_REDIRECTS + 1) {
            if (cancelled) throw CompanionUpdateException(UpdateFailure.CANCELLED)
            val connection = target.toURL().openConnection() as? HttpsURLConnection
                ?: throw CompanionUpdateException(UpdateFailure.UNTRUSTED_URL, target.toString())
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            active = connection
            val code = try { connection.responseCode } catch (error: IOException) {
                connection.disconnect()
                throw if (cancelled) CompanionUpdateException(UpdateFailure.CANCELLED) else error
            }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                val next = location?.let { runCatching { target.resolve(it) }.getOrNull() }
                if (next == null || !CompanionUpdatePolicy.trustedTransportUrl(next))
                    throw CompanionUpdateException(UpdateFailure.UNTRUSTED_REDIRECT, location)
                target = next
                return@repeat
            }
            val body = (if (code >= 400) connection.errorStream else connection.inputStream) ?: InputStream.nullInputStream()
            return TransportResponse(code, connection.contentLengthLong, connection::getHeaderField, body) {
                connection.disconnect()
                if (active === connection) active = null
            }
        }
        throw CompanionUpdateException(UpdateFailure.UNTRUSTED_REDIRECT, "Too many redirects")
    }

    override fun cancel() { cancelled = true; active?.disconnect() }

    private companion object { const val MAX_REDIRECTS = 5 }
}

/** Reads this repository's published releases and selects the newest stable companion APK among them. */
internal class CompanionReleaseClient(private val transport: UpdateTransport) {
    fun latest(): CompanionRelease? = transport.get(CompanionUpdatePolicy.RELEASES, mapOf(
        "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28",
    )).use { response ->
        if (response.code != 200) throw httpFailure(response)
        parse(JSONArray(response.boundedText(CompanionUpdatePolicy.MAX_METADATA.toLong())))
    }

    companion object {
        fun httpFailure(response: TransportResponse): CompanionUpdateException {
            val retrySeconds = response.header("Retry-After")?.trim()?.toLongOrNull()?.coerceAtLeast(0)
            val resetMs = response.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()
                ?.let { (it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 - System.currentTimeMillis()).coerceAtLeast(0) }
            val limited = response.code == 429 || (response.code == 403 && response.header("X-RateLimit-Remaining")?.trim() == "0")
            val wait = CompanionUpdatePolicy.cooldown(maxOf((retrySeconds ?: 0).coerceAtMost(Long.MAX_VALUE / 1000) * 1000, resetMs ?: 0))
            return CompanionUpdateException(if (limited) UpdateFailure.RATE_LIMITED else UpdateFailure.HTTP, "HTTP ${response.code}", wait)
        }

        /**
         * Drafts, prereleases and releases without a companion APK are ignored. A malformed or untrusted release is
         * skipped while a valid one exists; when none is valid, the first problem is reported instead of "up to date".
         */
        fun parse(releases: JSONArray): CompanionRelease? {
            val valid = mutableListOf<CompanionRelease>()
            var problem: Exception? = null
            for (index in 0 until releases.length()) {
                try {
                    val release = releases.getJSONObject(index)
                    // Missing flags are malformed metadata, never an implicit stable release.
                    if (release.getBoolean("draft") || release.getBoolean("prerelease")) continue
                    CompanionUpdatePolicy.releaseAssets(assets(release))?.let(valid::add)
                } catch (error: Exception) {
                    if (problem == null) problem = error
                }
            }
            return CompanionUpdatePolicy.newest(valid) ?: problem?.let { throw it as? CompanionUpdateException
                ?: CompanionUpdateException(UpdateFailure.METADATA, it.message) }
        }

        private fun assets(release: JSONObject): List<ReleaseAsset> {
            val array = release.getJSONArray("assets")
            return (0 until array.length()).map { index ->
                val asset = array.getJSONObject(index)
                ReleaseAsset(asset.getString("name"), asset.getString("browser_download_url"), asset.getLong("size"))
            }
        }
    }
}
