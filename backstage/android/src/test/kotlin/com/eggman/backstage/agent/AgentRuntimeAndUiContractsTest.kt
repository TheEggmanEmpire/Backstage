package com.eggman.backstage.agent

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.automation.BroadcastApi
import com.eggman.backstage.automation.PluginProtocol
import com.eggman.backstage.exec.Box
import com.eggman.backstage.exec.DesktopExecutor
import com.eggman.backstage.exec.DesktopKeyPolicy
import com.eggman.backstage.exec.MiniTileOverlay
import com.eggman.backstage.exec.OverlayController
import com.eggman.backstage.exec.OverlayHooks
import com.eggman.backstage.exec.ScreenMarkup
import com.eggman.backstage.exec.ScreenSerializer
import com.eggman.backstage.exec.TargetDecision
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.exec.UiNode
import com.eggman.backstage.exec.UiWindow
import com.eggman.backstage.exec.WindowKind
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.mcp.BackstageMcpTools
import com.eggman.backstage.mcp.McpAliases
import com.eggman.backstage.notify.TaskNotificationModel
import com.eggman.backstage.testutil.FakeDesktopHost
import com.eggman.backstage.testutil.FakeServices
import com.eggman.backstage.testutil.Screens
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.ScriptedLlm.Companion.call
import com.eggman.backstage.testutil.ScriptedLlm.Companion.text
import com.eggman.backstage.testutil.script
import com.eggman.backstage.tools.DesktopStatusReport
import com.eggman.backstage.tools.MemoryItem
import com.eggman.backstage.tools.ToolRegistry
import com.eggman.backstage.ui.Composer
import com.eggman.backstage.ui.Fixtures
import com.eggman.backstage.ui.TimeLimitChoices
import com.eggman.backstage.ui.licenses.Licenses
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agent lifecycle, tool permissions and user-interface invariants. */
class AgentRuntimeAndUiContractsTest {
    @After fun tearDown() {
        OverlayHooks.controller = null
    }

    // ----  -----------------------------------------------------------------------------------------------

    @Test fun completionIntentNeverGoesBackIntoThisApp() {
        val own = "com.eggman.backstage"
        // Explicit completion to the automation app: pinned to it.
        assertEquals("net.dinglisch.android.taskerm", PluginProtocol.completionPackage("net.dinglisch.android.taskerm", null, null, own))
        assertEquals("net.dinglisch.android.taskerm", PluginProtocol.completionPackage(null, "net.dinglisch.android.taskerm", null, own))
        // Redirects into Backstage itself (notification answers, schedule alarms) are refused.
        assertNull(PluginProtocol.completionPackage(own, null, null, own))
        assertNull(PluginProtocol.completionPackage(null, own, "net.dinglisch.android.taskerm", own))
        // Component and package naming different apps: refused.
        assertNull(PluginProtocol.completionPackage("a.b", "c.d", null, own))
        // Implicit: only to the broadcast's sender, never to nobody-knows-whom.
        assertEquals("com.arlosoft.macrodroid", PluginProtocol.completionPackage(null, null, "com.arlosoft.macrodroid", own))
        assertNull(PluginProtocol.completionPackage(null, null, null, own))
        assertNull(PluginProtocol.completionPackage(null, null, own, own))
    }

    @Test fun notificationAnswersMustNameTheirQuestion() {
        val q = Fixtures.snapshot(id = "t1", status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Pay 20 EUR?", true))
        val key = TaskNotificationModel.questionKey(q)
        assertTrue(TaskNotificationModel.answerApplies(q, "t1", key))
        assertFalse("an answer without a question key never applies", TaskNotificationModel.answerApplies(q, "t1", null))
    }

    // ----  -----------------------------------------------------------------------------------------------

    @Test fun passwordTextNeverLeavesTheDeviceInMarkupOrJson() {
        val pw = UiNode(className = "android.widget.EditText", text = "hunter2", password = true, editable = true, focusable = true,
            bounds = Box(0, 100, 1080, 200), packageName = "com.example")
        val capture = Screens.app(pw, Screens.node(text = "Sign in", cls = "android.widget.Button", b = Box(0, 300, 1080, 400), clickable = true))
        val markup = ScreenMarkup.render(capture)
        assertFalse(markup, markup.contains("hunter2"))
        assertTrue(markup, markup.contains(UiNode.PASSWORD_MASK))
        val state = ScreenSerializer().serialize(capture, "phone")
        val json = ScreenSerializer.toJson(state).toString()
        assertFalse(json, json.contains("hunter2"))
        assertFalse(state.text.contains("hunter2"))
        assertEquals(UiNode.PASSWORD_MASK, UiNode.maskedText("x", true))
        assertEquals("", UiNode.maskedText("", true))
        assertEquals("plain", UiNode.maskedText("plain", false))
    }

    // ----  ---------------------------------------------------------------------------------------------

    @Test fun desktopRefusesKeysThatActOnTheWholePhone() = runTest {
        val host = FakeDesktopHost()
        val exec = DesktopExecutor(host, host.geometry()!!, emptySet())
        if (PLAY_DISTRIBUTION) {
            for (key in listOf("power", "camera", "media_play_pause", "media_next", "ctrl+power", "enter", "volume_up")) {
                assertFalse(key, exec.key(key).ok)
            }
            assertTrue(host.calls.isEmpty())
        } else {
            for (k in listOf("power", "camera", "media_play_pause", "media_next", "ctrl+power")) {
                val r = exec.key(k)
                assertFalse(k, r.ok)
            }
            assertFalse(host.calls.any { it.startsWith("key 26") || it.startsWith("key 27") || it.startsWith("key 85") })
            assertTrue(exec.key("enter").ok)
            assertTrue(host.calls.contains("key 66 0"))
            assertTrue(exec.key("volume_up").ok)
            assertTrue(DesktopKeyPolicy.refusal(187)!!.isNotBlank())
            assertNull(DesktopKeyPolicy.refusal(66))
        }
    }

    @Test fun desktopRecentsNeverFallsBackToThePhonesOverview() = runTest {
        val fake = FakeDesktopHost()
        val host = object : DesktopHost by fake {
            override fun globalAction(action: String) = false
        }
        val exec = DesktopExecutor(host, fake.geometry()!!, emptySet())
        val r = exec.key("recents")
        assertFalse(r.ok)
        assertEquals(if (PLAY_DISTRIBUTION) "Use an on-screen control on this display." else DesktopKeyPolicy.RECENTS_UNAVAILABLE, r.message)
        assertFalse(fake.calls.any { it.startsWith("key 187") })
    }

    // ----  ------------------------------------------------------------------------------------------------

    @Test fun unattendedTaskWithoutAccessibilityFailsBeforeAnyModelCall() = runTest {
        val llm = ScriptedLlm(script(text("should not be called")))
        val env = FakeEnv(this, llm)
        env.a11y = false
        val c = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        val s = c.submit(TaskRequest("mute the phone", Source.TASKER, interactive = false)).await()
        assertEquals(TaskStatus.FAILED, s.status)
        assertTrue(s.result!!, s.result!!.startsWith("Not started"))
        assertTrue("no provider request", llm.requests.isEmpty())
        // Chat still answers (a question needs no screen).
        val chat = c.submit(TaskRequest("what is 2+2?", Source.CHAT)).await()
        assertEquals(TaskStatus.SUCCEEDED, chat.status)
        assertEquals(1, llm.requests.size)
    }

    @Test fun unattendedTaskWithoutTargetFailsWithTheSetupHint() {
        val i = ReadinessInputs(null, true, false, true, false, true, true, true, 34)
        val p = ReadinessCheck.unattendedProblem(TargetDecision(null, false, "The desktop is not running."), i)!!
        assertTrue(p, p.contains("The desktop is not running."))
        assertNull(ReadinessCheck.unattendedProblem(TargetDecision(TargetKind.PHONE, false, "ok"), i))
        assertTrue(ReadinessCheck.unattendedProblem(TargetDecision(TargetKind.PHONE, false, "ok"), i.copy(accessibilityEnabled = false))!!
            .contains("ccessibility"))
    }

    // ----  ------------------------------------------------------------------------------------------------

    @Test fun busyStateAndRefusalResults() {
        assertFalse(AgentState().busy)
        assertTrue(AgentState(queueSize = 1).busy)
        assertTrue(AgentState(current = Fixtures.snapshot(status = TaskStatus.RUNNING)).busy)
        assertTrue(AgentState(current = Fixtures.snapshot(status = TaskStatus.WAITING_FOR_USER)).busy)
        assertFalse(AgentState(current = Fixtures.snapshot(status = TaskStatus.SUCCEEDED)).busy)
        val vars = PluginProtocol.busyVariables()
        assertEquals("false", vars["%success"])
        assertEquals("busy", vars["%error"])
        assertEquals("BUSY", vars["%bs_status"])
        val extras = BroadcastApi.busyExtras("r1")
        assertEquals("BUSY", extras[BroadcastApi.EXTRA_STATUS])
        assertEquals("busy", extras[BroadcastApi.EXTRA_ERROR])
        assertEquals("r1", extras[BroadcastApi.EXTRA_REQUEST_ID])
    }

    // ----  ------------------------------------------------------------------------------------------------

    @Test fun promptHasPrecedenceAndMemoryDigest() {
        val digest = MemoryDigest.build(listOf(MemoryItem("1", "Prefers   Signal for family", "user", 2), MemoryItem("2", "Lives in Berlin", "user", 1)))
        assertEquals("- Prefers Signal for family\n- Lives in Berlin", digest)
        assertEquals("", MemoryDigest.build(emptyList()))
        val many = MemoryDigest.build((1..100).map { MemoryItem("$it", "fact number $it ".repeat(10), "u", it.toLong()) })
        assertTrue(many.length <= MemoryDigest.MAX_CHARS)
        val prompt = SystemPrompt.build(PromptContext(TargetKind.PHONE, null, false, false, true, true, true, 0L,
            java.time.ZoneId.of("UTC"), java.util.Locale.US, customInstructions = "Reply in English", memoryDigest = digest))
        val precedence = prompt.indexOf("## What takes precedence")
        assertTrue(precedence in 0 until prompt.indexOf("## How to work"))
        assertTrue(prompt.indexOf("## Notes you saved about the user") > prompt.indexOf("## The user's personal instructions"))
        assertTrue(prompt.contains("Prefers Signal for family"))
        assertFalse(SystemPrompt.build(PromptContext(TargetKind.PHONE, null, false, false, true, true, true, 0L,
            java.time.ZoneId.of("UTC"), java.util.Locale.US)).contains("## Notes you saved"))
    }

    @Test fun planSectionShowsMarkersProgressAndTheCurrentStep() {
        val text = PlanSection.render(listOf(PlanStep("Open Gmail", "done"), PlanStep("Find the invoice", "in_progress"), PlanStep("Reply", "pending")))
        assertTrue(text, text.contains("[x] 1. Open Gmail"))
        assertTrue(text.contains("[>] 2. Find the invoice"))
        assertTrue(text.contains("[ ] 3. Reply"))
        assertTrue(text.contains("Progress: 1 of 3 done; now: step 2."))
        assertTrue(PlanSection.render(emptyList()).contains("No plan yet"))
    }

    @Test fun planModeRequestsCarryTheLivePlanEveryStep() = runTest {
        val llm = ScriptedLlm(script(
            call("update_plan", """{"steps":[{"title":"Open settings","status":"in_progress"},{"title":"Turn on wifi","status":"pending"}]}"""),
            call("update_plan", """{"steps":[{"title":"Open settings","status":"done"},{"title":"Turn on wifi","status":"done"}]}"""),
            call("finish", """{"summary":"Done","success":true}"""),
        ))
        val env = FakeEnv(this, llm)
        val c = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        c.submit(TaskRequest("turn on wifi", Source.CHAT, planMode = true)).await()
        // the live plan rides with the newest user turn; the system prompt stays the same (cache prefix).
        assertTrue(llm.requests[0].messages.last().text.contains("No plan yet"))
        assertTrue(llm.requests[1].messages.last().text, llm.requests[1].messages.last().text.contains("[>] 1. Open settings"))
        assertTrue(llm.requests[2].messages.last().text.contains("Progress: 2 of 2 done"))
        assertEquals(1, llm.requests.map { it.system }.distinct().size)
    }

    @Test fun builtInAppRulesExistForTheMessengers() {
        for (p in listOf("com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca")) {
            assertTrue(p, AppRuleRegistry.rulesFor(p)!!.contains("chat header"))
        }
        assertNull(AppRuleRegistry.rulesFor("com.example"))
    }

    @Test fun builtInRulesAreShownWhenTheAppComesToTheFront() = runTest {
        val llm = ScriptedLlm(script(call("get_screen"), call("finish", """{"summary":"ok","success":true}""")))
        val env = FakeEnv(this, llm)
        env.phone.capture = Screens.app(Screens.node(text = "Chats", b = Box(0, 0, 500, 100)), pkg = "com.whatsapp")
        env.settings.appRules = mapOf("com.whatsapp" to "Always sign with -B")
        val c = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        c.submit(TaskRequest("check whatsapp", Source.CHAT)).await()
        val toolResults = llm.requests[1].messages.flatMap { it.parts }.filterIsInstance<com.eggman.backstage.llm.Part.ToolResult>()
        val shown = toolResults.joinToString { it.text }
        assertTrue(shown, shown.contains("Notes for this app (com.whatsapp)"))
        assertTrue(shown.indexOf("Always sign with -B") > shown.indexOf("Notes for this app"))
    }

    // ----  ------------------------------------------------------------------------------------------------

    @Test fun desktopStatusReportsGeometryAndPresentation() = runTest {
        val host = FakeDesktopHost()
        val j = DesktopStatusReport.json(host, com.eggman.backstage.exec.WindowInfo("Files", "com.files", Box(0, 0, 10, 10), true))
        assertTrue(j.getBoolean("active"))
        assertEquals(7, j.getInt("display_id"))
        assertEquals(1920, j.getInt("width"))
        assertEquals(1080, j.getInt("height"))
        assertEquals("mini", j.getString("presentation"))
        assertTrue(j.getBoolean("direct_display"))
        assertEquals("com.files", j.getJSONObject("focused_window").getString("package"))
        assertTrue(DesktopStatusReport.text(j).contains("1920x1080"))
        host.active = false
        assertFalse(DesktopStatusReport.json(host).getBoolean("active"))
        assertFalse(DesktopStatusReport.json(null).getBoolean("available"))
        assertTrue(ToolRegistry.default()["desktop_status"] != null)
        assertFalse(ToolRegistry.default()["desktop_status"]!!.mutating)

        val env = FakeEnv(this, ScriptedLlm(script()), FakeServices(desktop = FakeDesktopHost()))
        val tools = BackstageMcpTools({ AgentControllerImpl(env, StandardTestDispatcher(testScheduler)) }, env)
        val desktop = tools.call("get_device_status", JSONObject()).structured!!.getJSONObject("desktop")
        assertEquals(7, desktop.getInt("display_id"))
        assertEquals("mini", desktop.getString("presentation"))
        assertTrue(desktop.has("works_while_locked"))
    }

    // ----  ------------------------------------------------------------------------------------------------

    private fun TestScope.mcp(): Pair<BackstageMcpTools, FakeEnv> {
        val env = FakeEnv(this, ScriptedLlm(script()))
        return BackstageMcpTools({ AgentControllerImpl(env, StandardTestDispatcher(testScheduler)) }, env) to env
    }

    @Test fun alternativeToolNamesCanBeEnabledOneByOne() = runTest {
        val (tools, env) = mcp()
        env.settings.mcpEnabledTools = setOf("execute_task", "tap_screen")
        assertEquals(listOf("execute_task", "tap_screen"), tools.tools().map { it.name })
        env.settings.mcpExtendedTools = true
        val all = tools.tools().map { it.name }
        for (n in listOf("click_node", "long_click_node", "tap_screen", "long_tap_screen", "swipe_screen", "submit_text", "find_app",
            "navigate_back", "navigate_home", "navigate_recents", "show_all_apps", "take_screenshot", "capture_screenshot")) {
            assertTrue(n, n in all)
        }
        assertFalse("sms_send" in all)
    }

    @Test fun aliasesForwardWithTranslatedArguments() = runTest {
        val (tools, env) = mcp()
        env.settings.mcpExtendedTools = true
        assertFalse(tools.call("tap_screen", JSONObject("""{"x":100,"y":200,"reason":"r","content":"c"}""")).isError)
        assertEquals("tap 100,200", env.phone.actions.last())
        assertFalse(tools.call("swipe_screen", JSONObject("""{"start_x":1,"start_y":2,"end_x":3,"end_y":4}""")).isError)
        assertEquals("swipe 1,2->3,4", env.phone.actions.last())
        tools.call("navigate_back", JSONObject("""{"reason":"go back"}"""))
        assertEquals("key back", env.phone.actions.last())
        tools.call("submit_text", JSONObject("""{"text":"hello"}"""))
        assertTrue(env.phone.actions.last(), env.phone.actions.last().startsWith("text") || env.phone.actions.last() == "key enter")
        val apps = tools.call("find_app", JSONObject("""{"keywords":["mail"]}"""))
        assertTrue(apps.content.first().text!!.contains("Gmail"))
        val launched = tools.call("launch_app", JSONObject("""{"package_name":"com.google.android.gm"}"""))
        assertFalse(launched.content.first().text, launched.isError)
        assertTrue(McpAliases.byName.keys.none { it == "task_completed" || it == "ask_user" })
    }

    // ----  ----------------------------------------------------------------------------------------------

    @Test fun miniTileIsFoundAmongThePhonesWindows() {
        val own = setOf("com.eggman.backstage")
        val tile = UiWindow(3, WindowKind.ACCESSIBILITY_OVERLAY, MiniTileOverlay.TILE_TITLE, "com.eggman.backstage", bounds = Box(700, 1800, 1060, 2300))
        val pointer = UiWindow(4, WindowKind.ACCESSIBILITY_OVERLAY, "", "com.eggman.backstage", bounds = Box(0, 0, 1080, 2400))
        val other = UiWindow(5, WindowKind.ACCESSIBILITY_OVERLAY, "", "com.talkback", bounds = Box(0, 0, 100, 100))
        assertEquals(tile.bounds, MiniTileOverlay.tileBounds(listOf(pointer, other, tile), own, 1080, 2400))
        assertEquals(tile.bounds, MiniTileOverlay.tileBounds(listOf(pointer, tile.copy(title = "")), own, 1080, 2400))
        assertNull(MiniTileOverlay.tileBounds(listOf(pointer, other), own, 1080, 2400))
    }

    @Test fun phoneGesturesOnTheTileHideItOnlyForTheGesture() = runTest {
        val host = FakeDesktopHost()
        val tile = MiniTileOverlay({ host }, { Box(700, 1800, 1060, 2300) }, settleMs = 0)
        val bubble = object : OverlayController {
            var hidden = 0
            override suspend fun hide() { hidden++ }
            override fun restore() = Unit
            override fun bounds() = Box(0, 0, 100, 100)
        }
        OverlayHooks.controller = bubble
        var during: DesktopHost.Presentation? = null
        OverlayHooks.hiddenAt(800f, 2000f, tile) { during = host.presentation() }
        assertEquals(DesktopHost.Presentation.HIDDEN, during)
        assertEquals(DesktopHost.Presentation.MINI, host.presentation())
        assertEquals("the bubble elsewhere stays", 0, bubble.hidden)
        host.calls.clear()
        OverlayHooks.hiddenAt(10f, 10f, tile) { }
        assertEquals(1, bubble.hidden)
        assertTrue("a gesture away from the tile leaves the desktop alone", host.calls.isEmpty())
        // Screenshots hide everything; a HIDDEN or FULL desktop is left as it is.
        host.presentation = DesktopHost.Presentation.FULL
        assertNull(tile.bounds())
        OverlayHooks.hiddenDuring(tile) { }
        assertTrue(host.calls.isEmpty())
    }

    // ---- UI items -------------------------------------------------------------------------------------------------

    @Test fun composerResumesAPausedTaskWithTheTypedInstruction() {
        assertEquals(Composer.Mode.NEW_TASK, Composer.mode(null))
        assertEquals(Composer.Mode.ANSWER, Composer.mode(Fixtures.snapshot(status = TaskStatus.WAITING_FOR_USER)))
        assertEquals(Composer.Mode.RESUME, Composer.mode(Fixtures.snapshot(status = TaskStatus.RUNNING).copy(paused = true)))
        assertEquals(Composer.Mode.NEW_TASK, Composer.mode(Fixtures.snapshot(status = TaskStatus.RUNNING)))
        assertTrue(Composer.placeholder(Composer.Mode.RESUME, false).contains("resume"))
    }

    @Test fun resumeWithInstructionReachesTheModelThroughAgentAccess() = runTest {
        val env = FakeEnv(this, ScriptedLlm(script(call("wait", """{"seconds":1}"""), call("finish", """{"summary":"ok","success":true}"""))))
        val c = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        com.eggman.backstage.entry.AgentAccess.override = c
        try {
            val h = c.submit(TaskRequest("tidy up", Source.CHAT))
            testScheduler.runCurrent()
            assertTrue(c.pause(h.id))
            assertTrue(com.eggman.backstage.entry.AgentAccess.resume(h.id, "  only the downloads  "))
            h.await()
            assertTrue(c.transcript.value.any { it.text == "only the downloads" })
        } finally {
            com.eggman.backstage.entry.AgentAccess.override = null
        }
    }

    @Test fun timeLimitChoicesAreLabelled() {
        assertEquals("No limit", TimeLimitChoices.label(0))
        assertEquals("15 min", TimeLimitChoices.label(15))
        assertEquals("2 h", TimeLimitChoices.label(120))
        assertTrue(0 in TimeLimitChoices.options && 120 in TimeLimitChoices.options)
        assertTrue(TimeLimitChoices.summary(30).contains("30 min"))
    }

    @Test fun licensesNameTheSourceRevisionAndTheClosedSourceParts() {
        val line = Licenses.sourceLine("1.0.0", "dfcb1d0abc", "https://example.org/repo")
        assertTrue(line, line.contains("https://example.org/repo at commit dfcb1d0abc"))
        assertFalse(Licenses.sourceLine("1.0.0", "unknown").contains("unknown"))
        assertEquals("https://example.org/repo/tree/dfcb1d0abc", Licenses.sourceTreeUrl("dfcb1d0abc", "https://example.org/repo"))
        assertEquals(Licenses.SOURCE_URL, Licenses.sourceTreeUrl("dfcb1d0-dirty"))
        assertTrue(Licenses.closedSource.single().name.contains("Google Play services"))
        assertFalse(Licenses.closedSource.any { "Firebase" in it.name || "in_app_update" in it.name })
        val sections = Licenses.noticeSections("Header\n==== Native ====\n== lib.so ==\n---- LICENSE ----\ntext\n---- other ----\nmore")
        assertEquals(4, sections.size)
        assertTrue(sections[2].startsWith("---- LICENSE ----"))
    }

    @Test fun mcpToolSwitchesListEveryLowLevelToolButNoIrreversibleOne() {
        val names = com.eggman.backstage.ui.settings.McpToolChoices.lowLevel
        assertTrue("tap" in names && "tap_screen" in names && "desktop_status" in names)
        assertFalse("sms_send" in names || "finish" in names)
    }

    @Test fun desktopFocusWording() {
        val t = com.eggman.backstage.ui.settings.DesktopFocusText
        assertTrue(t.label("own").startsWith("Own"))
        assertEquals("Shared", t.label("shared"))
        assertTrue(t.detail("shared", "quiet").contains("Running desktop: own focus that never takes the phone's"))
        assertTrue(t.detail("own", null).contains("next time the desktop starts"))
    }
}
