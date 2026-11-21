package com.eggman.backstage.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI Responses API dialect (`POST {base}/responses`), used for OpenCode Go's gpt/grok model
 * families. Stateless (`store: false`): the whole conversation is sent as input items.
 */
class OpenAiResponsesClient(
    override val provider: ProviderId,
    private val baseUrl: String,
    private val apiKey: String,
    override val model: String,
    private val vision: Boolean = true,
    private val http: LlmHttp = LlmHttp(),
    private val extraHeaders: Map<String, String> = emptyMap(),
) : LlmClient {

    override suspend fun complete(request: LlmRequest): LlmReply {
        val headers = buildMap {
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
            putAll(extraHeaders)
        }
        val url = baseUrl.trimEnd('/').let { if (it.endsWith("/responses")) it else "$it/responses" }
        return decodeResponse(http.postJson(url, headers, encodeRequest(model, request, vision)))
    }

    companion object {
        private fun image(img: Part.Image) = JSONObject().put("type", "input_image").put("image_url", "data:${img.mediaType};base64,${img.base64}")

        fun encodeRequest(model: String, request: LlmRequest, vision: Boolean): JSONObject {
            val input = JSONArray()
            for (m in request.messages) {
                if (m.role == Role.ASSISTANT) {
                    m.text.takeIf { it.isNotEmpty() }?.let {
                        input.put(JSONObject().put("type", "message").put("role", "assistant")
                            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", it))))
                    }
                    m.toolCalls.forEach {
                        input.put(JSONObject().put("type", "function_call").put("call_id", it.id).put("name", it.name).put("arguments", com.eggman.backstage.tools.TypeTextTool.redactedArguments(it.name, it.args).toString()))
                    }
                    continue
                }
                m.toolResults.forEach {
                    input.put(JSONObject().put("type", "function_call_output").put("call_id", it.callId)
                        .put("output", it.text.ifEmpty { "ok" }.let { t -> if (it.isError) "ERROR: $t" else t }))
                }
                val content = JSONArray()
                m.parts.filterIsInstance<Part.Text>().forEach { if (it.text.isNotEmpty()) content.put(JSONObject().put("type", "input_text").put("text", it.text)) }
                val images = m.toolResults.mapNotNull { it.image } + m.parts.filterIsInstance<Part.Image>()
                if (images.isNotEmpty()) {
                    if (vision) images.forEach { content.put(image(it)) }
                    else content.put(JSONObject().put("type", "input_text").put("text", "[image omitted: the configured model does not accept images]"))
                }
                if (content.length() > 0) input.put(JSONObject().put("type", "message").put("role", "user").put("content", content))
            }
            val body = JSONObject()
                .put("model", model)
                .put("input", input)
                .put("store", false)
                .put("max_output_tokens", request.maxTokens)
            if (request.system.isNotBlank()) body.put("instructions", request.system)
            if (request.tools.isNotEmpty() && request.toolChoice != ToolChoice.NONE) {
                body.put("tools", JSONArray().apply {
                    request.tools.forEach { put(JSONObject().put("type", "function").put("name", it.name).put("description", it.description).put("parameters", it.schema)) }
                })
                body.put("tool_choice", if (request.toolChoice == ToolChoice.REQUIRED) "required" else "auto")
                body.put("parallel_tool_calls", false)
            }
            return body
        }

        fun decodeResponse(json: JSONObject): LlmReply {
            json.optJSONObject("error")?.let { throw LlmHttp.mapHttpError(500, json.toString()) }
            val parts = mutableListOf<Part>()
            val output = json.optJSONArray("output") ?: JSONArray()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "message" -> item.optJSONArray("content")?.let { c ->
                        for (j in 0 until c.length()) {
                            val b = c.optJSONObject(j) ?: continue
                            if (b.optString("type") == "output_text") b.optString("text").takeIf { it.isNotBlank() }?.let { parts += Part.Text(it) }
                        }
                    }
                    "function_call" -> parts += Part.ToolCall(
                        item.optString("call_id").ifBlank { item.optString("id").ifBlank { "call_$i" } },
                        item.getString("name"),
                        OpenAiCompatClient.parseArguments(item.opt("arguments")),
                    )
                }
            }
            val incomplete = json.optString("status") == "incomplete"
            val stop = when {
                parts.any { it is Part.ToolCall } -> StopReason.TOOL_USE
                incomplete && json.optJSONObject("incomplete_details")?.optString("reason") == "max_output_tokens" -> StopReason.MAX_TOKENS
                incomplete -> StopReason.REFUSAL
                else -> StopReason.END_TURN
            }
            val u = json.optJSONObject("usage")
            return LlmReply(
                Message(Role.ASSISTANT, parts), stop,
                Usage(
                    inputTokens = u?.optLong("input_tokens") ?: 0,
                    outputTokens = u?.optLong("output_tokens") ?: 0,
                    cacheReadTokens = u?.optJSONObject("input_tokens_details")?.optLong("cached_tokens") ?: 0,
                ),
                json.optString("status"), json.optString("model"),
            )
        }
    }
}
