package com.eggman.backstage.update

import com.eggman.backstage.a11y.companion.CompanionProtocol
import okhttp3.OkHttpClient
import org.json.JSONObject

internal class CompanionReleaseLink(
    client: OkHttpClient = GitHubReleaseMetadata.client(),
    private val endpoint: String = GitHubReleaseMetadata.LATEST,
) {
    private val client = client.withGitHubRedirects()

    fun resolve(): String = runCatching {
        val request = GitHubReleaseMetadata.request(endpoint, "Play")
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) downloadUrl(JSONObject(response.boundedText(1024 * 1024))) else null
        }
    }.getOrNull() ?: FALLBACK

    private fun downloadUrl(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (ASSET.matches(name) && GitHubReleaseMetadata.isDownloadUrl(url, name)) return url
        }
        return null
    }

    companion object {
        const val FALLBACK = "${CompanionProtocol.RELEASES}/latest"
        private val ASSET = Regex("""^backstage-play-agent-[0-9.]+-release\.apk$""")
    }
}
