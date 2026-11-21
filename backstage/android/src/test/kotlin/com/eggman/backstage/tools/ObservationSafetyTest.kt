package com.eggman.backstage.tools

import com.eggman.backstage.agent.*
import com.eggman.backstage.exec.*
import com.eggman.backstage.llm.Message
import com.eggman.backstage.testutil.*
import com.eggman.backstage.testutil.ScriptedLlm.Companion.text
import com.eggman.backstage.mcp.BackstageMcpTools
import com.eggman.backstage.agent.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ObservationSafetyTest {
    private class Exec(val fake: FakeExecutor = FakeExecutor(), maxElements: Int = 220, maxChars: Int = 14_000) : Executor by fake {
        val serializer = ScreenSerializer(maxElements = maxElements, maxChars = maxChars)
        override suspend fun screen(): ScreenState {
            fake.lastCapture = fake.capture
            return serializer.serialize(fake.capture, kind.label, lastScreen).also { fake.lastScreen = it }
        }
        override suspend fun awaitSettleOutcome(maxMs: Long, expectChangeMs: Long) = SettleOutcome(false, true, 0)
    }
    private class Ctx(val exec: Exec, override val hooks: AgentHooks? = RecordingHooks()) : ToolContext by TestToolContext() {
        override val visionEnabled = false
        override val stabilityMs = 0L
        override suspend fun executor(kind: TargetKind?) = exec
    }
    private fun capture(version: Int = 0, field: String? = null) = Screens.app(*(0 until 20).map { i ->
        if (i == 0 && field != null) UiNode(className = "android.widget.EditText", text = field, hint = "security code",
            editable = true, focused = true, bounds = Box(0, 0, 900, 40))
        else Screens.node("Row $i" + if (i == 5) " version $version" else "", cls = "android.widget.Button", clickable = true,
            b = Box(0, i * 50, 900, i * 50 + 40))
    }.toTypedArray())
    private suspend fun show(ctx: Ctx) = GetScreenTool().run(ctx, Args(JSONObject()))
    private suspend fun act(ctx: Ctx, message: String = "Tapped") = Observe.after(ctx, ctx.exec, ActionResult.ok(message))

    @Test fun unchangedReplyDoesNotRestartTheDiffResyncCadence() = runTest {
        val exec = Exec(); val ctx = Ctx(exec)
        exec.fake.capture = capture(); show(ctx)
        repeat(5) { exec.fake.capture = capture(it + 1); assertTrue(act(ctx).text.contains("Changes since")) }
        assertTrue(act(ctx).text.contains("No visible change"))
        exec.fake.capture = capture(6)
        assertTrue(act(ctx).text.contains("Elements [index]"))
    }

    @Test fun secretTypingMasksLaterDiffAndFullListings() = runTest {
        val exec = Exec(); val ctx = Ctx(exec)
        exec.fake.capture = capture(field = ""); show(ctx)
        exec.fake.onAction = { exec.fake.capture = capture(field = "4821") }
        val typed = TypeTextTool().run(ctx, Args(JSONObject().put("index", 0).put("text", "4821").put("secret", true)))
        assertFalse(typed.text.contains("4821"))
        val diff = act(ctx)
        assertTrue(diff.text, diff.text.contains("Changes since"))
        assertFalse(diff.text, diff.text.contains("4821"))
        assertTrue(diff.text.contains("••••"))
        val full = show(ctx)
        assertFalse(full.text, full.text.contains("4821"))
    }

    @Test fun successfulCompressionForcesTheNextObservationToBeFull() = runTest {
        val exec = Exec(); val ctx = Ctx(exec)
        exec.fake.capture = capture(); show(ctx)
        val conversation = mutableListOf(Message.user("Task"))
        repeat(20) { conversation += Message.assistant("older answer"); conversation += Message.user("older follow-up") }
        assertTrue(HistoryCompressor.compress(ScriptedLlm(script(text("Summary"))), conversation))
        exec.fake.capture = capture(1)
        assertTrue(act(ctx).text.contains("Elements [index]"))
    }

    @Test fun truncatedBaselineOrCurrentListingAlwaysForcesAFullListing() = runTest {
        val exec = Exec(maxElements = 20); val ctx = Ctx(exec)
        val base = capture()
        fun withExtra(cap: ScreenCapture) = cap.copy(windows = cap.windows.map { w ->
            w.copy(root = w.root!!.copy(children = w.root.children + Screens.node("Extra", b = Box(0, 1600, 900, 1640)))) })
        exec.fake.capture = withExtra(base); show(ctx)
        exec.fake.capture = capture(1)
        assertTrue("baseline truncated", act(ctx).text.contains("Elements [index]"))
        show(ctx)
        exec.fake.capture = withExtra(capture(2))
        assertTrue("current truncated", act(ctx).text.contains("Elements [index]"))
    }

    @Test fun diffReplyKeepsCoverageNotes() {
        val state = ScreenSerializer().serialize(capture(), "phone").let { it.copy(text = it.text.replace(
            ScreenSerializer.CLOSE_TAG, "(3 elements hidden behind other windows)\n(2 more elements not listed — scroll)\n${ScreenSerializer.CLOSE_TAG}")) }
        val rendered = ScreenSerializer.withChanges(state, "~ [5] Text \"changed\"")
        assertTrue(rendered.contains("3 elements hidden"))
        assertTrue(rendered.contains("2 more elements not listed"))
    }

    @Test fun characterTruncationAlsoForcesAFullListing() = runTest {
        val exec = Exec(maxChars = 400); val ctx = Ctx(exec)
        exec.fake.capture = capture(); show(ctx)
        assertTrue(exec.lastScreen!!.omittedElements > 0)
        exec.fake.capture = capture(1)
        val result = act(ctx)
        assertTrue(result.text.contains("Elements [index]"))
        assertFalse(result.text.contains("Changes since"))
    }

    @Test fun errorsWaitsAndResetRespectRegisteredSecrets() = runTest {
        val exec = Exec(); val ctx = Ctx(exec)
        exec.fake.capture = capture(); show(ctx)
        Observe.registerSecret(ctx, exec, "4821")
        exec.fake.capture = capture(field = "4821")
        val error = Observe.after(ctx, exec, ActionResult.fail("Could not tap 4821"))
        assertFalse(error.text, error.text.contains("4821"))
        val wait = WaitTool().run(ctx, Args(JSONObject().put("seconds", 0.5)))
        assertFalse(wait.text, wait.text.contains("4821"))
        val until = AwaitScreenChangeTool().run(ctx, Args(JSONObject().put("timeout_ms", 1000)))
        assertFalse(until.text, until.text.contains("4821"))
        Observe.reset(exec)
        assertTrue(show(ctx).text.contains("4821"))
    }

    @Test fun registrationWithoutAnEarlierShownScreenSurvivesTheFirstRead() = runTest {
        val exec = Exec(); val ctx = Ctx(exec)
        Observe.registerSecret(ctx, exec, "4821")
        exec.fake.capture = capture(field = "4821")
        assertFalse(act(ctx).text.contains("4821"))
    }

    @Test fun aNewTaskDoesNotInheritThePreviousTasksSecrets() = runTest {
        val exec = Exec(); val old = Ctx(exec)
        Observe.registerSecret(old, exec, "4821")
        exec.fake.capture = capture(field = "4821")
        assertFalse(show(old).text.contains("4821"))
        val next = Ctx(exec)
        assertTrue(show(next).text.contains("4821"))
    }

    @Test fun successfulCompressionKeepsMaskingSecretsInTheSameTask() = runTest {
        val exec = Exec(); val ctx = Ctx(exec)
        Observe.registerSecret(ctx, exec, "4821")
        exec.fake.capture = capture(field = "4821"); show(ctx)
        val conversation = mutableListOf(Message.user("Task"))
        repeat(20) { conversation += Message.assistant("earlier"); conversation += Message.user("continue") }
        assertTrue(HistoryCompressor.compress(ScriptedLlm(script(text("Summary"))), conversation))
        val result = act(ctx)
        assertTrue(result.text.contains("Elements [index]"))
        assertFalse(result.text, result.text.contains("4821"))
    }

    @Test fun mcpPollingKeepsTheAgentBaselineAndMasksBothStateFormatsAndJson() = runTest {
        val exec = Exec(); val ctx = Ctx(exec)
        exec.fake.capture = capture(field = "4821"); show(ctx)
        Observe.registerSecret(ctx, exec, "4821")
        val base = FakeEnv(this, ScriptedLlm(script(text("unused"))))
        val env = object : AgentEnvironment by base {
            override suspend fun executor(kind: TargetKind): Executor = exec
            override fun peekExecutor(kind: TargetKind): Executor = exec
        }
        val controller = object : AgentController {
            override val state = MutableStateFlow(AgentState(queueSize = 1)) // an agent is already busy
            override fun submit(request: TaskRequest): TaskHandle = error("unused")
            override fun answer(text: String) = Unit
            override fun cancel(taskId: String?) = Unit
            override fun readiness() = Readiness()
            override suspend fun history(limit: Int) = emptyList<TaskSnapshot>()
        }
        val tools = BackstageMcpTools({ controller }, env)
        for (format in listOf("list", "markup")) {
            val state = tools.call("get_screen_state", JSONObject().put("target", "phone").put("format", format))
            assertFalse(state.isError)
            assertFalse(state.content.toString(), state.content.any { it.text?.contains("4821") == true })
            assertFalse(state.structured.toString().contains("4821"))
            assertEquals("••••", state.structured!!.getJSONArray("elements").getJSONObject(0).getString("text"))
            assertEquals(1080, state.structured!!.getInt("width"))
        }
        exec.fake.capture = capture(1, field = "4821")
        val changed = act(ctx)
        assertTrue(changed.text, changed.text.contains("Changes since"))
        assertFalse(changed.text.contains("4821"))

        // Low-level MCP calls invalidate baselines too, while retaining their registered secret for later state reads.
        controller.state.value = AgentState()
        env.settings.mcpExtendedTools = true
        exec.fake.onAction = { exec.fake.capture = capture(field = "9367") }
        val typed = tools.call("type_text", JSONObject().put("target", "phone").put("index", 0).put("text", "9367").put("secret", true))
        assertFalse(typed.content.toString(), typed.isError)
        val afterDirect = tools.call("get_screen_state", JSONObject().put("target", "phone").put("format", "list"))
        assertFalse(afterDirect.content.any { it.text?.contains("9367") == true })
        assertFalse(afterDirect.structured.toString().contains("9367"))
    }
}
