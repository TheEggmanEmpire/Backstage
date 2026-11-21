package com.eggman.backstage.exec

import com.eggman.backstage.agent.PromptContext
import com.eggman.backstage.agent.SessionStop
import com.eggman.backstage.agent.SystemPrompt
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopTask
import com.eggman.backstage.host.TaskBounds
import com.eggman.backstage.host.TaskOp
import com.eggman.backstage.host.WindowModes
import com.eggman.backstage.surfaces.FakeAgent
import com.eggman.backstage.testutil.FakeDesktopHost
import com.eggman.backstage.testutil.FakeServices
import com.eggman.backstage.tools.AgentHooks
import com.eggman.backstage.tools.PresentationRequest
import com.eggman.backstage.tools.ToolContext
import com.eggman.backstage.tools.ToolRegistry
import com.eggman.backstage.tools.ToolResult
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * User reports on (Samsung, desktop as popup):
 * 1. "make the app full screen" put the whole DeX desktop full screen instead of maximizing the app's window;
 * 2. after the user went back to Android, the agent launched an app on the phone instead of DeX;
 * 3. the popup's X did not stop the agent (the running task started the desktop again);
 * 4. apps the agent opened on the phone came up as full-size floating windows behind the status bar.
 */
class ScreensAndWindowsTest {
    /** Desktop display 7 (1920x1080) and the phone (display 0, 1080x2400); records every task operation. */
    class WindowHost(val base: FakeDesktopHost = FakeDesktopHost()) : DesktopHost by base, DesktopHostExtras {
        val desktop = mutableListOf(
            DesktopTask(21, "com.google.android.youtube", "YouTube", displayId = 7, focused = true,
                bounds = TaskBounds(200, 100, 1200, 700), windowingMode = WindowModes.FREEFORM),
            DesktopTask(22, "org.example.notes", "Notes", displayId = 7, bounds = TaskBounds(50, 50, 600, 500), windowingMode = WindowModes.FREEFORM),
            DesktopTask(23, "com.eggman.backstage", "Backstage", displayId = 7),
        )
        val phone = mutableListOf(
            // Behind the status bar: a desktop-sized floating window on the phone (report 4).
            DesktopTask(31, "com.android.chrome", "Chrome", displayId = 0, focused = true,
                bounds = TaskBounds(0, 0, 1080, 1600), windowingMode = WindowModes.FREEFORM),
            DesktopTask(32, "com.whatsapp", "WhatsApp", displayId = 0, visible = false, windowingMode = WindowModes.FULLSCREEN),
        )
        val ops = mutableListOf<String>()
        var refuse = emptySet<TaskOp>()
        /** What a launch does: by default the window appears on the desktop. */
        var onLaunch: (String) -> Unit = { pkg -> desktop.add(0, DesktopTask(40, pkg, pkg, displayId = 7, windowingMode = WindowModes.FREEFORM)) }

        override fun setMiniStatus(text: String?) = Unit
        override fun setMiniShape(shape: String) = false
        override fun miniShape() = DesktopHostExtras.SHAPE_ROUNDED
        override fun usePrivilegeProvider(provider: String) = false
        override fun desktopAccessibilityComponent(): String? = null
        override fun desktopTasks(): List<DesktopTask> = desktop.toList()
        override fun phoneTasks(): List<DesktopTask> = phone.toList()
        override fun closeDesktopTask(taskId: Int): Boolean { ops += "close $taskId"; return desktop.removeAll { it.taskId == taskId } }
        override fun launchPackage(packageName: String, bounds: android.graphics.Rect?): Boolean =
            base.launchPackage(packageName, bounds).also { onLaunch(packageName) }

        override fun controlTask(taskId: Int, displayId: Int, op: TaskOp, bounds: TaskBounds?): Boolean {
            ops += "$op $taskId@$displayId" + (bounds?.let { " $it" } ?: "")
            if (op in refuse) return false
            val list = if (displayId == 0) phone else desktop
            val i = list.indexOfFirst { it.taskId == taskId }.takeIf { it >= 0 } ?: return false
            val t = list[i]
            when (op) {
                TaskOp.FULLSCREEN -> list[i] = t.copy(windowingMode = WindowModes.FULLSCREEN, bounds = if (displayId == 0) TaskBounds(0, 0, 1080, 2400) else TaskBounds(0, 0, 1920, 1080))
                TaskOp.FREEFORM -> list[i] = t.copy(windowingMode = WindowModes.FREEFORM, bounds = bounds)
                TaskOp.MINIMIZE -> list[i] = t.copy(visible = false)
                TaskOp.CLOSE -> list.removeAt(i)
                TaskOp.MOVE_TO_DESKTOP -> { list.removeAt(i); desktop.add(0, t.copy(displayId = 7, windowingMode = WindowModes.FREEFORM, bounds = bounds)) }
                TaskOp.MOVE_TO_PHONE -> { list.removeAt(i); phone.add(0, t.copy(displayId = 0, windowingMode = WindowModes.FULLSCREEN)) }
            }
            return true
        }
    }

    class Ctx(
        val host: DesktopHost,
        override val phoneAllowed: Boolean = false,
        override val userWords: List<String>? = listOf("open youtube"),
        override val desktopAllowed: Boolean = true,
        val phoneExec: Executor? = null,
    ) : ToolContext {
        val desktopExec = DesktopExecutor(host, host.geometry()!!, setOf("com.eggman.backstage"))
        override var currentTarget: TargetKind? = TargetKind.DESKTOP
        val asked = mutableListOf<TargetKind>()
        override suspend fun executor(kind: TargetKind?): Executor {
            val k = kind ?: currentTarget!!
            asked += k
            if (k == TargetKind.PHONE && !phoneAllowed) throw IllegalStateException(TargetResolver.PHONE_GATED)
            return if (k == TargetKind.PHONE) phoneExec ?: throw IllegalStateException("no phone") else desktopExec
        }
        override fun switchTarget(kind: TargetKind) { currentTarget = kind }
        override val services = FakeServices(apps = APPS, desktop = host)
        override val hooks: AgentHooks? = null
        override val visionEnabled = false
        override val autoScreenshot = false
        override val stabilityMs = 0L
    }

    companion object {
        val APPS = listOf(
            AppEntry("Chrome", "com.android.chrome"), AppEntry("YouTube", "com.google.android.youtube"),
            AppEntry("Google Maps", "com.google.android.apps.maps"), AppEntry("Player", "com.example.player"),
            AppEntry("Editor", "com.example.editor"), AppEntry("Notes", "org.example.notes"),
        )
    }

    private val reg = ToolRegistry.default()
    private suspend fun run(ctx: ToolContext, name: String, args: String): ToolResult = reg.execute(ctx, name, JSONObject(args))

    @Before fun reset() {
        WindowController.remembered.clear()
        SessionStop.reset()
    }

    // ---- 1. an app full screen is the app's window, not the desktop -------------------------------------------------

    @Test fun maximizeFillsTheDesktopWithTheWindowAndLeavesThePresentation() = runTest {
        val host = WindowHost()
        val r = run(Ctx(host), "window_control", """{"action":"maximize","app":"YouTube"}""")
        assertFalse(r.text, r.isError)
        assertEquals(listOf("FULLSCREEN 21@7"), host.ops)
        assertTrue(r.text, r.text.contains("fills the desktop"))
        assertTrue(r.text.contains("desktop_presentation was not changed"))
        // The desktop stays the popup: no presentation change at all.
        assertTrue(host.base.calls.toString(), host.base.calls.none { it.startsWith("present") })
        assertEquals(DesktopHost.Presentation.MINI, host.presentation())
        // Restore puts the window back where it is.
        val back = run(Ctx(host), "window_control", """{"action":"restore","app":"YouTube"}""")
        assertFalse(back.text, back.isError)
        assertEquals("FREEFORM 21@7 [200,100][1200,700]", host.ops.last())
    }

    @Test fun maximizeFallsBackToADesktopSizedWindowWhenFullscreenIsRefused() = runTest {
        val host = WindowHost().apply { refuse = setOf(TaskOp.FULLSCREEN) }
        val r = run(Ctx(host), "window_control", """{"action":"maximize"}""") // the focused window
        assertFalse(r.text, r.isError)
        assertEquals("FREEFORM 21@7 [0,0][1920,1080]", host.ops.last())
    }

    @Test fun minimizeAndCloseActOnTheDesktopWindowOnly() = runTest {
        val host = WindowHost()
        assertFalse(run(Ctx(host), "window_control", """{"action":"minimize","app":"notes"}""").isError)
        assertEquals("MINIMIZE 22@7", host.ops.last())
        assertFalse(run(Ctx(host), "window_control", """{"action":"close","app":"notes"}""").isError)
        assertEquals("close 22", host.ops.last())
        // Backstage itself is never a target.
        assertTrue(run(Ctx(host), "window_control", """{"action":"close","app":"com.eggman.backstage","screen":"desktop"}""").isError)
    }

    @Test fun desktopPresentationFullNeedsTheUsersExplicitWordsAboutTheDesktop() = runTest {
        val host = WindowHost()
        for (words in listOf("make youtube full screen", "make YouTube full screen on DeX", "open chrome in DeX full screen", "maximize the window")) {
            val r = run(Ctx(host, userWords = listOf(words)), "desktop_presentation", """{"mode":"fullscreen"}""")
            assertTrue(words, r.isError)
            assertTrue(r.text, r.text.contains("window_control"))
        }
        assertTrue(host.base.calls.none { it.startsWith("present") })
        for (words in listOf("show the desktop full screen", "put DeX in fullscreen", "full screen the desktop please", "expand the popup", "Desktop im Vollbild")) {
            val r = run(Ctx(host, userWords = listOf(words)), "desktop_presentation", """{"mode":"fullscreen"}""")
            assertFalse("$words: ${r.text}", r.isError)
        }
        // After asking: a short answer naming the desktop counts; a direct MCP call (no words) is explicit.
        assertTrue(PresentationRequest.explicitFullDesktop(listOf("make it full screen", "the desktop")))
        assertFalse(PresentationRequest.explicitFullDesktop(listOf("make it full screen", "YouTube")))
        assertTrue(PresentationRequest.explicitFullDesktop(null))
        // Other presentations never need it; the old argument name still works.
        assertFalse(run(Ctx(host, userWords = listOf("x")), "desktop_presentation", """{"presentation":"background"}""").isError)
        assertFalse(run(Ctx(host, userWords = listOf("x")), "desktop_start", """{"presentation":"popup"}""").isError)
        assertTrue(run(Ctx(host, userWords = listOf("open youtube full screen")), "desktop_start", """{"presentation":"fullscreen"}""").isError)
    }

    @Test fun launchWithWindowMaximizedMaximizesTheNewWindow() = runTest {
        val host = WindowHost()
        val r = run(Ctx(host), "desktop_launch_app", """{"app":"com.example.player","window":"maximized"}""")
        assertFalse(r.text, r.isError)
        assertTrue(r.text, r.text.contains("verified: its window is on the desktop (display 7)"))
        assertTrue(r.text, r.text.contains("maximized"))
        assertEquals("FULLSCREEN 40@7", host.ops.last())
        assertTrue(host.base.calls.none { it.startsWith("present") })
    }

    // ---- 2. launches stay on the desktop and the agent stays off the phone -------------------------------------------

    @Test fun aLaunchThatLandsOnThePhoneIsMovedToTheDesktop() = runTest {
        val host = WindowHost()
        host.onLaunch = { pkg -> host.phone.add(0, DesktopTask(50, pkg, "Maps", displayId = 0, visible = true, windowingMode = WindowModes.FULLSCREEN)) }
        val r = run(Ctx(host), "desktop_launch_app", """{"app":"com.google.android.apps.maps"}""")
        assertFalse(r.text, r.isError)
        assertTrue(host.ops.toString(), host.ops.any { it.startsWith("MOVE_TO_DESKTOP 50@0") })
        assertTrue(r.text, r.text.contains("was moved to the desktop"))
        assertTrue(host.phone.none { it.taskId == 50 })
    }

    @Test fun aLaunchStuckOnThePhoneFailsAndTellsTheAgentNotToActThere() = runTest {
        val host = WindowHost().apply { refuse = setOf(TaskOp.MOVE_TO_DESKTOP) }
        host.onLaunch = { pkg -> host.phone.add(0, DesktopTask(50, pkg, "Maps", displayId = 0, visible = true)) }
        val r = run(Ctx(host), "launch_app", """{"app":"com.google.android.apps.maps"}""")
        assertTrue(r.text, r.isError)
        assertTrue(r.text, r.text.contains("opened on the PHONE screen"))
        assertTrue(r.text.contains("Do not operate it on the phone"))
    }

    @Test fun aDesktopGatedTaskCannotActOnThePhone() = runTest {
        val host = WindowHost()
        val ctx = Ctx(host, phoneAllowed = false)
        val r = run(ctx, "launch_app", """{"app":"com.android.chrome","screen":"phone"}""")
        assertTrue(r.text, r.isError)
        assertTrue(r.text, r.text.contains("off-limits"))
        assertTrue(host.base.calls.none { it.startsWith("launch") })
        // The old argument name is gated the same way.
        assertTrue(run(ctx, "launch_app", """{"app":"com.android.chrome","target":"phone"}""").isError)
        // Phone window changes are refused, moving a phone app to the desktop (the recovery) is allowed.
        assertTrue(run(ctx, "window_control", """{"action":"fullscreen","screen":"phone","app":"Chrome"}""").isError)
        assertTrue(host.ops.isEmpty())
        val moved = run(ctx, "window_control", """{"action":"move_to_desktop","app":"Chrome"}""")
        assertFalse(moved.text, moved.isError)
        assertTrue(host.ops.single().startsWith("MOVE_TO_DESKTOP 31@0"))
        assertTrue(run(ctx, "window_control", """{"action":"move_to_phone","app":"YouTube"}""").isError)
    }

    @Test fun phoneAllowedFollowsTheUsersTargetChoice() {
        fun c(requested: Target, default: Target, source: Source = Source.CHAT, external: Boolean = false) = TargetContext(
            requested = requested, defaultTarget = default, source = source, prompt = "x", hostPresent = true,
            desktopActive = true, privilegedAvailable = true, autoStartDesktop = true, externalPhoneAllowed = external,
        )
        assertFalse(TargetResolver.phoneAllowed(c(Target.AUTO, Target.DESKTOP)))
        assertFalse(TargetResolver.phoneAllowed(c(Target.DESKTOP, Target.PHONE)))
        assertTrue(TargetResolver.phoneAllowed(c(Target.PHONE, Target.DESKTOP)))
        assertTrue(TargetResolver.phoneAllowed(c(Target.AUTO, Target.PHONE)))
        assertTrue(TargetResolver.phoneAllowed(c(Target.AUTO, Target.AUTO)))
        assertFalse(TargetResolver.phoneAllowed(c(Target.PHONE, Target.PHONE, Source.MCP)))
        assertTrue(TargetResolver.phoneAllowed(c(Target.PHONE, Target.PHONE, Source.MCP, external = true)))
    }

    @Test fun everyDesktopListingSaysWhichScreenAndWindowsItIs() = runTest {
        val host = WindowHost()
        val exec = DesktopExecutor(host, host.geometry()!!, setOf("com.eggman.backstage"))
        val line = exec.screen().text.lines()[1]
        assertTrue(line, line.startsWith("[state] You act on: the DeX desktop (display 7, 1920x1080), shown on the phone as a popup window."))
        assertTrue(line, line.contains("YouTube [freeform [200,100][1200,700], focused]"))
        assertFalse(line, line.contains("Backstage ["))
        assertTrue(line, line.contains("Phone screen (display 0, not yours): Chrome in front."))
        val phone = ScreenStateLine.phone(1080, 2400, host.phone, TaskBounds(0, 80, 1080, 2280), desktopRunning = true)
        assertTrue(phone, phone.contains("Front app: Chrome [freeform"))
        assertTrue(phone, phone.contains("WARNING: Chrome is a floating window outside the safe area"))
        assertTrue(phone.contains("window_control action fullscreen"))
    }

    // ---- 4. phone windows: fullscreen by default, pop-ups only inside the safe area ---------------------------------

    private val safe = TaskBounds(0, 80, 1080, 2280)

    @Test fun phoneLaunchesAreFullscreenAndPopupsStayInsideTheBars() {
        assertEquals(WindowModes.FULLSCREEN, PhoneLaunch.windowingMode(popup = false))
        assertEquals(WindowModes.FREEFORM, PhoneLaunch.windowingMode(popup = true))
        val stuck = DesktopTask(1, "a", "A", 0, bounds = TaskBounds(0, 0, 1080, 1600), windowingMode = WindowModes.FREEFORM)
        val normal = DesktopTask(1, "a", "A", 0, windowingMode = WindowModes.FULLSCREEN)
        // A floating window nobody asked for goes back to fullscreen; a normal app is left alone.
        assertEquals(TaskOp.FULLSCREEN, PhoneLaunch.correction(stuck, popup = false, safe = safe))
        assertNull(PhoneLaunch.correction(normal, popup = false, safe = safe))
        // A pop-up that is not one, or sits under the status bar, is placed again.
        assertEquals(TaskOp.FREEFORM, PhoneLaunch.correction(normal, popup = true, safe = safe))
        assertEquals(TaskOp.FREEFORM, PhoneLaunch.correction(stuck, popup = true, safe = safe))
        assertTrue(PhoneWindows.misplaced(stuck, safe))
        assertFalse(PhoneWindows.misplaced(normal, safe))

        for (pos in PopupPosition.values()) {
            val b = PhoneWindows.popupBounds(safe, pos, margin = 24)
            assertTrue("$pos $b", b.inside(safe))
            assertEquals((1080 * 0.6).toInt(), b.width)
            assertEquals((2200 * 0.45).toInt(), b.height)
        }
        val center = PhoneWindows.popupBounds(safe)
        assertEquals(safe.left + (safe.width - center.width) / 2, center.left)
        // Explicit bounds under the status bar are moved inside.
        val clamped = PhoneWindows.clampInto(TaskBounds(-50, 0, 600, 900), safe)
        assertTrue(clamped.toString(), clamped.inside(safe))
        assertEquals(650, clamped.width)
    }

    @Test fun aFloatingPhoneAppBehindTheStatusBarIsMadeFullscreenAgain() = runTest {
        val host = WindowHost()
        val phoneExec = FakePhone(safe)
        val ctx = Ctx(host, phoneAllowed = true, phoneExec = phoneExec)
        val r = run(ctx, "window_control", """{"action":"fullscreen","screen":"phone","app":"Chrome"}""")
        assertFalse(r.text, r.isError)
        assertEquals(listOf("FULLSCREEN 31@0"), host.ops)
        assertEquals(WindowModes.FULLSCREEN, host.phone.first { it.taskId == 31 }.windowingMode)
        // Asked for: a pop-up inside the safe area.
        val pop = run(ctx, "window_control", """{"action":"popup","screen":"phone","app":"Chrome","position":"bottom_right"}""")
        assertFalse(pop.text, pop.isError)
        val placed = host.phone.first { it.taskId == 31 }
        assertEquals(WindowModes.FREEFORM, placed.windowingMode)
        assertTrue(placed.bounds.toString(), placed.bounds!!.inside(safe))
    }

    @Test fun launchAppRoutesWindowModesPerScreen() = runTest {
        val host = WindowHost()
        val phone = com.eggman.backstage.testutil.FakeExecutor(TargetKind.PHONE)
        val ctx = Ctx(host, phoneAllowed = true, phoneExec = phone)
        assertFalse(run(ctx, "launch_app", """{"app":"com.android.chrome","screen":"phone"}""").isError)
        assertEquals("launch com.android.chrome", phone.actions.last())
        assertFalse(run(ctx, "launch_app", """{"app":"com.android.chrome","screen":"phone","window":"popup"}""").isError)
        assertEquals("launch com.android.chrome window=popup", phone.actions.last())
        // Slots are desktop-only; the desktop gets its freeform window on display 7.
        assertTrue(run(ctx, "launch_app", """{"app":"com.android.chrome","screen":"phone","slot":"left"}""").isError)
        assertTrue(run(ctx, "launch_app", """{"app":"x","window":"sideways"}""").isError)
        val d = run(ctx, "launch_app", """{"app":"com.example.editor","screen":"desktop"}""")
        assertFalse(d.text, d.isError)
        assertTrue(host.base.calls.contains("launch com.example.editor"))
        assertEquals(WindowModes.FREEFORM, host.desktop.first().windowingMode)
    }

    /** A phone executor that only reports its safe area (window_control reads it for pop-ups). */
    private class FakePhone(private val area: TaskBounds) : Executor by com.eggman.backstage.testutil.FakeExecutor(TargetKind.PHONE) {
        override fun safeArea(): TaskBounds = area
    }

    // ---- 3. stop controls ----------------------------------------------------------------------------------------------

    @Test fun popupStopCancelsTheAgentBeforeItStopsTheDesktopAndBlocksARestart() {
        val order = mutableListOf<String>()
        val agent = object : com.eggman.backstage.agent.api.AgentController by FakeAgent() {
            override fun cancelAll() { order += "agent" }
        }
        val host = object : DesktopHost by FakeDesktopHost() {
            override fun stopDesktop() { order += "desktop" }
        }
        SessionStop.stopAgentAndDesktop(agent, host, nowMs = 1_000L)
        assertEquals(listOf("agent", "desktop"), order)
        assertTrue(SessionStop.autoStartBlocked(1_000L + SessionStop.RESTART_BLOCK_MS - 1))
        assertFalse(SessionStop.autoStartBlocked(1_000L + SessionStop.RESTART_BLOCK_MS))
        // The chat panel's Stop: the agent only.
        order.clear()
        SessionStop.stopAgent(agent)
        assertEquals(listOf("agent"), order)
    }

    // ---- prompt ----------------------------------------------------------------------------------------------------

    @Test fun thePromptExplainsScreensWindowsAndRecovery() {
        fun prompt(phoneAllowed: Boolean) = SystemPrompt.build(
            PromptContext(
                target = TargetKind.DESKTOP, targetDescription = null, desktopAvailable = true, planMode = false,
                confirmSensitive = true, interactive = true, vision = false, nowMs = 0L, zone = ZoneId.of("UTC"),
                locale = Locale.ENGLISH, phoneAllowed = phoneAllowed,
            ),
        )
        val p = prompt(phoneAllowed = false)
        assertTrue(p.contains("## Screens and windows"))
        assertTrue(p.contains("display 0"))
        assertTrue(p.contains("[state]"))
        assertTrue(p.contains("window_control {\"action\":\"maximize\""))
        assertTrue(p.contains("desktop_presentation changes how the WHOLE desktop"))
        assertTrue(p.contains("window \"popup\""))
        assertTrue(p.contains("move_to_desktop"))
        assertTrue(p.contains("{\"action\":\"fullscreen\",\"screen\":\"phone\"}"))
        assertTrue(p.contains("Never use the phone's recent-apps view"))
        assertTrue(p.contains(SystemPrompt.DESKTOP_ONLY))
        assertFalse(prompt(phoneAllowed = true).contains(SystemPrompt.DESKTOP_ONLY))
        // Phone-only builds do not get the section.
        val phoneOnly = SystemPrompt.build(
            PromptContext(TargetKind.PHONE, null, false, false, true, true, false, 0L, ZoneId.of("UTC"), Locale.ENGLISH),
        )
        assertFalse(phoneOnly.contains("## Screens and windows"))
    }

    // ---- one shared target, tools bound to it ------------------------------------------------------------------------

    @Test fun aMessageNamingTheOtherScreenOverridesTheChips() {
        val ct = com.eggman.backstage.entry.CurrentTarget
        assertEquals(Target.PHONE, ct.forMessage(Target.PHONE, "open youtube"))
        assertEquals(Target.DESKTOP, ct.forMessage(Target.DESKTOP, "open youtube"))
        assertEquals(Target.PHONE, ct.forMessage(Target.DESKTOP, "open youtube on my phone"))
        assertEquals(Target.PHONE, ct.forMessage(Target.AUTO, "öffne YouTube auf dem Handy"))
        assertEquals(Target.DESKTOP, ct.forMessage(Target.PHONE, "open youtube on DeX"))
        assertEquals(Target.DESKTOP, ct.forMessage(Target.PHONE, "open chrome on the desktop"))
        // "phone" alone is not a screen choice ("call my phone provider").
        assertEquals(Target.DESKTOP, ct.forMessage(Target.DESKTOP, "call my phone provider"))
    }

    @Test fun aPhoneTaskGetsNoDesktopToolsAndADesktopTaskNoPhoneOnlyTools() {
        val reg = ToolRegistry.default()
        val base = ToolRegistry.Availability(desktop = true)
        val phoneTask = reg.forAgent(com.eggman.backstage.agent.TaskTools.availability(base, true, TargetKind.PHONE, phoneAllowed = true)).map { it.name }
        assertFalse(phoneTask.toString(), phoneTask.any { it.startsWith("desktop_") })
        assertTrue(phoneTask.containsAll(listOf("launch_app", "tap", "window_control", "window_state")))
        val desktopTask = reg.forAgent(com.eggman.backstage.agent.TaskTools.availability(base, true, TargetKind.DESKTOP, phoneAllowed = false)).map { it.name }
        assertTrue(desktopTask.containsAll(listOf("desktop_launch_app", "desktop_presentation", "window_control")))
        assertFalse(desktopTask.contains("move_floating_widget")) // phone-only
        // Phone-only build: the phone is the only screen.
        // (phone tools follow phoneAllowed, which TargetResolver grants local tasks on a phone-only build.)
        val phoneOnly = com.eggman.backstage.agent.TaskTools.availability(base, false, TargetKind.PHONE, phoneAllowed = true)
        assertTrue(phoneOnly.phone); assertFalse(phoneOnly.desktop)
        assertFalse(com.eggman.backstage.agent.TaskTools.availability(base, false, TargetKind.PHONE, phoneAllowed = false).phone)
    }

    @Test fun onAPhoneTaskWindowControlDefaultsToThePhoneAndLeavesTheDesktopAlone() = runTest {
        val host = WindowHost()
        val ctx = Ctx(host, phoneAllowed = true, desktopAllowed = false, phoneExec = FakePhone(safe)).apply { currentTarget = TargetKind.PHONE }
        val r = run(ctx, "window_control", """{"action":"fullscreen","app":"Chrome"}""")
        assertFalse(r.text, r.isError)
        assertEquals(listOf("FULLSCREEN 31@0"), host.ops)
        val d = run(ctx, "window_control", """{"action":"maximize","screen":"desktop","app":"YouTube"}""")
        assertTrue(d.text, d.isError)
        assertTrue(d.text.contains("off-limits"))
        assertEquals(1, host.ops.size)
    }
}
