package com.eggman.backstage.exec

import android.view.KeyEvent
import com.eggman.backstage.agent.PromptContext
import com.eggman.backstage.agent.SystemPrompt
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopTask
import com.eggman.backstage.testutil.FakeDesktopHost
import com.eggman.backstage.testutil.FakeServices
import com.eggman.backstage.tools.AgentHooks
import com.eggman.backstage.tools.ToolContext
import com.eggman.backstage.tools.ToolRegistry
import com.eggman.backstage.tools.ToolResult
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * User report on a real phone: asked to "close all foreground apps", the agent closed the apps on the phone
 * AND on DeX. desktop_close_windows closes desktop windows only; the recent-apps view's "Close all" is refused on the
 * desktop. Also: the desktop keyboard shortcut catalog and its tools.
 */
class DesktopWindowsAndShortcutsTest {
    /**
     * A host whose task list is deliberately sloppy: it also reports the phone's tasks (display 0) and the desktop's
     * launcher and Backstage itself, and it closes whatever it is asked to. The library must never ask for those.
   */
    class TaskHost(
        val base: FakeDesktopHost = FakeDesktopHost(),
        var env: String? = "samsung_dex",
        var listable: Boolean = true,
    ) : DesktopHost by base, DesktopHostExtras {
        val tasks = mutableListOf(
            DesktopTask(1, "com.whatsapp", "WhatsApp", displayId = 0, focused = true), // phone, in front
            DesktopTask(2, "com.android.chrome", "Chrome", displayId = 0), // phone
            DesktopTask(3, "com.google.android.gm", "Gmail", displayId = 0), // phone
            DesktopTask(11, "com.whatsapp", "WhatsApp", displayId = 7), // the same app on the desktop
            DesktopTask(12, "com.google.android.gm", "Gmail", displayId = 7, focused = true),
            DesktopTask(13, "com.sec.android.app.launcher", "One UI Home", displayId = 7, activityType = 2),
            DesktopTask(14, "com.eggman.backstage", "Backstage", displayId = 7),
            DesktopTask(15, "org.example.notes", "Notes", displayId = 7, visible = false),
        )
        val closed = mutableListOf<Int>()
        override fun setMiniStatus(text: String?) = Unit
        override fun setMiniShape(shape: String) = false
        override fun miniShape() = DesktopHostExtras.SHAPE_ROUNDED
        override fun usePrivilegeProvider(provider: String) = false
        override fun desktopAccessibilityComponent(): String? = null
        override fun desktopTasks(): List<DesktopTask>? = if (listable) tasks.toList() else null
        override fun closeDesktopTask(taskId: Int): Boolean {
            closed += taskId
            return tasks.removeAll { it.taskId == taskId }
        }
        override fun desktopEnvironment() = env
    }

    private val phoneTaskIds = setOf(1, 2, 3)

    class Ctx(val host: DesktopHost, own: Set<String> = setOf("com.eggman.backstage")) : ToolContext {
        val exec = DesktopExecutor(host, host.geometry()!!, own)
        override var currentTarget: TargetKind? = TargetKind.DESKTOP
        override suspend fun executor(kind: TargetKind?): Executor = exec
        override fun switchTarget(kind: TargetKind) { currentTarget = kind }
        override val services = FakeServices(desktop = host)
        override val hooks: AgentHooks? = null
        override val visionEnabled = false
        override val autoScreenshot = false
        override val stabilityMs = 0L
    }

    private val reg = ToolRegistry.default()
    private suspend fun run(ctx: ToolContext, name: String, args: String): ToolResult = reg.execute(ctx, name, JSONObject(args))

    // ---- (4) close windows on the desktop only -------------------------------------------------------------------

    @Test fun closeAllClosesOnlyTheDesktopsAppWindows() = runTest {
        val host = TaskHost()
        val r = run(Ctx(host), "desktop_close_windows", """{"scope":"all"}""")
        assertFalse(r.text, r.isError)
        assertEquals(listOf(11, 12, 15), host.closed)
        assertTrue(host.closed.none { it in phoneTaskIds })
        assertTrue(host.tasks.map { it.taskId }.containsAll(phoneTaskIds + setOf(13, 14)))
        assertTrue(r.text, r.text.contains("Closed 3 desktop window(s): WhatsApp, Gmail, Notes"))
        assertTrue(r.text.contains("Apps on the phone were not touched"))
        // Nothing is force-stopped and no key, tap or recents action is used.
        assertTrue(host.base.calls.none { it.startsWith("shell") || it.startsWith("key") || it.startsWith("tap") || it.startsWith("global") })
    }

    @Test fun closeOneAppLeavesItsPhoneWindowAlone() = runTest {
        val host = TaskHost()
        val r = run(Ctx(host), "desktop_close_windows", """{"scope":"app","app":"whatsapp"}""")
        assertFalse(r.text, r.isError)
        assertEquals(listOf(11), host.closed)
        assertTrue(host.tasks.any { it.taskId == 1 })
        // By package too; an app that is only open on the phone is not closed anywhere.
        val chrome = run(Ctx(host), "desktop_close_windows", """{"scope":"app","app":"com.android.chrome"}""")
        assertFalse(chrome.isError)
        assertTrue(chrome.text, chrome.text.contains("No windows of \"com.android.chrome\" to close"))
        assertEquals(listOf(11), host.closed)
        assertTrue(run(Ctx(host), "desktop_close_windows", """{"scope":"app"}""").isError)
        assertTrue(run(Ctx(host), "desktop_close_windows", """{"scope":"everything"}""").isError)
    }

    @Test fun closeFocusedClosesTheDesktopsFrontWindowNotThePhones() = runTest {
        val host = TaskHost()
        assertFalse(run(Ctx(host), "desktop_close_windows", """{"scope":"focused"}""").isError)
        assertEquals(listOf(12), host.closed)
    }

    @Test fun withoutATaskListNothingIsClosedAndTheModelIsToldWhy() = runTest {
        val host = TaskHost(listable = false)
        val r = run(Ctx(host), "desktop_close_windows", """{"scope":"all"}""")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("never use Close all in the recent-apps view"))
        assertTrue(host.closed.isEmpty())
        // A host without the extras at all (standalone build) behaves the same.
        val plain = run(Ctx(FakeDesktopHost()), "desktop_close_windows", """{"scope":"all"}""")
        assertTrue(plain.isError)
    }

    @Test fun aFailedCloseIsReported() = runTest {
        val host = object : DesktopHost by FakeDesktopHost(), DesktopHostExtras by TaskHost() {
            override fun closeDesktopTask(taskId: Int) = false
        }
        val r = run(Ctx(host), "desktop_close_windows", """{"scope":"all"}""")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("Could not close: WhatsApp, Gmail, Notes"))
    }

    @Test fun windowListShowsDesktopTasksOnly() = runTest {
        val r = run(Ctx(TaskHost()), "desktop_window_list", "{}")
        assertTrue(r.text, r.text.contains("WhatsApp, Gmail, Notes (minimized)"))
        assertFalse(r.text.contains("Chrome"))
        assertFalse(r.text.contains("One UI Home"))
    }

    @Test fun packagesForPrefersTheAppOpenOnTheDesktop() {
        val tasks = listOf(DesktopTask(5, "org.example.mail2", "Mail", displayId = 7))
        val installed = listOf(AppEntry("Mail", "org.example.mail1"), AppEntry("Mail", "org.example.mail2"))
        assertEquals(setOf("org.example.mail2"), DesktopWindowCloser.packagesFor("mail", tasks, installed))
        assertEquals(setOf("org.example.mail2"), DesktopWindowCloser.packagesFor("org.example.mail2", tasks, emptyList()))
        assertTrue(DesktopWindowCloser.packagesFor(" ", tasks, installed).isEmpty())
    }

    // ---- (4) the recent-apps "Close all" guard ---------------------------------------------------------------------

    private fun element(label: String, pkg: String, id: String = "") =
        UiElement(0, "Button", label, label, "", "", id, Box(0, 0, 100, 50), listOf("click"), 1, pkg)

    @Test fun closeAllLabelsInSeveralLanguages() {
        for (l in listOf("Close all", "CLEAR ALL", "Clear all (3)", "Alle schließen", "Alle schliessen", "ALLE LÖSCHEN",
            "Alle beenden", "Alle Apps schließen", "Tout fermer", "Cerrar todo", "Chiudi tutto", "Alles sluiten")) {
            assertTrue(l, SharedRecentsGuard.closeAllLabel(l))
        }
        for (l in listOf("Close", "Clear", "All", "Closed", "Schließen", "Alle", "Löschen", "Close tab", "")) {
            assertFalse(l, SharedRecentsGuard.closeAllLabel(l))
        }
        assertTrue(SharedRecentsGuard.closeAllId("com.google.android.apps.nexuslauncher:id/clear_all"))
        assertFalse(SharedRecentsGuard.closeAllId("com.example:id/close"))
    }

    @Test fun closeAllIsRefusedInSystemWindowsOnly() {
        val launcher = element("Close all", "com.sec.android.app.launcher")
        assertEquals(SharedRecentsGuard.CLOSE_ALL_REFUSAL, SharedRecentsGuard.tapRefusal(launcher, "", "com.sec.android.app.launcher"))
        assertNotNull(SharedRecentsGuard.tapRefusal(element("Alle schließen", "com.android.systemui"), "", "com.android.systemui"))
        // By view id, in any language; by a home app of this device; by a recents-titled window.
        assertNotNull(SharedRecentsGuard.tapRefusal(element("Tout effacer?", "x", "x:id/clear_all"), "", "com.android.launcher3"))
        assertNotNull(SharedRecentsGuard.tapRefusal(element("Close all", "com.teslacoilsw.launcher"), "", "", setOf("com.teslacoilsw.launcher")))
        assertNotNull(SharedRecentsGuard.tapRefusal(element("Clear all", "com.vendor.ui"), "Recent apps", "com.vendor.ui"))
        // An app's own "Close all tabs" is the user's business.
        assertNull(SharedRecentsGuard.tapRefusal(element("Close all tabs", "com.android.chrome"), "Chrome", "com.android.chrome"))
        assertNull(SharedRecentsGuard.tapRefusal(element("Open", "com.sec.android.app.launcher"), "", "com.sec.android.app.launcher"))
        // Swiping cards away in the recents view, but not on the launcher's home screen or taskbar.
        assertNotNull(SharedRecentsGuard.swipeRefusal("Recent apps", "com.sec.android.app.launcher"))
        assertNotNull(SharedRecentsGuard.swipeRefusal("Übersicht", "com.google.android.apps.nexuslauncher"))
        assertNull(SharedRecentsGuard.swipeRefusal("Home", "com.sec.android.app.launcher"))
        assertNull(SharedRecentsGuard.swipeRefusal("Recent files", "com.example.files"))
    }

    @Test fun recentsOnTheDesktopWarnsTheModel() = runTest {
        val host = FakeDesktopHost()
        val r = DesktopExecutor(host, host.geometry()!!, emptySet()).key("recents")
        assertTrue(r.ok)
        assertTrue(r.message, r.message.contains("never tap Close all"))
        assertTrue(r.message.contains("desktop_close_windows"))
    }

    // ---- (2) keyboard shortcuts ------------------------------------------------------------------------------------

    @Test fun catalogEntriesAreDocumentedAndParse() {
        val sources = setOf(DesktopShortcuts.SAMSUNG_DEX_HELP, DesktopShortcuts.AOSP_SYSTEM_SHORTCUTS,
            DesktopShortcuts.ANDROID_DESKTOP_HELP, DesktopShortcuts.AOSP_TEXT_SHORTCUTS)
        assertEquals(DesktopShortcuts.all.size, DesktopShortcuts.all.map { it.name }.toSet().size)
        for (s in DesktopShortcuts.all) {
            assertTrue(s.name, s.bindings.isNotEmpty())
            for (b in s.bindings) {
                assertTrue("${s.name}: ${b.source}", b.source in sources && b.source.startsWith("https://"))
                DesktopShortcuts.parse(b.keys) // every documented combination parses
            }
            if (s.route == ShortcutRoute.REFUSED) assertTrue(s.name, s.note.isNotBlank())
        }
        // A key shortcut never shares its combination with a refused one.
        for (s in DesktopShortcuts.all.filter { it.route != ShortcutRoute.REFUSED }) {
            for (b in s.bindings) assertEquals(s.name, s, DesktopShortcuts.match(DesktopShortcuts.parse(b.keys)))
        }
    }

    @Test fun samsungDexFactsFromSamsungsList() {
        val dex = DesktopShortcutEnv.SAMSUNG_DEX
        assertEquals(listOf(listOf("meta", "enter")), DesktopShortcuts.named("home")!!.keysIn(dex))
        assertEquals(listOf(listOf("meta", "backspace")), DesktopShortcuts.named("back")!!.keysIn(dex))
        assertEquals(listOf(listOf("alt", "f4")), DesktopShortcuts.named("close_window")!!.keysIn(dex))
        assertEquals(listOf(listOf("meta", "tab")), DesktopShortcuts.named("recent_apps")!!.keysIn(dex))
        assertEquals(listOf(listOf("alt", "tab")), DesktopShortcuts.named("switch apps")!!.keysIn(dex))
        assertEquals(listOf(listOf("meta", "n")), DesktopShortcuts.named("notifications")!!.keysIn(dex))
        assertEquals(listOf(listOf("meta", "l")), DesktopShortcuts.named("lock")!!.keysIn(dex))
        // Snapping is not in Samsung's list: not offered on DeX (window slots do it), offered on Android desktop windowing.
        assertFalse(DesktopShortcuts.named("snap_left")!!.availableIn(dex))
        assertTrue(DesktopShortcuts.named("snap_left")!!.availableIn(DesktopShortcutEnv.ANDROID_DESKTOP))
        assertEquals(listOf(listOf("meta", "left_bracket")), DesktopShortcuts.named("snap_left")!!.keysIn(DesktopShortcutEnv.ANDROID_DESKTOP))
        assertFalse(DesktopShortcuts.named("recent_apps")!!.availableIn(dex))
        assertTrue(DesktopShortcuts.named("close_window")!!.availableIn(DesktopShortcutEnv.DEXTOP_SHELL))
        assertNull(DesktopShortcuts.named("nope"))
    }

    @Test fun environmentAndParsing() {
        assertEquals(DesktopShortcutEnv.SAMSUNG_DEX, DesktopShortcutEnv.of("samsung_dex", "Google"))
        assertEquals(DesktopShortcutEnv.ANDROID_DESKTOP, DesktopShortcutEnv.of("android_freeform", "samsung"))
        assertEquals(DesktopShortcutEnv.ANDROID_DESKTOP, DesktopShortcutEnv.of("pixel_aosp_compat", null))
        assertEquals(DesktopShortcutEnv.DEXTOP_SHELL, DesktopShortcutEnv.of("dextop_plasma", "samsung"))
        assertEquals(DesktopShortcutEnv.SAMSUNG_DEX, DesktopShortcutEnv.of(null, "samsung"))
        assertEquals(DesktopShortcutEnv.ANDROID_DESKTOP, DesktopShortcutEnv.of(" ", "Google"))

        assertEquals(DesktopShortcuts.Chord(KeyEvent.KEYCODE_D, KeyEvent.META_META_ON), DesktopShortcuts.parse(listOf("meta", "d")))
        assertEquals(DesktopShortcuts.Chord(KeyEvent.KEYCODE_D, KeyEvent.META_META_ON), DesktopShortcuts.parse(listOf("Win+D")))
        assertEquals(DesktopShortcuts.Chord(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
            DesktopShortcuts.parse(listOf("ctrl", "shift", "t")))
        assertEquals(KeyEvent.KEYCODE_F4, DesktopShortcuts.parse(listOf("alt", "F4")).keyCode)
        assertEquals(KeyEvent.KEYCODE_LEFT_BRACKET, DesktopShortcuts.parse(listOf("cmd", "[")).keyCode)
        assertEquals(KeyEvent.KEYCODE_MINUS, DesktopShortcuts.parse(listOf("meta", "-")).keyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, DesktopShortcuts.parse(listOf("meta", "left")).keyCode)
        assertEquals(DesktopShortcuts.Chord(KeyEvent.KEYCODE_META_LEFT, 0), DesktopShortcuts.parse(listOf("meta")))
        assertEquals(DesktopShortcuts.Chord(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_CTRL_ON), DesktopShortcuts.parse(listOf("ctrl", "meta")))
        for (bad in listOf(emptyList(), listOf("ctrl", "a", "b"), listOf("hyper", "x"), listOf("a", "b", "c", "d", "e", "f"))) {
            assertTrue(bad.toString(), runCatching { DesktopShortcuts.parse(bad) }.isFailure)
        }
        assertEquals("lock", DesktopShortcuts.match(DesktopShortcuts.parse(listOf("win", "L")))?.name)
        assertNull(DesktopShortcuts.match(DesktopShortcuts.parse(listOf("ctrl", "l"))))
    }

    @Test fun keyCombosActingOnThePhoneAreRefusedAndHomeBackCloseStayOnTheDesktop() = runTest {
        val host = TaskHost()
        val ctx = Ctx(host)
        for (keys in listOf("""["meta","l"]""", """["alt","tab"]""", """["meta","tab"]""", """["meta","n"]""", """["meta","a"]""",
            """["meta","ctrl","s"]""", """["power"]""", """["ctrl","power"]""")) {
            val r = run(ctx, "desktop_key_combo", """{"keys":$keys}""")
            assertTrue("$keys: ${r.text}", r.isError)
        }
        assertTrue(host.base.calls.none { it.startsWith("key") })
        // Home and back are carried out on the desktop display, never as the system's own global shortcut.
        assertFalse(run(ctx, "desktop_key_combo", """{"keys":["meta","enter"]}""").isError)
        assertEquals("global home", host.base.calls.last())
        assertFalse(run(ctx, "desktop_key_combo", """{"keys":["meta","backspace"]}""").isError)
        assertEquals("global back", host.base.calls.last())
        // Alt+F4 closes the desktop's focused window through the task manager, not the phone's front app.
        assertFalse(run(ctx, "desktop_key_combo", """{"keys":["alt","f4"]}""").isError)
        assertEquals(listOf(12), host.closed)
        assertTrue(host.base.calls.none { it.startsWith("key") })
        // press_key chords take the same route.
        assertTrue(run(ctx, "press_key", """{"key":"meta+l"}""").isError)
        assertTrue(host.base.calls.none { it.startsWith("key") })
        // Ordinary combinations are sent as keys on the desktop.
        assertFalse(run(ctx, "desktop_key_combo", """{"keys":["ctrl","shift","t"]}""").isError)
        assertEquals("key ${KeyEvent.KEYCODE_T} ${KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON}", host.base.calls.last())
        assertFalse(run(ctx, "desktop_key_combo", """{"keys":"meta+d"}""").isError)
        assertEquals("key ${KeyEvent.KEYCODE_D} ${KeyEvent.META_META_ON}", host.base.calls.last())
        assertTrue(run(ctx, "desktop_key_combo", """{"keys":["ctrl","a","b"]}""").isError)
    }

    @Test fun namedShortcutsFollowTheDesktopEnvironment() = runTest {
        val host = TaskHost(env = "samsung_dex")
        val ctx = Ctx(host)
        assertFalse(run(ctx, "desktop_shortcut", """{"name":"home"}""").isError)
        assertEquals("global home", host.base.calls.last())
        assertFalse(run(ctx, "desktop_shortcut", """{"name":"back"}""").isError)
        assertEquals("global back", host.base.calls.last())
        val selectAll = run(ctx, "desktop_shortcut", """{"name":"select_all"}""")
        assertFalse(selectAll.text, selectAll.isError)
        assertEquals("key ${KeyEvent.KEYCODE_A} ${KeyEvent.META_CTRL_ON}", host.base.calls.last())
        val copy = run(ctx, "desktop_shortcut", """{"name":"copy"}""")
        assertTrue(copy.text, copy.text.contains("phone's clipboard"))
        // Not documented for DeX: refused with the way to do it instead.
        val snap = run(ctx, "desktop_shortcut", """{"name":"snap_left"}""")
        assertTrue(snap.isError)
        assertTrue(snap.text, snap.text.contains("desktop_launch_app") && snap.text.contains("Samsung DeX"))
        assertTrue(run(ctx, "desktop_shortcut", """{"name":"recent_apps"}""").isError)
        assertTrue(run(ctx, "desktop_shortcut", """{"name":"teleport"}""").isError)
        assertFalse(run(ctx, "desktop_shortcut", """{"name":"close_window"}""").isError)
        assertEquals(listOf(12), host.closed)

        host.env = "android_freeform"
        val snapAosp = run(ctx, "desktop_shortcut", """{"name":"snap_left"}""")
        assertFalse(snapAosp.text, snapAosp.isError)
        assertEquals("key ${KeyEvent.KEYCODE_LEFT_BRACKET} ${KeyEvent.META_META_ON}", host.base.calls.last())
        assertFalse(run(ctx, "desktop_shortcut", """{"name":"toggle_maximize"}""").isError)
        assertEquals("key ${KeyEvent.KEYCODE_EQUALS} ${KeyEvent.META_META_ON}", host.base.calls.last())
    }

    @Test fun shortcutToolsAreDesktopToolsAndReachMcp() {
        val desktop = reg.forAgent(ToolRegistry.Availability(desktop = true)).map { it.name }
        assertTrue(desktop.containsAll(listOf("desktop_close_windows", "desktop_shortcut", "desktop_key_combo")))
        val phoneOnly = reg.forAgent(ToolRegistry.Availability(desktop = false)).map { it.name }
        assertTrue(phoneOnly.none { it in setOf("desktop_close_windows", "desktop_shortcut", "desktop_key_combo") })
        assertTrue(reg.forMcp(ToolRegistry.Availability(desktop = true)).map { it.name }.contains("desktop_close_windows"))
        val schema = reg["desktop_shortcut"]!!.schema.getJSONObject("properties").getJSONObject("name").getJSONArray("enum").toString()
        assertTrue(schema.contains("close_window") && !schema.contains("recent_apps") && !schema.contains("lock"))
        assertTrue(reg["desktop_close_windows"]!!.description.contains("never use Close all"))
    }

    @Test fun promptTeachesShortcutsLaunchByNameAndDesktopOnlyClosing() {
        val line = DesktopShortcuts.promptLine(DesktopShortcutEnv.SAMSUNG_DEX)
        assertTrue(line, line.contains("home (Meta+Enter)") && line.contains("back (Meta+Backspace)") && line.contains("close_window (Alt+F4)"))
        assertTrue(line.contains("select_all (Ctrl+A)"))
        assertFalse(line.contains("snap_left") || line.contains("recent_apps") || line.contains("lock"))
        val aosp = DesktopShortcuts.promptLine(DesktopShortcutEnv.ANDROID_DESKTOP)
        assertTrue(aosp, aosp.contains("snap_left (Meta+[)") && aosp.contains("home (Meta+H)") && aosp.contains("minimize (Meta+-)"))

        val p = SystemPrompt.build(PromptContext(TargetKind.DESKTOP, "the Dextop desktop (display 7)", true, planMode = false,
            confirmSensitive = true, interactive = true, vision = true, nowMs = 0, zone = java.time.ZoneId.of("UTC"),
            locale = java.util.Locale.ENGLISH, desktopShortcuts = line))
        assertTrue(p.contains("desktop_shortcut — home (Meta+Enter)"))
        assertTrue(p.contains("desktop_key_combo"))
        assertTrue(p.contains("Open apps by name with desktop_launch_app/launch_app"))
        assertTrue(p.contains("Never browse, scroll or search the app drawer"))
        assertTrue(p.contains("To close desktop apps use desktop_close_windows"))
        assertTrue(p.contains("Never tap Close all / Clear all or swipe cards away in the recent-apps view"))
        // The phone target keeps its own rules.
        val phone = SystemPrompt.build(PromptContext(TargetKind.PHONE, null, true, planMode = false, confirmSensitive = true,
            interactive = true, vision = true, nowMs = 0, zone = java.time.ZoneId.of("UTC"), locale = java.util.Locale.ENGLISH))
        assertFalse(phone.contains("To close desktop apps use desktop_close_windows"))
        // Shared global guidance is present for every engine/target, independently of desktop-specific tools.
        assertTrue(phone.contains("## Global instructions"))
    }
}
