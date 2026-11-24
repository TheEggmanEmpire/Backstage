package com.eggman.backstage.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopHealthTest {
    private fun frame(w: Int, h: Int, fill: Int) = IntArray(w * h) { fill }

    @Test fun anAllBlackFrameIsBlank() {
        assertTrue(DesktopHealth.isBlank(frame(1920, 1080, 0xFF000000.toInt()), 1920, 1080))
    }

    @Test fun nearBlackNoiseIsStillBlank() {
        val px = IntArray(640 * 360) { i -> if (i % 7 == 0) 0xFF0A0A0A.toInt() else 0xFF000000.toInt() }
        assertTrue(DesktopHealth.isBlank(px, 640, 360))
    }

    @Test fun aWallpaperIsNotBlank() {
        assertFalse(DesktopHealth.isBlank(frame(1920, 1080, 0xFF3A5F8C.toInt()), 1920, 1080))
    }

    @Test fun aBlackDesktopWithATaskbarIsNotBlank() {
        val w = 1280; val h = 720
        val px = frame(w, h, 0xFF000000.toInt())
        for (y in h - 48 until h) for (x in 0 until w) px[y * w + x] = 0xFF202830.toInt()
        assertFalse(DesktopHealth.isBlank(px, w, h))
    }

    @Test fun aSingleBrightWindowIsNotBlank() {
        val w = 1280; val h = 720
        val px = frame(w, h, 0xFF000000.toInt())
        for (y in 200 until 400) for (x in 400 until 800) px[y * w + x] = 0xFFFFFFFF.toInt()
        assertFalse(DesktopHealth.isBlank(px, w, h))
    }

    @Test fun anEmptyOrShortBufferIsNotJudged() {
        assertFalse(DesktopHealth.isBlank(IntArray(0), 0, 0))
        assertFalse(DesktopHealth.isBlank(IntArray(10), 100, 100))
    }
}
