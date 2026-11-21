package com.eggman.backstage.mcp

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom

/** One tool as exposed over MCP. */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val readOnly: Boolean = false,
    val destructive: Boolean = false,
    val title: String? = null,
)

data class McpContent(val type: String, val text: String? = null, val data: String? = null, val mimeType: String? = null)

data class McpCallResult(val content: List<McpContent>, val isError: Boolean = false, val structured: JSONObject? = null) {
    companion object {
        fun text(t: String, isError: Boolean = false, structured: JSONObject? = null) = McpCallResult(listOf(McpContent("text", text = t)), isError, structured)
    }
}

/** Raised by tool providers for bad arguments → JSON-RPC -32602. */
class McpInvalidParams(message: String) : Exception(message)

interface McpToolProvider {
    fun tools(): List<McpTool>
    suspend fun call(name: String, arguments: JSONObject): McpCallResult

    /** [requestKey] identifies the JSON-RPC request (session + id) so a later `notifications/cancelled` can stop it. */
    suspend fun call(name: String, arguments: JSONObject, requestKey: String?): McpCallResult = call(name, arguments)

    /** The client cancelled the request [requestKey] (`notifications/cancelled`). */
    fun cancel(requestKey: String) = Unit
}

/**
 * Streamable-HTTP MCP endpoint logic (transport independent, unit-tested): JSON-RPC 2.0 framing,
 * batches, notifications (→ 202), protocol-version negotiation, Mcp-Session-Id sessions,
 * initialize / ping / tools/list / tools/call.
 */
class McpProtocol(
    private val tools: McpToolProvider,
    private val serverName: String = "backstage",
    private val serverVersion: String = "1.0.0",
    private val instructions: String = DEFAULT_INSTRUCTIONS,
) {
    /**
     * id -> negotiated protocol version. Many clients never send DELETE, so the oldest idle sessions are
     * dropped beyond [MAX_SESSIONS] (access order: a session in use stays).
   */
    private val sessions: MutableMap<String, String> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_SESSIONS
        },
    )
    private val random = SecureRandom()

    companion object {
        /** Newest first. */
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val VERSION_HEADER = "MCP-Protocol-Version"
        const val UNAUTHORIZED = -32001
        const val SESSION_EXPIRED = -32002
        const val MAX_SESSIONS = 64
        const val INVALID_TOKEN_TEXT = "Authorization rejected: this client's bearer token no longer matches the phone's (it may have been reset). " +
            "Open Backstage → MCP server, copy the token shown there and paste it into this client's settings."
        const val DEFAULT_INSTRUCTIONS =
            "This server drives an Android phone through Backstage. Hand a whole job to the phone's own agent with execute_task; " +
                "it answers with the outcome. get_screen_state shows what is displayed; for readiness (screen, lock, agent) ask get_device_status. " +
                "Optional fine-grained tools (tap, type_text, …) work on the phone display or on the Dextop desktop, picked by their target argument."

        fun error(id: Any?, code: Int, message: String): JSONObject = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

        fun result(id: Any?, result: JSONObject): JSONObject = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)
    }

    fun negotiate(requested: String?): String = if (requested != null && requested in SUPPORTED_VERSIONS) requested else SUPPORTED_VERSIONS.first()

    fun sessionCount() = sessions.size

    /** Handles POST /mcp (body already authenticated). */
    suspend fun handlePost(request: HttpRequest): HttpResponse {
        val sessionHeader = request.header(SESSION_HEADER)
        val versionHeader = request.header(VERSION_HEADER)
        if (versionHeader != null && versionHeader !in SUPPORTED_VERSIONS) {
            return HttpResponse.json(400, error(null, -32600, "Unsupported MCP-Protocol-Version: $versionHeader").toString())
        }
        val ctype = request.header("content-type")?.lowercase()
        if (ctype != null && !ctype.startsWith("application/json")) {
            return HttpResponse.json(415, error(null, -32700, "Content-Type must be application/json").toString())
        }
        val parsed: Any = try {
            val t = request.bodyText.trim()
            when {
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> JSONObject(t)
                else -> throw JSONException("not JSON")
            }
        } catch (e: JSONException) {
            return HttpResponse.json(400, error(null, -32700, "Parse error: ${e.message}").toString())
        }
        val messages: List<Any?> = if (parsed is JSONArray) (0 until parsed.length()).map { parsed.opt(it) } else listOf(parsed)
        if (messages.isEmpty()) return HttpResponse.json(400, error(null, -32600, "Empty batch").toString())

        val isInit = messages.any { (it as? JSONObject)?.optString("method") == "initialize" }
        if (!isInit && sessionHeader != null && sessions[sessionHeader] == null) {
            // 404 makes spec-following clients re-initialize; the response reports an expired session.
            return HttpResponse.json(404, error(firstId(messages), SESSION_EXPIRED, "Session expired; reconnect (initialize again)").toString())
        }

        var newSession: String? = null
        val responses = ArrayList<JSONObject>()
        for (m in messages) {
            val msg = m as? JSONObject
            if (msg == null) { responses += error(null, -32600, "Invalid Request"); continue }
            val r = dispatch(msg, sessionHeader) { version -> newSession = newSessionId().also { sessions[it] = version } }
            if (r != null) responses += r
        }
        val headers = buildMap { (newSession ?: sessionHeader?.takeIf { sessions[it] != null })?.let { put(SESSION_HEADER, it) } }
        if (responses.isEmpty()) return HttpResponse.empty(202, headers)
        val body = if (parsed is JSONArray) JSONArray(responses).toString() else responses.single().toString()
        return HttpResponse.json(200, body, headers)
    }

    /** DELETE /mcp terminates a session (always OK). */
    fun handleDelete(request: HttpRequest): HttpResponse {
        request.header(SESSION_HEADER)?.let { sessions.remove(it) }
        return HttpResponse.empty(200)
    }

    private fun firstId(messages: List<Any?>): Any? = (messages.firstOrNull() as? JSONObject)?.opt("id")

    /** Wrong bearer token: HTTP 200 with an isError tool result carrying the request id. */
    fun invalidToken(request: HttpRequest): HttpResponse {
        val id = runCatching { JSONObject(request.bodyText).opt("id") }.getOrNull()
        val result = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", INVALID_TOKEN_TEXT)))
            .put("isError", true)
        return HttpResponse.json(200, result(id, result).toString())
    }

    private fun newSessionId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** Returns null for notifications and client responses. */
    internal suspend fun dispatch(msg: JSONObject, session: String? = null, onInitialized: (String) -> Unit = {}): JSONObject? {
        val hasId = msg.has("id")
        val id: Any? = if (hasId) msg.opt("id") else null
        if (msg.optString("jsonrpc") != "2.0") return if (hasId) error(id, -32600, "jsonrpc must be \"2.0\"") else null
        if (!msg.has("method")) return null // a response from the client (we never send requests) — ignore
        if (id != null && id != JSONObject.NULL && id !is String && id !is Number) return error(null, -32600, "Invalid id")
        val method = msg.optString("method")
        val params = msg.optJSONObject("params") ?: JSONObject()
        if (!hasId) {
            // Notifications get no answer. A cancellation stops the matching running tool call (e.g. execute_task).
            if (method == "notifications/cancelled") {
                params.opt("requestId")?.takeIf { it != JSONObject.NULL }?.let { tools.cancel(requestKey(session, it)) }
            }
            return null
        }
        return try {
            when (method) {
                "initialize" -> {
                    val version = negotiate(params.optString("protocolVersion").ifBlank { null })
                    onInitialized(version)
                    result(
                        id,
                        JSONObject()
                            .put("protocolVersion", version)
                            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                            .put("serverInfo", JSONObject().put("name", serverName).put("version", serverVersion))
                            .put("instructions", instructions),
                    )
                }
                "ping" -> result(id, JSONObject())
                "tools/list" -> result(id, JSONObject().put("tools", JSONArray().apply { tools.tools().forEach { put(toolJson(it)) } }))
                "tools/call" -> {
                    val name = params.optString("name").ifBlank { return error(id, -32602, "Missing tool name") }
                    if (tools.tools().none { it.name == name }) return error(id, -32602, "Unknown or disabled tool: $name")
                    val args = when (val a = params.opt("arguments")) {
                        null, JSONObject.NULL -> JSONObject()
                        is JSONObject -> a
                        else -> return error(id, -32602, "arguments must be an object")
                    }
                    result(id, callResultJson(tools.call(name, args, requestKey(session, id))))
                }
                "logging/setLevel" -> result(id, JSONObject())
                else -> error(id, -32601, "Method not found: $method")
            }
        } catch (e: McpInvalidParams) {
            error(id, -32602, e.message ?: "Invalid params")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            error(id, -32603, "Internal error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** Request ids are only unique per client session. */
    private fun requestKey(session: String?, id: Any?): String = (session ?: "-") + "/" + id.toString().removeSuffix(".0")

    private fun toolJson(t: McpTool): JSONObject = JSONObject()
        .put("name", t.name)
        .putOpt("title", t.title)
        .put("description", t.description)
        .put("inputSchema", t.inputSchema)
        .put("annotations", JSONObject().put("readOnlyHint", t.readOnly).put("destructiveHint", t.destructive).put("openWorldHint", true))

    private fun callResultJson(r: McpCallResult): JSONObject {
        val content = JSONArray()
        r.content.forEach { c ->
            val o = JSONObject().put("type", c.type)
            c.text?.let { o.put("text", it) }
            c.data?.let { o.put("data", it) }
            c.mimeType?.let { o.put("mimeType", it) }
            content.put(o)
        }
        return JSONObject().put("content", content).put("isError", r.isError).putOpt("structuredContent", r.structured)
    }
}
