package com.eggman.backstage.agent

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.exec.Executor
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.llm.LlmErrorKind
import com.eggman.backstage.llm.LlmException
import com.eggman.backstage.llm.Message
import com.eggman.backstage.llm.Part
import com.eggman.backstage.llm.RetryPolicy
import com.eggman.backstage.llm.Role
import com.eggman.backstage.llm.StopReason
import com.eggman.backstage.testutil.FakeExecutor
import com.eggman.backstage.testutil.FakeServices
import com.eggman.backstage.testutil.Screens
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.ScriptedLlm.Companion.call
import com.eggman.backstage.testutil.ScriptedLlm.Companion.calls
import com.eggman.backstage.testutil.ScriptedLlm.Companion.text
import com.eggman.backstage.testutil.script
import com.eggman.backstage.tools.AgentHooks
import com.eggman.backstage.tools.DeviceServices
import com.eggman.backstage.tools.SensitiveActionGuard
import com.eggman.backstage.tools.ToolContext
import com.eggman.backstage.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    class Ctx(override val hooks: AgentHooks?, val exec: FakeExecutor) : ToolContext, PeekableContext {
        override val currentTarget: TargetKind? = TargetKind.PHONE
        override suspend fun executor(kind: TargetKind?): Executor = exec
        override fun switchTarget(kind: TargetKind) = Unit
        override fun peek(kind: TargetKind): Executor = exec
        override val services: DeviceServices = FakeServices()
        override val visionEnabled = true
        override val autoScreenshot = false
        override val stabilityMs = 0L
    }

    class User(var approve: Boolean = true) : UserChannel {
        val confirms = mutableListOf<String>()
        var plans = mutableListOf<List<PlanStep>>()
        override suspend fun ask(question: String, choices: List<String>): String? = "42"
        override suspend fun confirm(action: String): Boolean { confirms += action; return approve }
        override fun planChanged(steps: List<PlanStep>) { plans += steps }
    }

    private val exec = FakeExecutor().apply { capture = Screens.buttons("Compose", "Send") }

    private fun loop(
        llm: ScriptedLlm, plan: Boolean = false, maxSteps: Int = 20, user: User = User(),
        guard: SensitiveActionGuard = SensitiveActionGuard(enabled = true, preAuthorized = false),
    ) = AgentLoop(
        llm = llm,
        registry = ToolRegistry.default(),
        config = LoopConfig(
            system = "SYS", maxActions = maxSteps, planMode = plan, maxTokens = 1000,
            retry = RetryPolicy(maxAttempts = 3, baseDelayMs = 10, jitter = 0.0), guard = guard,
            availability = ToolRegistry.Availability(desktop = false, planMode = plan),
        ),
        user = user,
        contextFactory = { hooks -> Ctx(hooks, exec) },
    )

    @Test fun observeActFinish() = runBlocking {
        val llm = ScriptedLlm(script(
            call("get_screen"),
            call("tap", """{"index":0}""", text = "Opening compose"),
            call("finish", """{"summary":"Composer is open","success":true}"""),
        ))
        val conv = mutableListOf(Message.user("open the composer"))
        val out = loop(llm).run(conv)
        assertEquals(EndReason.FINISHED, out.reason)
        assertTrue(out.success)
        assertEquals("Composer is open", out.summary)
        assertEquals(3, out.steps)
        assertEquals(listOf("click [0] Compose"), exec.actions)
        // tool results are paired with calls in a user message
        val last = conv.last()
        assertEquals(Role.USER, last.role)
        assertEquals(1, last.toolResults.size)
        // tools offered to the model
        assertTrue(llm.requests.first().tools.any { it.name == "tap" })
        assertFalse(llm.requests.first().tools.any { it.name == "update_plan" })
    }

    @Test fun plainAnswerEndsChatTurn() = runBlocking {
        val out = loop(ScriptedLlm(script(text("Paris is the capital of France.")))).run(mutableListOf(Message.user("capital of france?")))
        assertEquals(EndReason.ANSWERED, out.reason)
        assertEquals("Paris is the capital of France.", out.summary)
    }

    @Test fun planModeBlocksEarlyFinishThenAllows() = runBlocking {
        val user = User()
        val llm = ScriptedLlm(script(
            call("update_plan", """{"steps":[{"title":"Open","status":"done"},{"title":"Send","status":"pending"}]}"""),
            call("finish", """{"summary":"done"}"""),
            call("update_plan", """{"steps":[{"title":"Open","status":"done"},{"title":"Send","status":"done"}]}"""),
            call("finish", """{"summary":"all done"}"""),
        ))
        val conv = mutableListOf(Message.user("x"))
        val l = loop(llm, plan = true, user = user)
        val out = l.run(conv)
        assertEquals("all done", out.summary)
        assertEquals(4, out.steps)
        val blocked = conv[4].toolResults.single()
        assertTrue(blocked.isError)
        assertTrue(blocked.text.startsWith(AgentLoop.PLAN_BLOCK))
        assertEquals("done", l.plan.last().status)
        assertEquals(2, user.plans.size)
        assertTrue(llm.requests.first().tools.any { it.name == "update_plan" })
    }

    @Test fun planModeNagsWhenModelStopsWithPendingSteps() = runBlocking {
        val llm = ScriptedLlm(script(
            call("update_plan", """{"steps":[{"title":"A","status":"pending"}]}"""),
            text("I think that's it"),
            call("finish", """{"summary":"ok","success":false}"""),
        ))
        val conv = mutableListOf(Message.user("x"))
        val out = loop(llm, plan = true).run(conv)
        assertEquals(EndReason.FINISHED, out.reason) // success=false finish is not blocked
        assertFalse(out.success)
        assertTrue(conv.any { m -> m.role == Role.USER && m.text.contains("unfinished steps") })
    }

    @Test fun staleIndicesAfterMutationInSameTurnAreSkipped() = runBlocking {
        exec.onAction = { exec.capture = Screens.buttons("Other") }
        val llm = ScriptedLlm(script(
            call("get_screen"),
            calls(listOf("tap" to """{"index":0}""", "tap" to """{"index":1}""", "web_search" to """{"query":"x"}""")),
            call("finish", """{"summary":"ok"}"""),
        ))
        val conv = mutableListOf(Message.user("x"))
        loop(llm).run(conv)
        assertEquals(1, exec.actions.size)
        val results = conv[4].toolResults
        assertEquals(3, results.size)
        assertTrue(results[1].text.startsWith("Skipped"))
        // only the first call of a turn runs, also for read-only tools
        assertEquals(AgentLoop.ONE_PER_TURN, results[2].text)
    }

    @Test fun sensitiveTapAsksTheUserDirectly() = runBlocking {
        // the loop asks the user itself (tool-worded question); no Blocked → confirm_action → retry round trip.
        val user = User(approve = true)
        val llm = ScriptedLlm(script(
            call("get_screen"),
            call("tap", """{"index":1}"""), // "Send" → the user is asked, then it runs
            call("finish", """{"summary":"sent"}"""),
        ))
        val conv = mutableListOf(Message.user("send it"))
        val out = loop(llm, user = user).run(conv)
        assertEquals(EndReason.FINISHED, out.reason)
        assertEquals(listOf("click [1] Send"), exec.actions)
        assertEquals(1, user.confirms.size)
        assertTrue(user.confirms.single(), user.confirms.single().startsWith("Tap “Send”") && "irreversible send" in user.confirms.single())
        assertEquals(3, llm.requests.size)

        // refused: nothing happens and the task ends unsuccessfully
        exec.actions.clear()
        val denied = loop(ScriptedLlm(script(call("get_screen"), call("tap", """{"index":1}"""), call("finish", """{"summary":"x"}"""))), user = User(approve = false))
            .run(mutableListOf(Message.user("send it")))
        assertEquals(EndReason.DECLINED, denied.reason)
        assertTrue(exec.actions.isEmpty())
    }

    @Test fun deniedConfirmationEndsTheTaskUnsuccessfully() = runBlocking {
        // / a refusal ends the task with success=false; the model cannot report it as a success.
        val llm = ScriptedLlm(script(
            call("get_screen"),
            call("confirm_action", """{"action":"Send"}"""),
            call("tap", """{"index":1}"""),
            call("finish", """{"summary":"Okay, all done","success":true}"""),
        ))
        val conv = mutableListOf(Message.user("send"))
        val out = loop(llm, user = User(approve = false)).run(conv)
        assertTrue(exec.actions.isEmpty())
        assertFalse(out.success)
        assertEquals(EndReason.DECLINED, out.reason)
        assertTrue(out.summary, out.summary.contains("Send") && out.summary.contains("not approved"))
        assertEquals(2, llm.requests.size) // the model receives no further request
        // the history stays well-formed for a follow-up turn: the confirm call has its result
        assertTrue(conv.last().toolResults.single().text.startsWith("denied"))
    }

    class IrreversibleUser(var approve: Boolean) : UserChannel {
        val asked = mutableListOf<String>()
        override suspend fun ask(question: String, choices: List<String>): String? = null
        override suspend fun confirm(action: String): Boolean { asked += "confirm:$action"; return approve }
        override suspend fun confirmIrreversible(action: String): Boolean { asked += "irreversible:$action"; return approve }
        override fun planChanged(steps: List<PlanStep>) = Unit
    }

    private fun smsLoop(llm: ScriptedLlm, user: UserChannel, dc: com.eggman.backstage.tools.ToolsTest.FakeDeviceControl) = AgentLoop(
        llm = llm,
        registry = ToolRegistry.default(),
        config = LoopConfig(
            system = "SYS", maxActions = 20, planMode = false, maxTokens = 1000,
            retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 10, jitter = 0.0),
            // "Confirm before irreversible actions" is OFF: SMS and calls must still be put to the user.
            guard = SensitiveActionGuard(enabled = false, preAuthorized = false),
            availability = ToolRegistry.Availability(desktop = false, deviceCapabilities = setOf("sms", "call")),
        ),
        user = user,
        contextFactory = { hooks ->
            object : ToolContext by Ctx(hooks, exec) {
                override val services: DeviceServices = com.eggman.backstage.tools.ToolsTest.DcServices(dc)
            }
        },
    )

    @Test fun smsIsPutToTheUserEvenWithConfirmationsOff() = runBlocking {
        val dc = com.eggman.backstage.tools.ToolsTest.FakeDeviceControl(setOf("sms", "call"))
        val user = IrreversibleUser(approve = true)
        val llm = ScriptedLlm(script(
            call("sms_send", """{"phone_number":"+49 170 1234567","message":"Running late"}"""),
            call("finish", """{"summary":"Sent","success":true}"""),
        ))
        val out = smsLoop(llm, user, dc).run(mutableListOf(Message.user("text Anna I'm running late")))
        assertTrue(out.success)
        val action = if (PLAY_DISTRIBUTION) "Prepare an SMS draft" else "Send an SMS"
        assertEquals(listOf("irreversible:$action to +49 170 1234567: “Running late”"), user.asked)
        assertEquals(listOf("sms +49 170 1234567 Running late"), dc.calls)
    }

    @Test fun refusedSmsIsNotSentAndTheTaskFails() = runBlocking {
        val dc = com.eggman.backstage.tools.ToolsTest.FakeDeviceControl(setOf("sms", "call"))
        val user = IrreversibleUser(approve = false)
        val llm = ScriptedLlm(script(
            call("sms_send", """{"phone_number":"+49 170 1234567","message":"Running late"}"""),
            call("finish", """{"summary":"Okay, I didn't send it","success":true}"""),
        ))
        val out = smsLoop(llm, user, dc).run(mutableListOf(Message.user("text Anna I'm running late")))
        assertTrue(dc.calls.isEmpty())
        assertFalse(out.success)
        assertEquals(EndReason.DECLINED, out.reason)
        assertEquals(1, llm.requests.size)
    }

    @Test fun stuckDetectionWarnsThenAborts() = runBlocking {
        val replies = (1..10).map { call("tap", """{"x":10,"y":10}""") }.toTypedArray()
        val conv = mutableListOf(Message.user("x"))
        val out = loop(ScriptedLlm(script(*replies))).run(conv)
        assertEquals(EndReason.STUCK, out.reason)
        assertTrue(conv.any { m -> m.toolResults.any { it.text.contains(StuckDetector.WARNING) } })
        // first tap has no prior screen; then 6 identical (action, screen) repeats trigger the abort
        assertEquals(7, exec.actions.size)
    }

    @Test fun stepLimit() = runBlocking {
        exec.onAction = { a -> exec.capture = Screens.buttons("n${exec.actions.size}") }
        val replies = (1..10).map { i -> call("tap", """{"x":$i,"y":10}""") }.toTypedArray()
        val conv = mutableListOf(Message.user("x"))
        val out = loop(ScriptedLlm(script(*replies)), maxSteps = 3).run(conv)
        assertEquals(EndReason.STEP_LIMIT, out.reason)
        // Three actions ran; the fourth is refused (with a result, so the history stays well-formed).
        assertEquals(3, exec.actions.size)
        assertTrue(out.summary, out.summary.startsWith("Stopped after 3 actions"))
        assertTrue(conv.last().toolResults.single().text.contains("safety limit"))
        assertFalse(out.success)
    }

    @Test fun lookingAndWaitingDoNotUseUpTheActionBudget() = runBlocking {
        // with a budget of 3 actions, waits are free, so 3 taps still run between them (6 turns = 2 x 3)...
        exec.onAction = { exec.capture = Screens.buttons("n${exec.actions.size}") }
        val replies = listOf(
            call("tap", """{"x":1,"y":10}"""), call("wait", """{"seconds":0}"""),
            call("tap", """{"x":2,"y":10}"""), call("await_screen_change", """{"timeout_ms":1}"""),
            call("tap", """{"x":3,"y":10}"""), call("finish", """{"summary":"done","success":true}"""),
        ).toTypedArray()
        val out = loop(ScriptedLlm(script(*replies)), maxSteps = 3).run(mutableListOf(Message.user("x")))
        assertEquals(EndReason.FINISHED, out.reason)
        assertTrue(out.success)
        assertEquals(3, exec.actions.size)
    }

    @Test fun everyTurnCountsTowardsTwiceTheBudget() = runBlocking {
        //...but a task that only looks around still ends: every model turn counts towards 2 x the budget.
        val replies = (1..20).map { call("get_screen") }.toTypedArray()
        val llm = ScriptedLlm(script(*replies))
        val out = loop(llm, maxSteps = 3).run(mutableListOf(Message.user("x")))
        assertEquals(EndReason.STEP_LIMIT, out.reason)
        assertEquals(6, llm.requests.size)
        assertTrue(out.summary, out.summary.startsWith("Stopped after 6 steps"))
    }

    @Test fun transientErrorsRetriedFatalErrorsEndTask() = runTest {
        val llm = ScriptedLlm(mutableListOf(
            ScriptedLlm.error(LlmException(LlmErrorKind.OVERLOADED, "busy")),
            { _ -> text("hello") },
        ))
        assertEquals("hello", loop(llm).run(mutableListOf(Message.user("hi"))).summary)
        val fatal = ScriptedLlm(mutableListOf(ScriptedLlm.error(LlmException(LlmErrorKind.AUTH, "bad key"))))
        val out = loop(fatal).run(mutableListOf(Message.user("hi")))
        assertEquals(EndReason.LLM_ERROR, out.reason)
        assertTrue(out.summary.contains("API key"))
    }

    @Test fun truncatedReplyGetsContinueNudge() = runBlocking {
        val conv = mutableListOf(Message.user("long"))
        val out = loop(ScriptedLlm(script(text("part one", StopReason.MAX_TOKENS), text("part two")))).run(conv)
        assertEquals("part two", out.summary)
        assertTrue(conv.any { it.text.contains("cut off") })
    }

    @Test fun emptyLengthCutReplyIsRetriedWithMoreRoomAndNeverSentBack() = runBlocking {
        // A reasoning model spent its whole budget thinking: content "" with finish_reason=length.
        val llm = ScriptedLlm(script(text("", StopReason.MAX_TOKENS), call("finish", """{"summary":"ok"}""")))
        val conv = mutableListOf(Message.user("x"))
        val out = loop(llm).run(conv)
        assertEquals(EndReason.FINISHED, out.reason)
        assertEquals(1000, llm.requests[0].maxTokens)
        assertEquals(4000, llm.requests[1].maxTokens)
        assertTrue(conv.none { it.role == Role.ASSISTANT && it.text.isEmpty() && it.toolCalls.isEmpty() })
        // Exactly what an OpenAI-compatible provider would receive on the retry: no null-content assistant turn.
        val body = com.eggman.backstage.llm.OpenAiCompatClient.encodeRequest("m", llm.requests[1], com.eggman.backstage.llm.OpenAiCompatClient.Dialect())
        val msgs = body.getJSONArray("messages")
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            if (m.getString("role") == "assistant") assertTrue(m.has("tool_calls") || !m.isNull("content"))
        }
    }

    @Test fun emptyRepliesWithoutCutOffEndAsFailureAfterNudges() = runBlocking {
        val llm = ScriptedLlm(script(text(""), text(""), text("")))
        val conv = mutableListOf(Message.user("x"))
        val out = loop(llm).run(conv)
        assertEquals(EndReason.LLM_ERROR, out.reason)
        assertFalse(out.success)
        assertTrue(conv.none { it.role == Role.ASSISTANT })
    }

    @Test fun callsAfterFinishAreSkipped() = runBlocking {
        val conv = mutableListOf(Message.user("x"))
        loop(ScriptedLlm(script(calls(listOf("finish" to """{"summary":"ok"}""", "tap" to """{"x":1,"y":1}"""))))).run(conv)
        assertTrue(exec.actions.isEmpty())
        assertTrue(conv.last().toolResults[1].text.startsWith("Skipped"))
    }

    @Test fun oldScreensAndImagesArePrunedInRequests() = runBlocking {
        val replies = mutableListOf(
            call("screenshot"), call("get_screen"), call("screenshot"), call("get_screen"), call("screenshot"), call("get_screen"),
            call("finish", """{"summary":"ok"}"""),
        )
        val llm = ScriptedLlm(script(*replies.toTypedArray()))
        loop(llm).run(mutableListOf(Message.user("x")))
        val lastReq = llm.requests.last().messages
        val images = lastReq.count { it.hasImage }
        assertEquals(2, images)
        val fullScreens = lastReq.flatMap { it.toolResults }.count { ContextManager.SCREEN.containsMatchIn(it.text) && !it.text.contains("omitted") }
        assertEquals(2, fullScreens)
    }
}

class ContextAndGuardsTest {
    private fun result(id: String, text: String, img: Boolean = false) =
        Message(Role.USER, listOf(Part.ToolResult(id, text, if (img) Part.Image("x") else null)))

    @Test fun contextManagerCollapsesOldScreens() {
        val screen = "Tapped\n\nScreen phone (display 0, 1080x2400)\n[0] Button \"A\"\n"
        val msgs = listOf(Message.user("go"), result("1", screen, img = true), result("2", screen, img = true), result("3", screen, img = true))
        val out = ContextManager(keepScreens = 1, keepImages = 1).prepare(msgs)
        assertEquals(1, out.count { it.hasImage })
        assertTrue(out[1].toolResults[0].text.startsWith("Tapped\n[Screen phone"))
        assertTrue(out[1].toolResults[0].text.contains("older screen listing omitted"))
        assertEquals(screen, out[3].toolResults[0].text)
        // finished-task compaction keeps only first lines and no images
        val compact = ContextManager().compactFinished(msgs)
        assertTrue(compact.none { it.hasImage })
        assertEquals("Tapped […]", compact[1].toolResults[0].text)
    }

    @Test fun budgetShortensWithoutDroppingMessages() {
        val big = "x".repeat(5_000)
        val msgs = listOf(Message.user("go")) + (1..20).map { result("$it", "line one\n$big") }
        val out = ContextManager(budgetChars = 20_000).prepare(msgs)
        assertEquals(msgs.size, out.size)
        assertTrue(ContextManager.size(out) < ContextManager.size(msgs))
        assertEquals(msgs.last(), out.last())
    }

    @Test fun stuckDetector() {
        val d = StuckDetector(warnAfter = 3, abortAfter = 5)
        val a = org.json.JSONObject("""{"x":1,"y":2}""")
        val b = org.json.JSONObject("""{"y":2,"x":1.0}""")
        assertEquals(StuckDetector.Verdict.OK, d.record("tap", a, "s1", "s1"))
        assertEquals(StuckDetector.Verdict.OK, d.record("tap", b, "s1", "s1"))
        assertEquals(StuckDetector.Verdict.WARN, d.record("tap", a, "s1", "s1"))
        d.record("tap", a, "s1", "s1")
        assertEquals(StuckDetector.Verdict.ABORT, d.record("tap", a, "s1", "s1"))
        d.reset()
        // progress (screen changes) never trips it
        repeat(10) { i -> assertEquals(StuckDetector.Verdict.OK, d.record("scroll", a, "s$i", "s${i + 1}")) }
    }

    @Test fun quantityHints() {
        assertEquals(listOf(2), QuantityHints.extract("Delete 2 photos from yesterday").mapNotNull { it.count })
        assertTrue(QuantityHints.reminder("send three messages to Tom")!!.contains("exactly 3"))
        assertTrue(QuantityHints.reminder("Lösche zwei Fotos")!!.contains("exactly 2"))
        assertEquals(null, QuantityHints.reminder("set a timer for 5 minutes"))
        assertEquals(null, QuantityHints.reminder("open settings"))
        assertTrue(QuantityHints.extract("archive all emails").any { it.count == null })
    }

    @Test fun systemPromptSections() {
        val base = PromptContext(TargetKind.DESKTOP, "the Dextop desktop (display 7)", true, planMode = false, confirmSensitive = true,
            interactive = true, vision = true, nowMs = 0, zone = java.time.ZoneId.of("UTC"), locale = java.util.Locale.GERMANY, customInstructions = "Call me Sam.")
        val p = SystemPrompt.build(base)
        assertTrue(p.contains("display 7"))
        assertTrue(p.contains("confirm_action"))
        assertTrue(p.contains("untrusted"))
        assertTrue(p.contains("Quantities are literal"))
        assertTrue(p.contains("Call me Sam."))
        assertTrue(p.contains("de-DE"))
        assertFalse(p.contains("## Plan mode"))
        assertTrue(SystemPrompt.build(base.copy(planMode = true)).contains("## Plan mode"))
        val ni = SystemPrompt.build(base.copy(interactive = false, confirmSensitive = false, target = TargetKind.PHONE, targetDescription = null))
        assertTrue(ni.contains("not watching"))
        assertFalse(ni.contains("call confirm_action"))
        assertTrue(ni.contains("phone's own screen"))
    }

    @Test fun readinessAndTimerAndApproval() {
        val ok = ReadinessCheck.compute(ReadinessInputs(null, true, true, true, true, true, true, true, 34))
        assertTrue(ReadinessCheck.ready(ok))
        assertTrue(ok.problems.isEmpty())
        val bad = ReadinessCheck.compute(ReadinessInputs("No API key", false, false, true, false, true, false, false, 34))
        assertFalse(ReadinessCheck.ready(bad))
        assertTrue(bad.problems.any { it.contains("No API key") })
        assertTrue(bad.problems.any { it.contains("Allow restricted settings") })
        assertTrue(bad.problems.any { it.contains("screen is off") })
        assertTrue(bad.problems.any { it.contains("wireless debugging") })
        val locked = ReadinessCheck.compute(ReadinessInputs(null, true, true, false, false, true, true, false, 34))
        assertTrue(locked.problems.single().contains("locked"))

        val t = ActiveTimer(1_000)
        t.pause(3_000)
        t.resume(10_000)
        assertEquals(5_000, t.activeMs(13_000))

        assertTrue(Approval.parse("Yes, send it"))
        assertTrue(Approval.parse("approve"))
        assertTrue(Approval.parse("Ja"))
        assertFalse(Approval.parse("no"))
        assertFalse(Approval.parse("don't"))
        assertFalse(Approval.parse(null))
        assertFalse(Approval.parse("maybe later"))
    }
}
