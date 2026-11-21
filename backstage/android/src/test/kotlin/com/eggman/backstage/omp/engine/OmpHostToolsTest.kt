package com.eggman.backstage.omp.engine

import com.eggman.backstage.agent.*
import com.eggman.backstage.exec.*
import com.eggman.backstage.llm.RetryPolicy
import com.eggman.backstage.testutil.*
import com.eggman.backstage.tools.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class OmpHostToolsTest {
    private fun config(max: Int = 2) = LoopConfig("SYS", max, false, 1000, RetryPolicy(), SensitiveActionGuard(true, false), ToolRegistry.Availability(desktop = false))
    private fun context(hooks: AgentHooks) = AgentLoopTest.Ctx(hooks, FakeExecutor().apply { capture = Screens.buttons("Send") })
    @Test fun socketPeerGetsTextImageErrorsAndCancellation() = runBlocking {
        val listener = ServerSocket(0)
        val peer = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 4000 }
        val client = OmpRpcClient(listener.accept()); listener.close()
        val began = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val fake = object : Tool {
            override val name = "echo"; override val description = "Echo"
            override val schema = Schema.obj(); override val targets = emptySet<TargetKind>()
            override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
                assertNotNull(ctx.hooks)
                if (args.optString("mode") == "wait") {
                    began.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                return if (args.optString("mode") == "error") ToolResult.error("failed")
                else ToolResult("done", ScreenImage("SU1H", "image/jpeg", 1, 1, 1, 1))
            }
        }
        val registry = ToolRegistry(listOf(fake))
        val policy = OmpToolPolicy(registry, config(), AgentLoopTest.User(), ::context, object : LoopListener {})
        val adapter = OmpHostTools(registry, config(), policy)
        val input = OmpJsonl(peer.getInputStream())
        fun call(id: String, mode: String) = JSONObject().put("type", "host_tool_call").put("id", id).put("toolCallId", "tc_$id")
            .put("toolName", "backstage_echo").put("arguments", JSONObject().put("mode", mode))
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for (mode in listOf("text", "error")) {
                adapter.handle(call(mode, mode), jobs, client::send)
                val result = withContext(Dispatchers.IO) { input.read()!! }
                assertEquals("host_tool_result", result.getString("type"))
                assertEquals(mode, result.getString("id"))
                assertEquals(mode == "error", result.optBoolean("isError"))
                val content = result.getJSONObject("result").getJSONArray("content")
                if (mode == "text") assertEquals("image/jpeg", content.getJSONObject(1).getString("mimeType"))
            }
            adapter.handle(call("slow", "wait"), jobs, client::send)
            withTimeout(4000) { began.await() }
            adapter.handle(JSONObject().put("type", "host_tool_cancel").put("id", "cancel1").put("targetId", "slow"), jobs, client::send)
            withTimeout(4000) { cancelled.await() }
            assertTrue(withContext(Dispatchers.IO) { input.read()!! }.getBoolean("isError"))
        } finally { jobs.cancel(); client.close(); peer.close() }
    }
    @Test fun sensitiveApprovalAndActionLimitStillApply() = runBlocking {
        val user = AgentLoopTest.User(false)
        val registry = ToolRegistry.default()
        val policy = OmpToolPolicy(registry, config(), user, ::context, object : LoopListener {})
        policy.execute(com.eggman.backstage.llm.Part.ToolCall("1", "get_screen", JSONObject()))
        val r = policy.execute(com.eggman.backstage.llm.Part.ToolCall("2", "tap", JSONObject().put("index", 0)))
        assertTrue(r.isError); assertEquals(1, user.confirms.size)
        assertEquals(EndReason.DECLINED, policy.outcome!!.reason)
        val limited = OmpToolPolicy(registry, config(0), AgentLoopTest.User(), ::context, object : LoopListener {})
        assertTrue(limited.execute(com.eggman.backstage.llm.Part.ToolCall("3", "tap", JSONObject().put("index", 0))).isError)
        assertEquals(EndReason.STEP_LIMIT, limited.outcome!!.reason)
    }
    @Test fun definitionsUseNativeGatingAndKeepAgentOnlyControls() {
        val registry = ToolRegistry.default()
        val cfg = config()
        val policy = OmpToolPolicy(registry, cfg, AgentLoopTest.User(), ::context, object : LoopListener {})
        val defs = OmpHostTools(registry, cfg, policy).definitions()
        val names = (0 until defs.length()).map { defs.getJSONObject(it).getString("name") }.toSet()
        assertEquals(registry.forAgent(cfg.availability).map { "backstage_${it.name}" }.toSet(), names)
        assertTrue("backstage_finish" in names)
        assertFalse("backstage_desktop_start" in names)
    }
    @Test fun aSecondDeviceCallInTheSameTurnIsWithheld() = runBlocking {
        val registry = ToolRegistry.default()
        val policy = OmpToolPolicy(registry, config(), AgentLoopTest.User(), ::context, object : LoopListener {})
        policy.beginTurn()
        policy.execute(com.eggman.backstage.llm.Part.ToolCall("one", "get_screen", JSONObject()))
        val second = policy.execute(com.eggman.backstage.llm.Part.ToolCall("two", "tap", JSONObject().put("index", 0)))
        assertTrue(second.isError)
        assertEquals(AgentLoop.ONE_PER_TURN, second.text)
    }

}
