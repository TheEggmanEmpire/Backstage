package com.eggman.backstage.host

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreviewHealthTest {
    @Test fun missingOrBlackFramesAreBlank() {
        assertTrue(PreviewHealth.blank(null))
        val black = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        assertTrue(PreviewHealth.blank(black))
        black.setPixel(32, 18, Color.rgb(40, 90, 200))
        assertTrue("one stray pixel between samples is not a picture", PreviewHealth.blank(black))
        val desktop = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 40, 90)) }
        assertFalse(PreviewHealth.blank(desktop))
    }

    @Test fun repairsRebindFirstThenRestartOnceAndNeverDuringAgentWork() {
        assertEquals(PreviewHealth.Action.NONE, PreviewHealth.next(blank = false, attempt = 0, restarted = false, agentBusy = false))
        assertEquals(PreviewHealth.Action.REBIND, PreviewHealth.next(true, 0, false, true))
        assertEquals(PreviewHealth.Action.RESTART, PreviewHealth.next(true, 1, false, false))
        assertEquals(PreviewHealth.Action.GIVE_UP, PreviewHealth.next(true, 1, false, true))
        assertEquals(PreviewHealth.Action.GIVE_UP, PreviewHealth.next(true, 2, true, false))
    }
}
