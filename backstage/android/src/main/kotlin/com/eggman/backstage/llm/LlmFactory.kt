package com.eggman.backstage.llm

import com.eggman.backstage.data.WireFormat
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Everything needed to build a client; produced from settings, testable without Android. */
data class ProviderConfig(
    val provider: ProviderId,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val vision: Boolean = provider.visionByDefault,
    val timeouts: HttpTimeouts = HttpTimeouts(),
    /** low | medium | high, or null for the provider default. */
    val reasoningEffort: String? = null,
    /** Per-task id; OpenCode receives a stable session header derived from it. */
    val sessionId: String = "",
    val userAgent: String = "Backstage",
    /** The ChatGPT sign-in, for [ProviderId.CHATGPT] only. */
    val chatGpt: ChatGptSession? = null,
) {
    /** Null when usable, else a remediation text. */
    fun problem(): String? = when {
        provider == ProviderId.CHATGPT && (chatGpt == null || apiKey.isBlank()) -> "Not signed in to ChatGPT (Settings → AI provider → Sign in with ChatGPT)."
        provider.requiresKey && apiKey.isBlank() -> "No API key set for ${provider.label} (Settings → AI provider)."
        provider == ProviderId.CUSTOM && baseUrl.isBlank() -> "Enter the chat-completions URL of your OpenAI-compatible endpoint."
        model.isBlank() -> "No model selected for ${provider.label}."
        !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") -> "Invalid base URL for ${provider.label}: '$baseUrl'."
        else -> null
    }
}

object LlmFactory {
    enum class OpenCodeFamily { MESSAGES, RESPONSES, CHAT }

    /** OpenCode Go routes model families to different API styles. */
    fun openCodeFamily(model: String): OpenCodeFamily {
        val m = model.lowercase()
        return when {
            listOf("muse-spark-", "grok-", "gpt-").any { m.startsWith(it) } -> OpenCodeFamily.RESPONSES
            listOf("qwen", "union-alpha", "claude-", "minimax-").any { m.startsWith(it) } -> OpenCodeFamily.MESSAGES
            else -> OpenCodeFamily.CHAT
        }
    }

    /** OpenCode's session header: a stable name-based UUID of the task id (random when blank). */
    fun openCodeHeaders(config: ProviderConfig): Map<String, String> = mapOf(
        "x-opencode-session" to (if (config.sessionId.isBlank()) java.util.UUID.randomUUID() else java.util.UUID.nameUUIDFromBytes(config.sessionId.toByteArray())).toString(),
        "User-Agent" to config.userAgent,
    )

    fun create(config: ProviderConfig): LlmClient {
        config.problem()?.let { throw LlmException(LlmErrorKind.CONFIG, it) }
        val http = LlmHttp(timeouts = config.timeouts)
        if (config.provider == ProviderId.OPENCODE) {
            val headers = openCodeHeaders(config)
            return when (openCodeFamily(config.model)) {
                OpenCodeFamily.MESSAGES -> AnthropicClient(config.baseUrl, config.apiKey, config.model, http,
                    authHeaders = headers + ("Authorization" to "Bearer ${config.apiKey}"), provider = ProviderId.OPENCODE)
                OpenCodeFamily.RESPONSES -> OpenAiResponsesClient(config.provider, config.baseUrl, config.apiKey, config.model, config.vision, http, headers)
                OpenCodeFamily.CHAT -> OpenAiCompatClient(config.provider, config.baseUrl, config.apiKey, config.model, config.vision, http,
                    reasoningEffort = config.reasoningEffort, extraHeaders = headers)
            }
        }
        if (config.provider == ProviderId.CHATGPT) {
            return ChatGptClient(config.model, config.chatGpt!!, config.baseUrl, config.vision, http,
                reasoningEffort = config.reasoningEffort, sessionId = config.sessionId, userAgent = config.userAgent)
        }
        return when (config.provider.wire) {
            WireFormat.ANTHROPIC_MESSAGES -> AnthropicClient(config.baseUrl, config.apiKey, config.model, http)
            WireFormat.OPENAI_CHAT -> OpenAiCompatClient(config.provider, config.baseUrl, config.apiKey, config.model, config.vision, http,
                reasoningEffort = config.reasoningEffort)
        }
    }
}

/**
 * Exponential backoff with jitter for transient provider failures (429/5xx/overloaded/network).
 * Honors `retry-after` when the provider sends it.
 */
data class RetryPolicy(
    val maxAttempts: Int = 4,
    val baseDelayMs: Long = 1_500,
    val maxDelayMs: Long = 30_000,
    val jitter: Double = 0.2,
) {
    fun delayFor(attempt: Int, error: LlmException, random: Random = Random.Default): Long {
        error.retryAfterMs?.let { return it.coerceIn(0, maxDelayMs) }
        val exp = (baseDelayMs * (1L shl (attempt - 1).coerceIn(0, 16))).coerceAtMost(maxDelayMs)
        val spread = (exp * jitter).toLong()
        return if (spread <= 0) exp else (exp - spread + random.nextLong(spread * 2 + 1)).coerceIn(0, maxDelayMs)
    }

    /**
     * Runs [block], retrying retryable [LlmException]s. [onRetry] is told about each retry
     * (attempt number starting at 1, the delay, the error) so the UI can show "retrying…".
   */
    suspend fun <T> run(
        onRetry: suspend (attempt: Int, delayMs: Long, error: LlmException) -> Unit = { _, _, _ -> },
        block: suspend (attempt: Int) -> T,
    ): T {
        var attempt = 1
        while (true) {
            try {
                return block(attempt)
            } catch (e: LlmException) {
                if (!e.retryable || attempt >= maxAttempts) throw e
                val wait = delayFor(attempt, e)
                onRetry(attempt, wait, e)
                delay(wait)
                attempt++
            }
        }
    }
}

/**
 * Per-task token and cost accounting. Prices are user-editable (USD per million tokens) because
 * list prices change; when the provider reports a cost (OpenRouter) that figure wins.
 */
class UsageMeter(private val prices: PriceTable = PriceTable()) {
    var usage: Usage = Usage()
        private set
    var calls: Int = 0
        private set
    private var estimatedCost: Double = 0.0
    private var costKnown = true

    fun record(model: String, u: Usage) {
        usage += u
        calls++
        val c = u.reportedCostUsd ?: prices.cost(model, u)
        if (c == null) costKnown = false else estimatedCost += c
    }

    /** Null when at least one call had no known price. */
    val costUsd: Double? get() = if (costKnown && calls > 0) estimatedCost else if (calls == 0) 0.0 else null
}

data class Price(val inputPerMTok: Double, val outputPerMTok: Double, val cacheReadPerMTok: Double? = null)

class PriceTable(private val prices: Map<String, Price> = emptyMap()) {
    fun cost(model: String, u: Usage): Double? {
        val p = prices[model] ?: prices.entries.firstOrNull { model.endsWith("/" + it.key) || model.startsWith(it.key) }?.value ?: return null
        val cacheRate = p.cacheReadPerMTok ?: p.inputPerMTok
        val uncachedIn = (u.inputTokens - u.cacheReadTokens - u.cacheWriteTokens).coerceAtLeast(0)
        return (uncachedIn * p.inputPerMTok + u.cacheReadTokens * cacheRate + u.cacheWriteTokens * p.inputPerMTok + u.outputTokens * p.outputPerMTok) / 1_000_000.0
    }

    companion object {
        /** Parses "model=in,out[,cacheRead]" lines (user setting). Bad lines are ignored. */
        fun parse(text: String): PriceTable = PriceTable(
            text.lines().mapNotNull { line ->
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) return@mapNotNull null
                val model = l.substringBefore('=').trim()
                val nums = l.substringAfter('=', "").split(',').map { it.trim().toDoubleOrNull() }
                if (model.isEmpty() || nums.size < 2 || nums[0] == null || nums[1] == null) return@mapNotNull null
                model to Price(nums[0]!!, nums[1]!!, nums.getOrNull(2))
            }.toMap(),
        )
    }
}
