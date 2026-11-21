package com.eggman.backstage.a11y

import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.tools.NotificationEntry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yTest {
    // Generic component strings for the list logic (the app's real one is MirrorService, see ensureEnabledDropsTheRetiredService).
    private val me = "com.eggman.backstage/com.eggman.backstage.a11y.AgentAccessibilityService"
    private val dextop = "moe.n4tsu.dextop/moe.n4tsu.dextop.MirrorService"

    @Test fun mergeKeepsOtherServicesAndOrder() {
        assertEquals(me, AccessibilityEnabler.merge(null, me))
        assertEquals(me, AccessibilityEnabler.merge("null", me))
        assertEquals("$dextop:$me", AccessibilityEnabler.merge(dextop, me))
        assertEquals("$dextop:$me", AccessibilityEnabler.merge("$dextop:$me", me))
        // short form of our component counts as present
        val short = "com.eggman.backstage/.a11y.AgentAccessibilityService"
        assertTrue(AccessibilityEnabler.contains("$dextop:$short", me))
        assertEquals("$dextop:$short", AccessibilityEnabler.merge("$dextop:$short", me))
        assertEquals(dextop, AccessibilityEnabler.remove("$dextop:$short", me))
        assertEquals(listOf(dextop), AccessibilityEnabler.parse(" $dextop :: "))
    }

    private class FakeShell(var secure: MutableMap<String, String>, val failPut: Boolean = false, val dropOnPut: Boolean = false) : AccessibilityEnabler.Shell {
        val commands = mutableListOf<String>()
        override suspend fun run(vararg argv: String): Pair<Int, String> {
            commands += argv.joinToString(" ")
            return when (argv[1]) {
                "get" -> 0 to (secure[argv[3]] ?: "null")
                "put" -> if (failPut) 1 to "denied" else { if (!dropOnPut) secure[argv[3]] = argv[4]; 0 to "" }
                else -> 1 to ""
            }
        }
    }

    @Test fun ensureEnabledViaShell() = runBlocking {
        val sh = FakeShell(mutableMapOf("enabled_accessibility_services" to dextop, "accessibility_enabled" to "1"))
        assertEquals(AccessibilityEnabler.Outcome.Enabled, AccessibilityEnabler.ensureEnabled(sh, me))
        assertEquals("$dextop:$me", sh.secure["enabled_accessibility_services"])
        assertEquals("1", sh.secure["accessibility_enabled"])
        assertEquals(AccessibilityEnabler.Outcome.AlreadyEnabled, AccessibilityEnabler.ensureEnabled(sh, me))

        val off = FakeShell(mutableMapOf("enabled_accessibility_services" to me, "accessibility_enabled" to "0"))
        assertEquals(AccessibilityEnabler.Outcome.Enabled, AccessibilityEnabler.ensureEnabled(off, me))
        assertEquals("1", off.secure["accessibility_enabled"])

        assertTrue(AccessibilityEnabler.ensureEnabled(FakeShell(mutableMapOf(), failPut = true), me) is AccessibilityEnabler.Outcome.Failed)
        assertTrue(AccessibilityEnabler.ensureEnabled(FakeShell(mutableMapOf(), dropOnPut = true), me) is AccessibilityEnabler.Outcome.Failed)
    }

    @Test fun ensureEnabledDropsTheRetiredService() = runBlocking {
        val mirror = "com.eggman.backstage/moe.n4tsu.dextop.MirrorService"
        val retired = "com.eggman.backstage/" + AccessibilityBridge.RETIRED_AGENT_SERVICE
        // had both services on: the update keeps MirrorService and drops the stale agent entry.
        val both = FakeShell(mutableMapOf("enabled_accessibility_services" to "x/.Y:$mirror:$retired", "accessibility_enabled" to "1"))
        assertEquals(AccessibilityEnabler.Outcome.Enabled, AccessibilityEnabler.ensureEnabled(both, mirror, retired))
        assertEquals("x/.Y:$mirror", both.secure["enabled_accessibility_services"])
        assertEquals(AccessibilityEnabler.Outcome.AlreadyEnabled, AccessibilityEnabler.ensureEnabled(both, mirror, retired))
        // Only the agent service is on: MirrorService replaces it.
        val agentOnly = FakeShell(mutableMapOf("enabled_accessibility_services" to retired, "accessibility_enabled" to "1"))
        assertEquals(AccessibilityEnabler.Outcome.Enabled, AccessibilityEnabler.ensureEnabled(agentOnly, mirror, retired))
        assertEquals(mirror, agentOnly.secure["enabled_accessibility_services"])
    }

    @Test fun guidanceNamesTheOneBackstageService() {
        val g = AccessibilityEnabler.manualGuidance("Backstage", 32)
        assertTrue(g, g.contains("\"Backstage\" accessibility service"))
        assertFalse(g, g.contains("agent"))
    }

    @Test fun guidanceMentionsRestrictedSettingsOn13Plus() {
        assertTrue(AccessibilityEnabler.manualGuidance("Backstage", 34).contains("Allow restricted settings"))
        assertFalse(AccessibilityEnabler.manualGuidance("Backstage", 32).contains("restricted"))
    }

    @Test fun settleDetectorWaitsForQuietPeriodWithCap() = runTest {
        val d = UiSettleDetector { testScheduler.currentTime }
        // no events: settles after min + quiet
        val r1 = d.awaitSettled(quietMs = 300, maxMs = 2_000, minMs = 100)
        assertTrue(r1.settled)
        assertTrue(r1.waitedMs in 300..400)
        // events keep coming every 100 ms for 1 s: settles 300 ms after the last one
        val start = testScheduler.currentTime
        val feeder = launch { repeat(10) { kotlinx.coroutines.delay(100); d.onEvent() } }
        val r2 = d.awaitSettled(quietMs = 300, maxMs = 5_000, minMs = 0)
        feeder.join()
        assertTrue(r2.settled)
        assertTrue(testScheduler.currentTime - start in 1_300..1_400)
        assertEquals(10, r2.events)
        // endless events: capped
        val busy = launch { while (true) { kotlinx.coroutines.delay(50); d.onEvent() } }
        val r3 = d.awaitSettled(quietMs = 300, maxMs = 1_000, minMs = 0)
        busy.cancel()
        assertFalse(r3.settled)
        assertTrue(r3.waitedMs in 1_000..1_100)
    }

    @Test fun notificationFeedDedupesAndCaps() {
        NotificationFeed.clear()
        NotificationFeed.ignoredPackages = setOf("com.eggman.backstage")
        NotificationFeed.add(NotificationEntry("com.whatsapp", "WhatsApp", "Anna: hi", 1))
        NotificationFeed.add(NotificationEntry("com.whatsapp", "WhatsApp", "Anna: hi", 2))
        NotificationFeed.add(NotificationEntry("com.eggman.backstage", "Backstage", "Working", 3))
        NotificationFeed.add(NotificationEntry("com.x", "X", "", 4))
        assertEquals(1, NotificationFeed.recent().size)
        assertEquals(2L, NotificationFeed.recent().first().timeMs)
        repeat(100) { NotificationFeed.add(NotificationEntry("p$it", "P", "msg $it", it.toLong())) }
        assertEquals(60, NotificationFeed.recent().size)
        assertEquals("msg 99", NotificationFeed.recent().first().text)
        NotificationFeed.clear()
    }

    @Test fun shellWireDecoding() {
        val ok = Privileged.decode("0\nhello\nworld\n\u0000")
        assertTrue(ok.ok)
        assertEquals("hello\nworld", ok.out)
        val err = Privileged.decode("2\n\n\u0000no such file")
        assertEquals(2, err.code)
        assertEquals("no such file", err.err)
        assertEquals(127, Privileged.decode("garbage").code)
    }
}
