package com.eggman.backstage.mcp

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket

class FakeTools : McpToolProvider {
    val calls = mutableListOf<Pair<String, JSONObject>>()
    override fun tools() = listOf(
        McpTool("execute_task", "run", JSONObject("""{"type":"object","properties":{"task":{"type":"string"}},"required":["task"]}""")),
        McpTool("get_device_status", "status", JSONObject("""{"type":"object"}"""), readOnly = true),
    )
    override suspend fun call(name: String, arguments: JSONObject): McpCallResult {
        calls += name to arguments
        if (name == "execute_task" && !arguments.has("task")) throw McpInvalidParams("task is required")
        if (name == "get_device_status") throw IllegalStateException("boom")
        return McpCallResult(listOf(McpContent("text", text = "done: ${arguments.optString("task")}"), McpContent("image", data = "AAA", mimeType = "image/jpeg")),
            structured = JSONObject().put("status", "succeeded"))
    }
}

class McpProtocolTest {
    private val tools = FakeTools()
    private val p = McpProtocol(tools)

    private fun req(body: String, headers: Map<String, String> = emptyMap()) =
        HttpRequest("POST", "/mcp", "HTTP/1.1", headers.mapKeys { it.key.lowercase() } + ("content-type" to "application/json"), body.toByteArray())

    private fun post(body: String, headers: Map<String, String> = emptyMap()) = runBlocking { p.handlePost(req(body, headers)) }

    private fun init(version: String = "2025-06-18"): String {
        val r = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        assertEquals(200, r.status)
        return r.headers.getValue(McpProtocol.SESSION_HEADER)
    }

    @Test fun initializeNegotiatesVersionAndIssuesSession() {
        val r = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""")
        val res = JSONObject(String(r.body)).getJSONObject("result")
        assertEquals("2025-03-26", res.getString("protocolVersion"))
        assertTrue(res.getJSONObject("capabilities").has("tools"))
        assertEquals("backstage", res.getJSONObject("serverInfo").getString("name"))
        assertEquals(32, r.headers.getValue(McpProtocol.SESSION_HEADER).length)
        val unknown = post("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")
        assertEquals(McpProtocol.SUPPORTED_VERSIONS.first(), JSONObject(String(unknown.body)).getJSONObject("result").getString("protocolVersion"))
    }

    @Test fun notificationsAreAcceptedWith202() {
        val sid = init()
        val r = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", mapOf(McpProtocol.SESSION_HEADER to sid))
        assertEquals(202, r.status)
        assertEquals(0, r.body.size)
        // client responses also get 202
        assertEquals(202, post("""{"jsonrpc":"2.0","id":5,"result":{}}""").status)
    }

    @Test fun sessionsAreValidated() {
        val sid = init()
        assertEquals(200, post("""{"jsonrpc":"2.0","id":3,"method":"ping"}""", mapOf(McpProtocol.SESSION_HEADER to sid)).status)
        assertEquals(sid, post("""{"jsonrpc":"2.0","id":3,"method":"ping"}""", mapOf(McpProtocol.SESSION_HEADER to sid)).headers[McpProtocol.SESSION_HEADER])
        val expired = post("""{"jsonrpc":"2.0","id":3,"method":"ping"}""", mapOf(McpProtocol.SESSION_HEADER to "nope"))
        assertEquals(404, expired.status)
        assertEquals(McpProtocol.SESSION_EXPIRED, JSONObject(String(expired.body)).getJSONObject("error").getInt("code"))
        assertEquals(3, JSONObject(String(expired.body)).getInt("id"))
        // missing session header is tolerated (stateless clients)
        assertEquals(200, post("""{"jsonrpc":"2.0","id":3,"method":"ping"}""").status)
        // DELETE ends it
        assertEquals(200, p.handleDelete(HttpRequest("DELETE", "/mcp", "HTTP/1.1", mapOf("mcp-session-id" to sid), ByteArray(0))).status)
        assertEquals(404, post("""{"jsonrpc":"2.0","id":3,"method":"ping"}""", mapOf(McpProtocol.SESSION_HEADER to sid)).status)
        assertEquals(400, post("""{"jsonrpc":"2.0","id":3,"method":"ping"}""", mapOf(McpProtocol.VERSION_HEADER to "2001-01-01")).status)
    }

    @Test fun toolsListAndCall() {
        val list = JSONObject(String(post("""{"jsonrpc":"2.0","id":"a","method":"tools/list"}""").body))
        assertEquals("a", list.get("id"))
        val t = list.getJSONObject("result").getJSONArray("tools")
        assertEquals("execute_task", t.getJSONObject(0).getString("name"))
        assertTrue(t.getJSONObject(0).has("inputSchema"))
        assertTrue(t.getJSONObject(1).getJSONObject("annotations").getBoolean("readOnlyHint"))

        val call = JSONObject(String(post("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"execute_task","arguments":{"task":"open maps"}}}""").body))
        val res = call.getJSONObject("result")
        assertFalse(res.getBoolean("isError"))
        assertEquals("done: open maps", res.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("image", res.getJSONArray("content").getJSONObject(1).getString("type"))
        assertEquals("image/jpeg", res.getJSONArray("content").getJSONObject(1).getString("mimeType"))
        assertEquals("succeeded", res.getJSONObject("structuredContent").getString("status"))
    }

    @Test fun errorsMapToJsonRpcCodes() {
        fun code(body: String) = JSONObject(String(post(body).body)).getJSONObject("error").getInt("code")
        assertEquals(-32601, code("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}"""))
        assertEquals(-32602, code("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nope"}}"""))
        assertEquals(-32602, code("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"execute_task","arguments":{}}}"""))
        assertEquals(-32602, code("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"execute_task","arguments":[1]}}"""))
        assertEquals(-32603, code("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_device_status"}}"""))
        assertEquals(-32600, code("""{"jsonrpc":"1.0","id":1,"method":"ping"}"""))
        val parse = post("{not json")
        assertEquals(400, parse.status)
        assertEquals(-32700, JSONObject(String(parse.body)).getJSONObject("error").getInt("code"))
        assertTrue(JSONObject(String(parse.body)).isNull("id"))
    }

    @Test fun batches() {
        val r = post("""[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/cancelled"},{"jsonrpc":"2.0","id":2,"method":"tools/list"}, 5]""")
        val arr = JSONArray(String(r.body))
        assertEquals(3, arr.length())
        assertEquals(1, arr.getJSONObject(0).getInt("id"))
        assertEquals(-32600, arr.getJSONObject(2).getJSONObject("error").getInt("code"))
        assertEquals(202, post("""[{"jsonrpc":"2.0","method":"notifications/initialized"}]""").status)
        assertEquals(400, post("[]").status)
    }
}

class HttpCoreTest {
    private fun read(raw: String, maxBody: Int = 1024): HttpRequest? = HttpReader(ByteArrayInputStream(raw.toByteArray()), maxBody = maxBody).read()

    @Test fun contentLengthAndHeaders() {
        val r = read("POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nX-A: 1\r\nX-A: 2\r\n\r\nhello")!!
        assertEquals("POST", r.method)
        assertEquals("hello", r.bodyText)
        assertEquals("1, 2", r.header("x-a"))
        assertTrue(r.keepAlive)
    }

    @Test fun chunkedBodyWithExtensionsAndTrailers() {
        val r = read("POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4;ext=1\r\nWiki\r\n5\r\npedia\r\n0\r\nX-T: y\r\n\r\n")!!
        assertEquals("Wikipedia", r.bodyText)
    }

    @Test fun limitsAndMalformedInput() {
        fun status(raw: String, maxBody: Int = 1024) = try { read(raw, maxBody); 0 } catch (e: HttpError) { e.status }
        assertEquals(413, status("POST / HTTP/1.1\r\nContent-Length: 5000\r\n\r\n"))
        assertEquals(413, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n800\r\n", maxBody = 100))
        // Int overflow (1 + 0x7fffffff) must still be rejected, not allocated.
        assertEquals(413, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n7fffffff\r\n"))
        assertEquals(400, status("GARBAGE\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort"))
        assertEquals(431, status("GET /" + "a".repeat(9000) + " HTTP/1.1\r\n\r\n"))
        assertEquals(501, status("POST / HTTP/1.1\r\nTransfer-Encoding: gzip\r\n\r\n"))
        assertNull(read(""))
    }

    @Test fun expectContinueAndHttp10() {
        val out = ByteArrayOutputStream()
        val r = HttpReader(ByteArrayInputStream("POST / HTTP/1.0\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\nok".toByteArray()), out).read()!!
        assertTrue(String(out.toByteArray()).startsWith("HTTP/1.1 100 Continue"))
        assertFalse(r.keepAlive)
    }

    @Test fun tokenAuth() {
        assertTrue(TokenAuth.check("Bearer abc123", "abc123"))
        assertTrue(TokenAuth.check("bearer   abc123 ", "abc123"))
        assertFalse(TokenAuth.check("Bearer abc124", "abc123"))
        assertFalse(TokenAuth.check("Basic abc123", "abc123"))
        assertFalse(TokenAuth.check(null, "abc123"))
        assertFalse(TokenAuth.check("Bearer ", ""))
    }
}

/** End-to-end over real sockets. */
class McpHttpServerTest {
    private lateinit var server: McpHttpServer
    private val tools = FakeTools()

    @Before fun start() {
        server = McpHttpServer(0, lan = false, token = { "secret" }, protocol = McpProtocol(tools))
        server.start()
    }

    @After fun stop() = server.stop()

    private class Resp(val status: Int, val headers: Map<String, String>, val body: String)

    private fun readResponse(input: InputStream): Resp {
        fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                sb.append(c.toChar())
            }
            return sb.toString().trimEnd('\r')
        }
        val status = line().split(' ')[1].toInt()
        val headers = HashMap<String, String>()
        while (true) {
            val h = line()
            if (h.isEmpty()) break
            headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim()
        }
        val len = headers["content-length"]!!.toInt()
        val body = ByteArray(len)
        var off = 0
        while (off < len) off += input.read(body, off, len - off)
        return Resp(status, headers, String(body))
    }

    private fun request(socket: Socket, method: String, body: String = "", headers: String = "Authorization: Bearer secret\r\n", path: String = "/mcp"): Resp {
        val bytes = body.toByteArray()
        socket.getOutputStream().write(("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
            "Accept: application/json, text/event-stream\r\n${headers}Content-Length: ${bytes.size}\r\n\r\n").toByteArray() + bytes)
        socket.getOutputStream().flush()
        return readResponse(socket.getInputStream())
    }

    private fun socket() = Socket("127.0.0.1", server.boundPort).apply { soTimeout = 5_000 }

    @Test fun authIsRequired() {
        socket().use { s ->
            val r = request(s, "POST", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", headers = "")
            assertEquals(401, r.status)
            assertNotNull(r.headers["www-authenticate"])
        }
        socket().use { s ->
            // a stale token gets a visible isError result (HTTP 200) echoing the request id
            val r = request(s, "POST", """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"execute_task"}}""", headers = "Authorization: Bearer wrong\r\n")
            assertEquals(200, r.status)
            val res = JSONObject(r.body)
            assertEquals(7, res.getInt("id"))
            assertTrue(res.getJSONObject("result").getBoolean("isError"))
            assertTrue(res.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text").contains("no longer matches"))
        }
        socket().use { s -> assertEquals(401, request(s, "POST", "{}", headers = "Authorization: Basic abc\r\n").status) }
        socket().use { s -> assertEquals(204, request(s, "OPTIONS", headers = "").status) }
    }

    @Test fun keepAliveSessionFlow() {
        socket().use { s ->
            val init = request(s, "POST", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
            assertEquals(200, init.status)
            assertEquals("application/json", init.headers["content-type"])
            val sid = init.headers["mcp-session-id"]!!
            val note = request(s, "POST", """{"jsonrpc":"2.0","method":"notifications/initialized"}""", "Authorization: Bearer secret\r\nMcp-Session-Id: $sid\r\n")
            assertEquals(202, note.status)
            val call = request(s, "POST", """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"execute_task","arguments":{"task":"hi"}}}""",
                "Authorization: Bearer secret\r\nMcp-Session-Id: $sid\r\n")
            assertEquals(200, call.status)
            assertTrue(call.body.contains("done: hi"))
            assertEquals("keep-alive", call.headers["connection"])
        }
        assertEquals("execute_task", tools.calls.single().first)
    }

    @Test fun chunkedRequestOverSocket() {
        socket().use { s ->
            val body = """{"jsonrpc":"2.0","id":9,"method":"ping"}"""
            val half = body.length / 2
            val chunked = "%x\r\n%s\r\n%x\r\n%s\r\n0\r\n\r\n".format(half, body.substring(0, half), body.length - half, body.substring(half))
            s.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer secret\r\nContent-Type: application/json\r\n" +
                "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n$chunked").toByteArray())
            val r = readResponse(s.getInputStream())
            assertEquals(200, r.status)
            assertEquals(9, JSONObject(r.body).getInt("id"))
            assertEquals("close", r.headers["connection"])
            assertEquals(-1, s.getInputStream().read()) // server closed
        }
    }

    @Test fun routingAndOrigin() {
        socket().use { s -> assertEquals(405, request(s, "GET").status) }
        socket().use { s -> assertEquals(404, request(s, "POST", "{}", path = "/other").status) }
        socket().use { s ->
            assertEquals(403, request(s, "POST", """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
                "Authorization: Bearer secret\r\nOrigin: https://evil.example\r\n").status)
        }
        socket().use { s ->
            assertEquals(200, request(s, "POST", """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
                "Authorization: Bearer secret\r\nOrigin: http://localhost:3000\r\n").status)
        }
    }

    @Test fun malformedRequestGets400AndClose() {
        socket().use { s ->
            s.getOutputStream().write("NONSENSE\r\n\r\n".toByteArray())
            val r = readResponse(s.getInputStream())
            assertEquals(400, r.status)
        }
    }

    private fun sendHead(s: Socket, head: String) {
        s.getOutputStream().write(head.toByteArray())
        s.getOutputStream().flush()
    }

    @Test fun unauthenticatedBodyIsNeverRead() {
        // The body is announced but never sent: answering at all proves the server did not wait for it.
        socket().use { s ->
            sendHead(s, "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 3000000\r\n\r\n")
            val r = readResponse(s.getInputStream())
            assertEquals(401, r.status)
            assertEquals("close", r.headers["connection"])
        }
        socket().use { s ->
            sendHead(s, "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n7fffffff\r\n")
            assertEquals(401, readResponse(s.getInputStream()).status)
        }
        // A wrong token may only send a small body (enough for the JSON-RPC id).
        socket().use { s ->
            sendHead(s, "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer wrong\r\nContent-Length: ${McpHttpServer.UNAUTHENTICATED_BODY + 1}\r\n\r\n")
            assertEquals(413, readResponse(s.getInputStream()).status)
        }
    }

    @Test fun hostileChunkSizeNeitherStopsServerNorEscapes() {
        val uncaught = ArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(uncaught) { uncaught += e } }
        try {
            socket().use { s ->
                sendHead(s, "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer secret\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n7fffffff\r\n")
                assertEquals(413, readResponse(s.getInputStream()).status)
            }
            // the accept loop is still alive
            socket().use { s -> assertEquals(200, request(s, "POST", """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status) }
            server.stop()
            Thread.sleep(200)
            assertTrue(uncaught.toString(), uncaught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun rebindingHostIsRefused() {
        socket().use { s ->
            sendHead(s, "POST /mcp HTTP/1.1\r\nHost: rebind.attacker.example:8485\r\nAuthorization: Bearer secret\r\nContent-Length: 2\r\n\r\n{}")
            assertEquals(403, readResponse(s.getInputStream()).status)
        }
        assertTrue(McpHttpServer.hostAllowed("127.0.0.1:8485"))
        assertTrue(McpHttpServer.hostAllowed("192.168.1.20"))
        assertTrue(McpHttpServer.hostAllowed("[::1]:8485"))
        assertTrue(McpHttpServer.hostAllowed("localhost:8485"))
        assertTrue(McpHttpServer.hostAllowed("pixel.local"))
        assertTrue(McpHttpServer.hostAllowed(null))
        assertFalse(McpHttpServer.hostAllowed("evil.example"))
        assertFalse(McpHttpServer.hostAllowed("127.0.0.1.nip.io:8485"))
        assertFalse(McpHttpServer.hostAllowed(""))
    }
}
