package com.eggman.backstage.llm

import org.json.JSONObject

/**
 * Provider-neutral conversation model. Every provider adapter converts this to and from its own
 * wire format, so the agent loop, the context manager and the tests never see provider JSON.
 */
sealed interface Part {
    data class Text(val text: String) : Part

    /** Base64 payload without a `data:` prefix. Width/height are informational (coordinate scaling hints). */
    data class Image(
        val base64: String,
        val mediaType: String = "image/jpeg",
        val width: Int = 0,
        val height: Int = 0,
    ) : Part

    /**
     * [meta] carries provider-opaque data that must be sent back unchanged with the call
     * (e.g. Gemini's thought signatures in `extra_content`).
   */
    data class ToolCall(val id: String, val name: String, val args: JSONObject, val meta: JSONObject? = null) : Part {
        override fun equals(other: Any?): Boolean =
            other is ToolCall && other.id == id && other.name == name && other.args.toString() == args.toString()
        override fun hashCode(): Int = id.hashCode() * 31 + name.hashCode()
    }

    data class ToolResult(
        val callId: String,
        val text: String,
        val image: Image? = null,
        val isError: Boolean = false,
        /** Tool name; some wire formats (Gemini native) key results by name instead of id. */
        val name: String = "",
    ) : Part
}

enum class Role { USER, ASSISTANT }

data class Message(val role: Role, val parts: List<Part>) {
    val text: String get() = parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
    val toolCalls: List<Part.ToolCall> get() = parts.filterIsInstance<Part.ToolCall>()
    val toolResults: List<Part.ToolResult> get() = parts.filterIsInstance<Part.ToolResult>()
    val hasImage: Boolean get() = parts.any { it is Part.Image || (it is Part.ToolResult && it.image != null) }

    companion object {
        fun user(text: String) = Message(Role.USER, listOf(Part.Text(text)))
        fun assistant(text: String) = Message(Role.ASSISTANT, listOf(Part.Text(text)))
    }
}

/** A function the model may call. [schema] is a JSON-Schema object (type=object). */
data class ToolSpec(val name: String, val description: String, val schema: JSONObject)

enum class ToolChoice { AUTO, REQUIRED, NONE }

data class LlmRequest(
    val system: String,
    val messages: List<Message>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 4096,
    val temperature: Double? = null,
    val toolChoice: ToolChoice = ToolChoice.AUTO,
)

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    /** Provider-reported cost in USD (OpenRouter reports it); null when unknown. */
    val reportedCostUsd: Double? = null,
) {
    val totalTokens: Long get() = inputTokens + outputTokens

    operator fun plus(o: Usage) = Usage(
        inputTokens + o.inputTokens,
        outputTokens + o.outputTokens,
        cacheReadTokens + o.cacheReadTokens,
        cacheWriteTokens + o.cacheWriteTokens,
        when {
            reportedCostUsd == null -> o.reportedCostUsd
            o.reportedCostUsd == null -> reportedCostUsd
            else -> reportedCostUsd + o.reportedCostUsd
        },
    )
}

data class LlmReply(
    val message: Message,
    val stopReason: StopReason,
    val usage: Usage = Usage(),
    val rawStopReason: String = "",
    val model: String = "",
)

interface LlmClient {
    val provider: ProviderId
    val model: String
    suspend fun complete(request: LlmRequest): LlmReply
}

enum class LlmErrorKind(val retryable: Boolean) {
    AUTH(false),
    BAD_REQUEST(false),
    CONTEXT_LENGTH(false),
    NOT_FOUND(false),
    RATE_LIMIT(true),
    OVERLOADED(true),
    SERVER(true),
    TIMEOUT(true),
    NETWORK(true),
    INVALID_RESPONSE(true),
    CONFIG(false),
}

class LlmException(
    val kind: LlmErrorKind,
    message: String,
    val httpCode: Int = 0,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val retryable: Boolean get() = kind.retryable

    /** Short user-facing explanation with a remediation hint. */
    fun userMessage(): String = when (kind) {
        LlmErrorKind.AUTH -> if (message?.startsWith("ChatGPT") == true || message?.startsWith("Not signed in to ChatGPT") == true) message!!
            else "The AI provider rejected the API key ($message). Check the key in Settings."
        LlmErrorKind.RATE_LIMIT -> "The AI provider is rate-limiting requests. Try again in a moment."
        LlmErrorKind.OVERLOADED -> "The AI provider is overloaded. Try again shortly."
        LlmErrorKind.CONTEXT_LENGTH -> "The conversation is too long for this model. Start a new conversation."
        LlmErrorKind.NOT_FOUND -> "Model or endpoint not found ($message). Check the model id and base URL."
        LlmErrorKind.CONFIG -> message ?: "AI provider is not configured."
        LlmErrorKind.NETWORK, LlmErrorKind.TIMEOUT -> "Could not reach the AI provider ($message)."
        else -> "AI request failed: $message"
    }
}
