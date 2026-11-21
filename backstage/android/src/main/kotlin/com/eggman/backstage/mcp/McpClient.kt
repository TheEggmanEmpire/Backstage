package com.eggman.backstage.mcp

import com.eggman.backstage.exec.ScreenImage
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.llm.LlmHttp
import com.eggman.backstage.tools.Args
import com.eggman.backstage.tools.Tool
import com.eggman.backstage.tools.ToolContext
import com.eggman.backstage.tools.ToolResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class McpRemoteException(message: String, val code: Int = 0) : Exception(message)

/**
 * Client for a remote MCP server over Streamable HTTP (JSON or SSE responses), used to add a remote
 * server's tools to the agent. Static bearer token auth (OAuth sign-in is not implemented yet).
 */
class McpClient(
    val url: String,
    private val token: String? = null,
    base: OkHttpClient = LlmHttp.sharedClient,
    timeoutMs: Long = 15_000,
) {
    private val http = base.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
    // Tool execution can outlive discovery; a short timeout can hide a completed side effect from the agent.
    private val toolHttp = base.newBuilder().callTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    @Volatile private var sessionId: String? = null
    @Volatile private var protocolVersion: String? = null
    @Volatile var serverName: String = ""
        private set
    private var nextId = 1

    val initialized: Boolean get() = protocolVersion != null

    suspend fun initialize(): JSONObject = lock.withLock {
        val result = rpcLocked("initialize", JSONObject()
            .put("protocolVersion", McpProtocol.SUPPORTED_VERSIONS.first())
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "backstage").put("version", "1.0")))
        protocolVersion = result.optString("protocolVersion").ifBlank { McpProtocol.SUPPORTED_VERSIONS.first() }
        serverName = result.optJSONObject("serverInfo")?.optString("name").orEmpty()
        post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), expectResponse = false)
        result
    }

    suspend fun listTools(): List<McpTool> {
        if (!initialized) initialize()
        val out = ArrayList<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val r = lock.withLock { rpcLocked("tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } }) }
            val arr = r.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val ann = t.optJSONObject("annotations")
                out += McpTool(
                    t.optString("name"), t.optString("description"),
                    t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                    readOnly = ann?.optBoolean("readOnlyHint") == true,
                    destructive = ann?.optBoolean("destructiveHint", true) ?: true,
                    title = t.optString("title").ifBlank { null },
                )
            }
            cursor = r.optString("nextCursor").ifBlank { null }
        } while (cursor != null && ++pages < 20)
        return out.filter { it.name.isNotBlank() }
    }

    suspend fun callTool(name: String, arguments: JSONObject): McpCallResult {
        if (!initialized) initialize()
        val r = lock.withLock { rpcLocked("tools/call", JSONObject().put("name", name).put("arguments", arguments)) }
        val content = r.optJSONArray("content") ?: JSONArray()
        val items = (0 until content.length()).mapNotNull { content.optJSONObject(it) }.map {
            McpContent(it.optString("type"), it.optString("text").ifEmpty { null }, it.optString("data").ifEmpty { null }, it.optString("mimeType").ifEmpty { null })
        }
        return McpCallResult(items, r.optBoolean("isError"), r.optJSONObject("structuredContent"))
    }

    private suspend fun rpcLocked(method: String, params: JSONObject): JSONObject {
        val id = nextId++
        val resp = post(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params), expectResponse = true, id = id)
            ?: throw McpRemoteException("No response to $method")
        resp.optJSONObject("error")?.let { throw McpRemoteException(it.optString("message", "error"), it.optInt("code")) }
        return resp.optJSONObject("result") ?: JSONObject()
    }

    private suspend fun post(body: JSONObject, expectResponse: Boolean, id: Int = -1): JSONObject? {
        val b = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
        token?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        sessionId?.let { b.header(McpProtocol.SESSION_HEADER, it) }
        protocolVersion?.let { b.header(McpProtocol.VERSION_HEADER, it) }
        val (code, text, contentType, session) = execute(b.build(), toolCall = body.optString("method") == "tools/call")
        session?.let { sessionId = it }
        if (code == 401 || code == 403) throw McpRemoteException("The MCP server rejected the credentials (HTTP $code)", code)
        if (code == 404 && sessionId != null && body.optString("method") != "initialize") {
            sessionId = null; protocolVersion = null
            throw McpRemoteException("MCP session expired", code)
        }
        if (code !in 200..299) throw McpRemoteException("HTTP $code: ${LlmHttp.errorMessage(text)}", code)
        if (!expectResponse || text.isBlank()) return null
        return if (contentType?.contains("text/event-stream") == true) parseSse(text, id) else JSONObject(text)
    }

    private data class Raw(val code: Int, val body: String, val contentType: String?, val session: String?)

    /** Cancellable: a Stop cancels the HTTP call instead of waiting up to the 120 s tool timeout. */
    private suspend fun execute(req: Request, toolCall: Boolean): Raw = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val call = (if (toolCall) toolHttp else http).newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val raw = runCatching {
                    response.use { r -> Raw(r.code, r.body?.string().orEmpty(), r.header("Content-Type"), r.header(McpProtocol.SESSION_HEADER)) }
                }
                raw.fold({ if (cont.isActive) cont.resumeWith(Result.success(it)) }, { e ->
                    if (cont.isActive) cont.resumeWith(Result.failure(mapFailure(e as? java.io.IOException ?: java.io.IOException(e), toolCall)))
                })
            }
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(mapFailure(e, toolCall)))
            }
        })
    }

    private fun mapFailure(e: java.io.IOException, toolCall: Boolean): McpRemoteException =
        if (toolCall && e is java.io.InterruptedIOException) {
            McpRemoteException("The MCP tool call timed out and may have completed. Check its outcome before retrying: ${e.message}")
        } else McpRemoteException("Cannot reach the MCP server: ${e.message}")

    companion object {
        /** Finds the JSON-RPC response with [id] in an SSE body (`data:` lines, events separated by blank lines). */
        fun parseSse(body: String, id: Int): JSONObject? {
            val events = body.replace("\r\n", "\n").split("\n\n")
            for (e in events) {
                val data = e.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }
                if (data.isBlank()) continue
                val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                if (json.optInt("id", Int.MIN_VALUE) == id && (json.has("result") || json.has("error"))) return json
            }
            return null
        }

        fun slug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "server" }
    }
}

/** A remote server's tool offered to the agent as `mcp_<server>_<tool>`. */
class RemoteMcpTool(serverName: String, private val remote: McpTool, private val client: McpClient) : Tool {
    /** The tool's name on its server (what the per-tool switches in Settings store). */
    val remoteName: String get() = remote.name
    override val name: String = ("mcp_" + McpClient.slug(serverName) + "_" + remote.name.replace(Regex("[^A-Za-z0-9_-]"), "_")).take(64)
    override val description: String = "[${serverName} MCP] " + remote.description.take(1_000)
    override val schema: JSONObject = remote.inputSchema.let { if (it.optString("type") == "object") it else JSONObject().put("type", "object").put("properties", JSONObject()) }
    override val targets = emptySet<TargetKind>()
    override val mutating: Boolean = !remote.readOnly

    override suspend fun run(ctx: ToolContext, args: Args): ToolResult = try {
        val r = client.callTool(remote.name, args.json)
        val text = r.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n").ifBlank {
            r.structured?.toString() ?: "(no text result)"
        }
        val img = r.content.firstOrNull { it.type == "image" && it.data != null }?.let { ScreenImage(it.data!!, it.mimeType ?: "image/png", 0, 0, 0, 0) }
        ToolResult(text.take(20_000), image = img, isError = r.isError, data = r.structured)
    } catch (e: McpRemoteException) {
        ToolResult.error("MCP server error: ${e.message}")
    }
}
