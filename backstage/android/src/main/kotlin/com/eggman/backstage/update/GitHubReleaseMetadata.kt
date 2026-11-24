package com.eggman.backstage.update

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object GitHubReleaseMetadata {
    const val LATEST = "https://api.github.com/repos/TheEggmanEmpire/Backstage/releases/latest"

    fun client(): OkHttpClient = OkHttpClient.Builder().callTimeout(7, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS).followSslRedirects(false).retryOnConnectionFailure(false).build()

    fun request(endpoint: String, installedVersion: String): Request = Request.Builder().url(endpoint)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Backstage/$installedVersion").build()

    fun isDownloadUrl(value: String, assetName: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host == "github.com" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath.startsWith("/TheEggmanEmpire/Backstage/releases/download/") &&
            url.pathSegments.last() == assetName && url.query == null && url.fragment == null
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
