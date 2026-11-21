package com.eggman.backstage.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Timeouts for one provider. The call timeout bounds the whole request (vision calls can be slow). */
data class HttpTimeouts(
    val connectMs: Long = 20_000,
    val readMs: Long = 120_000,
    val callMs: Long = 180_000,
)

/** Thin suspending JSON-over-HTTP helper shared by all provider adapters. */
class LlmHttp(
    base: OkHttpClient = sharedClient,
    timeouts: HttpTimeouts = HttpTimeouts(),
    /** Larger provider replies are rejected (never buffered whole). */
    private val maxResponse: Long = MAX_LLM_RESPONSE,
) {
    private val client: OkHttpClient = base.newBuilder()
        .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.readMs, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(timeouts.callMs, TimeUnit.MILLISECONDS)
        .build()

    suspend fun postJson(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val builder = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val resp = execute(client, builder.build(), maxResponse)
        if (resp.code in 200..299 && resp.truncated) {
            throw LlmException(LlmErrorKind.INVALID_RESPONSE, "Provider response exceeded $maxResponse bytes", resp.code)
        }
        if (resp.code !in 200..299) throw mapHttpError(resp.code, resp.body, resp.retryAfter)
        return try {
            JSONObject(resp.body)
        } catch (e: JSONException) {
            throw LlmException(LlmErrorKind.INVALID_RESPONSE, "Provider returned non-JSON: ${resp.body.take(200)}", resp.code, cause = e)
        }
    }

    /** One request with this helper's timeouts and body limit; the caller interprets the status. */
    suspend fun raw(request: Request): RawResponse = execute(client, request, maxResponse)

    data class RawResponse(
        val code: Int,
        val body: String,
        val retryAfter: String?,
        val contentType: String?,
        /** True when the body is cut at the byte limit given to [execute]. */
        val truncated: Boolean = false,
        /** URL of the response actually received (after redirects). */
        val finalUrl: String? = null,
    )

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Default body limit for [execute]; LLM replies are far below it, a runaway download is cut there. */
        const val MAX_LLM_RESPONSE = 32L * 1024 * 1024

        /**
         * Reads at most [maxBytes] of [response]'s body without buffering the rest, so a huge or endless body
         * (a large file behind a link, a prompt-injected URL) cannot exhaust memory.
       */
        fun readCapped(response: Response, maxBytes: Long): Pair<String, Boolean> {
            val body = response.body ?: return "" to false
            val declared = body.contentLength()
            val source = body.source()
            val complete = source.request(maxBytes + 1).not()
            val buffer = source.buffer
            val take = minOf(buffer.size, maxBytes)
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            val text = buffer.readString(take, charset)
            val truncated = !complete || (declared > maxBytes)
            return text to truncated
        }

        /**
         * opens the connection (DNS, TCP, TLS) to [baseUrl]'s host in the background at task start, so the first
         * model call does not pay for it. A HEAD of the host root without credentials; the answer is ignored.
       */
        fun prewarm(baseUrl: String, client: OkHttpClient = sharedClient): Boolean {
            val url = prewarmUrl(baseUrl) ?: return false
            return runCatching {
                client.newCall(Request.Builder().url(url).head().build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = Unit
                    override fun onResponse(call: Call, response: Response) = response.close()
                })
            }.isSuccess
        }

        /** The host root of an https/http provider address; null for anything else. */
        fun prewarmUrl(baseUrl: String): String? {
            val u = runCatching { java.net.URI(baseUrl.trim()) }.getOrNull() ?: return null
            if (u.scheme?.lowercase() !in setOf("http", "https") || u.host.isNullOrBlank()) return null
            return "${u.scheme.lowercase()}://${u.host}" + (if (u.port > 0) ":${u.port}" else "") + "/"
        }

        /** One connection pool for the whole process (LLM + web tools). */
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }

        suspend fun execute(client: OkHttpClient, request: Request, maxBytes: Long = MAX_LLM_RESPONSE): RawResponse = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val call = client.newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(mapIoError(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // Nothing may escape this callback: OkHttp rethrows non-IOExceptions on its own thread,
                        // which would reach the uncaught-exception handler and kill the whole (Dextop) process.
                        val raw = try {
                            response.use {
                                val (text, truncated) = readCapped(it, maxBytes)
                                RawResponse(it.code, text, it.header("retry-after"), it.header("content-type"), truncated, it.request.url.toString())
                            }
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(mapIoError(e))
                            return
                        } catch (t: Throwable) {
                            if (cont.isActive) {
                                cont.resumeWithException(LlmException(LlmErrorKind.INVALID_RESPONSE, "Could not read the response: ${t.javaClass.simpleName}", response.code))
                            }
                            return
                        }
                        if (cont.isActive) cont.resume(raw)
                    }
                })
            }
        }

        fun mapIoError(e: IOException): LlmException = when (e) {
            is SocketTimeoutException -> LlmException(LlmErrorKind.TIMEOUT, "timed out", cause = e)
            is InterruptedIOException -> LlmException(LlmErrorKind.TIMEOUT, e.message ?: "timed out", cause = e)
            is UnknownHostException -> LlmException(LlmErrorKind.NETWORK, "unknown host ${e.message}", cause = e)
            else -> LlmException(LlmErrorKind.NETWORK, e.message ?: e.javaClass.simpleName, cause = e)
        }

        /** Pulls a human-readable message out of the error envelopes used by Anthropic, OpenAI-compatible servers and Gemini. */
        fun errorMessage(body: String): String {
            val trimmed = body.trim()
            val obj: JSONObject? = try {
                when {
                    trimmed.startsWith("{") -> JSONObject(trimmed)
                    trimmed.startsWith("[") -> JSONArray(trimmed).optJSONObject(0)
                    else -> null
                }
            } catch (_: JSONException) {
                null
            }
            if (obj != null) {
                val err = obj.opt("error")
                when (err) {
                    is JSONObject -> err.optString("message").takeIf { it.isNotBlank() }?.let { return it }
                    is String -> if (err.isNotBlank()) return err
                }
                obj.optString("message").takeIf { it.isNotBlank() }?.let { return it }
                obj.optString("detail").takeIf { it.isNotBlank() }?.let { return it }
            }
            return trimmed.take(300).ifBlank { "empty response" }
        }

        fun parseRetryAfter(value: String?): Long? {
            val v = value?.trim() ?: return null
            v.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(0) }
            return null
        }

        fun mapHttpError(code: Int, body: String, retryAfter: String? = null): LlmException {
            val msg = errorMessage(body)
            val lower = (msg + " " + body.take(500)).lowercase()
            val kind = when {
                code == 401 || code == 403 -> LlmErrorKind.AUTH
                code == 404 -> LlmErrorKind.NOT_FOUND
                code == 408 -> LlmErrorKind.TIMEOUT
                code == 413 -> LlmErrorKind.CONTEXT_LENGTH
                code == 429 && ("quota" in lower && ("exceeded" in lower || "insufficient" in lower) && "rate" !in lower) -> LlmErrorKind.AUTH
                code == 429 -> LlmErrorKind.RATE_LIMIT
                code == 529 || code == 503 || "overloaded" in lower -> LlmErrorKind.OVERLOADED
                code in 500..599 -> LlmErrorKind.SERVER
                code == 400 && (
                    "context length" in lower || "context_length" in lower || "too many tokens" in lower ||
                        "prompt is too long" in lower || "maximum context" in lower || "too long" in lower
                    ) -> LlmErrorKind.CONTEXT_LENGTH
                code == 402 -> LlmErrorKind.AUTH // payment required (OpenRouter credits)
                else -> LlmErrorKind.BAD_REQUEST
            }
            return LlmException(kind, "HTTP $code: $msg", code, parseRetryAfter(retryAfter))
        }
    }
}
