package com.eggman.backstage.omp.engine

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import com.eggman.backstage.agent.*
import com.eggman.backstage.agent.api.*
import com.eggman.backstage.data.*
import com.eggman.backstage.llm.*
import com.eggman.backstage.tools.*
import com.eggman.backstage.testutil.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

class OmpEngineTest {
    private class Peer(val scope: CoroutineScope, var behavior: String = "answer") : AutoCloseable {
        val listener = ServerSocket(0)
        val peer = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 5000 }
        val client = OmpRpcClient(listener.accept())
        val commands = Collections.synchronizedList(mutableListOf<JSONObject>())
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val continueAnswer = CompletableDeferred<Unit>()
        var echoedSecrets: List<String> = emptyList()
        private val job: Job
        init {
            listener.close()
            job = scope.launch(Dispatchers.IO) {
                val lines = OmpJsonl(peer.getInputStream())
                fun send(json: String) { peer.getOutputStream().apply { write((json + "\n").toByteArray()); flush() } }
                send("""{"type":"ready","supportedProtocolVersions":[1,2],"maxFrameBytes":1048576}""")
                var promptId = ""
                try {
                    while (isActive) {
                        val frame = lines.read() ?: break
                        commands += frame
                        val type = frame.getString("type")
                        if (type == "host_tool_result") {
                            send("""{"type":"agent_end","messages":[],"yielded":true,"isTerminal":true}""")
                            send("""{"type":"prompt_result","id":"$promptId","status":"completed","sessionSettled":true}""")
                            continue
                        }
                        send(JSONObject().put("type", "response").put("id", frame.optString("id")).put("command", type).put("success", true).toString())
                        when (type) {
                            "prompt" -> {
                                promptId = frame.getString("id"); started.complete(Unit)
                                if (behavior == "drop") { peer.close(); break }
                                if (behavior == "wait") continue
                                if (behavior == "tool") {
                                    send("""{"type":"host_tool_call","id":"h1","toolCallId":"t1","toolName":"backstage_send","arguments":{}}""")
                                    continue
                                }
                                if (behavior == "host_label") {
                                    send(JSONObject().put("type", "host_tool_call").put("id", "hostlabel1").put("toolCallId", "hostlabel1")
                                        .put("toolName", "backstage_clipboard_set").put("arguments", JSONObject().put("text", echoedSecrets.single())).toString())
                                    continue
                                }
                                if (behavior == "secrets") {
                                    val command = "curl -H 'Authorization: Bearer HEADER_SENTINEL' https://example.test token=ARG_SENTINEL"
                                    send(JSONObject().put("type", "tool_execution_start").put("toolName", "bash").put("toolCallId", "secret1")
                                        .put("args", JSONObject().put("command", command)).toString())
                                    val output = "prefix".repeat(500) + " password=RESULT_SENTINEL " + echoedSecrets.joinToString(" ")
                                    send(JSONObject().put("type", "tool_execution_end").put("toolCallId", "secret1")
                                        .put("result", JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", output)))).toString())
                                }
                                if (behavior == "multi") {
                                    send("""{"type":"message_end","messageId":"m1","message":{"role":"assistant","content":[{"type":"text","text":"Checking"}]}}""")
                                    send("""{"type":"tool_execution_start","toolName":"read","toolCallId":"read1","args":{"path":"a.kt"}}""")
                                    val output = "old output".repeat(400) + "RESULT TAIL"
                                    send(JSONObject().put("type", "tool_execution_end").put("toolCallId", "read1")
                                        .put("result", JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", output)))).toString())
                                    send("""{"type":"message_end","messageId":"m2","message":{"role":"assistant","content":[{"type":"text","text":"Final"}]}}""")
                                    send("""{"type":"agent_end","yielded":true,"messages":[{"role":"assistant","content":[{"type":"text","text":"Checking"}]},{"role":"assistant","content":[{"type":"text","text":"Final"}]}]}""")
                                    send("""{"type":"prompt_result","id":"$promptId","status":"completed","sessionSettled":true}""")
                                    continue
                                }
                                if (behavior == "thinking") {
                                    send("""{"type":"message_update","messageId":"m1","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"Check files"}}""")
                                    continueAnswer.await()
                                }
                                if (behavior == "steps") {
                                    send("""{"type":"tool_execution_start","toolName":"bash","toolCallId":"builtin1","args":{"command":"ls","reason":"Inspect files"}}""")
                                    send("""{"type":"tool_execution_end","toolName":"bash","toolCallId":"builtin1","result":{"content":[{"type":"text","text":"listed"}]},"isError":false}""")
                                }
                                send("""{"type":"message_update","messageId":"m1","assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"answer"}}""")
                                send("""{"type":"agent_end","messages":[],"yielded":false,"isTerminal":false}""")
                                send("""{"type":"agent_end","messages":[],"yielded":true,"isTerminal":true}""")
                                send("""{"type":"prompt_result","id":"$promptId","status":"completed","sessionSettled":true}""")
                            }
                            "abort" -> {
                                stopped.complete(Unit)
                                send("""{"type":"prompt_result","id":"$promptId","status":"aborted","sessionSettled":true}""")
                            }
                        }
                    }
                } catch (_: java.io.IOException) { }
            }
        }
        override fun close() { peer.close(); client.close(); job.cancel() }
    }
    private fun cfg() = LoopConfig("device system", 10, false, 1000, RetryPolicy(), SensitiveActionGuard(true, false), ToolRegistry.Availability(false))
    private fun context(h: AgentHooks) = AgentLoopTest.Ctx(h, FakeExecutor())
    private suspend fun run(engine: OmpEngine, key: Any, registry: ToolRegistry = ToolRegistry.default(), user: UserChannel = AgentLoopTest.User()) =
        engine.run(key, "provider/model", registry, cfg(), user, ::context, object : LoopListener {}, listOf(Message.user("hello")))

    @Test fun followUpsReuseProcessAndSessionAndNewThreadResetsSession() = runBlocking {
        Peer(this).use { peer ->
            var connections = 0
            val engine = OmpEngine({ null }, { connections++; peer.client })
            val thread = Any()
            assertEquals("answer", run(engine, thread)!!.summary)
            assertEquals("answer", run(engine, thread)!!.summary)
            assertEquals("answer", run(engine, Any())!!.summary)
            assertEquals(1, connections)
            assertEquals(2, peer.commands.count { it.optString("type") == "new_session" })
            val prompts = peer.commands.filter { it.optString("type") == "prompt" }
            assertTrue(prompts[0].getString("message").contains("device system"))
            assertFalse(prompts[1].getString("message").contains("device system"))
            engine.close()
        }
    }
    @Test fun stopSendsAbortAndNeverUsesNativeFallback() = runBlocking {
        Peer(this, "wait").use { peer ->
            val engine = OmpEngine({ null }, { peer.client })
            val thread = Any()
            val task = launch { run(engine, thread) }
            withTimeout(5000) { peer.started.await() }
            task.cancelAndJoin()
            assertTrue(peer.commands.any { it.optString("type") == "abort" })
            peer.behavior = "answer"
            assertEquals("answer", run(engine, thread)!!.summary)
            assertEquals(1, peer.commands.count { it.optString("type") == "new_session" })
            engine.close()
        }
    }
    @Test fun bridgeDropFailsThisTaskAndNextTaskReconnects() = runBlocking {
        Peer(this, "drop").use { broken -> Peer(this).use { good ->
            var connects = 0
            val engine = OmpEngine({ null }, { if (++connects == 1) broken.client else good.client })
            var failed = false
            try { run(engine, Any()) } catch (_: Exception) { failed = true }
            assertTrue(failed)
            assertEquals("answer", run(engine, Any())!!.summary)
            assertEquals(2, connects)
            engine.close()
        } }
    }
    @Test fun missingRuntimeOrPrivilegeDoesNotConnect() = runBlocking {
        for (reason in listOf("Runtime not installed", "Privileged access missing")) {
            val engine = OmpEngine({ reason }, { error("must not start") })
            assertNull(run(engine, Any()))
            assertEquals(reason, engine.unavailableReason)
        }
    }
    @Test fun irreversibleHostToolStillAsksThroughTheEngine() = runBlocking {
        Peer(this, "tool").use { peer ->
            var ran = false
            val tool = object : Tool {
                override val name = "send"; override val description = "Send"
                override val schema = Schema.obj(); override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
                override val irreversible = true
                override suspend fun run(ctx: ToolContext, args: Args): ToolResult { ran = true; return ToolResult("sent") }
            }
            val user = AgentLoopTest.User(false)
            val engine = OmpEngine({ null }, { peer.client })
            val result = run(engine, Any(), ToolRegistry(listOf(tool)), user)!!
            assertFalse(result.success); assertEquals(EndReason.DECLINED, result.reason)
            assertEquals(1, user.confirms.size); assertFalse(ran)
            assertTrue(peer.commands.any { it.optString("type") == "host_tool_result" && it.optBoolean("isError") })
            engine.close()
        }
    }
    @Test fun controllerFallbackNoticeIsShownForEveryTaskAndNativeTasksStillFinish() = runTest {
        org.junit.Assume.assumeFalse(PLAY_DISTRIBUTION)
        val env = FakeEnv(this, ScriptedLlm(script(ScriptedLlm.text("first"), ScriptedLlm.text("second"))))
        env.settings.agentEngine = AgentEngine.OMP
        val controller = AgentControllerImpl(env, StandardTestDispatcher(testScheduler), OmpEngine({ "privilege missing" }, { error("unavailable") }))
        val first = controller.submit(TaskRequest("hello", Source.CHAT))
        advanceUntilIdle()
        assertEquals(TaskStatus.SUCCEEDED, first.await().status)
        val second = controller.submit(TaskRequest("again", Source.CHAT))
        advanceUntilIdle()
        assertEquals("second", second.await().result)
        assertNull(second.await().thinking)
        assertNull(second.await().chatLines)
        assertEquals(2, controller.transcript.value.count { it.text.startsWith("omp unavailable;") })
    }
    @Test fun controllerPublishesOmpTextStepsAndTheFinalSnapshot() = runBlocking {
        org.junit.Assume.assumeFalse(PLAY_DISTRIBUTION)
        Peer(this, "steps").use { peer ->
            val native = ScriptedLlm(mutableListOf())
            val env = FakeEnv(TestScope(), native)
            env.settings.agentEngine = AgentEngine.OMP
            val engine = OmpEngine({ null }, { peer.client })
            val controller = AgentControllerImpl(env, Dispatchers.Default, engine)
            val task = controller.submit(TaskRequest("hello", Source.CHAT))
            val result = withTimeout(5000) { task.await() }
            assertEquals(TaskStatus.SUCCEEDED, result.status)
            assertEquals("answer", result.result)
            assertEquals("bash: ls", result.lastAction)
            assertEquals(1, result.shownActions)
            assertTrue(controller.transcript.value.any { it.kind == TranscriptItem.Kind.ACTION && it.text == "bash: ls" })
            assertTrue(controller.transcript.value.any { it.kind == TranscriptItem.Kind.ASSISTANT && it.text == "answer" })
            assertTrue(native.requests.isEmpty())
            engine.close()
        }
    }
    @Test fun controllerPublishesLiveThinkingAndKeepsItInTheFinishedThread() = runBlocking {
        org.junit.Assume.assumeFalse(PLAY_DISTRIBUTION)
        Peer(this, "thinking").use { peer ->
            val env = FakeEnv(TestScope(), ScriptedLlm(mutableListOf()))
            env.settings.agentEngine = AgentEngine.OMP
            val store = FileChatThreadStore(null)
            env.threadStore = store
            val engine = OmpEngine({ null }, { peer.client })
            val controller = AgentControllerImpl(env, Dispatchers.Default, engine)
            val task = controller.submit(TaskRequest("hello", Source.CHAT))
            val live = withTimeout(5000) { controller.state.first { it.current?.thinking != null }.current!! }
            assertEquals("Check files", live.thinking)
            assertTrue(live.thinkingActive)
            assertEquals("Check files", live.chatLines!!.filterNot { it.text.startsWith("Starting omp") }.single().thinking)
            peer.continueAnswer.complete(Unit)
            val finished = withTimeout(5000) { task.await() }
            assertFalse(finished.thinkingActive)
            assertEquals("answer", finished.result)
            assertEquals("Check files", store.get(store.currentId.value)!!.entries.first { it.kind == "assistant" || it.kind == "result" }.thinking)
            assertFalse(finished.chatLines!!.any { it.thinkingActive })
            engine.close()
        }
    }
    @Test fun orderedMessagesAndBoundedToolTailSurviveSnapshotAndThreadReload() = runBlocking {
        org.junit.Assume.assumeFalse(PLAY_DISTRIBUTION)
        Peer(this, "multi").use { peer ->
            val env = FakeEnv(TestScope(), ScriptedLlm(mutableListOf()))
            env.settings.agentEngine = AgentEngine.OMP
            val store = FileChatThreadStore(null)
            env.threadStore = store
            val engine = OmpEngine({ null }, { peer.client })
            val controller = AgentControllerImpl(env, Dispatchers.Default, engine)
            val finished = withTimeout(5000) { controller.submit(TaskRequest("hello", Source.CHAT)).await() }
            assertEquals("Final", finished.result)
            assertTrue(finished.chatLines!!.first().text.startsWith("Starting omp"))
            val lines = finished.chatLines!!.filterNot { it.text.startsWith("Starting omp") }
            assertEquals(listOf("Checking", "read: a.kt", "Final"), lines.map { it.text })
            assertEquals(2048, lines[1].detail.toByteArray().size)
            assertTrue(lines[1].detail.endsWith("RESULT TAIL"))
            val saved = store.get(store.currentId.value)!!
            assertEquals(listOf("user", "assistant", "action", "result"), saved.entries.map { it.kind })
            assertEquals(listOf("hello", "Checking", "read: a.kt", "Final"), saved.entries.map { it.text })
            assertEquals(lines[1].detail, com.eggman.backstage.ui.chat.Transcript.fromThread(saved).entries[2].detail)
            engine.close()
        }
    }
    @Test fun toolSecretsNeverReachSnapshotsRenderedEntriesOrEncodedThreadsAndHistory() = runBlocking {
        org.junit.Assume.assumeFalse(PLAY_DISTRIBUTION)
        Peer(this, "secrets").use { peer ->
            val env = FakeEnv(TestScope(), ScriptedLlm(mutableListOf()))
            env.settings.agentEngine = AgentEngine.OMP
            val providerKey = "EXACT_PROVIDER_SENTINEL_FOR_OMP"
            env.settings.setApiKey(env.settings.provider, providerKey)
            val otherProviderKey = "EXACT_OTHER_PROVIDER_SENTINEL_FOR_OMP"
            env.settings.setApiKey(Provider.OPENAI, otherProviderKey)
            val brokerToken = env.settings.ompHarnessToken
            peer.echoedSecrets = listOf(providerKey, brokerToken, otherProviderKey)
            val store = FileChatThreadStore(null); env.threadStore = store
            val engine = OmpEngine({ null }, { peer.client })
            val controller = AgentControllerImpl(env, Dispatchers.Default, engine)
            val snapshots = java.util.concurrent.CopyOnWriteArrayList<TaskSnapshot>()
            val collector = launch(Dispatchers.Unconfined) { controller.state.collect { it.current?.let(snapshots::add) } }
            try {
                val finished = withTimeout(5000) { controller.submit(TaskRequest("hello", Source.CHAT)).await() }
                val rendered = com.eggman.backstage.ui.chat.Transcript().onSnapshot(finished).entries.toString()
                val encodedThreads = ChatThreadCodec.encode(listOf(store.get(store.currentId.value)!!), store.currentId.value).toString()
                val encodedHistory = TaskRecordCodec.encode(env.history.get(finished.id)!!).toString()
                val surfaces = listOf(snapshots.toString(), controller.transcript.value.toString(), rendered, encodedThreads, encodedHistory)
                for (sentinel in listOf("HEADER_SENTINEL", "ARG_SENTINEL", "RESULT_SENTINEL", providerKey, brokerToken, otherProviderKey)) {
                    assertTrue("A synthetic tool secret reached a visible or persisted surface", surfaces.none { it.contains(sentinel) })
                }
                assertTrue(finished.lastAction!!.contains("•••"))
                assertTrue(finished.chatLines!!.first { it.kind == "action" }.detail.contains("•••"))
            } finally { collector.cancelAndJoin(); engine.close() }
        }
    }
    @Test fun hostLabelsRedactFullSecretsBeforeTruncatingWithoutChangingTheToolInput() = runBlocking {
        org.junit.Assume.assumeFalse(PLAY_DISTRIBUTION)
        Peer(this, "host_label").use { peer ->
            val env = FakeEnv(TestScope(), ScriptedLlm(mutableListOf()))
            env.settings.agentEngine = AgentEngine.OMP
            val key = "EXACT_PROVIDER_SENTINEL_".repeat(4)
            env.settings.setApiKey(env.settings.provider, key)
            peer.echoedSecrets = listOf(key)
            val engine = OmpEngine({ null }, { peer.client })
            try {
                val controller = AgentControllerImpl(env, Dispatchers.Default, engine)
                val finished = withTimeout(5000) { controller.submit(TaskRequest("hello", Source.CHAT)).await() }
                assertTrue("Host label redaction must precede truncation", finished.lastAction == "clipboard set \"•••\"")
                assertTrue("The original tool input must stay intact", env.services.clipboard == key)
            } finally { engine.close() }
        }
    }
    @Test fun startupBridgeFailureAllowsNativeFallback() = runBlocking {
        val engine = OmpEngine({ null }, { throw java.io.IOException("listener unavailable") })
        assertNull(run(engine, Any()))
        assertTrue(engine.unavailableReason!!.contains("listener unavailable"))
    }

}
