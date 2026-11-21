package com.eggman.backstage.a11y

import android.view.InputEvent
import com.eggman.backstage.agent.AgentService
import com.eggman.backstage.exec.InputInjector
import com.eggman.backstage.mcp.FakeTools
import com.eggman.backstage.mcp.HttpRequest
import com.eggman.backstage.mcp.McpProtocol
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Accessibility state, input injection, and MCP runtime invariants. */
class AccessibilityAndMcpRuntimeTest {

    @Test fun settleIsTrackedPerDisplay() {
        var now = 1_000L
        val s = PerDisplaySettle { now }
        val phone = s.forDisplay(0)
        val desktop = s.forDisplay(7)
        s.onEvent(0)
        assertEquals(1, phone.eventCount)
        assertEquals(0, desktop.eventCount) // a busy phone does not keep the desktop "unsettled"
        now += 10
        s.onEvent(-1) // no display: counts everywhere
        assertEquals(2, phone.eventCount)
        assertEquals(1, desktop.eventCount)
    }

    @Test fun userTurningTheServiceOffIsRespected() {
        val w = AccessibilityAutoEnable.TRANSITION_WINDOW_MS
        // gone from the list, no desktop change or own write nearby → the user switched it off
        assertTrue(AccessibilityAutoEnable.isUserOptOut(stillListed = false, nowMs = 100_000, lastDesktopChangeMs = 0, lastSelfWriteMs = 0))
        // Dextop's enabled-list rewrite at session start
        assertFalse(AccessibilityAutoEnable.isUserOptOut(false, 100_000, 100_000 - w / 2, 0))
        // our own write
        assertFalse(AccessibilityAutoEnable.isUserOptOut(false, 100_000, 0, 100_000 - w / 2))
        // still listed (process restart, app update): not an opt-out
        assertFalse(AccessibilityAutoEnable.isUserOptOut(true, 100_000, 0, 0))
    }

    class FakeInputManager(var fail: Boolean = false) {
        var calls = 0
        @Suppress("unused", "UNUSED_PARAMETER")
        fun injectInputEvent(event: InputEvent, mode: Int): Boolean {
            calls++
            if (fail) throw IllegalStateException("binder died")
            return true
        }
    }

    @Test fun injectorPicksUpPrivilegeThatAppearsLaterAndRecoversFromADeadProxy() = runBlocking {
        var current: FakeInputManager? = null
        var lookups = 0
        val inj = InputInjector(0) { lookups++; current }
        assertFalse(inj.available) // no Shizuku yet: not cached as "never"
        current = FakeInputManager()
        assertTrue(inj.available)
        assertTrue(inj.key(66))
        val first = current!!
        first.fail = true
        assertFalse(inj.key(66)) // dead proxy: dropped
        current = FakeInputManager()
        assertTrue(inj.key(66)) // looked up again
        assertTrue(current!!.calls > 0)
        assertTrue(lookups >= 3)
    }

    @Test fun mcpSessionsAreCapped() = runBlocking {
        val p = McpProtocol(FakeTools())
        val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
        repeat(McpProtocol.MAX_SESSIONS + 40) {
            p.handlePost(HttpRequest("POST", "/mcp", "HTTP/1.1", mapOf("content-type" to "application/json"), init.toByteArray()))
        }
        assertEquals(McpProtocol.MAX_SESSIONS, p.sessionCount())
        assertTrue(JSONObject(init).has("id"))
    }

    @Test fun notificationNamesTheReachableMcpAddress() {
        assertEquals("MCP server: http://192.168.1.20:8485/mcp", AgentService.mcpUrlLine("192.168.1.20", 8485, lan = true))
        val local = AgentService.mcpUrlLine("192.168.1.20", 8485, lan = false)
        assertTrue(local.startsWith("MCP server: http://127.0.0.1:8485/mcp"))
        assertTrue("adb forward tcp:8485 tcp:8485" in local)
    }
}
