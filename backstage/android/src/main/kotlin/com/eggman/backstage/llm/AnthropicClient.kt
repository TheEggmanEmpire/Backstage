package com.eggman.backstage.llm

import org.json.JSONArray
import org.json.JSONObject

typealias ProviderId = com.eggman.backstage.data.Provider

/** Anthropic Messages API (`POST {base}/v1/messages`). */
class AnthropicClient(
    private val baseUrl: String,
    private val apiKey: String,
    override val model: String,
    private val http: LlmHttp = LlmHttp(),
    /** Replaces x-api-key auth (OpenCode's Messages endpoint wants a Bearer token). */
    private val authHeaders: Map<String, String>? = null,
    override val provider: ProviderId = ProviderId.ANTHROPIC,
) : LlmClient {

    override suspend fun complete(request: LlmRequest): LlmReply {
        val body = encodeRequest(model, request)
        val url = baseUrl.trimEnd('/').let { if (it.endsWith("/messages")) it else if (it.endsWith("/v1")) "$it/messages" else "$it/v1/messages" }
        val headers = (authHeaders ?: mapOf("x-api-key" to apiKey)) + ("anthropic-version" to API_VERSION)
        val json = http.postJson(url, headers, body)
        return decodeResponse(json)
    }

    companion object {
        const val API_VERSION = "2023-06-01"

        fun encodeRequest(model: String, request: LlmRequest): JSONObject {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", request.maxTokens)
            if (request.system.isNotBlank()) {
                // The system prompt and tool list are stable across a task: mark them cacheable.
                body.put(
                    "system",
                    JSONArray().put(
                        JSONObject().put("type", "text").put("text", request.system)
                            .put("cache_control", JSONObject().put("type", "ephemeral")),
                    ),
                )
            }
            request.temperature?.let { body.put("temperature", it) }
            body.put("messages", encodeMessages(request.messages).also(::markCacheTail))
            if (request.tools.isNotEmpty() && request.toolChoice != ToolChoice.NONE) {
                val tools = JSONArray()
                request.tools.forEachIndexed { i, t ->
                    val tool = JSONObject().put("name", t.name).put("description", t.description).put("input_schema", t.schema)
                    if (i == request.tools.lastIndex) tool.put("cache_control", JSONObject().put("type", "ephemeral"))
                    tools.put(tool)
                }
                body.put("tools", tools)
                // One tool call per turn: the agent observes the result before acting again.
                body.put(
                    "tool_choice",
                    JSONObject().put("type", if (request.toolChoice == ToolChoice.REQUIRED) "any" else "auto")
                        .put("disable_parallel_tool_use", true),
                )
            }
            return body
        }

        /**
         * a third cache breakpoint at the end of the conversation (system and tools are the first two), so the
         * next step reads everything up to here from the cache. In a user turn with tool results it goes on the last
         * result: text after it (the live plan) changes every step and must stay outside the cached prefix.
       */
        fun markCacheTail(messages: JSONArray) {
            val content = messages.optJSONObject(messages.length() - 1)?.optJSONArray("content") ?: return
            if (content.length() == 0) return
            val idx = (content.length() - 1 downTo 0).firstOrNull { content.optJSONObject(it)?.optString("type") == "tool_result" }
                ?: (content.length() - 1)
            content.optJSONObject(idx)?.put("cache_control", JSONObject().put("type", "ephemeral"))
        }

        private fun image(img: Part.Image) = JSONObject().put("type", "image").put(
            "source", JSONObject().put("type", "base64").put("media_type", img.mediaType).put("data", img.base64),
        )

        /** Anthropic requires strictly alternating roles starting with user; adjacent same-role messages are merged. */
        fun encodeMessages(messages: List<Message>): JSONArray {
            val out = JSONArray()
            var lastRole: Role? = null
            var current: JSONArray? = null
            for (message in messages) {
                val blocks = encodeBlocks(message)
                if (blocks.length() == 0) continue
                if (lastRole == null && message.role == Role.ASSISTANT) {
                    // Conversation must start with a user turn.
                    val placeholder = JSONArray().put(JSONObject().put("type", "text").put("text", "(continue)"))
                    out.put(JSONObject().put("role", "user").put("content", placeholder))
                    lastRole = Role.USER
                }
                if (message.role == lastRole && current != null) {
                    // Tool results must come first in a user turn; keep order: existing blocks then new ones.
                    for (i in 0 until blocks.length()) current.put(blocks.get(i))
                } else {
                    current = blocks
                    out.put(JSONObject().put("role", if (message.role == Role.USER) "user" else "assistant").put("content", blocks))
                    lastRole = message.role
                }
            }
            return out
        }

        private fun encodeBlocks(message: Message): JSONArray {
            val content = JSONArray()
            // tool_result blocks first (API requirement when mixed with text in one user turn).
            message.parts.filterIsInstance<Part.ToolResult>().forEach { part ->
                val inner = JSONArray().put(JSONObject().put("type", "text").put("text", part.text.ifEmpty { "ok" }))
                part.image?.let { inner.put(image(it)) }
                val block = JSONObject().put("type", "tool_result").put("tool_use_id", part.callId).put("content", inner)
                if (part.isError) block.put("is_error", true)
                content.put(block)
            }
            message.parts.forEach { part ->
                when (part) {
                    is Part.Text -> if (part.text.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", part.text))
                    is Part.Image -> content.put(image(part))
                    is Part.ToolCall -> content.put(
                        JSONObject().put("type", "tool_use").put("id", part.id).put("name", part.name).put("input", com.eggman.backstage.tools.TypeTextTool.redactedArguments(part.name, part.args)),
                    )
                    is Part.ToolResult -> Unit
                }
            }
            return content
        }

        fun decodeResponse(json: JSONObject): LlmReply {
            if (json.optString("type") == "error") throw LlmHttp.mapHttpError(500, json.toString())
            val parts = mutableListOf<Part>()
            val content = json.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> block.optString("text").takeIf { it.isNotEmpty() }?.let { parts += Part.Text(it) }
                    "tool_use" -> parts += Part.ToolCall(
                        block.optString("id").ifBlank { "toolu_$i" },
                        block.getString("name"),
                        block.optJSONObject("input") ?: JSONObject(),
                    )
                }
            }
            val raw = json.optString("stop_reason")
            val stop = when (raw) {
                "end_turn", "stop_sequence" -> StopReason.END_TURN
                "tool_use" -> StopReason.TOOL_USE
                "max_tokens" -> StopReason.MAX_TOKENS
                "refusal" -> StopReason.REFUSAL
                else -> if (parts.any { it is Part.ToolCall }) StopReason.TOOL_USE else StopReason.OTHER
            }
            val u = json.optJSONObject("usage")
            val usage = Usage(
                // Anthropic input_tokens is the uncached portion, unlike our total-prompt convention.
                inputTokens = (u?.optLong("input_tokens") ?: 0) +
                    (u?.optLong("cache_read_input_tokens") ?: 0) + (u?.optLong("cache_creation_input_tokens") ?: 0),
                outputTokens = u?.optLong("output_tokens") ?: 0,
                cacheReadTokens = u?.optLong("cache_read_input_tokens") ?: 0,
                cacheWriteTokens = u?.optLong("cache_creation_input_tokens") ?: 0,
            )
            return LlmReply(Message(Role.ASSISTANT, parts), stop, usage, raw, json.optString("model"))
        }
    }
}
