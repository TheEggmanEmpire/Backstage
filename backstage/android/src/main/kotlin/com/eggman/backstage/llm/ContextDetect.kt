package com.eggman.backstage.llm

import com.eggman.backstage.data.ModelIds
import com.eggman.backstage.data.Provider
import com.eggman.backstage.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the selected model's capabilities from the provider's own model list and remembers them per model,
 *  so the runtime uses the endpoint's truth. Vision/tools are trusted only from providers that actually
 *  advertise them; for providers whose list only carries ids (OpenAI, DeepSeek, Custom, Ollama, OpenCode)
 *  the catalog guesses from the name, so those guesses are NOT persisted and the recommended/default fallback
 *  stands. Context is only present where advertised, so it is safe to persist wherever reported. */
object ContextDetect {
    private val catalog by lazy { ModelCatalog() }

    /** Providers whose model list reports capabilities rather than guessing them from the id. */
    val ADVERTISES = setOf(Provider.FREE, Provider.ANTHROPIC, Provider.GEMINI, Provider.OPENROUTER, Provider.VENICE)

    data class Caps(val context: Long?, val vision: Boolean?, val tools: Boolean?)

    /** Same endpoint/cache as capability detection, also exposed to Settings' multi-model picker. */
    suspend fun fetchModels(settings: Settings, provider: Provider = settings.provider, force: Boolean = false): ModelListResult = withContext(Dispatchers.IO) {
        try {
            if (provider == com.eggman.backstage.data.Provider.CHATGPT) {
                val listed = settings.chatGptTokens?.let { ChatGptModels.fetch(settings.baseUrl(provider), it) }
                return@withContext ModelListResult.Loaded(listed ?: provider.recommended)
            }
            val result = catalog.fetch(settings.providerConfig(provider), force)
            if (result is ModelListResult.Loaded) for (info in result.models + result.hidden) {
                info.context?.takeIf { it > 0 }?.let { settings.setDetectedContext(provider, info.id, it) }
                if (provider in ADVERTISES) {
                    settings.setDetectedCapability(provider, info.id, "vision", info.vision)
                    settings.setDetectedCapability(provider, info.id, "tools", info.tools)
                }
            }
            result
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { ModelListResult.Unavailable("Could not load models; check the endpoint and key.") }
    }

    /** [force] bypasses the 6 h model-list cache so a stale null (fetched before the key is valid) is corrected. */
    suspend fun detect(settings: Settings, provider: Provider = settings.provider, force: Boolean = false): Caps? = withContext(Dispatchers.IO) {
        runCatching {
            val model = settings.model(provider).trim()
            if (model.isEmpty() || !provider.canListModels) return@runCatching null
            val result = fetchModels(settings, provider, force) as? ModelListResult.Loaded ?: return@runCatching null
            val info = (result.models + result.hidden).firstOrNull { ModelIds.same(it.id, model) } ?: return@runCatching null
            info.context?.takeIf { it > 0 }?.let { settings.setDetectedContext(provider, model, it) }
            if (provider in ADVERTISES) {
                settings.setDetectedCapability(provider, model, "vision", info.vision)
                settings.setDetectedCapability(provider, model, "tools", info.tools)
            }
            Caps(info.context?.takeIf { it > 0 }, info.vision?.takeIf { provider in ADVERTISES }, info.tools?.takeIf { provider in ADVERTISES })
        }.getOrNull()
    }

    /** Detect for every provider that has a key or a base URL configured (not just the current one). */
    suspend fun detectAll(settings: Settings, force: Boolean = false) {
        for (p in Provider.entries) {
            val configured = p == settings.provider || if (p == Provider.FREE) settings.freeModelsEnabled
                else settings.apiKey(p).isNotBlank() || (!p.requiresKey && settings.baseUrl(p).isNotBlank())
            if (configured) runCatching { detect(settings, p, force) }
        }
    }
}
