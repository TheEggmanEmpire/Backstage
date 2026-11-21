package com.eggman.backstage.tools

import com.eggman.backstage.agent.*
import com.eggman.backstage.exec.*
import com.eggman.backstage.llm.Message
import com.eggman.backstage.llm.Part
import com.eggman.backstage.llm.RetryPolicy
import com.eggman.backstage.omp.engine.OmpToolPolicy
import com.eggman.backstage.testutil.*
import com.eggman.backstage.testutil.ScriptedLlm.Companion.call
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ShownScreenBaselineTest {
    private class CapturingExecutor(val fake: FakeExecutor = FakeExecutor()) : Executor by fake {
        private val serializer = ScreenSerializer()
        var outcome = SettleOutcome(false, true, 0)
        override suspend fun screen(): ScreenState {
            fake.lastCapture = fake.capture
            return serializer.serialize(fake.capture, kind.label, lastScreen).also { fake.lastScreen = it }
        }
        override suspend fun awaitSettleOutcome(maxMs: Long, expectChangeMs: Long) = outcome
    }
    private open class Context(val exec: Executor, override val hooks: AgentHooks? = null) : ToolContext by TestToolContext(), PeekableContext {
        override val currentTarget = TargetKind.PHONE
        override val visionEnabled = false
        override val stabilityMs = 0L
        override suspend fun executor(kind: TargetKind?) = exec
        override fun peek(kind: TargetKind) = exec
    }
    private fun capture(changed: Set<Int> = emptySet(), extra: String? = null) = Screens.app(*((0 until 20).map { i ->
        Screens.node("Row $i" + if (i in changed) " updated" else "", cls = "android.widget.Button", clickable = true,
            b = Box(0, i * 50, 900, i * 50 + 30))
    } + listOfNotNull(extra?.let { Screens.node(it, b = Box(0, 1500, 900, 1530)) })).toTypedArray())
    private suspend fun show(ctx: Context) = GetScreenTool().run(ctx, Args(JSONObject()))
    private suspend fun act(ctx: Context) = Observe.after(ctx, ctx.exec, ActionResult.ok("Tapped"))

    @Test fun anInternalCaptureCannotEstablishAShownBaseline() = runTest {
        val exec = CapturingExecutor()
        val ctx = Context(exec)
        exec.fake.capture = capture()
        exec.screen()
        exec.fake.capture = capture(setOf(5))
        val result = act(ctx)
        assertTrue(result.text.contains("Elements [index]"))
        assertFalse(result.text.contains("Changes since"))
    }

    @Test fun internalReadsCannotHideChangesSinceTheShownListing() = runTest {
        val exec = CapturingExecutor()
        val ctx = Context(exec)
        exec.fake.capture = capture()
        show(ctx)
        exec.fake.capture = capture(setOf(5))
        exec.screen() // fresh-screen guard / desktop_window_list / another internal reader
        exec.fake.capture = capture(setOf(5, 6))
        val result = act(ctx)
        assertTrue(result.text, result.text.contains("Changes since"))
        assertTrue(result.text.contains("~ [5]"))
        assertTrue(result.text.contains("~ [6]"))
    }

    @Test fun aDiffBecomesTheNextShownBaseline() = runTest {
        val exec = CapturingExecutor()
        val ctx = Context(exec)
        exec.fake.capture = capture(); show(ctx)
        exec.fake.capture = capture(setOf(5)); assertTrue(act(ctx).text.contains("~ [5]"))
        exec.fake.capture = capture(setOf(5, 6))
        val result = act(ctx)
        assertTrue(result.text.contains("~ [6]"))
        assertFalse(result.text.contains("~ [5]"))
    }

    @Test fun hiddenObservationsDoNotAdvanceTheShownBaseline() = runTest {
        val exec = CapturingExecutor()
        val ctx = Context(exec)
        exec.fake.capture = capture(); show(ctx)
        exec.fake.capture = capture(setOf(5))
        val hidden = Observe.after(ctx, exec, ActionResult.ok("Entered hidden input"), showScreen = false)
        assertEquals("Entered hidden input", hidden.text)
        exec.fake.capture = capture(setOf(5, 6))
        val result = act(ctx)
        assertTrue(result.text, result.text.contains("~ [5]") && result.text.contains("~ [6]"))
    }

    @Test fun anotherTaskCannotInheritTheSameExecutorsBaseline() = runTest {
        val exec = CapturingExecutor()
        val first = Context(exec, RecordingHooks())
        exec.fake.capture = capture(); show(first)
        exec.fake.capture = capture(setOf(5))
        val nextTask = Context(exec, RecordingHooks())
        assertTrue(act(nextTask).text.contains("Elements [index]"))
    }

    @Test fun directClientsAlwaysGetFullListingsAndInvalidateAgentBaseline() = runTest {
        val exec = CapturingExecutor()
        val agent = Context(exec)
        val direct = object : Context(exec), FullScreenListingContext {}
        exec.fake.capture = capture(); show(agent)
        exec.fake.capture = capture(setOf(5))
        repeat(3) { assertTrue(act(direct).text.contains("Elements [index]")) }
        exec.fake.capture = capture(setOf(5, 6))
        assertTrue(act(agent).text.contains("Elements [index]"))
    }

    @Test fun nestedHiddenScopesRestoreVisibilityAndNeverCommitInnerScreens() = runTest {
        val exec = CapturingExecutor()
        val ctx = Context(exec)
        exec.fake.capture = capture(); show(ctx)
        Observe.withoutShowing(exec) {
            exec.fake.capture = capture(setOf(5))
            assertEquals("Tapped", act(ctx).text)
            Observe.withoutShowing(exec) { assertEquals("Tapped", act(ctx).text) }
            assertEquals("Tapped", act(ctx).text)
        }
        val result = act(ctx)
        assertTrue(result.text, result.text.contains("~ [5]"))
    }

    private fun config() = LoopConfig("SYS", 20, false, 1000, RetryPolicy(1), SensitiveActionGuard(false, false),
        ToolRegistry.Availability(desktop = false))
    private val sequence = """{"steps":[{"tool":"tap","args":{"x":20,"y":20}},{"tool":"tap","args":{"x":20,"y":70}}]}"""
    private fun prepareSequence(): CapturingExecutor = CapturingExecutor().apply {
        fake.capture = capture()
        var step = 0
        fake.onAction = { fake.capture = if (++step == 1) capture(extra = "Added in step one") else capture(setOf(5), "Added in step one") }
    }

    @Test fun nativeSequenceReturnsFullScreenIncludingEarlierSteps() = runTest {
        val exec = prepareSequence()
        val llm = ScriptedLlm(script(call("get_screen"), call("act_sequence", sequence), call("finish", """{"summary":"done"}""")))
        val loop = AgentLoop(llm, ToolRegistry.default(), config(), AgentLoopTest.User(), { Context(exec, it) })
        val conversation = mutableListOf(Message.user("two taps"))
        loop.run(conversation)
        val result = conversation.flatMap { it.toolResults }.single { it.name == "act_sequence" }
        assertTrue(result.text, result.text.contains("Elements [index]"))
        assertTrue(result.text.contains("Added in step one"))
        assertTrue(result.text.contains("Row 5 updated"))
    }

    @Test fun ompSequenceReturnsFullScreenIncludingEarlierSteps() = runTest {
        val exec = prepareSequence()
        val policy = OmpToolPolicy(ToolRegistry.default(), config(), AgentLoopTest.User(), { Context(exec, it) }, object : LoopListener {})
        policy.execute(Part.ToolCall("get", "get_screen", JSONObject()))
        val result = policy.execute(Part.ToolCall("sequence", "act_sequence", JSONObject(sequence)))
        assertTrue(result.text, result.text.contains("Elements [index]"))
        assertTrue(result.text.contains("Added in step one"))
        assertTrue(result.text.contains("Row 5 updated"))
    }
}
