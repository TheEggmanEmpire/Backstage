package com.eggman.backstage.ui.settings

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import com.eggman.backstage.data.Provider

/**
 * Bring-your-own-key providers offered in Settings: a view of the agent core's [Provider] catalog
 * (ids = enum names, defaults = the core's defaults), so the UI never shows a model the agent would not use.
 */
data class ProviderOption(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val needsKey: Boolean = true,
    val keyHint: String = "API key",
    val note: String = "",
)

object ProviderOptions {
    val all: List<ProviderOption> = Provider.entries.map {
        ProviderOption(
            id = it.name,
            label = it.label,
            defaultBaseUrl = it.defaultBaseUrl,
            defaultModel = it.defaultModel,
            needsKey = it.requiresKey,
            keyHint = it.keyHint.ifBlank { "API key" },
            note = it.tagline,
        )
    }

    fun byId(id: String?): ProviderOption? = all.firstOrNull { it.id == id }

    /** Problems that block a provider from working; empty = looks usable. */
    fun validate(option: ProviderOption, key: String, model: String, baseUrl: String): List<String> = buildList {
        if (option.id == Provider.CHATGPT.name) {
            if (key.isBlank()) add("Sign in with ChatGPT.")
        } else if (option.needsKey && key.isBlank()) add("Add an API key for ${option.label}.")
        if (model.isBlank()) add("Choose a model.")
        val url = baseUrl.trim()
        if (!(url.startsWith("https://") || url.startsWith("http://"))) add("Enter an address beginning with http:// or https://.")
        else if (!PLAY_DISTRIBUTION && url.startsWith("http://") && option.needsKey && !isLocalHost(url)) add("Use https:// so your key is not sent in clear text.")
    }

    private fun isLocalHost(url: String): Boolean {
        val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
        return host == "localhost" || host == "127.0.0.1" || host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
    }
}
