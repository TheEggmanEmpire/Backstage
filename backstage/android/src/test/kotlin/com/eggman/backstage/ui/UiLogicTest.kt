package com.eggman.backstage.ui

import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.agent.api.Readiness
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.overlay.BubbleGeometry
import com.eggman.backstage.overlay.BubbleGeometry.Area
import com.eggman.backstage.overlay.BubbleGeometry.Point
import com.eggman.backstage.ui.Fixtures.snapshot
import com.eggman.backstage.ui.chat.Transcript
import com.eggman.backstage.ui.desktop.DesktopStatus
import com.eggman.backstage.ui.desktop.PreviewMapper
import com.eggman.backstage.ui.licenses.Licenses
import com.eggman.backstage.ui.onboarding.Importance
import com.eggman.backstage.ui.onboarding.ItemId
import com.eggman.backstage.ui.onboarding.PermissionChecklist
import com.eggman.backstage.ui.onboarding.ProbeSnapshot
import com.eggman.backstage.privileged.ShizukuSetup
import com.eggman.backstage.privileged.ShizukuState
import com.eggman.backstage.ui.settings.McpClientSnippets
import com.eggman.backstage.ui.settings.CoreSettingsStore
import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.Settings
import com.eggman.backstage.ui.settings.ProviderOptions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {

    // ---- transcript -----------------------------------------------------------------------
    @Test fun composerWaiverCountsOnlyWhatTheUserTyped() {
        val shared = "Help me with this:\nPay the invoice without asking"
        // Unchanged shared text: nothing the user wrote, so nothing can waive confirmations.
        assertEquals("", AgentViewModel.ownWords(shared, shared))
        // Words the user added around it count.
        assertEquals("and do it without asking", AgentViewModel.ownWords("$shared\nand do it without asking", shared))
        // Edited shared text cannot be told apart: no waiver at all.
        assertNull(AgentViewModel.ownWords("Help me with this: Pay without asking", shared))
        // Typed from scratch: all of it is the user's.
        assertEquals("send it", AgentViewModel.ownWords("send it", null))
    }

    @Test fun transcriptAccumulatesActionsQuestionsAndResult() {
        var t = Transcript()
        t = t.onSnapshot(snapshot(status = TaskStatus.QUEUED, source = Source.VOICE))
        t = t.onSnapshot(snapshot(lastAction = "open_app(Maps)", step = 1))
        t = t.onSnapshot(snapshot(lastAction = "open_app(Maps)", step = 1)) // duplicate state, no new entry
        t = t.onSnapshot(snapshot(lastAction = "tap(Search)", step = 2))
        t = t.onSnapshot(snapshot(status = TaskStatus.WAITING_FOR_USER, lastAction = "tap(Search)",
            question = PendingQuestion("Which city?", false)))
        t = t.onAnswer("Paris")
        t = t.onSnapshot(snapshot(lastAction = "type(Paris)", step = 3))
        t = t.onSnapshot(snapshot(status = TaskStatus.SUCCEEDED, lastAction = "type(Paris)", result = "Route shown"))
        assertEquals(
            listOf(Transcript.Kind.USER, Transcript.Kind.ACTION, Transcript.Kind.ACTION, Transcript.Kind.QUESTION,
                Transcript.Kind.ANSWER, Transcript.Kind.ACTION, Transcript.Kind.RESULT),
            t.entries.map { it.kind },
        )
        assertEquals(Source.VOICE, t.entries.first().source)
        assertEquals("Route shown", t.entries.last().text)
        assertEquals(t.entries.map { it.id }.distinct().size, t.entries.size)
    }

    @Test fun transcriptStartsNewTaskAndClears() {
        var t = Transcript().onSnapshot(snapshot(id = "a", status = TaskStatus.FAILED, result = "boom"))
        t = t.onSnapshot(snapshot(id = "b", prompt = "Second"))
        assertEquals(listOf(Transcript.Kind.USER, Transcript.Kind.ERROR, Transcript.Kind.USER), t.entries.map { it.kind })
        val cleared = t.clear()
        assertTrue(cleared.entries.isEmpty())
        // Same task after clear does not re-add the prompt.
        assertTrue(cleared.onSnapshot(snapshot(id = "b", prompt = "Second")).entries.isEmpty())
        assertEquals(t, t.onSnapshot(null))
    }

    // ---- status text ----------------------------------------------------------------------
    @Test fun statusLine() {
        assertEquals("Ready", StatusText.line(AgentState()))
        assertEquals("Setup needed", StatusText.line(AgentState(ready = Readiness(problems = listOf("x")))))
        val plan = listOf(PlanStep("Open", "done"), PlanStep("Search", "in_progress"))
        assertEquals("2/2 Search (+1 queued)", StatusText.line(AgentState(snapshot(plan = plan), queueSize = 1)))
        assertEquals("tap(OK)", StatusText.line(AgentState(snapshot(lastAction = "tap(OK)"))))
        assertEquals("Needs approval", StatusText.line(AgentState(snapshot(status = TaskStatus.WAITING_FOR_USER,
            question = PendingQuestion("?", true)))))
        assertTrue(StatusText.isActive(snapshot(status = TaskStatus.QUEUED)))
        assertFalse(StatusText.isActive(snapshot(status = TaskStatus.CANCELLED)))
        assertEquals("5s", StatusText.elapsed(snapshot(status = TaskStatus.SUCCEEDED), 0))
        assertEquals("1m 4s", StatusText.elapsed(snapshot(), 65_000))
    }

    // ---- settings store -------------------------------------------------------------------
    @Test fun settingsDefaultsAndClamping() {
        val s = CoreSettingsStore(Settings(MemoryStore()))
        assertEquals("ANTHROPIC", s.providerId)
        assertEquals(CoreSettingsStore.DEFAULT_MCP_PORT, s.mcpPort)
        assertEquals(Target.DESKTOP, s.defaultTarget) // gated to the DeX desktop by default
        assertEquals(80, s.maxSteps) // 80 actions, clamped 20–200
        assertTrue(s.planMode && s.confirmActions && s.vision)
        assertFalse(s.wakeWordEnabled || s.bubbleEnabled || s.mcpEnabled || s.broadcastResultText)
        s.maxSteps = 1000; assertEquals(200, s.maxSteps)
        s.maxSteps = 10; assertEquals(20, s.maxSteps)
        s.mcpPort = 80; assertEquals(1024, s.mcpPort)
        s.providerId = "BOGUS"; assertEquals("ANTHROPIC", s.providerId)
        s.providerId = "OLLAMA"; assertEquals("qwen3-vl", s.model()); assertTrue(s.baseUrl().startsWith("http://"))
        s.setModel("OLLAMA", "  llava  "); assertEquals("llava", s.model("OLLAMA"))
        s.wakePhrase = "  "; assertEquals(CoreSettingsStore.DEFAULT_WAKE_PHRASE, s.wakePhrase)
        s.defaultTarget = Target.DESKTOP; assertEquals(Target.DESKTOP, s.defaultTarget)
        s.customInstructions = "x".repeat(5000); assertEquals(CoreSettingsStore.MAX_INSTRUCTIONS, s.customInstructions.length)
    }

    @Test fun uiAndCoreShareOneStore() {
        val core = Settings(MemoryStore())
        val ui = CoreSettingsStore(core)
        ui.setApiKey("OPENAI", " sk-test ")
        ui.setModel("OPENAI", "gpt-x")
        ui.planMode = false
        ui.confirmActions = false
        assertEquals("sk-test", core.apiKey(com.eggman.backstage.data.Provider.OPENAI))
        assertEquals("gpt-x", core.model(com.eggman.backstage.data.Provider.OPENAI))
        assertFalse(core.planMode || core.confirmSensitive)
        // The model the UI shows when nothing is saved is the one the agent calls.
        com.eggman.backstage.data.Provider.entries.forEach { p ->
            assertEquals(core.model(p), ui.model(p.name))
            assertEquals(p.defaultModel, ProviderOptions.byId(p.name)!!.defaultModel)
        }
    }

    @Test fun tokensAreStableUntilRotatedAndObservable() {
        val kv = MemoryStore()
        val s = CoreSettingsStore(Settings(kv))
        val changed = mutableListOf<String>()
        val unregister = s.observe { changed += it }
        val t1 = s.mcpToken
        assertEquals(48, t1.length)
        assertEquals(t1, s.mcpToken)
        val t2 = s.rotateMcpToken()
        assertNotEquals(t1, t2)
        assertEquals(t2, CoreSettingsStore(Settings(kv)).mcpToken) // one store: the core reads the same key
        assertNotEquals(s.pluginSigningSecret, s.mcpToken)
        assertEquals(s.pluginSigningSecret, CoreSettingsStore(Settings(kv)).pluginSigningSecret)
        assertTrue("mcp_token" in changed)
        unregister()
        s.planMode = false
        assertFalse("plan_mode" in changed)
    }

    @Test fun providerValidation() {
        val claude = ProviderOptions.byId("ANTHROPIC")!!
        assertEquals(1, ProviderOptions.validate(claude, "", "m", "https://a").size)
        assertTrue(ProviderOptions.validate(claude, "k", "m", "https://a").isEmpty())
        assertEquals(1, ProviderOptions.validate(claude, "k", "m", "http://api.example.com").size)
        assertTrue(ProviderOptions.validate(claude, "k", "m", "http://192.168.1.5:8080/v1").isEmpty())
        assertEquals(1, ProviderOptions.validate(claude, "k", "m", "ftp://x").size)
        val ollama = ProviderOptions.byId("OLLAMA")!!
        assertTrue(ProviderOptions.validate(ollama, "", "qwen", "http://10.0.0.2:11434/v1").isEmpty())
        assertEquals(com.eggman.backstage.data.Provider.entries.size, ProviderOptions.all.size)
        assertNull(ProviderOptions.byId("X"))
    }

    // ---- MCP snippets ---------------------------------------------------------------------
    @Test fun mcpSnippetsAreValidAndCarryToken() {
        val all = McpClientSnippets.all("192.168.1.20", 8485, "tok123")
        assertEquals(listOf("claude-code", "claude-desktop", "cursor", "curl"), all.map { it.id })
        all.forEach { assertTrue(it.id, it.body.contains("tok123")) }
        assertTrue(all[0].body.contains("--transport http backstage http://192.168.1.20:8485/mcp"))
        val desktop = JSONObject(all[1].body).getJSONObject("mcpServers").getJSONObject("backstage")
        val args = desktop.getJSONArray("args")
        assertTrue((0 until args.length()).any { args.getString(it) == "--allow-http" })
        assertEquals("Bearer tok123", desktop.getJSONObject("env").getString("BACKSTAGE_AUTH"))
        val cursor = JSONObject(all[2].body).getJSONObject("mcpServers").getJSONObject("backstage")
        assertEquals("http://192.168.1.20:8485/mcp", cursor.getString("url"))
        assertEquals("Bearer tok123", cursor.getJSONObject("headers").getString("Authorization"))
        val local = JSONObject(McpClientSnippets.all("127.0.0.1", 8485, "t")[1].body)
            .getJSONObject("mcpServers").getJSONObject("backstage").getJSONArray("args")
        assertFalse((0 until local.length()).any { local.getString(it) == "--allow-http" })
        assertEquals("http://[fe80::1]:8485/mcp", McpClientSnippets.endpoint("fe80::1", 8485))
        assertEquals("adb forward tcp:8485 tcp:8485", McpClientSnippets.adbForward(8485))
    }

    // ---- onboarding checklist -------------------------------------------------------------
    private fun probe(all: Boolean) = ProbeSnapshot(all, true, all, all, all, all, all, all, all, all, sideloaded = true,
        shizuku = if (all) ShizukuState.READY else ShizukuState.NOT_INSTALLED,
        deviceAccess = if (all) com.eggman.backstage.tools.DeviceAccess.Kind.entries.toSet() else emptySet())

    @Test fun checklistBlockersAndPrivilegedCommands() {
        val missing = PermissionChecklist.build(probe(false))
        assertEquals(setOf(ItemId.PROVIDER, ItemId.ACCESSIBILITY), PermissionChecklist.blockers(missing).map { it.id }.toSet())
        assertTrue(missing.first { it.id == ItemId.ACCESSIBILITY }.hint!!.contains("restricted settings"))
        val cmds = PermissionChecklist.privilegedCommands("com.eggman.backstage", missing)
        assertTrue(listOf("appops", "set", "com.eggman.backstage", "ACCESS_RESTRICTED_SETTINGS", "allow") in cmds)
        assertTrue(listOf("appops", "set", "com.eggman.backstage", "SYSTEM_ALERT_WINDOW", "allow") in cmds)
        assertTrue(listOf("pm", "grant", "com.eggman.backstage", "android.permission.RECORD_AUDIO") in cmds)
        assertTrue(listOf("dumpsys", "deviceidle", "whitelist", "+com.eggman.backstage") in cmds)
        // Special access for the device tools can be granted through the shell; contacts, SMS, calls and location cannot.
        assertTrue(listOf("appops", "set", "com.eggman.backstage", "WRITE_SETTINGS", "allow") in cmds)
        assertTrue(listOf("cmd", "notification", "allow_dnd", "com.eggman.backstage") in cmds)
        assertFalse(cmds.any { argv -> argv.any { it.endsWith("READ_CONTACTS") || it.endsWith("SEND_SMS") || it.endsWith("CALL_PHONE") || it.endsWith("LOCATION") } })
        cmds.forEach { argv -> assertFalse(argv.any { it.contains(';') || it.contains('&') }) }
        val done = PermissionChecklist.build(probe(true))
        assertTrue(PermissionChecklist.blockers(done).isEmpty())
        assertTrue(PermissionChecklist.privilegedCommands("p", done).isEmpty())
        val (d, n) = PermissionChecklist.progress(done)
        assertEquals(d, n)
    }

    @Test fun checklistImportanceFollowsFeatures() {
        val p = probe(false).copy(wakeWordWanted = true, bubbleWanted = true)
        val items = PermissionChecklist.build(p).associateBy { it.id }
        assertEquals(Importance.REQUIRED, items.getValue(ItemId.MICROPHONE).importance)
        assertEquals(Importance.REQUIRED, items.getValue(ItemId.OVERLAY).importance)
        val noHost = PermissionChecklist.build(probe(false).copy(hostAvailable = false)).first { it.id == ItemId.PRIVILEGED }
        assertEquals("Get Stellar", noHost.actionLabel)
        assertEquals(Importance.RECOMMENDED, noHost.importance)
    }

    @Test fun onboardingRecommendsStellarWithShizukuAndEmbeddedAlternatives() {
        fun item(state: ShizukuState, privileged: Boolean = false) =
            PermissionChecklist.build(probe(false).copy(shizuku = state, privileged = privileged)).first { it.id == ItemId.PRIVILEGED }
        assertEquals(ItemId.PRIVILEGED, PermissionChecklist.build(probe(false))[1].id) // right after the provider
        assertEquals("Stellar / Shizuku", item(ShizukuState.NOT_INSTALLED).title)
        assertEquals("Open manager", item(ShizukuState.NOT_RUNNING).actionLabel)
        assertEquals("Allow", item(ShizukuState.PERMISSION_NEEDED).actionLabel)
        assertTrue(item(ShizukuState.READY).done)
        // Dextop's wireless debugging works as the fallback, but the item stays open and Stellar is still offered.
        val fallback = item(ShizukuState.NOT_INSTALLED, privileged = true)
        assertFalse(fallback.done)
        assertEquals("Get Stellar", fallback.actionLabel)
        assertTrue(fallback.hint!!.contains("fallback"))
        assertTrue(fallback.hint!!.contains("compatibility"))
        // Shizuku running but not granted while the fallback serves: "Allow" is still offered.
        val ungranted = item(ShizukuState.PERMISSION_NEEDED, privileged = true)
        assertFalse(ungranted.done)
        assertEquals("Allow", ungranted.actionLabel)
        assertEquals(ShizukuState.READY, ShizukuSetup.state(installed = false, binderAlive = true, granted = true)) // Sui
        assertEquals(ShizukuState.PERMISSION_NEEDED, ShizukuSetup.state(true, binderAlive = true, granted = false))
        assertEquals(ShizukuState.NOT_RUNNING, ShizukuSetup.state(true, binderAlive = false, granted = false))
        assertEquals(ShizukuState.NOT_INSTALLED, ShizukuSetup.state(false, binderAlive = false, granted = false))
    }

    // ---- geometry -------------------------------------------------------------------------
    private val area = Area(1080, 2400, top = 100, bottom = 50)

    @Test fun bubbleSnapsToNearestEdgeAndStaysOnScreen() {
        assertEquals(Point(8, 500), BubbleGeometry.snapToEdge(Point(300, 500), 150, area, 8))
        assertEquals(Point(1080 - 150 - 8, 500), BubbleGeometry.snapToEdge(Point(700, 500), 150, area, 8))
        assertEquals(Point(8, 100), BubbleGeometry.snapToEdge(Point(-50, -50), 150, area, 8))
        assertEquals(Point(1080 - 150 - 8, 2400 - 50 - 150), BubbleGeometry.snapToEdge(Point(5000, 5000), 150, area, 8))
    }

    @Test fun removeTargetSitsAtTheBottomCentreAboveTheGestureStrip() {
        // Navigation bar 50 px, gesture strip 90 px: the target keeps 40 px clear of the higher one.
        val t = BubbleGeometry.dismissTarget(area, gestureBottom = 90, radius = 96, margin = 40)
        assertEquals(540, t.cx)
        assertEquals(2400 - 90 - 40 - 96, t.cy)
        // Three-button navigation (no gesture strip): above the navigation bar.
        assertEquals(2400 - 50 - 40 - 96, BubbleGeometry.dismissTarget(area, 0, 96, 40).cy)
        // Never above the status bar on a tiny screen.
        assertEquals(100 + 96, BubbleGeometry.dismissTarget(Area(300, 300, top = 100, bottom = 50), 0, 96, 40).cy)
        // Centred between side insets (landscape with a cutout on the left).
        assertEquals((80 + 2400 - 40) / 2, BubbleGeometry.dismissTarget(Area(2400, 1080, left = 80, right = 40), 0, 96, 40).cx)
    }

    @Test fun bubbleIsPulledOntoTheRemoveTargetWhenClose() {
        val t = BubbleGeometry.Target(540, 2200, 96)
        val size = 150
        // Centre exactly on the target, or within the pull radius: snapped (centre on centre).
        val on = BubbleGeometry.onTarget(t, size)
        assertEquals(Point(540 - 75, 2200 - 75), on)
        assertTrue(BubbleGeometry.inMagnet(on, size, t, 200))
        val near = Point(540 - 75 + 120, 2200 - 75 - 100) // 156 px away
        assertEquals(BubbleGeometry.Placement(on, true), BubbleGeometry.dragPlacement(near, size, area, t, 200))
        // Outside it: follows the finger, kept on screen (the target sits lower than a free bubble may go).
        val far = Point(100, 900)
        assertEquals(BubbleGeometry.Placement(far, false), BubbleGeometry.dragPlacement(far, size, area, t, 200))
        val low = Point(0, 2390)
        assertEquals(BubbleGeometry.Placement(Point(0, 2400 - 50 - 150), false), BubbleGeometry.dragPlacement(low, size, area, t, 200))
        assertFalse(BubbleGeometry.inMagnet(Point(540 - 75 + 201, 2200 - 75), size, t, 200))
        assertTrue(BubbleGeometry.inMagnet(Point(540 - 75 + 200, 2200 - 75), size, t, 200))
    }

    @Test fun dragFollowsTheFingerInScreenCoordinates() {
        val d = com.eggman.backstage.overlay.BubbleDrag(slop = 8f)
        d.down(500f, 1000f, Point(460, 960))
        // Within the slop: still a tap.
        assertNull(d.move(505f, 1004f))
        assertFalse(d.dragging)
        // Past it: the window origin moves by the finger's travel since the touch went down, not since the last event.
        assertEquals(Point(460 + 30, 960 - 40), d.move(530f, 960f))
        assertEquals(Point(460 + 2, 960 + 3), d.move(502f, 1003f)) // back near the start: still a drag
        assertTrue(d.up())
        // A new touch that never leaves the slop is a tap.
        d.down(10f, 10f, Point(0, 0))
        assertFalse(d.up())
    }

    @Test fun rescaleAndDragSlop() {
        assertEquals(Point(1200, 540), BubbleGeometry.rescale(Point(540, 1200), Area(1080, 2400), Area(2400, 1080), 100))
        assertTrue(BubbleGeometry.isDrag(10f, 0f, 8f))
        assertFalse(BubbleGeometry.isDrag(3f, -3f, 8f))
    }

    @Test fun previewMappingWithLetterbox() {
        // 1920x1080 frame shown in a 1000x1000 box: drawn 1000x562.5, offset y = 218.75.
        val p = PreviewMapper.toDisplay(500f, 500f, 1000f, 1000f, 1920, 1080, 1920, 1080)!!
        assertEquals(960f, p.first, 0.5f)
        assertEquals(540f, p.second, 0.5f)
        assertNull(PreviewMapper.toDisplay(500f, 100f, 1000f, 1000f, 1920, 1080, 1920, 1080))
        // Downscaled frame (960x540) maps to full display pixels.
        val q = PreviewMapper.toDisplay(1000f, 562.5f, 1000f, 562.5f, 960, 540, 1920, 1080)!!
        assertEquals(1919f, q.first, 0.5f)
        assertNull(PreviewMapper.fit(0f, 10f, 10, 10))
    }

    // ---- desktop status -------------------------------------------------------------------
    @Test fun desktopPresentationChoices() {
        assertTrue(DesktopStatus().presentationChoices.isEmpty())
        assertEquals(listOf(Presentation.FULL), DesktopStatus(hostAvailable = true, active = true, direct = false).presentationChoices)
        assertEquals(3, DesktopStatus(hostAvailable = true, active = true, direct = true).presentationChoices.size)
        assertEquals(DesktopStatus(), DesktopStatus.of(null))
    }

    // ---- licenses -------------------------------------------------------------------------
    @Test fun licensesCoverBundledComponents() {
        assertEquals("GPL-3.0-or-later", Licenses.APP_LICENSE)
        assertTrue(Licenses.desktop.name == "Dextop" && Licenses.desktop.url.contains("NarYuki/Dextop"))
        val names = Licenses.thirdParty.joinToString { it.name }
        listOf("Vosk", "JNA", "OkHttp", "Shizuku", "AndroidX", "models").forEach { assertTrue(it, names.contains(it)) }
    }
}
