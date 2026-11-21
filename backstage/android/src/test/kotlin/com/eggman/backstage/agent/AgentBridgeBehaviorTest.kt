package com.eggman.backstage.agent

import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.exec.Box
import com.eggman.backstage.exec.OverlayController
import com.eggman.backstage.exec.OverlayHooks
import com.eggman.backstage.exec.ScreenSerializer
import com.eggman.backstage.llm.Message
import com.eggman.backstage.llm.RetryPolicy
import com.eggman.backstage.testutil.FakeExecutor
import com.eggman.backstage.testutil.Screens
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.ScriptedLlm.Companion.call
import com.eggman.backstage.testutil.ScriptedLlm.Companion.text
import com.eggman.backstage.testutil.script
import com.eggman.backstage.tools.SensitiveActionGuard
import com.eggman.backstage.tools.TestToolContext
import com.eggman.backstage.tools.ToolRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBridgeBehaviorTest {
    private val ser = ScreenSerializer()

    @Test fun freshScreenGuardRules() {
        val g = FreshScreenGuard(maxBlocks = 2)
        val a = ser.serialize(Screens.buttons("Inbox", "Compose"), "phone")
        val clock = ser.serialize(Screens.buttons("Inbox 12", "Compose"), "phone") // digits ignored
        val dialog = ser.serialize(Screens.buttons("Inbox", "Compose", "Rate this app"), "phone")
        assertNull(g.check(a, clock))
        val msg = g.check(a, dialog)!!
        assertTrue(msg.contains("1 element(s) appeared, 0 disappeared"))
        assertTrue(msg.contains("Rate this app"))
        assertTrue(msg.contains("[2] Button \"Rate this app\""))
        assertNull(g.check(a, dialog)) // never twice in a row
        g.clear()
        assertTrue(g.check(a, dialog) != null) // second block
        g.clear()
        assertNull(g.check(a, dialog)) // cap reached
    }

    private fun loop(exec: FakeExecutor, llm: ScriptedLlm, rules: Map<String, String> = emptyMap()) = AgentLoop(
        llm = llm,
        registry = ToolRegistry.default(),
        config = LoopConfig("S", 20, false, 1000, RetryPolicy(1), SensitiveActionGuard(false, false),
            ToolRegistry.Availability(desktop = false), appRules = rules),
        user = AgentLoopTest.User(),
        contextFactory = { h -> AgentLoopTest.Ctx(h, exec) },
    )

    @Test fun actionWithheldWhenScreenChangedMeanwhile() = runBlocking {
        val exec = FakeExecutor().apply { capture = Screens.buttons("Like", "Share") }
        val llm = ScriptedLlm(mutableListOf(
            { _ -> call("get_screen") },
            { _ -> exec.capture = Screens.buttons("Ad: Install now", "Like", "Share"); call("tap", """{"index":0}""") },
            { _ -> call("tap", """{"index":1}""") },
            { _ -> call("finish", """{"summary":"ok"}""") },
        ))
        val conv = mutableListOf(Message.user("like it"))
        loop(exec, llm).run(conv)
        assertEquals(listOf("click [1] Like"), exec.actions)
        assertTrue(conv[4].toolResults.single().text.startsWith("Action withheld because the display changed"))
    }

    @Test fun appRulesShownOnceWhenAppComesToFront() = runBlocking {
        val exec = FakeExecutor().apply { capture = Screens.buttons("Chats") }
        val llm = ScriptedLlm(script(call("launch_app", """{"app":"whatsapp"}"""), call("tap", """{"index":0}"""), call("finish", """{"summary":"ok"}""")))
        val conv = mutableListOf(Message.user("x"))
        loop(exec, llm, mapOf("com.example" to "Never send voice notes.")).run(conv)
        val texts = conv.flatMap { it.toolResults }.map { it.text }
        assertEquals(1, texts.count { it.contains("Never send voice notes.") })
    }

    @Test fun unchangedScreensAreSuppressedAtMostTwiceInARow() = runBlocking {
        val ctx = TestToolContext()
        val reg = ToolRegistry.default()
        reg.execute(ctx, "get_screen", JSONObject())
        val results = (1..4).map { reg.execute(ctx, "tap", JSONObject("""{"x":5,"y":5}""")).text }
        assertTrue(results[0].contains("No visible change"))
        assertTrue(results[1].contains("No visible change"))
        assertTrue(results[2].contains("Screen phone")) // re-sent on the third unchanged result
        assertTrue(results[3].contains("No visible change"))
    }

    @Test fun stableScreenWaitsForContentToSettle() = runTest {
        // Fast mode off: the stability polling, unchanged.
        com.eggman.backstage.exec.AgentPace.fast = { false }
        try {
            val exec = FakeExecutor().apply { capture = Screens.buttons("Loading") }
            val ctx = TestToolContext(phone = exec, stabilityMs = 600)
            backgroundScope.launch {
                kotlinx.coroutines.delay(500)
                exec.capture = Screens.buttons("Loaded")
            }
            val r = ToolRegistry.default().execute(ctx, "tap", JSONObject("""{"x":5,"y":5}"""))
            assertTrue(r.text, r.text.contains("Loaded"))
            assertTrue(testScheduler.currentTime >= 1_200) // change at 600 ms restarted the 600 ms window
        } finally {
            com.eggman.backstage.exec.AgentPace.fast = { true }
        }
    }

    /** fast mode: a loading screen is re-checked until it settles, without the fixed stability window. */
    @Test fun fastModeWaitsOnlyWhileLoading() = runTest {
        com.eggman.backstage.exec.AgentPace.fast = { true }
        val exec = FakeExecutor().apply { capture = Screens.buttons("Loading") }
        val ctx = TestToolContext(phone = exec, stabilityMs = 300)
        backgroundScope.launch {
            kotlinx.coroutines.delay(500)
            exec.capture = Screens.buttons("Loaded")
        }
        val r = ToolRegistry.default().execute(ctx, "tap", JSONObject("""{"x":5,"y":5}"""))
        assertTrue(r.text, r.text.contains("Loaded"))
        assertTrue(testScheduler.currentTime < 1_200)
        // nothing loading: no polling at all
        val t0 = testScheduler.currentTime
        ToolRegistry.default().execute(ctx, "tap", JSONObject("""{"x":5,"y":5}"""))
        assertEquals(t0, testScheduler.currentTime)
    }

    @Test fun pendingUserActionAndMoveWidget() = runBlocking {
        val hooks = com.eggman.backstage.tools.RecordingHooks(answer = "Continue")
        val ctx = TestToolContext(hooks = hooks)
        val reg = ToolRegistry.default()
        assertFalse(reg.execute(ctx, "pending_user_action", JSONObject("""{"instruction":"Enter your PIN"}""")).isError)
        hooks.answer = "Cancel"
        assertTrue(reg.execute(ctx, "pending_user_action", JSONObject("""{"instruction":"x"}""")).isError)

        assertFalse(reg.forAgent(ToolRegistry.Availability(desktop = false)).any { it.name == "move_floating_widget" })
        var moved: String? = null
        OverlayHooks.controller = object : OverlayController {
            override suspend fun hide() = Unit
            override fun restore() = Unit
            override fun bounds() = Box(900, 2000, 1080, 2200)
            override fun moveTo(corner: String): Boolean { moved = corner; return true }
        }
        try {
            assertTrue(reg.forAgent(ToolRegistry.Availability(desktop = false)).any { it.name == "move_floating_widget" })
            assertFalse(reg.execute(ctx, "move_floating_widget", JSONObject("""{"position":"top_left"}""")).isError)
            assertEquals("TOP_LEFT", moved)
            assertTrue(OverlayHooks.covers(950f, 2100f))
            assertFalse(OverlayHooks.covers(10f, 10f))
        } finally {
            OverlayHooks.controller = null
        }
    }

    @Test fun initialScreenIsAttachedToTheRequest() = runTest {
        val llm = ScriptedLlm(script(text("ok")))
        val env = FakeEnv(this, llm)
        env.phone.capture = Screens.buttons("Search flights")
        val c = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        c.submit(TaskRequest("book a flight", Source.OVERLAY, Target.PHONE)).await()
        val first = llm.requests.single().messages.first().text
        assertTrue(first.contains("Current screen:"))
        assertTrue(first.contains("Search flights"))
    }

    @Test fun historyIsSummarizedNearTheContextLimit() = runBlocking {
        val exec = FakeExecutor()
        exec.onAction = { exec.capture = Screens.buttons("n" + exec.actions.size) }
        // Requests without tools are housekeeping: the memory flush (, memory is on in the fakes) and the
        // history summary. Agent requests get six taps, then finish.
        var taps = 0
        var flushes = 0
        val replies = mutableListOf<(com.eggman.backstage.llm.LlmRequest) -> com.eggman.backstage.llm.LlmReply>()
        repeat(40) {
            replies += { req ->
                when {
                    req.tools.isEmpty() && req.system.contains("lasting facts") -> { flushes++; text("- The user prefers aisle seats.\nNONE") }
                    req.tools.isEmpty() -> text("Opened the app and tapped six items.")
                    taps < 6 -> { taps++; call("tap", """{"x":$taps,"y":5}""") }
                    else -> call("finish", """{"summary":"done"}""")
                }
            }
        }
        val llm = ScriptedLlm(replies)
        var ctxRef: AgentLoopTest.Ctx? = null
        val l = AgentLoop(llm, ToolRegistry.default(),
            LoopConfig("S", 30, false, 1000, RetryPolicy(1), SensitiveActionGuard(false, false), ToolRegistry.Availability(desktop = false),
                contextWindow = 100, compressAt = 0.8),
            AgentLoopTest.User(), { h -> AgentLoopTest.Ctx(h, exec).also { ctxRef = it } })
        // usage 100 > 80: compression is attempted before each request once enough history exists
        val conv = mutableListOf(Message.user("tap things"))
        val out = l.run(conv)
        assertEquals("done", out.summary)
        assertEquals("tap things", conv.first().text)
        assertTrue(conv[1].text.startsWith("Summary of the earlier steps"))
        assertTrue(conv[1].text.contains("tapped six items"))
        // tool calls and their results stay paired after compression
        conv.forEachIndexed { i, m ->
            if (m.role == com.eggman.backstage.llm.Role.ASSISTANT && m.toolCalls.isNotEmpty()) {
                assertEquals(m.toolCalls.map { it.id }, conv[i + 1].toolResults.map { it.callId })
            }
        }
        assertEquals(null, HistoryCompressor.splitPoint(listOf(Message.user("a"), Message.assistant("b"))))
        val remembered = ctxRef!!.services.memory!!.all()
        assertEquals(listOf("The user prefers aisle seats."), remembered.map { it.content })
        assertEquals(HistoryCompressor.SOURCE_FLUSH, remembered.single().source) // repeated flushes do not duplicate it
        assertTrue(flushes >= 1)
        assertEquals(6, exec.actions.size)
    }
}
