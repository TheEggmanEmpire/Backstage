package com.eggman.backstage.omp.engine

import com.eggman.backstage.agent.*
import com.eggman.backstage.agent.api.*
import com.eggman.backstage.data.AgentEngine
import com.eggman.backstage.llm.Message
import com.eggman.backstage.llm.RetryPolicy
import com.eggman.backstage.tools.*
import com.eggman.backstage.testutil.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class OmpLifecycleRegressionTest {
    private fun send(socket: Socket, frame: JSONObject) {
        synchronized(socket) { socket.getOutputStream().apply { write((frame.toString() + "\n").toByteArray()); flush() } }
    }
    private fun hostCall(id: String) = JSONObject().put("type", "host_tool_call").put("id", id)
        .put("toolCallId", id).put("toolName", "backstage_probe").put("arguments", JSONObject())
    private fun context(hooks: AgentHooks) = AgentLoopTest.Ctx(hooks, FakeExecutor())
    private suspend fun task(engine: OmpEngine, key: Any, registry: ToolRegistry = ToolRegistry.default()) = engine.run(
        key, "provider/model", registry,
        LoopConfig("system", 10, false, 1000, RetryPolicy(), SensitiveActionGuard(true, false), ToolRegistry.Availability(false)),
        AgentLoopTest.User(), ::context, object : LoopListener {}, listOf(Message.user("hello")),
    )

    @Test fun blankLineDribblerCannotHoldTheClaimAndLegitimatePeerStillConnects() = runBlocking {
        OmpRpcBridgeServer().use { server ->
            val accepted = async(Dispatchers.IO) { server.awaitConnection() }
            Socket("127.0.0.1", server.port).use { bad ->
                val dribble = launch(Dispatchers.IO) {
                    try { while (isActive) { bad.getOutputStream().write(10); bad.getOutputStream().flush(); delay(100) } }
                    catch (_: java.io.IOException) { }
                }
                try {
                    val closed = withContext(Dispatchers.IO) {
                        bad.soTimeout = 100
                        val deadline = System.nanoTime() + 2_800_000_000L
                        var dropped = false
                        while (!dropped && System.nanoTime() < deadline) {
                            try { dropped = bad.getInputStream().read() == -1 } catch (_: SocketTimeoutException) { }
                        }
                        dropped
                    }
                    assertTrue("blank-line dribbler exceeded the absolute handshake deadline", closed)
                    Socket("127.0.0.1", server.port).use { good ->
                        send(good, JSONObject().put("type", "claim").put("code", server.claim.code))
                        good.soTimeout = 2000
                        assertEquals("claimed", OmpJsonl(good.getInputStream()).read()!!.getString("type"))
                        withTimeout(2000) { accepted.await() }.close()
                    }
                } finally { bad.close(); dribble.cancelAndJoin(); accepted.cancelAndJoin() }
            }
        }
    }

    @Test fun cancellingAClaimClosesTheDribblingSocketPromptly() = runBlocking {
        OmpRpcBridgeServer().use { server ->
            val accepted = async(Dispatchers.IO) { server.awaitConnection() }
            Socket("127.0.0.1", server.port).use { bad ->
                val dribble = launch(Dispatchers.IO) {
                    try { while (isActive) { bad.getOutputStream().write(10); bad.getOutputStream().flush(); delay(100) } }
                    catch (_: java.io.IOException) { }
                }
                try {
                    delay(200)
                    val start = System.nanoTime()
                    accepted.cancel()
                    // Closing the client after this bound also prevents a broken implementation hanging the test.
                    val joined = withTimeoutOrNull(900) { accepted.join(); true } == true
                    assertTrue("claim cancellation did not release the blocked reader", joined)
                    assertTrue((System.nanoTime() - start) / 1_000_000 < 900)
                    bad.soTimeout = 500
                    assertEquals(-1, withContext(Dispatchers.IO) { bad.getInputStream().read() })
                } finally { bad.close(); dribble.cancelAndJoin(); accepted.cancelAndJoin() }
            }
        }
    }

    @Test fun overallClaimExpiryAlsoBoundsAnInProgressHandshake() = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong()
        OmpRpcBridgeServer(OmpBridgeClaim { clock.get() }).use { server ->
            val accepted = async(Dispatchers.IO) { runCatching { server.awaitConnection() } }
            Socket("127.0.0.1", server.port).use { bad ->
                val dribble = launch(Dispatchers.IO) {
                    try { while (isActive) { bad.getOutputStream().write(10); bad.getOutputStream().flush(); delay(100) } }
                    catch (_: java.io.IOException) { }
                }
                try {
                    delay(200); clock.set(OmpBridgeClaim.TTL_MS)
                    val result = withTimeoutOrNull(900) { accepted.await() }
                    assertNotNull("overall claim expiry was not checked during the handshake", result)
                    assertTrue(result!!.exceptionOrNull() is OmpProtocolException)
                } finally { bad.close(); dribble.cancelAndJoin(); accepted.cancelAndJoin() }
            }
        }
    }

    @Test fun commandResponsesFlowPastMoreThan64UndrainedEvents() = runBlocking {
        ServerSocket(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { peer ->
                OmpRpcClient(listener.accept()).use { client ->
                    val command = async { client.command("steer") }
                    withContext(Dispatchers.IO) {
                        peer.soTimeout = 2000
                        val request = OmpJsonl(peer.getInputStream()).read()!!
                        repeat(100) { send(peer, JSONObject().put("type", "turn_start")) }
                        send(peer, JSONObject().put("type", "response").put("id", request.getString("id")).put("success", true))
                    }
                    try { assertTrue(withTimeout(2000) { command.await() }.getBoolean("success")) }
                    finally { client.close(); command.cancelAndJoin() }
                }
            }
        }
    }

    @Test fun eventOverflowFailsPendingCommandsPromptly() = runBlocking {
        ServerSocket(0).use { listener -> Socket("127.0.0.1", listener.localPort).use { peer ->
            OmpRpcClient(listener.accept()).use { client ->
                val command = async { runCatching { client.command("steer") } }
                withContext(Dispatchers.IO) {
                    peer.soTimeout = 2000
                    OmpJsonl(peer.getInputStream()).read()!!
                    runCatching { repeat(300) { send(peer, JSONObject().put("type", "turn_start")) } }
                }
                try {
                    val failure = withTimeout(2000) { command.await() }.exceptionOrNull()
                    assertTrue(failure is OmpProtocolException)
                    assertTrue(failure!!.message!!.contains("buffer"))
                    assertFalse(client.alive)
                } finally { client.close(); command.cancelAndJoin() }
            }
        } }
    }

    private inner class EnginePeer(scope: CoroutineScope, val veto: Boolean = false, val afterCompletionCall: Boolean = false) : AutoCloseable {
        val listener = ServerSocket(0)
        val peer = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 5000 }
        val client = OmpRpcClient(listener.accept())
        val commands = Collections.synchronizedList(mutableListOf<JSONObject>())
        val rejected = CompletableDeferred<JSONObject>()
        private val job = scope.launch(Dispatchers.IO) {
            listener.close()
            send(peer, JSONObject().put("type", "ready").put("supportedProtocolVersions", org.json.JSONArray("[1,2]")))
            try {
                val lines = OmpJsonl(peer.getInputStream())
                while (isActive) {
                    val frame = lines.read() ?: break
                    commands += frame
                    if (frame.optString("type") == "host_tool_result") { rejected.complete(frame); continue }
                    val response = JSONObject().put("type", "response").put("id", frame.optString("id")).put("success", true)
                    if (veto && frame.optString("type") == "new_session") response.put("data", JSONObject().put("cancelled", true))
                    send(peer, response)
                    if (frame.optString("type") == "prompt") {
                        send(peer, JSONObject("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"answer"}}"""))
                        send(peer, JSONObject().put("type", "prompt_result").put("id", frame.getString("id")).put("status", "completed").put("sessionSettled", true))
                        if (afterCompletionCall) send(peer, hostCall("finished"))
                    }
                }
            } catch (_: java.io.IOException) { }
        }
        override fun close() { client.close(); peer.close(); job.cancel(); listener.close() }
    }

    @Test fun idleHostCallIsRejectedAndCannotExecuteUnderTheNextTask() = runBlocking {
        val executions = AtomicInteger()
        val registry = ToolRegistry(listOf(object : Tool {
            override val name = "probe"; override val description = "probe"
            override val schema = Schema.obj(); override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
            override suspend fun run(ctx: ToolContext, args: Args): ToolResult { executions.incrementAndGet(); return ToolResult("executed") }
        }))
        EnginePeer(this).use { first -> EnginePeer(this).use { second ->
            var connections = 0
            val engine = OmpEngine({ null }, { if (++connections == 1) first.client else second.client })
            try {
                val key = Any()
                assertEquals("answer", task(engine, key, registry)!!.summary)
                send(first.peer, hostCall("between"))
                val rejected = withTimeout(1500) { first.rejected.await() }
                assertEquals("between", rejected.getString("id"))
                assertTrue(rejected.getBoolean("isError"))
                withTimeout(1500) { while (first.client.alive) delay(10) }
                assertEquals("answer", task(engine, key, registry)!!.summary)
                assertEquals(2, connections)
                assertEquals(0, executions.get())
            } finally { engine.close() }
        } }
    }

    @Test fun hostCallImmediatelyAfterPromptResultIsRejectedBeforeConsumerFinishes() = runBlocking {
        EnginePeer(this, afterCompletionCall = true).use { peer ->
            val engine = OmpEngine({ null }, { peer.client })
            try {
                assertEquals("answer", task(engine, Any())!!.summary)
                assertTrue(withTimeout(1500) { peer.rejected.await() }.getBoolean("isError"))
                withTimeout(1500) { while (peer.client.alive) delay(10) }
            } finally { engine.close() }
        }
    }

    @Test fun newSessionVetoFallsBackBeforeAnyPromptIsSubmitted() = runBlocking {
        EnginePeer(this, veto = true).use { peer ->
            val engine = OmpEngine({ null }, { peer.client })
            try {
                assertNull(task(engine, Any()))
                assertTrue(engine.unavailableReason!!.contains("cancel"))
                assertFalse(peer.client.alive)
                assertFalse(peer.commands.any { it.optString("type") == "prompt" })
            } finally { engine.close() }
        }
    }

    @Test fun sessionVetoUsesNativeControllerFallbackAndShowsTheNoticePerTask() = runBlocking {
        EnginePeer(this, veto = true).use { peer ->
            val native = ScriptedLlm(script(ScriptedLlm.text("first"), ScriptedLlm.text("second")))
            val env = FakeEnv(TestScope(), native)
            env.settings.agentEngine = AgentEngine.OMP
            var connects = 0
            val engine = OmpEngine({ null }, {
                if (++connects == 1) peer.client else throw java.io.IOException("fresh process unavailable")
            })
            val controller = AgentControllerImpl(env, Dispatchers.Default, engine)
            try {
                assertEquals("first", withTimeout(5000) { controller.submit(TaskRequest("hello", Source.CHAT)).await() }.result)
                assertEquals("second", withTimeout(5000) { controller.submit(TaskRequest("again", Source.CHAT)).await() }.result)
                assertEquals(2, controller.transcript.value.count { it.text.startsWith("omp unavailable;") })
                assertFalse(peer.commands.any { it.optString("type") == "prompt" })
                assertEquals(2, native.requests.size)
            } finally { engine.close() }
        }
    }

}
