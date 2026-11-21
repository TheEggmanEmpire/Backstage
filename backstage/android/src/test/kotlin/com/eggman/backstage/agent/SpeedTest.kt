package com.eggman.backstage.agent

import com.eggman.backstage.agent.api.StepTiming
import com.eggman.backstage.agent.api.TaskTiming
import com.eggman.backstage.exec.AgentPace
import com.eggman.backstage.llm.Message
import com.eggman.backstage.llm.RetryPolicy
import com.eggman.backstage.testutil.FakeExecutor
import com.eggman.backstage.testutil.Screens
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.ScriptedLlm.Companion.call
import com.eggman.backstage.testutil.ScriptedLlm.Companion.calls
import com.eggman.backstage.testutil.script
import com.eggman.backstage.tools.SensitiveActionGuard
import com.eggman.backstage.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** act_sequence, plan handling, step timings, fast-mode pacing, cache-friendly context. */
class SpeedTest {
    private val exec = FakeExecutor().apply { capture = Screens.buttons("1", "2", "3", "OK") }
    private val timings = mutableListOf<StepTiming>()
    private var now = 0L

    private fun loop(llm: ScriptedLlm, plan: Boolean = false, maxActions: Int = 20, user: AgentLoopTest.User = AgentLoopTest.User()) = AgentLoop(
        llm = llm,
        registry = ToolRegistry.default(),
        config = LoopConfig(
            system = "SYS", maxActions = maxActions, planMode = plan, maxTokens = 1000,
            retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 1, jitter = 0.0), guard = SensitiveActionGuard(enabled = true, preAuthorized = false),
            availability = ToolRegistry.Availability(desktop = false, planMode = plan),
        ),
        user = user,
        contextFactory = { hooks -> AgentLoopTest.Ctx(hooks, exec) },
        listener = object : LoopListener {
            override fun onTiming(timing: StepTiming) { timings += timing }
        },
        clock = { now += 100; now },
    )

    @Test fun actSequenceRunsSeveralStepsInOneTurn() = runBlocking {
        val llm = ScriptedLlm(script(
            call("get_screen"),
            call("act_sequence", """{"steps":[{"tool":"tap","args":{"index":0}},{"tool":"tap","args":{"index":2}},{"tool":"tap","args":{"index":3}}]}"""),
            call("finish", """{"summary":"typed 13"}"""),
        ))
        val conv = mutableListOf(Message.user("type 13 and OK"))
        val l = loop(llm)
        val out = l.run(conv)
        assertEquals(EndReason.FINISHED, out.reason)
        assertEquals(listOf("click [0] 1", "click [2] 3", "click [3] OK"), exec.actions)
        assertEquals(3, l.actions) // every step counts against the action budget
        assertEquals(3, llm.requests.size) // one model turn for three taps
        val result = conv[4].toolResults.single().text
        assertTrue(result, result.startsWith("Sequence: 3 of 3 steps done"))
        assertTrue(result.contains("Screen phone"))
    }

    @Test fun actSequenceStopsWhenTheListingNoLongerMatches() = runBlocking {
        exec.onAction = { exec.capture = Screens.buttons("Cancel", "Delete everything") }
        val llm = ScriptedLlm(script(
            call("get_screen"),
            call("act_sequence", """{"steps":[{"tool":"tap","args":{"index":0}},{"tool":"tap","args":{"index":1}}]}"""),
            call("finish", """{"summary":"x"}"""),
        ))
        val conv = mutableListOf(Message.user("x"))
        loop(llm).run(conv)
        assertEquals(listOf("click [0] 1"), exec.actions)
        val text = conv[4].toolResults.single().text
        assertTrue(text, text.contains("stopped: step 2 skipped"))
    }

    @Test fun actSequenceRefusesOtherToolsAndRespectsTheBudget() = runBlocking {
        val llm = ScriptedLlm(script(
            call("get_screen"),
            call("act_sequence", """{"steps":[{"tool":"tap","args":{"index":0}},{"tool":"launch_app","args":{"app":"x"}}]}"""),
            call("act_sequence", """{"steps":[{"tool":"tap","args":{"index":0}},{"tool":"tap","args":{"index":1}},{"tool":"tap","args":{"index":2}}]}"""),
            call("finish", """{"summary":"x"}"""),
        ))
        val conv = mutableListOf(Message.user("x"))
        loop(llm, maxActions = 2).run(conv)
        assertTrue(conv[4].toolResults.single().isError)
        assertEquals(listOf("click [0] 1", "click [1] 2"), exec.actions)
        assertTrue(conv[6].toolResults.single().text.contains("safety limit"))
        assertFalse(ToolRegistry.default().forMcp(ToolRegistry.Availability(desktop = true)).any { it.name == "act_sequence" })
    }

    @Test fun actSequenceStepsStillAskForSensitiveActions() = runBlocking {
        exec.capture = Screens.buttons("1", "Send")
        val user = AgentLoopTest.User(approve = false)
        val llm = ScriptedLlm(script(
            call("get_screen"),
            call("act_sequence", """{"steps":[{"tool":"tap","args":{"index":0}},{"tool":"tap","args":{"index":1}}]}"""),
            call("finish", """{"summary":"x"}"""),
        ))
        val out = loop(llm, user = user).run(mutableListOf(Message.user("x")))
        assertEquals(listOf("click [0] 1"), exec.actions)
        assertEquals(EndReason.DECLINED, out.reason)
    }

    @Test fun planGoesWithTheNewestTurnAndRidesAlongWithActions() = runBlocking {
        val user = AgentLoopTest.User()
        val llm = ScriptedLlm(script(
            calls(listOf("update_plan" to """{"steps":[{"title":"Press 1","status":"in_progress"},{"title":"Confirm","status":"pending"}]}""",
                "tap" to """{"index":0}""")),
            call("tap", """{"index":3,"plan":[{"title":"Press 1","status":"done"},{"title":"Confirm","status":"done"}]}"""),
            call("finish", """{"summary":"done"}"""),
        ))
        val l = loop(llm, plan = true, user = user)
        val out = l.run(mutableListOf(Message.user("x")))
        assertEquals("done", out.summary) // finish is not blocked: the plan arrived with the last tap
        assertEquals(listOf("click [0] 1", "click [3] OK"), exec.actions) // update_plan did not use up the turn's action
        assertEquals(listOf("done", "done"), l.plan.map { it.status })
        // the system prompt stays the same for every request; the plan is in the newest user message
        assertTrue(llm.requests.all { it.system == "SYS" })
        assertTrue(llm.requests[1].messages.last().text.contains("Current plan"))
        assertFalse(llm.requests[1].messages.dropLast(1).any { it.text.contains("Current plan") })
        val tap = llm.requests[0].tools.first { it.name == "tap" }
        assertTrue(tap.schema.getJSONObject("properties").has("plan"))
    }

    @Test fun stepTimingsAreReported() = runBlocking {
        val llm = ScriptedLlm(script(call("get_screen"), call("tap", """{"index":0}"""), call("finish", """{"summary":"x"}""")))
        loop(llm).run(mutableListOf(Message.user("x")))
        assertEquals(3, timings.size)
        assertEquals(listOf("get_screen", "tap", "finish"), timings.map { it.tool })
        assertTrue(timings.all { it.modelMs == 100L })
        val total = timings.fold(TaskTiming()) { t, s -> t + s }
        assertEquals(3, total.steps)
        assertEquals(300L, total.modelMs)
        assertEquals("ai", total.dominant())
    }

    @Test fun taskTimingModel() {
        val t = TaskTiming() + StepTiming(1, "tap", 1_200, 300, 400) + StepTiming(2, "type_text", 2_000, 100, 900) + StepTiming(3, null, 800, 0, 0)
        assertEquals(3, t.steps)
        assertEquals(4_000L, t.modelMs)
        assertEquals(400L, t.actionMs)
        assertEquals(1_300L, t.settleMs)
        assertEquals(2, t.slowest?.step)
        val line = t.summary()
        assertTrue(line, line.startsWith("AI 4.0 s · actions 0.4 s · screen settling 1.3 s (3 steps, avg AI 1.3 s; slowest: step 2 (type_text), 3.0 s)"))
        assertEquals(t, TaskTiming.fromJson(t.toJson()))
        assertNull(TaskTiming.fromJson(null))
        assertEquals("", TaskTiming().summary())
        assertEquals("12 s", TaskTiming.secs(12_400))
        assertEquals("settle", (TaskTiming() + StepTiming(1, "x", 1, 2, 9)).dominant())
        // negative clock skew never subtracts
        assertEquals(0L, (TaskTiming() + StepTiming(1, "x", -5, -5, -5)).totalMs)
    }

    @Test fun fastModePacing() {
        AgentPace.fast = { true }
        assertEquals(200L, AgentPace.settleQuietMs())
        assertEquals(0, AgentPace.cursorLeadMs(200))
        assertEquals(300L, AgentPace.stabilityMs())
        AgentPace.fast = { false }
        try {
            assertEquals(350L, AgentPace.settleQuietMs())
            assertEquals(200, AgentPace.cursorLeadMs(200))
            assertEquals(600L, AgentPace.stabilityMs())
            assertEquals(250L, AgentPace.launchPollMs())
        } finally {
            AgentPace.fast = { true }
        }
    }

    @Test fun olderListingsCollapseInBatchesSoThePrefixStaysStable() {
        fun listing(i: Int) = com.eggman.backstage.llm.Message(com.eggman.backstage.llm.Role.USER,
            listOf(com.eggman.backstage.llm.Part.ToolResult("c$i", "Tapped\n\nScreen phone (display 0) step $i\n[0] Button")))
        val cm = ContextManager(keepScreens = 2, collapseBatch = 4)
        val conv = (1..5).map(::listing)
        // 3 excess listings: nothing collapses yet
        assertTrue(cm.prepare(conv).none { it.toolResults.single().text.contains("omitted") })
        val six = cm.prepare((1..6).map(::listing))
        assertEquals(4, six.count { it.toolResults.single().text.contains("omitted") })
        val seven = cm.prepare((1..7).map(::listing))
        // the same four stay collapsed; the prefix of the 6-listing request is unchanged
        assertEquals(six.take(5).map { it.toolResults.single().text }, seven.take(5).map { it.toolResults.single().text })
    }

    @Test fun anthropicCachesTheConversationTailButNotThePlanText() {
        val msgs = JSONArray(listOf(
            org.json.JSONObject().put("role", "user").put("content", JSONArray().put(org.json.JSONObject().put("type", "text").put("text", "x"))),
            org.json.JSONObject().put("role", "user").put("content", JSONArray()
                .put(org.json.JSONObject().put("type", "tool_result").put("tool_use_id", "a"))
                .put(org.json.JSONObject().put("type", "text").put("text", "## Current plan"))),
        ))
        com.eggman.backstage.llm.AnthropicClient.markCacheTail(msgs)
        val last = msgs.getJSONObject(1).getJSONArray("content")
        assertTrue(last.getJSONObject(0).has("cache_control"))
        assertFalse(last.getJSONObject(1).has("cache_control"))
    }

    private fun swapAfterLook(vararg labels: String): MutableList<(com.eggman.backstage.llm.LlmRequest) -> com.eggman.backstage.llm.LlmReply> = mutableListOf(
        { _ -> call("get_screen") },
        // the app swaps its buttons while the model thinks
        { _ -> exec.capture = Screens.buttons(*labels); call("tap", """{"index":0}""") },
        { _ -> call("finish", """{"summary":"x"}""") },
    )

    @Test fun reorderedElementsDoNotRedirectAnIndex() = runBlocking {
        exec.capture = Screens.buttons("Archive", "Delete")
        val conv = mutableListOf(Message.user("x"))
        loop(ScriptedLlm(swapAfterLook("Delete", "Archive"))).run(conv)
        assertTrue(exec.actions.isEmpty()) // [0] is "Delete" now: withheld, not tapped
        assertTrue(conv[4].toolResults.single().text.startsWith("Action withheld: element [0] is no longer “Archive”"))
        val ser = com.eggman.backstage.exec.ScreenSerializer(setOf())
        assertNull(FreshScreenGuard().indexMoved(ser.serialize(Screens.buttons("A", "B"), "phone"), ser.serialize(Screens.buttons("A", "B", "C"), "phone"), 1))
    }

    @Test fun guardSkipsTheReReadWhenNoEventsArrived() = runBlocking {
        exec.capture = Screens.buttons("Archive", "Delete")
        exec.uiEvents = 0 // the event stream says nothing changed since the listing
        loop(ScriptedLlm(swapAfterLook("Delete", "Archive"))).run(mutableListOf(Message.user("x")))
        // no re-read: the tap resolves against the listing the model saw
        assertEquals(listOf("click [0] Archive"), exec.actions)
        exec.actions.clear()
        exec.capture = Screens.buttons("Archive", "Delete")
        exec.uiEvents = 3
        loop(ScriptedLlm(swapAfterLook("Delete", "Archive"))).run(mutableListOf(Message.user("x")))
        assertTrue(exec.actions.isEmpty())
    }

    @Test fun settleConfirmsOnlyWhileLoading() = runBlocking {
        AgentPace.fast = { true }
        val ex = FakeExecutor().apply { capture = Screens.buttons("Done") }
        val (s1, changing1) = com.eggman.backstage.tools.Observe.settledScreen(ex, 300)
        assertFalse(changing1)
        assertTrue(s1.text.contains("Done"))
        ex.capture = Screens.app(Screens.node(text = "", cls = "android.widget.ProgressBar", b = com.eggman.backstage.exec.Box(0, 0, 100, 100)))
        val (_, changing2) = com.eggman.backstage.tools.Observe.settledScreen(ex, 300)
        assertTrue(changing2) // still loading after the cap: reported, not waited on forever
        assertTrue(com.eggman.backstage.tools.Observe.looksLoading(ex.screen(), ex.lastCapture))
        assertFalse(com.eggman.backstage.tools.Observe.looksLoading(Screens.buttons("Loading dock is closed today, see the map").let { ex.capture = it; ex.screen() }))
    }
}
