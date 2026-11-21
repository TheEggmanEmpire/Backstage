package com.eggman.backstage.data

/** Which request/response dialect a provider speaks. */
enum class WireFormat { ANTHROPIC_MESSAGES, OPENAI_CHAT }

/** One entry of the recommended-model catalog (provider → models with capability flags). */
data class ModelInfo(
    val id: String,
    val name: String = id,
    val default: Boolean = false,
    val tools: Boolean? = null,
    val vision: Boolean? = null,
    val context: Long? = null,
    val note: String = "",
)

/**
 * AI providers: user-supplied API credentials plus signing in with a ChatGPT subscription ([CHATGPT],
 * OAuth instead of a pasted key). Base URLs and models are only defaults: every value is editable in settings.
 *
 * [hostedCatalog]: pasted API key + provider-side model list. [canListModels]: a model list can be
 * fetched (hosted providers and Custom). Ollama lists pulled models from its own server.
 */
enum class Provider(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val anthropicFormat: Boolean = false,
    val requiresKey: Boolean = true,
    /** Whether the default model accepts images. */
    val visionByDefault: Boolean = true,
    val recommended: List<ModelInfo> = emptyList(),
    val keyHint: String = "",
    val tagline: String = "",
    val hostedCatalog: Boolean = true,
    val canListModels: Boolean = true,
    /** Context window assumed when the model's own value is unknown. */
    val defaultContext: Long = 128_000,
) {
    ANTHROPIC(
        "Anthropic Claude", "https://api.anthropic.com", "claude-sonnet-5", anthropicFormat = true,
        recommended = listOf(
            ModelInfo("claude-sonnet-5", "Claude Sonnet 5", default = true, tools = true, vision = true, context = 200_000, note = "balanced"),
            ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", tools = true, vision = true, context = 200_000, note = "faster, cheaper"),
            ModelInfo("claude-opus-5", "Claude Opus 5", tools = true, vision = true, context = 200_000, note = "most capable"),
        ),
        keyHint = "console.anthropic.com → API keys", tagline = "Anthropic's Claude family", defaultContext = 200_000,
    ),
    OPENAI(
        "OpenAI", "https://api.openai.com/v1", "gpt-5.6-terra",
        recommended = listOf(
            ModelInfo("gpt-5.6-terra", "GPT-5.6 Terra", default = true, tools = true, vision = true, context = 1_050_000, note = "balanced"),
            ModelInfo("gpt-5.6-luna", "GPT-5.6 Luna", tools = true, vision = true, context = 1_050_000, note = "faster, cheaper"),
            ModelInfo("gpt-6-astra", "GPT-6 Astra", tools = true, vision = true, context = 1_050_000, note = "most capable"),
        ),
        keyHint = "platform.openai.com → API keys", tagline = "OpenAI's GPT family",
    ),
    /** ChatGPT Plus/Pro/Business subscription via "Sign in with ChatGPT" (no API key; [com.eggman.backstage.llm.ChatGptAuth]). */
    CHATGPT(
        "ChatGPT (sign in)", "https://chatgpt.com/backend-api/codex", "gpt-6.1-terra",
        recommended = listOf(
            ModelInfo("gpt-6.1-terra", "GPT-6.1 Terra", default = true, tools = true, vision = true, context = 400_000, note = "balanced"),
            ModelInfo("gpt-6-luna", "GPT-6 Luna", tools = true, vision = true, context = 400_000, note = "faster, lighter on your limits"),
            ModelInfo("gpt-6.1-sol", "GPT-6.1 Sol", tools = true, vision = true, context = 400_000, note = "most capable"),
            ModelInfo("gpt-6-astra", "GPT-6 Astra", tools = true, vision = true, context = 400_000),
        ),
        keyHint = "Sign in with your ChatGPT account; usage counts against your plan's limits",
        tagline = "Your ChatGPT subscription instead of an API key", hostedCatalog = false, canListModels = false,
        defaultContext = 400_000,
    ),
    GEMINI(
        "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash",
        recommended = listOf(
            ModelInfo("gemini-3.8-flash", "Gemini 3.8 Flash", default = true, tools = true, vision = true, context = 1_048_576, note = "balanced"),
            ModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", tools = true, vision = true, context = 1_048_576, note = "faster, cheaper"),
            ModelInfo("gemini-3.1-pro-preview", "Gemini 3.1 Pro (preview)", tools = true, vision = true, context = 1_048_576, note = "most capable"),
        ),
        keyHint = "aistudio.google.com → Get API key", tagline = "Google's Gemini family", defaultContext = 1_048_576,
    ),
    OPENROUTER(
        "OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-5",
        recommended = listOf(
            ModelInfo("anthropic/claude-sonnet-5", "Claude Sonnet 5", default = true, tools = true, vision = true),
            ModelInfo("google/gemini-3.8-flash", "Gemini 3.8 Flash", tools = true, vision = true),
            ModelInfo("openai/gpt-5.6-terra", "GPT-5.6 Terra", tools = true, vision = true),
        ),
        keyHint = "openrouter.ai → Keys", tagline = "Many vendors behind a single account",
    ),
    DEEPSEEK(
        "DeepSeek", "https://api.deepseek.com", "deepseek-flash",
        recommended = listOf(ModelInfo("deepseek-flash", "DeepSeek Flash", default = true, tools = true, vision = true, context = 1_000_000)),
        keyHint = "platform.deepseek.com → API keys", tagline = "DeepSeek's own API",
    ),
    OLLAMA(
        "Ollama (local/LAN)", "http://192.168.0.20:11434", "qwen3-vl", requiresKey = false,
        keyHint = "Run `OLLAMA_HOST=0.0.0.0 ollama serve` on a computer in your network and enter its address",
        tagline = "Self-hosted on your network, free", hostedCatalog = false, canListModels = true,
    ),
    CUSTOM(
        "OpenAI-compatible endpoint", "", "", requiresKey = false,
        keyHint = "Complete endpoint address ending in /chat/completions, e.g. https://gateway.example/v1/chat/completions",
        tagline = "Your own proxy or gateway", hostedCatalog = false, canListModels = true,
    ),
    VENICE(
        "Venice", "https://api.venice.ai/api/v1", "",
        keyHint = "venice.ai → API", tagline = "Privacy-focused hosting",
    ),
    OPENCODE(
        "OpenCode Go", "https://opencode.ai/zen/go/v1", "glm-5.3", visionByDefault = false,
        recommended = listOf(
            ModelInfo("glm-5.3", "GLM 5.3", default = true, tools = true, vision = false, note = "balanced"),
            ModelInfo("glm-5.3-flash", "GLM 5.3 Flash", tools = true, vision = false, note = "faster"),
            ModelInfo("kimi-k3", "Kimi K3", tools = true, vision = false, note = "most capable"),
        ),
        keyHint = "opencode.ai → API keys", tagline = "GLM, Kimi and others via OpenCode",
    ),
    ;

    val wire: WireFormat get() = if (anthropicFormat) WireFormat.ANTHROPIC_MESSAGES else WireFormat.OPENAI_CHAT

    /** Recommended ids (legacy name used by the prototype UI). */
    val suggestedModels: List<String> get() = recommended.map { it.id }.ifEmpty { listOfNotNull(defaultModel.takeIf { it.isNotEmpty() }) }

    fun recommendedInfo(model: String): ModelInfo? = recommended.firstOrNull { ModelIds.same(it.id, model) }

    companion object {
        fun parse(value: String?): Provider? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
            ?: when (value?.trim()?.lowercase()) {
                "claude" -> ANTHROPIC
                "google" -> GEMINI
                "chatgpt", "gpt" -> OPENAI
                "opencode go" -> OPENCODE
                else -> null
            }
    }
}

object ModelIds {
    private val dated = Regex("-(\\d{8}|\\d{4}-\\d{2}-\\d{2})$")

    /** Same model ignoring a trailing date snapshot suffix (claude-x-20250101 == claude-x). */
    fun same(a: String, b: String): Boolean = a.equals(b, true) || dated.replace(a.lowercase(), "") == dated.replace(b.lowercase(), "")

    /** Version number parsed from an id for "newest first" ordering (gpt-5.6 → 5.6). */
    fun version(id: String): Double = Regex("(\\d+(?:\\.\\d+)?)").findAll(id).mapNotNull { it.value.toDoubleOrNull() }
        .filter { it < 1000 }.maxOrNull() ?: 0.0
}
