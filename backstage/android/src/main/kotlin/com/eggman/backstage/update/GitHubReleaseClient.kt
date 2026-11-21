package com.eggman.backstage.update

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class UpdateHttpException(message: String, val retryAfterMs: Long) : IOException(message)

internal class GitHubReleaseClient(
    private val installedVersion: String,
    client: OkHttpClient = OkHttpClient.Builder().callTimeout(7, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val endpoint: String = ENDPOINT,
) {
    private val client = client.withGitHubRedirects()

    fun latest(): UpdateRelease? = client.newCall(Request.Builder().url(endpoint)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Backstage/$installedVersion").build()).execute().use { response ->
        if (!response.isSuccessful) {
            val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 7 * UpdatePolicy.DAY / 1000) ?: 0
            val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.let {
                (it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 - System.currentTimeMillis()).coerceAtLeast(0)
            } ?: 0
            throw UpdateHttpException("GitHub API returned HTTP ${response.code}", maxOf(seconds * 1000, reset))
        }
        parse(JSONObject(response.boundedText(1024 * 1024)))
    }

    companion object {
        const val ENDPOINT = "https://api.github.com/repos/TheEggmanEmpire/Backstage/releases/latest"
        fun parse(json: JSONObject): UpdateRelease? {
            // Missing flags are malformed metadata, not an implicit stable release.
            if (json.getBoolean("draft") || json.getBoolean("prerelease")) return null
            val version = requireNotNull(UpdatePolicy.version(json.getString("tag_name"))) { "Invalid release version" }
            val array = json.getJSONArray("assets")
            val selected = UpdatePolicy.assets(version, (0 until array.length()).map { index ->
                val asset = array.getJSONObject(index)
                ReleaseAsset(asset.getString("name"), asset.getString("browser_download_url"), asset.getLong("size"))
            })
            listOf(selected.apk, selected.sums).forEach { asset ->
                val url = asset.url.toHttpUrl()
                require(url.isHttps && url.host == "github.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
                    url.encodedPath.startsWith("/TheEggmanEmpire/Backstage/releases/download/") &&
                    url.pathSegments.last() == asset.name && url.query == null && url.fragment == null) { "Untrusted release asset URL" }
            }
            return UpdateRelease(version, selected)
        }
    }
}

internal fun Response.boundedText(maxBytes: Int): String {
    val body = body ?: throw IOException("Empty response")
    require(body.contentLength() <= maxBytes) { "Update metadata is too large" }
    val bytes = body.byteStream().readNBytes(maxBytes + 1)
    require(bytes.size <= maxBytes) { "Update metadata is too large" }
    return bytes.toString(Charsets.UTF_8)
}

private fun trustedUpdateUrl(url: okhttp3.HttpUrl): Boolean = url.isHttps &&
    url.username.isEmpty() && url.password.isEmpty() && url.host in setOf("api.github.com", "github.com",
        "release-assets.githubusercontent.com", "objects.githubusercontent.com")

/** Validate every redirect before connecting and every redirected response before consuming its body. */
internal fun OkHttpClient.withGitHubRedirects(): OkHttpClient = newBuilder()
    .followRedirects(true).followSslRedirects(false)
    .addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        val redirected = response.request.url != chain.call().request().url
        val destination = response.header("Location")?.let { response.request.url.resolve(it) }
        if ((redirected && !trustedUpdateUrl(response.request.url)) ||
            (response.isRedirect && (destination == null || !trustedUpdateUrl(destination)))) {
            response.close()
            throw IOException("Untrusted update redirect")
        }
        response
    }.build()
