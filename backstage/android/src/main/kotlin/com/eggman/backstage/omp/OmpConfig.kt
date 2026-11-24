package com.eggman.backstage.omp

import com.eggman.backstage.data.Provider
import com.eggman.backstage.data.RemoteMcpServer
import com.eggman.backstage.data.Settings
import java.util.Locale

/** Generated files contain credential command references, never provider keys or remote MCP tokens. */
object OmpConfig {
    data class Entry(val provider: Provider, val baseUrl: String, val model: String, val hasKey: Boolean,
        /** Known context window (custom: always; built-in: only an override or a detected value). */
        val context: Long? = null, val maxTokens: Int? = null, val vision: Boolean? = null,
        /** omp provider id; free models use one per lane (`backstage-free-kilo`, `backstage-free-ovh`). */
        val ompProvider: String = providerId(provider))
    data class Files(val models: String, val config: String, val mcp: String = "{\"mcpServers\":{}}", val instructions: String = Settings.DEFAULT_GLOBAL_INSTRUCTIONS)

    // omp 18.18.6 pi-catalog defaults (checked by scripts/tests/check_omp_config.py).
    // Backstage's Google and ChatGPT URLs include protocol-specific suffixes omp adds itself.
    private val builtinBases = mapOf(
        Provider.ANTHROPIC to "https://api.anthropic.com",
        Provider.OPENAI to "https://api.openai.com/v1",
        Provider.GEMINI to "https://generativelanguage.googleapis.com/v1beta",
        Provider.OPENROUTER to "https://openrouter.ai/api/v1",
        Provider.DEEPSEEK to "https://api.deepseek.com",
        Provider.CHATGPT to "https://chatgpt.com/backend-api",
    )

    fun providerId(provider: Provider): String = when (provider) {
        Provider.ANTHROPIC -> "anthropic"
        Provider.OPENAI -> "openai"
        Provider.GEMINI -> "google"
        Provider.OPENROUTER -> "openrouter"
        Provider.DEEPSEEK -> "deepseek"
        Provider.CHATGPT -> "openai-codex"
        else -> "backstage-${provider.name.lowercase(Locale.ROOT)}"
    }
    fun yaml(value: String): String = buildString {
        append('"')
        value.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 32 || c.code in 0x7f..0x9f || c == '\u2028' || c == '\u2029') append("\\u%04x".format(c.code)) else append(c)
        } }
        append('"')
    }
    /** Credential identity depends only on the exact full ID, never on another server's membership or state. */
    fun mcpSlug(server: RemoteMcpServer): String = "mcp-" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(server.id.toByteArray(Charsets.UTF_8)).take(20).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun mcp(servers: List<RemoteMcpServer>): String {
        val included = servers.filter { it.enabled && (it.knownTools.isEmpty() || !it.disabledTools.containsAll(it.knownTools)) }
        val names = included.groupingBy { it.name }.eachCount()
        // Reserve unique display names before assigning fallbacks, which may themselves match a name.
        val usedNames = names.filter { (name, count) -> name.isNotBlank() && count == 1 }.keys.toMutableSet()
        return included.joinToString(prefix = "{\"mcpServers\":{", postfix = "}}") { server ->
            val slug = mcpSlug(server)
            val name = if (server.name.isNotBlank() && names[server.name] == 1) server.name else {
                var fallback = slug
                var suffix = 2
                while (!usedNames.add(fallback)) fallback = "$slug-${suffix++}"
                fallback
            }
            // yaml emits JSON-compatible double-quoted strings, including control-character escapes.
            "${yaml(name)}:{\"type\":\"http\",\"url\":${yaml(server.url)}," +
                "\"headers\":{\"Authorization\":${yaml("!backstage-omp-key $slug")}},\"enabled\":true}"
        }
    }
    fun generate(entries: List<Entry>, current: Provider, model: String, remoteMcpServers: List<RemoteMcpServer> = emptyList(),
        mainId: String? = null): Files {
        val models = buildString {
            appendLine(if (entries.isEmpty()) "providers: {}" else "providers:")
            for ((_, providerEntries) in entries.groupBy { it.ompProvider }) {
                val e = providerEntries.first()
                val id = e.ompProvider
                appendLine("  ${yaml(id)}:")
                val custom = id.startsWith("backstage-")
                if (custom) appendLine("    api: ${yaml("openai-completions")}")
                var base = e.baseUrl.trimEnd('/').removeSuffix("/chat/completions")
                if (!custom && e.baseUrl.trimEnd('/') == e.provider.defaultBaseUrl.trimEnd('/'))
                    base = builtinBases.getValue(e.provider)
                if (e.provider == Provider.OLLAMA && !base.endsWith("/v1")) base += "/v1"
                appendLine("    baseUrl: ${yaml(base)}")
                if (custom && !e.hasKey) appendLine("    auth: ${yaml("none")}")
                else appendLine("    apiKey: ${yaml("!backstage-omp-key $id")}")
                if (e.provider == Provider.CHATGPT) {
                    appendLine("    auth: ${yaml("oauth")}")
                    appendLine("    headers:")
                    appendLine("      ${yaml("chatgpt-account-id")}: ${yaml("!backstage-omp-key openai-codex-account-id")}")
                }
                // Built-ins inherit catalog models and APIs. A models list switches omp to full
                // custom-provider validation, which rejects a block without an explicit API.
                if (custom) {
                    appendLine("    models:")
                    for (card in providerEntries.distinctBy { it.model }.filter { it.model.isNotBlank() }) {
                        appendLine("      - id: ${yaml(card.model)}")
                        card.vision?.let { appendLine("        input: ${if (it) "[text, image]" else "[text]"}") }
                        card.context?.takeIf { it > 0 }?.let { appendLine("        contextWindow: $it") }
                        card.maxTokens?.takeIf { it > 0 }?.let { appendLine("        maxTokens: $it") }
                    }
                } else {
                    val overrides = providerEntries.distinctBy { it.model }.filter { it.context != null && it.context > 0 && it.model.isNotBlank() }
                    if (overrides.isNotEmpty()) {
                        // Only explicit or advertised limits; all other cards keep omp's catalog metadata.
                        appendLine("    modelOverrides:")
                        for (card in overrides) {
                            appendLine("      ${yaml(card.model)}:")
                            appendLine("        contextWindow: ${card.context}")
                        }
                    }
                }
            }
        }
        // An overlay (backstage.yml): providers come from Backstage, so omp's first-run sign-in wizard is skipped.
        val main = mainId ?: "${providerId(current)}/$model"
        val enabled = (listOf(main).filter { model.isNotBlank() } + entries.filter { it.model.isNotBlank() }
            .map { "${it.ompProvider}/${it.model}" }.sorted()).distinct()
        val config = buildString {
            if (model.isNotBlank()) {
                appendLine("modelRoles:")
                appendLine("  default: ${yaml(main)}")
            }
            for (key in listOf("enabledModels", "cycleOrder")) {
                appendLine(if (enabled.isEmpty()) "$key: []" else "$key:")
                enabled.forEach { appendLine("  - ${yaml(it)}") }
            }
            appendLine("startup:")
            appendLine("  setupWizard: false")
        }
        return Files(models, config, mcp(remoteMcpServers))
    }
    /** Free models as omp sees them: one custom provider per lane. Free Auto has no omp equivalent (no failover there). */
    private fun freeEntries(settings: Settings): List<Entry> = settings.enabledModels(Provider.FREE).sorted().mapNotNull { id ->
        val r = com.eggman.backstage.llm.FreeModels.route(id) ?: return@mapNotNull null
        Entry(Provider.FREE, r.lane.base, r.model, false, settings.detectedContext(Provider.FREE, id),
            settings.maxOutputTokens, settings.detectedCapability(Provider.FREE, id, "vision"), "backstage-free-${r.lane.key}")
    }

    /** The chat's main model as an omp id (`provider/model`); for Free Auto, the free model it would try first. */
    fun mainModelId(settings: Settings): String =
        if (settings.provider == Provider.FREE) freeMain(settings) ?: "" else "${providerId(settings.provider)}/${settings.model()}"

    private fun freeMain(settings: Settings): String? {
        val id = settings.model(Provider.FREE).let { if (it == com.eggman.backstage.llm.FreeModels.AUTO) settings.freeAutoRank().firstOrNull()?.id else it }
        val r = id?.let { com.eggman.backstage.llm.FreeModels.route(it) } ?: return null
        return "backstage-free-${r.lane.key}/${r.model}"
    }

    fun from(settings: Settings): Files {
        val freeOn = settings.freeModelsEnabled
        val currentFree = settings.provider == Provider.FREE
        val main = if (currentFree && freeOn) freeMain(settings) else null
        return generate((if (freeOn) freeEntries(settings) else emptyList()) + Provider.entries.filter {
        it != Provider.FREE && (it == settings.provider || settings.apiKey(it).isNotBlank() || (!it.requiresKey && settings.baseUrl(it).isNotBlank()))
    }.flatMap { provider ->
        val custom = providerId(provider).startsWith("backstage-")
        settings.enabledModels(provider).sorted().map { model ->
            // Limits/capabilities belong to each card, never copied from the chat main.
            val context = settings.contextOverride(provider) ?: settings.detectedContext(provider, model)
            val vision = settings.visionOverride(provider) ?: settings.detectedCapability(provider, model, "vision")
            Entry(provider, settings.baseUrl(provider), model, settings.apiKey(provider).isNotBlank(), context,
                if (custom) settings.maxOutputTokens else null, vision)
        }
    }, settings.provider, if (currentFree && main == null) "" else settings.model(), settings.remoteMcpServers, main)
        .copy(instructions = settings.effectiveGlobalInstructions())
    }
}
