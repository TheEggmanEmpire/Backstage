package com.eggman.backstage.llm

import com.eggman.backstage.data.ModelInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The model list of a ChatGPT sign-in: the same backend's `/models` (entries with `visibility` "list"), in its
 * order. Any failure answers null and the caller keeps the built-in recommendations.
 */
object ChatGptModels {
    const val CLIENT_VERSION = "0.167.2"
    private val client by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }

    fun fetch(baseUrl: String, tokens: ChatGptTokens): List<ModelInfo>? = runCatching {
        val url = baseUrl.trimEnd('/').removeSuffix("/responses") + "/models?client_version=$CLIENT_VERSION"
        val request = Request.Builder().url(url).get()
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("originator", ChatGptAuth.ORIGINATOR)
            .header("Accept", "application/json")
            .apply { if (tokens.accountId.isNotBlank()) header("chatgpt-account-id", tokens.accountId) }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            parse(response.body?.string().orEmpty())
        }
    }.getOrNull()

    fun parse(body: String): List<ModelInfo>? {
        val models = JSONObject(body).optJSONArray("models") ?: return null
        return (0 until models.length()).mapNotNull { models.optJSONObject(it) }
            .filter { it.optString("visibility", "list") == "list" && it.optString("slug").isNotBlank() }
            .map { ModelInfo(it.optString("slug"), it.optString("display_name").ifBlank { it.optString("slug") }, tools = true, vision = true) }
            .ifEmpty { null }
    }
}
