package com.eggman.backstage.host

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopInputMathTest {

    @Test
    fun clampPointKeepsInteriorPoints() {
        assertEquals(10f to 20f, DesktopInputMath.clampPoint(10f, 20f, 1920, 1080))
    }

    @Test
    fun clampPointSnapsEdgeRoundingOntoTheGrid() {
        assertEquals(1919f to 1079f, DesktopInputMath.clampPoint(1920f, 1080f, 1920, 1080))
        assertEquals(0f to 0f, DesktopInputMath.clampPoint(-1.5f, -0.2f, 1920, 1080))
    }

    @Test
    fun clampPointRejectsFarOutsideAndNonFinite() {
        assertNull(DesktopInputMath.clampPoint(-50f, 10f, 1920, 1080))
        assertNull(DesktopInputMath.clampPoint(10f, 2000f, 1920, 1080))
        assertNull(DesktopInputMath.clampPoint(Float.NaN, 10f, 1920, 1080))
        assertNull(DesktopInputMath.clampPoint(10f, Float.POSITIVE_INFINITY, 1920, 1080))
        assertNull(DesktopInputMath.clampPoint(10f, 10f, 0, 1080))
    }

    @Test
    fun swipePlanSpreadsEventTimesAndEndsOnTarget() {
        val plan = DesktopInputMath.swipePlan(0f, 0f, 300f, 600f, durationMs = 300L, frameMs = 10L)
        assertEquals(30, plan.size)
        // strictly increasing, evenly spaced timestamps: VelocityTracker needs them (fling pitfall)
        plan.zipWithNext().forEach { (a, b) -> assertTrue(b.offsetMs > a.offsetMs) }
        assertEquals(10L, plan.first().offsetMs)
        assertEquals(300L, plan.last().offsetMs)
        assertEquals(300f, plan.last().x, 0.001f)
        assertEquals(600f, plan.last().y, 0.001f)
        assertEquals(150f, plan[14].x, 0.001f)
    }

    @Test
    fun swipePlanAlwaysHasAtLeastTwoMoves() {
        val plan = DesktopInputMath.swipePlan(5f, 5f, 6f, 6f, durationMs = 1L)
        assertEquals(2, plan.size)
        assertTrue(plan[1].offsetMs >= plan[0].offsetMs)
        assertEquals(6f, plan.last().x, 0.001f)
    }

    @Test
    fun swipePlanIsBoundedForHugeDurations() {
        val plan = DesktopInputMath.swipePlan(0f, 0f, 1f, 1f, durationMs = Long.MAX_VALUE, frameMs = 1L)
        assertEquals(2_000, plan.size)
        assertEquals(60_000L, plan.last().offsetMs)
    }

    @Test
    fun scrollStepsInvertVerticalAndSplitIntoDetents() {
        val steps = DesktopInputMath.scrollSteps(vertical = 3f, horizontal = 0f)
        assertEquals(3, steps.size)
        steps.forEach { (v, h) ->
            assertEquals(-1f, v, 0.0001f)
            assertEquals(0f, h, 0.0001f)
        }
        val mixed = DesktopInputMath.scrollSteps(vertical = -1f, horizontal = 2f)
        assertEquals(2, mixed.size)
        assertEquals(0.5f, mixed[0].first, 0.0001f)
        assertEquals(1f, mixed[0].second, 0.0001f)
    }

    @Test
    fun scrollStepsHandleZeroAndGarbage() {
        assertTrue(DesktopInputMath.scrollSteps(0f, 0f).isEmpty())
        assertTrue(DesktopInputMath.scrollSteps(Float.NaN, 0f).isEmpty())
        assertEquals(50, DesktopInputMath.scrollSteps(1_000f, 0f).size)
        assertEquals(1, DesktopInputMath.scrollSteps(0.25f, 0f).size)
    }

    @Test
    fun pasteIsRequiredOnlyOutsidePrintableAscii() {
        assertFalse(DesktopInputMath.requiresPaste("Hello, world! ~{}[]\n\t"))
        assertTrue(DesktopInputMath.requiresPaste("Grüße"))
        assertTrue(DesktopInputMath.requiresPaste("日本語"))
        assertTrue(DesktopInputMath.requiresPaste("emoji 😀"))
        assertTrue(DesktopInputMath.requiresPaste("bell\u0007"))
    }

    @Test
    fun chunksCoverTheTextExactly() {
        val text = "a".repeat(130)
        val chunks = DesktopInputMath.chunks(text, 64)
        assertEquals(listOf(64, 64, 2), chunks.map { it.length })
        assertEquals(text, chunks.joinToString(""))
        assertTrue(DesktopInputMath.chunks("").isEmpty())
    }

    @Test
    fun modifiersArePressedInStableOrder() {
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON
        assertEquals(
            listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT),
            DesktopInputMath.modifierKeyCodes(meta),
        )
        assertTrue(DesktopInputMath.modifierKeyCodes(0).isEmpty())
    }

    @Test
    fun normalizedMetaAddsLeftSideBits() {
        val meta = DesktopInputMath.normalizeMetaState(KeyEvent.META_CTRL_ON)
        assertTrue(meta and KeyEvent.META_CTRL_LEFT_ON != 0)
        assertTrue(meta and KeyEvent.META_CTRL_ON != 0)
        assertEquals(0, DesktopInputMath.normalizeMetaState(0))
    }

    @Test
    fun scalePointMapsScreenshotPixelsToDesktopPixels() {
        assertEquals(1920f to 1080f, DesktopInputMath.scalePoint(480f, 270f, 960, 540, 3840, 2160))
        assertEquals(3840f to 2160f, DesktopInputMath.scalePoint(960f, 540f, 960, 540, 3840, 2160))
        assertNull(DesktopInputMath.scalePoint(1f, 1f, 0, 10, 10, 10))
    }

    @Test
    fun durationsAreClamped() {
        assertEquals(500L, DesktopInputMath.longPressDuration(10L))
        assertEquals(650L, DesktopInputMath.longPressDuration(650L))
        assertEquals(16L, DesktopInputMath.swipeDuration(0L))
        assertEquals(10_000L, DesktopInputMath.swipeDuration(99_999L))
    }
}
