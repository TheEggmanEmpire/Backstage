package com.eggman.backstage.exec

import com.eggman.backstage.testutil.Screens
import com.eggman.backstage.tools.TestToolContext
import com.eggman.backstage.tools.ToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMarkupTest {
    private fun n(cls: String, text: String = "", desc: String = "", b: Box = Box(0, 0, 100, 100), clickable: Boolean = false,
                  editable: Boolean = false, scrollable: Boolean = false, children: List<UiNode> = emptyList(), pkg: String = "com.app",
                  id: String = "", focused: Boolean = false) =
        UiNode(className = cls, text = text, contentDescription = desc, bounds = b, clickable = clickable, editable = editable,
            scrollable = scrollable, children = children, packageName = pkg, resourceId = id, focusable = focused, focused = focused)

    private fun capture(vararg windows: UiWindow) = ScreenCapture(0, 1080, 2400, windows.toList())
    private fun win(id: Int, root: UiNode, layer: Int = 1, pkg: String = root.packageName, kind: WindowKind = WindowKind.APPLICATION,
                    bounds: Box = Box(0, 0, 1080, 2400)) = UiWindow(id, kind, "", pkg, layer, bounds, root = root)

    @Test fun nidIsStableHexAndSensitiveToTextAndPosition() {
        val a = n("android.widget.Button", "Send", b = Box(10, 20, 110, 80))
        val id = ScreenMarkup.nid(a, 5)
        assertEquals(16, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
        assertEquals(id, ScreenMarkup.nid(a.copy(), 5))
        assertNotEquals(id, ScreenMarkup.nid(a.copy(text = "Sent"), 5))
        assertNotEquals(id, ScreenMarkup.nid(a.copy(bounds = Box(10, 21, 110, 80)), 5))
        assertNotEquals(id, ScreenMarkup.nid(a, 6))
    }

    @Test fun markupStructureAttributesAndBoundsRule() {
        val row = n("android.widget.LinearLayout", clickable = true, b = Box(0, 100, 1080, 200), children = listOf(
            n("android.widget.TextView", "Anna", b = Box(10, 110, 500, 150)),
            n("android.widget.TextView", "See \"you\" <8>", b = Box(10, 150, 900, 190)),
        ))
        val field = n("android.widget.EditText", "hello", editable = true, focused = true, b = Box(0, 2000, 900, 2100))
        val list = n("androidx.recyclerview.widget.RecyclerView", children = listOf(row), b = Box(0, 100, 1080, 1900)) // not relevant: promoted
        val root = n("android.widget.FrameLayout", children = listOf(list, field), b = Box(0, 0, 1080, 2400))
        val md = ScreenMarkup.render(capture(win(3, root)))
        assertTrue(md, md.startsWith("<app package=\"com.app\">\n"))
        assertTrue(md.endsWith("</app>"))
        assertTrue(md.contains("<LinearLayout nid=\""))
        assertTrue(md.contains(" clickable b=\"0,100,1080,200\">"))
        // children of an interactive node lose their bounds
        assertTrue(md.contains("<TextView txt=\"Anna\" nid=\""))
        assertFalse(md.contains("txt=\"Anna\" nid=\"${ScreenMarkup.nid(row.children[0], 3)}\" b="))
        assertTrue(md.contains("txt=\"See &quot;you&quot; &lt;8&gt;\""))
        assertTrue(md.contains("<EditText txt=\"hello\" nid=\"${ScreenMarkup.nid(field, 3)}\" editable focused b=\"0,2000,900,2100\" val=\"hello\" />"))
        assertFalse(md.contains("RecyclerView"))
        assertTrue(md.contains("</LinearLayout>"))
    }

    @Test fun groupsExcludeKeyboardSystemUiAndOwnApp() {
        val app = win(1, n("android.widget.FrameLayout", children = listOf(n("android.widget.Button", "Go", clickable = true))))
        val ime = win(2, n("android.widget.Button", "q", clickable = true, pkg = "com.google.android.inputmethod.latin"), layer = 5, kind = WindowKind.INPUT_METHOD)
        val sysui = win(3, n("android.widget.TextView", "12:00", pkg = "com.android.systemui"), layer = 9, kind = WindowKind.SYSTEM)
        val mine = win(4, n("android.widget.Button", "Bubble", clickable = true, pkg = "com.eggman.backstage"), layer = 10)
        val popup = win(5, n("android.widget.Button", "Menu item", clickable = true), layer = 3)
        val md = ScreenMarkup.render(capture(app, ime, sysui, mine, popup), setOf("com.eggman.backstage"))
        assertEquals(1, Regex("<app ").findAll(md).count()) // popup merged into the same package group
        assertTrue(md.indexOf("Menu item") < md.indexOf("txt=\"Go\"")) // top-most window first
        assertFalse(md.contains("12:00") || md.contains("Bubble") || md.contains("txt=\"q\""))
        // SystemUI is shown when nothing else is there
        assertTrue(ScreenMarkup.render(capture(sysui)).contains("com.android.systemui"))
        assertEquals("<app package=\"unknown\">\n</app>", ScreenMarkup.render(capture()))
    }

    @Test fun findByNidSearchesAllWindows() {
        val target = n("android.widget.Button", "Allow", clickable = true, pkg = "com.android.permissioncontroller")
        val c = capture(win(1, n("android.widget.FrameLayout", children = listOf(n("android.widget.TextView", "x")))), win(2, target, layer = 2))
        val (node, w) = ScreenMarkup.findByNid(c, ScreenMarkup.nid(target, 2))!!
        assertEquals("Allow", node.text)
        assertEquals(2, w)
    }

    @Test fun volatileTextDoesNotChangeFingerprint() {
        assertTrue(VolatileText.isVolatile("12:45"))
        assertTrue(VolatileText.isVolatile("9:05 PM"))
        assertTrue(VolatileText.isVolatile("87%"))
        assertTrue(VolatileText.isVolatile("3/10"))
        assertTrue(VolatileText.isVolatile("Anna is typing…"))
        assertTrue(VolatileText.isVolatile("online"))
        assertTrue(VolatileText.isVolatile("last seen today at 10:02"))
        assertFalse(VolatileText.isVolatile("Loading 45%"))
        assertFalse(VolatileText.isVolatile("Send"))
        val s = ScreenSerializer()
        assertEquals(s.serialize(Screens.buttons("OK", "12:00"), "phone").fingerprint, s.serialize(Screens.buttons("OK", "12:01"), "phone").fingerprint)
    }

    @Test fun systemUiBarsAreDroppedFromListing() {
        val base = Screens.buttons("Go")
        val bar = UiWindow(7, WindowKind.SYSTEM, "", "com.android.systemui", 9, Box(0, 0, 1080, 80),
            root = UiNode(className = "android.widget.TextView", text = "12:00", bounds = Box(0, 0, 200, 80), packageName = "com.android.systemui"))
        val shade = bar.copy(id = 8, bounds = Box(0, 0, 1080, 2400), root = UiNode(className = "android.widget.TextView", text = "Wi-Fi",
            bounds = Box(0, 300, 500, 400), packageName = "com.android.systemui"))
        val st = ScreenSerializer().serialize(base.copy(windows = base.windows + bar), "phone")
        assertEquals(listOf("Go"), st.elements.map { it.label })
        val withShade = ScreenSerializer().serialize(base.copy(windows = base.windows + shade), "phone")
        assertEquals(listOf("Wi-Fi"), withShade.elements.map { it.label })
        assertEquals(16, st.elements.single().nid.length)
    }
}

class NewToolsTest {
    private val reg = ToolRegistry.default()
    private fun run(ctx: TestToolContext, name: String, args: String = "{}") = runBlocking { reg.execute(ctx, name, JSONObject(args)) }

    @Test fun tapByNid() {
        val ctx = TestToolContext()
        ctx.phone.capture = Screens.buttons("A", "B")
        val st = runBlocking { ctx.phone.screen() }
        val r = run(ctx, "tap", """{"nid":"${st.elements[1].nid}"}""")
        assertFalse(r.text, r.isError)
        assertEquals("click [1] B", ctx.phone.actions.single())
        assertTrue(run(ctx, "tap", """{"nid":"0000000000000000"}""").text.contains("NODE_NOT_FOUND"))
    }

    @Test fun listAppsKeywords() {
        val ctx = TestToolContext()
        val r = run(ctx, "list_apps", """{"keywords":["MAPS","gm"]}""")
        assertTrue(r.text, r.text.startsWith("Found 2 app(s)"))
        assertTrue(r.text.contains("- Gmail (com.google.android.gm)"))
        assertTrue(run(ctx, "list_apps", """{"keywords":["zzz"]}""").text.contains("No installed app"))
        assertTrue(run(ctx, "list_apps", """{"keywords":[" "]}""").isError)
    }

    @Test fun reasonParameterOnActingToolsOnly() {
        assertTrue(reg["tap"]!!.spec().schema.getJSONObject("properties").has("reason"))
        assertFalse(reg["get_screen"]!!.spec().schema.getJSONObject("properties").has("reason"))
        assertFalse(reg["tap"]!!.schema.getJSONObject("properties").has("reason")) // MCP schema unchanged
    }

    @Test fun awaitScreenChange() = runTest {
        val ctx = TestToolContext()
        val exec = ctx.phone
        exec.capture = Screens.buttons("Loading")
        exec.screen()
        val job = backgroundScope.launch {
            delay(2_500)
            exec.capture = Screens.buttons("Loaded")
        }
        val r = reg.execute(ctx, "await_screen_change", JSONObject("""{"timeout_ms":6000}"""))
        assertFalse(r.text, r.isError)
        assertTrue(r.text.contains("Screen changed after"))
        job.cancel()
        val t = reg.execute(ctx, "await_screen_change", JSONObject("""{"timeout_ms":2500}"""))
        assertTrue(t.isError)
        assertTrue(t.text.startsWith("TIMEOUT"))
        assertTrue(t.text.contains("timeout_ms=6000"))
    }

    /** repeated waits for the same reason within a minute escalate instead of suggesting more waiting. */
    @Test fun repeatedWaitsForTheSameReasonEscalate() = runTest {
        val ctx = TestToolContext()
        ctx.phone.capture = Screens.buttons("Loading")
        ctx.phone.screen()
        val args = JSONObject("""{"timeout_seconds":1,"reason":"Results to load"}""")
        val first = reg.execute(ctx, "await_screen_change", args)
        assertTrue(first.text.contains("call again with a longer wait"))
        val second = reg.execute(ctx, "await_screen_change", JSONObject("""{"timeout_seconds":1,"reason":"results  to LOAD"}"""))
        assertTrue(second.text, second.text.contains("wait number 2"))
        val other = reg.execute(ctx, "await_screen_change", JSONObject("""{"timeout_seconds":1,"reason":"the dialog"}"""))
        assertFalse(other.text.contains("wait number"))
    }
}
