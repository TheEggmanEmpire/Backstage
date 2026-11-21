package com.eggman.backstage.llm

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Chat Completions dialect (`POST {base}/chat/completions`) used by OpenAI, OpenRouter, DeepSeek,
 * Ollama (`/v1`) and Gemini's OpenAI-compatible endpoint. Provider quirks are captured in [Dialect].
 */
class OpenAiCompatClient(
    override val provider: ProviderId,
    private val baseUrl: String,
    private val apiKey: String,
    override val model: String,
    private val vision: Boolean = provider.visionByDefault,
    private val http: LlmHttp = LlmHttp(),
    reasoningEffort: String? = null,
    extraHeaders: Map<String, String> = emptyMap(),
) : LlmClient {

    data class Dialect(
        /** OpenAI's newer models reject `max_tokens` and require `max_completion_tokens`. */
        val maxTokensField: String = "max_tokens",
        val extraHeaders: Map<String, String> = emptyMap(),
        /** OpenRouter: ask for cost accounting in the usage block. */
        val requestUsageCost: Boolean = false,
        val supportsVision: Boolean = true,
        /** Some servers reject `tool_choice: "required"`; we then just send "auto". */
        val supportsRequiredToolChoice: Boolean = true,
        /** One tool call per turn (`parallel_tool_calls: false`); Gemini and Ollama do not take the flag. */
        val disableParallelToolCalls: Boolean = true,
        /** Venice prepends its own system prompt unless told not to. */
        val veniceParameters: Boolean = false,
        /** "flat" → reasoning_effort, "openrouter" → reasoning.effort, null → not sent. */
        val reasoningStyle: String? = null,
        val reasoningEffort: String? = null,
    )

    val dialect: Dialect = dialectFor(provider, vision, reasoningEffort).let { it.copy(extraHeaders = it.extraHeaders + extraHeaders) }

    override suspend fun complete(request: LlmRequest): LlmReply {
        val body = encodeRequest(model, request, dialect)
        val headers = buildMap {
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
            putAll(dialect.extraHeaders)
        }
        val json = http.postJson(chatUrl(provider, baseUrl), headers, body)
        return decodeResponse(json)
    }

    companion object {
        /**
         * Endpoint URL: a full `/chat/completions` URL is used as given (Custom endpoints); Ollama
         * takes the server address (`http://host:11434`) and gets its OpenAI-compatible `/v1`.
       */
        fun chatUrl(provider: ProviderId, base: String): String {
            val b = base.trim().trimEnd('/')
            if (b.endsWith("/chat/completions")) return b
            if (provider == ProviderId.OLLAMA && !b.endsWith("/v1")) return "$b/v1/chat/completions"
            return "$b/chat/completions"
        }

        /** Model-list URL next to the chat endpoint. */
        fun modelsUrl(provider: ProviderId, base: String): String = chatUrl(provider, base).removeSuffix("/chat/completions") + "/models"

        fun dialectFor(provider: ProviderId, vision: Boolean, effort: String? = null): Dialect {
            val e = effort?.lowercase()?.takeIf { it in setOf("low", "medium", "high") }
            return when (provider) {
                ProviderId.OPENAI -> Dialect(maxTokensField = "max_completion_tokens", supportsVision = vision, reasoningStyle = "flat", reasoningEffort = e)
                ProviderId.OPENROUTER -> Dialect(
                    extraHeaders = mapOf("HTTP-Referer" to "https://github.com/eggman/backstage", "X-Title" to "Backstage"),
                    requestUsageCost = true,
                    supportsVision = vision,
                    reasoningStyle = "openrouter", reasoningEffort = e,
                )
                ProviderId.OLLAMA -> Dialect(supportsVision = vision, supportsRequiredToolChoice = false, disableParallelToolCalls = false)
                ProviderId.GEMINI -> Dialect(supportsVision = vision, disableParallelToolCalls = false, reasoningStyle = "flat", reasoningEffort = e)
                ProviderId.VENICE -> Dialect(supportsVision = vision, veniceParameters = true)
                else -> Dialect(supportsVision = vision)
            }
        }

        fun encodeRequest(model: String, request: LlmRequest, dialect: Dialect): JSONObject {
            val messages = JSONArray()
            if (request.system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", request.system))
            request.messages.forEach { encodeMessage(it, messages, dialect) }
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put(dialect.maxTokensField, request.maxTokens)
            request.temperature?.let { body.put("temperature", it) }
            if (request.tools.isNotEmpty() && request.toolChoice != ToolChoice.NONE) {
                body.put("tools", JSONArray().apply {
                    request.tools.forEach {
                        put(
                            JSONObject().put("type", "function").put(
                                "function",
                                JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.schema),
                            ),
                        )
                    }
                })
                body.put(
                    "tool_choice",
                    if (request.toolChoice == ToolChoice.REQUIRED && dialect.supportsRequiredToolChoice) "required" else "auto",
                )
                if (dialect.disableParallelToolCalls) body.put("parallel_tool_calls", false)
            }
            if (dialect.requestUsageCost) body.put("usage", JSONObject().put("include", true))
            if (dialect.veniceParameters) body.put("venice_parameters", JSONObject().put("include_venice_system_prompt", false))
            dialect.reasoningEffort?.let { eff ->
                when (dialect.reasoningStyle) {
                    "flat" -> body.put("reasoning_effort", eff)
                    "openrouter" -> body.put("reasoning", JSONObject().put("effort", eff))
                }
            }
            return body
        }

        private fun imagePart(img: Part.Image) = JSONObject().put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:${img.mediaType};base64,${img.base64}"))

        private const val NO_VISION = "[image omitted: the configured model does not accept images]"

        fun encodeMessage(message: Message, out: JSONArray, dialect: Dialect) {
            if (message.role == Role.ASSISTANT) {
                val text = message.text
                // OpenAI-compatible APIs reject an assistant turn with neither content nor tool_calls (an empty reply
                // after a length cut-off); the following user nudge carries on without it.
                if (text.isEmpty() && message.toolCalls.isEmpty()) return
                val m = JSONObject().put("role", "assistant").put("content", if (text.isEmpty()) JSONObject.NULL else text)
                if (message.toolCalls.isNotEmpty()) m.put("tool_calls", JSONArray().apply {
                    message.toolCalls.forEach {
                        val call = JSONObject().put("id", it.id).put("type", "function")
                            .put("function", JSONObject().put("name", it.name).put("arguments", com.eggman.backstage.tools.TypeTextTool.redactedArguments(it.name, it.args).toString()))
                        // Gemini (OpenAI-compat) requires its thought signature back with the call.
                        it.meta?.optJSONObject("extra_content")?.let { extra -> call.put("extra_content", extra) }
                        put(call)
                    }
                })
                out.put(m)
                return
            }
            // Tool results become role=tool messages (text only: most compatible servers reject images there);
            // their screenshots ride along in one follow-up user message.
            val results = message.toolResults
            results.forEach {
                val text = it.text.ifEmpty { "ok" }.let { t -> if (it.isError) "ERROR: $t" else t }
                out.put(JSONObject().put("role", "tool").put("tool_call_id", it.callId).put("content", text))
            }
            val content = JSONArray()
            val images = results.mapNotNull { it.image } + message.parts.filterIsInstance<Part.Image>()
            message.parts.filterIsInstance<Part.Text>().forEach {
                if (it.text.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", it.text))
            }
            if (images.isNotEmpty()) {
                if (dialect.supportsVision) {
                    if (results.isNotEmpty() && content.length() == 0) {
                        content.put(JSONObject().put("type", "text").put("text", "Screenshot returned by the last tool call:"))
                    }
                    images.forEach { content.put(imagePart(it)) }
                } else {
                    content.put(JSONObject().put("type", "text").put("text", NO_VISION))
                }
            }
            if (content.length() == 0) return
            // Plain text-only user turns are sent as a string for maximum server compatibility.
            val simple = content.length() == 1 && content.getJSONObject(0).optString("type") == "text"
            out.put(JSONObject().put("role", "user").put("content", if (simple) content.getJSONObject(0).getString("text") else content))
        }

        /** Arguments arrive as a JSON string (OpenAI) or an object (Ollama); tolerate fenced or empty payloads. */
        fun parseArguments(raw: Any?): JSONObject = when (raw) {
            is JSONObject -> raw
            null, JSONObject.NULL -> JSONObject()
            else -> {
                val s = raw.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                if (s.isEmpty()) JSONObject() else try {
                    JSONObject(s)
                } catch (_: JSONException) {
                    JSONObject().put("_raw", s)
                }
            }
        }

        fun decodeResponse(json: JSONObject): LlmReply {
            json.opt("error")?.let { if (it is JSONObject) throw LlmHttp.mapHttpError(it.optInt("code", 500).takeIf { c -> c in 400..599 } ?: 500, json.toString()) }
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
                ?: throw LlmException(LlmErrorKind.INVALID_RESPONSE, "No choices in response: ${json.toString().take(300)}")
            val msg = choice.optJSONObject("message") ?: JSONObject()
            val parts = mutableListOf<Part>()
            val content = msg.opt("content")
            when (content) {
                is String -> if (content.isNotBlank()) parts += Part.Text(content)
                is JSONArray -> for (i in 0 until content.length()) {
                    val c = content.optJSONObject(i) ?: continue
                    if (c.optString("type") == "text") c.optString("text").takeIf { it.isNotBlank() }?.let { parts += Part.Text(it) }
                }
            }
            msg.optJSONArray("tool_calls")?.let { calls ->
                for (i in 0 until calls.length()) {
                    val call = calls.optJSONObject(i) ?: continue
                    val fn = call.optJSONObject("function") ?: continue
                    val name = fn.optString("name").takeIf { it.isNotBlank() } ?: continue
                    val meta = call.optJSONObject("extra_content")?.let { JSONObject().put("extra_content", it) }
                    parts += Part.ToolCall(call.optString("id").ifBlank { "call_${i}_$name" }, name, parseArguments(fn.opt("arguments")), meta)
                }
            }
            val raw = choice.optString("finish_reason")
            val stop = when (raw) {
                "stop" -> if (parts.any { it is Part.ToolCall }) StopReason.TOOL_USE else StopReason.END_TURN
                "tool_calls", "function_call" -> StopReason.TOOL_USE
                "length" -> StopReason.MAX_TOKENS
                "content_filter" -> StopReason.REFUSAL
                else -> if (parts.any { it is Part.ToolCall }) StopReason.TOOL_USE else StopReason.END_TURN
            }
            val u = json.optJSONObject("usage")
            val cached = u?.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens")
                ?: u?.optLong("prompt_cache_hit_tokens") // DeepSeek
                ?: 0
            val usage = Usage(
                inputTokens = u?.optLong("prompt_tokens") ?: 0,
                outputTokens = u?.optLong("completion_tokens") ?: 0,
                cacheReadTokens = cached,
                reportedCostUsd = u?.let { if (it.has("cost")) it.optDouble("cost") else null }?.takeIf { !it.isNaN() },
            )
            return LlmReply(Message(Role.ASSISTANT, parts), stop, usage, raw, json.optString("model"))
        }
    }
}
