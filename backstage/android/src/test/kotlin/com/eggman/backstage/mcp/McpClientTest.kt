package com.eggman.backstage.mcp

import com.eggman.backstage.data.RemoteMcpServer
import com.eggman.backstage.tools.Args
import com.eggman.backstage.tools.TestToolContext
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpClientTest {
    private lateinit var server: McpHttpServer
    private val tools = FakeTools()

    @Before fun start() {
        server = McpHttpServer(0, lan = false, token = { "tok" }, protocol = McpProtocol(tools, serverName = "phone"))
        server.start()
    }

    @After fun stop() = server.stop()

    private val url get() = "http://127.0.0.1:${server.boundPort}/mcp"

    @Test fun clientTalksToOurServer() = runBlocking {
        val c = McpClient(url, "tok")
        val init = c.initialize()
        assertEquals("phone", c.serverName)
        assertEquals(McpProtocol.SUPPORTED_VERSIONS.first(), init.getString("protocolVersion"))
        val list = c.listTools()
        assertEquals(listOf("execute_task", "get_device_status"), list.map { it.name })
        assertTrue(list[1].readOnly)
        val r = c.callTool("execute_task", JSONObject().put("task", "hello"))
        assertFalse(r.isError)
        assertEquals("done: hello", r.content.first().text)
        assertEquals("succeeded", r.structured!!.getString("status"))
    }

    @Test fun wrongTokenSurfacesAsToolError() = runBlocking {
        val r = McpClient(url, "nope").callTool("execute_task", JSONObject().put("task", "x"))
        assertTrue(r.isError) // our server answers a stale token with an isError result
        try {
            McpClient(url, null).listTools()
            throw AssertionError("expected failure")
        } catch (e: McpRemoteException) {
            assertEquals(401, e.code)
        }
    }

    @Test fun remoteToolsWrapAndCache() = runBlocking {
        var connects = 0
        val remote = RemoteMcpTools(clientFactory = { connects++; McpClient(it.url, it.token) })
        val servers = listOf(RemoteMcpServer("1", "My Phone", url, "tok"), RemoteMcpServer("2", "Off", url, "tok", enabled = false))
        val t = remote.tools(servers)
        assertEquals(listOf("mcp_my_phone_execute_task", "mcp_my_phone_get_device_status"), t.map { it.name })
        assertTrue(t[0].mutating)
        assertFalse(t[1].mutating)
        val res = t[0].run(TestToolContext(), Args(JSONObject().put("task", "x")))
        assertEquals("done: x", res.text)
        remote.tools(servers)
        assertEquals(1, connects) // cached
        assertTrue(remote.status["1"]!!.startsWith("connected"))
        val bad = RemoteMcpTools().tools(listOf(RemoteMcpServer("3", "Dead", "http://127.0.0.1:1/mcp")))
        assertTrue(bad.isEmpty())
    }

    /** per-tool switches of a remote server: off tools are not offered; new ones default on; settings persist. */
    @Test fun remoteToolSwitches() = runBlocking {
        val remote = RemoteMcpTools()
        val on = RemoteMcpServer("1", "My Phone", url, "tok")
        assertEquals(2, remote.tools(listOf(on)).size)
        val off = on.copy(disabledTools = setOf("execute_task"))
        assertEquals(listOf("mcp_my_phone_get_device_status"), remote.tools(listOf(off)).map { it.name })
        assertEquals(2, remote.allTools(off).size) // the Settings list still shows both
        assertTrue(remote.tools(listOf(off.copy(enabled = false))).isEmpty())

        val logic = com.eggman.backstage.ui.settings.ModuleSettingsLogic
        val first = logic.remoteToolRows(on, listOf("b", "a"))
        assertEquals(listOf("a", "b"), first.map { it.name })
        assertTrue(first.none { it.isNew }) // the first listing marks nothing new
        val seen = logic.acknowledgeRemoteTools(on, listOf("a", "b"))
        val later = logic.remoteToolRows(logic.setRemoteTool(seen, "a", false), listOf("a", "b", "c"))
        assertEquals(listOf(false, true, true), later.map { it.enabled }) // c appeared later: on by default
        assertEquals(listOf(false, false, true), later.map { it.isNew })
        assertEquals(setOf("a", "b", "c"), logic.setAllRemoteTools(seen, listOf("a", "b", "c"), false).disabledTools)
        assertTrue(logic.setAllRemoteTools(off, listOf("a"), true).disabledTools.isEmpty())

        val settings = com.eggman.backstage.data.Settings(com.eggman.backstage.data.MemoryStore())
        settings.remoteMcpServers = listOf(seen.copy(disabledTools = setOf("a")))
        val back = settings.remoteMcpServers.single()
        assertEquals(setOf("a"), back.disabledTools)
        assertEquals(setOf("a", "b"), back.knownTools)
        assertEquals("tok", back.token)
    }

    /** expired lists are served while refreshing; a failed server is skipped for a while instead of retried inline. */
    @Test fun staleWhileRefreshAndNegativeCache() = runBlocking {
        var now = 0L
        var connects = 0
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val remote = RemoteMcpTools(clientFactory = { connects++; McpClient(it.url, it.token) }, clock = { now }, ttlMs = 1_000, failTtlMs = 1_000, refreshScope = scope)
        val good = listOf(RemoteMcpServer("1", "My Phone", url, "tok"))
        assertEquals(2, remote.tools(good).size)
        now = 5_000 // expired: still served, refreshed in the background
        assertEquals(2, remote.tools(good).size)
        assertEquals(2, connects)

        var deadConnects = 0
        val dead = RemoteMcpTools(clientFactory = { deadConnects++; McpClient(it.url, it.token) }, clock = { now }, failTtlMs = 60_000,
            refreshScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().also { it.cancel() }))
        val servers = listOf(RemoteMcpServer("3", "Dead", "http://127.0.0.1:1/mcp"))
        assertTrue(dead.tools(servers).isEmpty())
        assertTrue(dead.tools(servers).isEmpty())
        assertEquals(1, deadConnects) // the second task did not wait for the dead server again
        assertTrue(dead.status["3"]!!.startsWith("failed"))
    }

    @Test fun sseResponsesAndSessionHeaders() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setHeader("Mcp-Session-Id", "s1")
            .setBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{\"name\":\"remote\"}}}\n\n"))
        mock.enqueue(MockResponse().setResponseCode(202))
        mock.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(": keepalive\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\n" +
                "data: \"result\":{\"tools\":[{\"name\":\"search\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n"))
        mock.start()
        try {
            val c = McpClient(mock.url("/mcp").toString(), "t")
            val tools = c.listTools()
            assertEquals("search", tools.single().name)
            val init = mock.takeRequest()
            assertEquals("Bearer t", init.getHeader("Authorization"))
            assertTrue(init.getHeader("Accept")!!.contains("text/event-stream"))
            val note = mock.takeRequest()
            assertEquals("s1", note.getHeader("Mcp-Session-Id"))
            assertEquals("notifications/initialized", JSONObject(note.body.readUtf8()).getString("method"))
            val list = mock.takeRequest()
            assertEquals("2025-06-18", list.getHeader("MCP-Protocol-Version"))
        } finally {
            mock.shutdown()
        }
        assertEquals(null, McpClient.parseSse("data: {\"id\":9,\"result\":{}}\n\n", 1))
    }
}
