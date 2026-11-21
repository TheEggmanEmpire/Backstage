package com.eggman.backstage.robolectric

import android.app.Application
import android.content.Intent
import com.eggman.backstage.a11y.AgentAccessibility
import com.eggman.backstage.agent.AgentService
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.init.Backstage
import com.eggman.backstage.mcp.BackstageMcpTools
import com.eggman.backstage.mcp.McpProtocol
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
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The MCP server as the app runs it: started by [AgentService] from the settings, wired to the real controller,
 * environment and accessibility service, and spoken to over a real loopback socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi")
class McpEndToEndRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var service: ServiceController<AgentService>
    private lateinit var a11y: ServiceController<HostAccessibilityService>
    private var llm: LlmBlackHole? = null
    private var port = 0
    private lateinit var token: String
    private val pool = Executors.newCachedThreadPool()

    @Before fun setUp() {
        com.eggman.backstage.agent.AgentService.laneOverride = java.util.concurrent.Executor { it.run() }
        RoboSupport.resetProcess()
        Backstage.bridgeClassName = "moe.n4tsu.dextop.NotInThisBuild"
        Backstage.init(app)
        port = RoboSupport.freePort()
        Backstage.environment.settings.apply {
            mcpPort = port
            mcpEnabled = true
            autoEnableAccessibility = false
            // The tests drive the phone screen from an MCP client: the user's opt-in for remote phone control.
            externalPhoneControl = true
        }
        token = Backstage.environment.settings.mcpToken
        a11y = A11yTrees.connectService()
        shadowOf(a11y.get()).setWindows(listOf(A11yTrees.MailScreen().window))
        service = Robolectric.buildService(AgentService::class.java, Intent(app, AgentService::class.java)).create().startCommand(0, 1)
    }

    @After fun tearDown() {
        com.eggman.backstage.agent.AgentService.laneOverride = null
        runCatching { Backstage.controller.cancelAll() }
        runCatching { service.destroy() }
        runCatching { a11y.destroy() }
        llm?.close()
        pool.shutdownNow()
        RoboSupport.resetProcess()
    }

    // ---- a minimal HTTP/1.1 client over a raw socket (exact control over headers and body size) ----------------

    class Resp(val status: Int, val headers: Map<String, String>, val body: String) {
        fun json() = JSONObject(body)
        fun header(name: String) = headers[name.lowercase()]
    }

    private fun readResponse(input: InputStream): Resp {
        fun line(): String {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) b.write(c)
            }
            return b.toString(Charsets.UTF_8)
        }
        val status = line().split(' ')[1].toInt()
        val headers = HashMap<String, String>()
        while (true) {
            val l = line()
            if (l.isEmpty()) break
            headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
        }
        val len = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = input.read(body, off, len - off)
            if (r < 0) break
            off += r
        }
        return Resp(status, headers, String(body, 0, off, Charsets.UTF_8))
    }

    private fun send(
        body: String?,
        method: String = "POST",
        auth: String? = "Bearer $token",
        session: String? = null,
        extra: Map<String, String> = emptyMap(),
        timeoutMs: Int = 10_000,
        contentType: String = "application/json",
    ): Resp = Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        s.soTimeout = timeoutMs
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val head = buildString {
            append("$method /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
            if (body != null) append("Content-Type: $contentType\r\nAccept: application/json, text/event-stream\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            auth?.let { append("Authorization: $it\r\n") }
            session?.let { append("${McpProtocol.SESSION_HEADER}: $it\r\n") }
            extra.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
        readResponse(s.getInputStream())
    }

    private fun rpc(id: Any?, method: String, params: JSONObject = JSONObject()) =
        JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params).also { if (id != null) it.put("id", id) }

    private fun initialize(): String {
        val r = send(rpc(1, "initialize", JSONObject().put("protocolVersion", "2025-06-18")
            .put("clientInfo", JSONObject().put("name", "robolectric").put("version", "1"))).toString())
        assertEquals(200, r.status)
        assertEquals("2025-06-18", r.json().getJSONObject("result").getString("protocolVersion"))
        return r.header(McpProtocol.SESSION_HEADER) ?: throw AssertionError("no session id")
    }

    private fun call(session: String, id: Int, tool: String, args: JSONObject = JSONObject(), timeoutMs: Int = 10_000): JSONObject {
        val r = send(rpc(id, "tools/call", JSONObject().put("name", tool).put("arguments", args)).toString(), session = session, timeoutMs = timeoutMs)
        assertEquals(r.body, 200, r.status)
        return r.json().getJSONObject("result")
    }

    private fun JSONObject.text() = getJSONArray("content").getJSONObject(0).getString("text")

    // ---- tests --------------------------------------------------------------------------------------------------

    @Test fun authIsEnforcedOnEveryRequest() {
        val missing = send(rpc(1, "tools/list").toString(), auth = null)
        assertEquals(401, missing.status)
        assertTrue(missing.header("www-authenticate")!!.startsWith("Bearer"))
        assertEquals(McpProtocol.UNAUTHORIZED, missing.json().getJSONObject("error").getInt("code"))

        assertEquals(401, send(rpc(1, "tools/list").toString(), auth = "Basic dXNlcjpwYXNz").status)

        // A stale token gets a visible tool-style error carrying the request id, so chat clients show what to fix.
        val wrong = send(rpc(7, "tools/list").toString(), auth = "Bearer not-the-token")
        assertEquals(200, wrong.status)
        assertEquals(7, wrong.json().getInt("id"))
        assertTrue(wrong.json().getJSONObject("result").getBoolean("isError"))
        assertTrue(wrong.json().getJSONObject("result").text().startsWith("Authorization rejected"))
        // Neither reached the agent.
        assertNull(Backstage.controller.state.value.current)

        // Rotating the token in Settings takes effect immediately, without a restart.
        val old = token
        token = Backstage.environment.settings.rotateMcpToken()
        assertTrue(send(rpc(8, "ping").toString(), auth = "Bearer $old").json().getJSONObject("result").optBoolean("isError"))
        assertEquals(200, send(rpc(9, "ping").toString()).status)
    }

    @Test fun sessionNotificationsBatchesAndTermination() {
        val session = initialize()
        val init = send(rpc(1, "initialize").toString()).json().getJSONObject("result")
        assertTrue(init.getJSONObject("serverInfo").getString("name").isNotBlank())
        assertTrue(init.getJSONObject("serverInfo").has("version"))
        assertTrue(init.getJSONObject("capabilities").has("tools"))

        // A notification has no id: accepted with 202 and an empty body.
        val note = send(rpc(null, "notifications/initialized").toString(), session = session)
        assertEquals(202, note.status)
        assertEquals("", note.body)

        val list = send(rpc(2, "tools/list").toString(), session = session)
        assertEquals(session, list.header(McpProtocol.SESSION_HEADER))
        val names = list.json().getJSONObject("result").getJSONArray("tools").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
        assertEquals(BackstageMcpTools.DEFAULT_ENABLED, names.toSet())

        // Batch: answers for the requests only, in order; the notification inside gets none.
        val batch = JSONArray().put(rpc(10, "ping")).put(rpc(null, "notifications/progress")).put(rpc(11, "tools/list")).put(rpc(12, "no/such"))
        val br = send(batch.toString(), session = session)
        assertEquals(200, br.status)
        val answers = JSONArray(br.body)
        assertEquals(listOf(10, 11, 12), (0 until answers.length()).map { answers.getJSONObject(it).getInt("id") })
        assertEquals(-32601, answers.getJSONObject(2).getJSONObject("error").getInt("code"))

        // Malformed JSON and wrong content type.
        assertEquals(400, send("{not json", session = session).status)
        assertEquals(415, send(rpc(3, "ping").toString(), session = session, contentType = "text/plain").status)
        // GET has no SSE stream.
        assertEquals(405, send(null, method = "GET").status)

        // DELETE ends the session; using it afterwards asks the client to re-initialize.
        assertEquals(200, send(null, method = "DELETE", session = session).status)
        val expired = send(rpc(4, "tools/list").toString(), session = session)
        assertEquals(404, expired.status)
        assertEquals(McpProtocol.SESSION_EXPIRED, expired.json().getJSONObject("error").getInt("code"))
    }

    @Test fun oversizedBodiesAreRefusedAndTheServerKeepsServing() {
        // Authenticated: over the 4 MiB cap, refused from the Content-Length alone.
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            s.soTimeout = 10_000
            s.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${5 * 1024 * 1024}\r\n\r\n{\"jsonrpc\":").toByteArray())
            assertEquals(413, readResponse(s.getInputStream()).status)
        }
        // Wrong token: only a small body is allowed at all.
        val big = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"pad\":\"" + "x".repeat(100 * 1024) + "\"}"
        assertEquals(413, send(big, auth = "Bearer wrong").status)
        // Within limits with the right token: fine, and the server is still up for the next client.
        assertEquals(200, send(big).status)
        assertEquals(200, send(rpc(2, "ping").toString()).status)
    }

    @Test fun browserOriginsAndRebindingHostsAreRefused() {
        assertEquals(403, send(rpc(1, "ping").toString(), extra = mapOf("Origin" to "https://evil.example")).status)
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            s.soTimeout = 5_000
            s.getOutputStream().write("POST /mcp HTTP/1.1\r\nHost: attacker.example:$port\r\nAuthorization: Bearer $token\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
            assertEquals(403, readResponse(s.getInputStream()).status)
        }
        assertEquals(404, Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            s.soTimeout = 5_000
            s.getOutputStream().write("POST /other HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
            readResponse(s.getInputStream()).status
        })
    }

    @Test fun deviceStatusAndScreenStateComeFromTheRealDevice() {
        val session = initialize()
        val status = call(session, 2, "get_device_status").getJSONObject("structuredContent")
        assertTrue(status.getBoolean("accessibility_enabled"))
        assertFalse(status.getBoolean("provider_configured"))
        assertFalse(status.getBoolean("ready"))
        assertTrue(status.getString("reason"), status.getString("reason").startsWith("AI provider"))

        val screen = call(session, 3, "get_screen_state", JSONObject().put("target", "phone"))
        assertFalse(screen.getBoolean("isError"))
        val markup = screen.text()
        assertTrue(markup, markup.contains("<app package=\"com.example.mail\">"))
        assertTrue(markup, markup.contains("Compose"))
        val elements = screen.getJSONObject("structuredContent").getJSONArray("elements")
        assertTrue((0 until elements.length()).any { elements.getJSONObject(it).toString().contains("Anna: Lunch tomorrow?") })
        // The list format numbers the elements.
        assertTrue(call(session, 4, "get_screen_state", JSONObject().put("format", "list")).text().contains("[0]"))
        // Invalid arguments are protocol errors, not crashes.
        val bad = send(rpc(5, "tools/call", JSONObject().put("name", "get_screen_state").put("arguments", JSONObject().put("target", "moon"))).toString(), session = session)
        assertEquals(-32602, bad.json().getJSONObject("error").getInt("code"))
        // Low-level tools stay hidden unless the user enabled them.
        assertEquals(-32602, send(rpc(6, "tools/call", JSONObject().put("name", "tap").put("arguments", JSONObject())).toString(), session = session)
            .json().getJSONObject("error").getInt("code"))
    }

    @Test fun executeTaskRefusesWithoutAProviderAndStartsNothing() {
        val session = initialize()
        val r = call(session, 2, "execute_task", JSONObject().put("task", "Delete all my email"))
        assertTrue(r.getBoolean("isError"))
        assertTrue(r.text(), r.text().startsWith("AI provider not configured"))
        assertNull(Backstage.controller.state.value.current)
        // A missing task is an invalid-params error.
        val missing = send(rpc(3, "tools/call", JSONObject().put("name", "execute_task").put("arguments", JSONObject())).toString(), session = session)
        assertEquals(-32602, missing.json().getJSONObject("error").getInt("code"))
    }

    @Test fun runningTaskIsExclusiveAndCancellableByNotification() {
        llm = LlmBlackHole().also { it.install(Backstage.environment.settings) }
        val session = initialize()
        val first = pool.submit(Callable {
            call(session, 42, "execute_task", JSONObject().put("task", "Summarise my unread mail").put("target", "phone"), timeoutMs = 30_000)
        })
        assertTrue("task reached the model", RoboSupport.waitFor(10_000) { llm!!.requests > 0 })
        val running = Backstage.controller.state.value.current!!
        assertEquals(TaskStatus.RUNNING, running.status)
        assertEquals(com.eggman.backstage.agent.api.Source.MCP, running.request.source)
        assertFalse(running.request.interactive)

        // A second client is told the agent is busy instead of queueing behind it.
        val busy = call(session, 43, "execute_task", JSONObject().put("task", "Something else"))
        assertTrue(busy.getBoolean("isError"))
        assertTrue(busy.text(), busy.text().startsWith("Busy"))

        // The client gives up: notifications/cancelled for request 42 stops the task.
        val note = send(rpc(null, "notifications/cancelled", JSONObject().put("requestId", 42).put("reason", "user aborted")).toString(), session = session)
        assertEquals(202, note.status)
        val result = first.get(20, TimeUnit.SECONDS)
        assertTrue(result.getBoolean("isError"))
        assertEquals("The task was stopped before it finished.", result.text())
        assertEquals("cancelled", result.getJSONObject("structuredContent").getString("status"))
        assertEquals(running.id, result.getJSONObject("structuredContent").getString("taskId"))
        assertNotNull(Backstage.controller.state.value.current?.finishedAtMs)
    }

    @Test fun stoppingTheServiceClosesThePort() {
        assertEquals(200, send(rpc(1, "ping").toString()).status)
        service.destroy()
        assertTrue(RoboSupport.portReleased(port))
        service = Robolectric.buildService(AgentService::class.java, Intent(app, AgentService::class.java)).create() // for tearDown
    }
}
