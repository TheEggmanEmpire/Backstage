package com.eggman.backstage.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Model calls paid by a ChatGPT subscription: the Responses dialect on ChatGPT's Codex backend
 * (`{base}/responses`). That backend only streams (server-sent events) and never stores; the reply is read to
 * its `response.completed` event and decoded like a plain Responses reply. A rejected access token is
 * refreshed once and the call repeated.
 */
class ChatGptClient(
    override val model: String,
    private val session: ChatGptSession,
    private val baseUrl: String = DEFAULT_BASE,
    private val vision: Boolean = true,
    private val http: LlmHttp = LlmHttp(),
    private val reasoningEffort: String? = null,
    private val sessionId: String = "",
    private val userAgent: String = "Backstage",
) : LlmClient {
    override val provider: ProviderId = ProviderId.CHATGPT
    private val conversation = sessionId.ifBlank { java.util.UUID.randomUUID().toString() }

    override suspend fun complete(request: LlmRequest): LlmReply {
        val body = encodeRequest(model, request, vision, reasoningEffort)
        val url = baseUrl.trimEnd('/').let { if (it.endsWith("/responses")) it else "$it/responses" }
        var tokens = session.tokens()
        repeat(2) { attempt ->
            val raw = post(url, headers(tokens), body)
            if (raw.code == 401 && attempt == 0) {
                tokens = session.tokens(forceRefresh = true)
                return@repeat
            }
            if (raw.code == 401 || raw.code == 403) {
                throw LlmException(LlmErrorKind.AUTH, "ChatGPT rejected the sign-in (HTTP ${raw.code}: ${LlmHttp.errorMessage(raw.body)}). Sign in again in Settings → AI provider.", raw.code)
            }
            if (raw.code !in 200..299) throw mapError(raw.code, raw.body, raw.retryAfter)
            if (raw.truncated) throw LlmException(LlmErrorKind.INVALID_RESPONSE, "ChatGPT reply exceeded the size limit", raw.code)
            return decodeStream(raw.body)
        }
        throw LlmException(LlmErrorKind.AUTH, "ChatGPT rejected the sign-in. Sign in again in Settings → AI provider.", 401)
    }

    private fun headers(t: ChatGptTokens): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${t.accessToken}")
        if (t.accountId.isNotBlank()) put("chatgpt-account-id", t.accountId)
        put("OpenAI-Beta", "responses=experimental")
        put("originator", ChatGptAuth.ORIGINATOR)
        put("session_id", conversation)
        put("Accept", "text/event-stream")
        put("User-Agent", userAgent)
    }

    private suspend fun post(url: String, headers: Map<String, String>, body: JSONObject): LlmHttp.RawResponse {
        val b = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.raw(b.build())
    }

    companion object {
        const val DEFAULT_BASE = "https://chatgpt.com/backend-api/codex"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun encodeRequest(model: String, request: LlmRequest, vision: Boolean, reasoningEffort: String? = null): JSONObject {
            val body = OpenAiResponsesClient.encodeRequest(model, request, vision)
            body.remove("max_output_tokens") // not accepted by this backend
            body.put("stream", true).put("store", false)
            if (!body.has("instructions")) body.put("instructions", "")
            reasoningEffort?.let { body.put("reasoning", JSONObject().put("effort", it)) }
            return body
        }

        /** Reads the event stream; the final `response` object carries the whole reply. */
        fun decodeStream(text: String): LlmReply {
            var final: JSONObject? = null
            val items = JSONArray()
            for (line in text.lineSequence()) {
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val ev = runCatching { JSONObject(data) }.getOrNull() ?: continue
                when (ev.optString("type")) {
                    "response.output_item.done" -> ev.optJSONObject("item")?.let { items.put(it) }
                    "response.completed", "response.incomplete" -> final = ev.optJSONObject("response")
                    "response.failed" -> throw streamError(ev.optJSONObject("response")?.optJSONObject("error") ?: JSONObject().put("message", "response failed"))
                    "error" -> throw streamError(ev.optJSONObject("error") ?: ev)
                }
            }
            // A stream that stops before its final event is a partial turn: retry it rather than act on half a reply.
            val response = final ?: throw LlmException(LlmErrorKind.INVALID_RESPONSE,
                if (items.length() > 0) "ChatGPT reply ended early" else "ChatGPT returned no reply: ${text.take(200)}")
            // Some streams send an empty output in the final event; the items seen on the way are the reply.
            if ((response.optJSONArray("output")?.length() ?: 0) == 0 && items.length() > 0) response.put("output", items)
            return OpenAiResponsesClient.decodeResponse(response)
        }

        /** An error reported inside the stream (HTTP 200): mapped by its code, not as a server fault. */
        fun streamError(err: JSONObject): LlmException {
            val code = err.optString("code").ifBlank { err.optString("type") }
            val msg = err.optString("message").ifBlank { code.ifBlank { "request failed" } }
            return when {
                code == "usage_limit_reached" || code == "usage_not_included" -> mapError(429, JSONObject().put("error", err).toString())
                code == "context_length_exceeded" -> LlmException(LlmErrorKind.CONTEXT_LENGTH, msg)
                code == "insufficient_quota" || code == "invalid_api_key" -> LlmException(LlmErrorKind.AUTH, "ChatGPT: $msg")
                code == "rate_limit_exceeded" -> LlmException(LlmErrorKind.RATE_LIMIT, msg)
                code == "invalid_prompt" || code == "invalid_request_error" -> LlmException(LlmErrorKind.BAD_REQUEST, msg)
                code == "server_is_overloaded" || code == "slow_down" -> LlmException(LlmErrorKind.OVERLOADED, msg)
                else -> LlmException(LlmErrorKind.SERVER, "ChatGPT: $msg")
            }
        }

        /** Plan limits are not worth retrying for minutes: say when they reset instead. */
        fun mapError(code: Int, body: String, retryAfter: String? = null): LlmException {
            val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            if (err?.optString("type") == "usage_limit_reached" || err?.optString("code") == "usage_limit_reached") {
                val mins = err.optLong("resets_in_seconds").takeIf { it > 0 }?.let { (it + 59) / 60 }
                val plan = err.optString("plan_type").takeIf { it.isNotBlank() }?.let { " ($it plan)" }.orEmpty()
                return LlmException(LlmErrorKind.AUTH, "ChatGPT usage limit reached$plan" + (mins?.let { ", resets in about $it min" } ?: ""), code)
            }
            if (err?.optString("type") == "usage_not_included" || err?.optString("code") == "usage_not_included") {
                return LlmException(LlmErrorKind.AUTH, "ChatGPT: your plan does not include this use (Codex). Use another plan or an API key provider.", code)
            }
            return LlmHttp.mapHttpError(code, body, retryAfter)
        }
    }
}
