package com.eggman.backstage.update

import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException

internal class UpdateHttpException(message: String, val retryAfterMs: Long) : IOException(message)

internal class GitHubReleaseClient(
    private val installedVersion: String,
    client: OkHttpClient = GitHubReleaseMetadata.client(),
    private val endpoint: String = ENDPOINT,
) {
    private val client = client.withGitHubRedirects()

    fun latest(): UpdateRelease? = client.newCall(GitHubReleaseMetadata.request(endpoint, installedVersion)).execute().use { response ->
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
        const val ENDPOINT = GitHubReleaseMetadata.LATEST
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
                require(GitHubReleaseMetadata.isDownloadUrl(asset.url, asset.name)) { "Untrusted release asset URL" }
            }
            return UpdateRelease(version, selected)
        }
    }
}
