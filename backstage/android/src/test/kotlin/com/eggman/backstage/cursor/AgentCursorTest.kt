package com.eggman.backstage.cursor

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import android.graphics.Bitmap
import com.eggman.backstage.exec.DesktopExecutor
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.testutil.FakeDesktopHost
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The animated agent cursor: state per action type, geometry, timing, and that the agent never captures it. */
class AgentCursorTest {
    class RecordingSink(var scale: Float = 1f, val log: MutableList<String> = mutableListOf()) : CursorSink {
        val states = mutableListOf<CursorState>()
        override fun render(state: CursorState) { states += state; log += "render ${state.action}" }
        override suspend fun hideForCapture(displayId: Int) { log += "hide $displayId" }
        override fun restoreAfterCapture(displayId: Int) { log += "restore $displayId" }
        override fun animatorScale() = scale
    }

    @Before fun setUp() = AgentCursor.reset()
    @After fun tearDown() = AgentCursor.reset()

    @Test fun eachActionTypeMovesTheCursorAndShowsItsEffect() {
        var s = CursorState()
        assertFalse(s.visible)
        s = CursorModel.next(s, CursorEvent.Tap(7, 100f, 200f))
        assertEquals(CursorAction.TAP, s.action); assertEquals(CursorPoint(100f, 200f), s.at); assertTrue(s.visible); assertEquals(7, s.displayId)
        s = CursorModel.next(s, CursorEvent.LongPress(7, 110f, 210f, 650))
        assertEquals(CursorAction.LONG_PRESS, s.action); assertEquals(650L, s.durationMs)
        s = CursorModel.next(s, CursorEvent.Swipe(7, 10f, 20f, 300f, 400f, 300))
        assertEquals(CursorAction.SWIPE, s.action)
        assertEquals(listOf(CursorPoint(10f, 20f), CursorPoint(300f, 400f)), s.path)
        assertEquals(CursorPoint(300f, 400f), CursorModel.endPoint(s))
        // Typing into the focused field keeps the cursor where it is; into an element it parks there.
        val parked = CursorModel.next(s, CursorEvent.Type(7, null, null))
        assertEquals(CursorAction.TYPE, parked.action); assertEquals(s.at, parked.at); assertTrue(parked.path.isEmpty())
        s = CursorModel.next(s, CursorEvent.Type(7, 50f, 60f))
        assertEquals(CursorPoint(50f, 60f), s.at)
        s = CursorModel.next(s, CursorEvent.Scroll(7, 500f, 500f, "Down"))
        assertEquals(CursorAction.SCROLL, s.action); assertEquals("down", s.direction)
        // Thinking stays at the last position; with no position yet there is nothing to show.
        val thinking = CursorModel.next(s, CursorEvent.Thinking())
        assertEquals(CursorAction.THINKING, thinking.action); assertEquals(s.at, thinking.at); assertEquals(7, thinking.displayId)
        assertEquals(CursorState(), CursorModel.next(CursorState(), CursorEvent.Thinking()))
        // A short label; another display starts fresh there; hide clears everything.
        val labelled = CursorModel.next(thinking, CursorEvent.Label("Tap   \"Compose\" in Gmail to start a brand new message"))
        assertTrue(labelled.label!!.length <= CursorModel.LABEL_MAX)
        assertTrue(labelled.label!!.endsWith("…"))
        val phone = CursorModel.next(labelled, CursorEvent.Tap(0, 5f, 6f))
        assertEquals(0, phone.displayId); assertEquals(CursorPoint(5f, 6f), phone.at); assertEquals(labelled.label, phone.label)
        assertEquals(CursorState(), CursorModel.next(phone, CursorEvent.Hide()))
    }

    @Test fun desktopCoordinatesMapOntoTheOverlay() {
        val p = CursorPoint(960f, 540f)
        assertEquals(p, CursorGeometry.toOverlay(p, 1920, 1080, 1920, 1080))
        assertEquals(CursorPoint(480f, 270f), CursorGeometry.toOverlay(p, 1920, 1080, 960, 540))
        // Never outside the overlay; unknown sizes keep the point.
        assertEquals(CursorPoint(959f, 0f), CursorGeometry.toOverlay(CursorPoint(5000f, -20f), 1920, 1080, 960, 540))
        assertEquals(p, CursorGeometry.toOverlay(p, 0, 0, 960, 540))
    }

    @Test fun timingFollowsTheSettingAndRemovedAnimations() {
        assertEquals(200L, CursorTiming.leadMs(true, 200, 1f))
        assertEquals(0L, CursorTiming.leadMs(true, 0, 1f))
        assertEquals(0L, CursorTiming.leadMs(false, 200, 1f))
        assertEquals(0L, CursorTiming.leadMs(true, 200, 0f)) // "Remove animations": static markers, no delay
        assertEquals(CursorTiming.MAX_LEAD_MS.toLong(), CursorTiming.leadMs(true, 5_000, 1f))
        assertEquals(0L, CursorTiming.glideMs(0f))
        assertTrue(CursorTiming.glideMs(1f) in 150L..300L)
        assertTrue(CursorTiming.glideMs(10f) <= 300L)
    }

    @Test fun theCursorLeadsTheActionOnceAndRespectsTheSwitch() = runTest {
        val sink = RecordingSink()
        AgentCursor.sink = sink
        AgentCursor.clock = { currentTime }
        AgentCursor.before(CursorEvent.Tap(7, 1f, 2f))
        assertEquals(200L, currentTime)
        // The same tap again right away (a click falling back to a tap) is not delayed a second time.
        AgentCursor.before(CursorEvent.Tap(7, 1f, 2f))
        assertEquals(200L, currentTime)
        AgentCursor.leadSettingMs = { 0 }
        AgentCursor.before(CursorEvent.Tap(7, 9f, 9f))
        assertEquals(200L, currentTime)
        // Animations removed: static markers, no delay.
        AgentCursor.leadSettingMs = { 200 }
        sink.scale = 0f
        AgentCursor.before(CursorEvent.Tap(7, 30f, 30f))
        assertEquals(200L, currentTime)
        // Switched off: nothing is drawn and nothing waits.
        sink.scale = 1f
        AgentCursor.enabled = { false }
        val drawn = sink.states.size
        AgentCursor.before(CursorEvent.Tap(7, 50f, 50f))
        AgentCursor.thinking()
        assertEquals(drawn, sink.states.size)
        assertEquals(200L, currentTime)
        AgentCursor.hide()
        assertFalse(sink.states.last().visible)
    }

    @Test fun theAgentsOwnScreenshotsNeverShowTheCursor() = runTest {
        val log = mutableListOf<String>()
        val sink = RecordingSink(log = log)
        AgentCursor.sink = sink
        AgentCursor.leadSettingMs = { 0 }
        val host = object : DesktopHost by FakeDesktopHost() {
            override suspend fun captureFrame(): Bitmap? { log += "capture"; return null }
        }
        val exec = DesktopExecutor(host, host.geometry()!!, setOf("com.eggman.backstage"))
        exec.tap(100f, 100f)
        log.clear()
        assertNull(exec.screenshot())
        if (PLAY_DISTRIBUTION) {
            assertEquals(listOf("hide 7", "restore 7"), log)
        } else {
            assertEquals(listOf("hide 7", "capture", "restore 7"), log)
            // No cursor on screen (or on another display): nothing to hide.
            AgentCursor.hide()
            log.clear()
            exec.screenshot()
            assertEquals(listOf("capture"), log.filterNot { it.startsWith("render") })
        }
    }
}
