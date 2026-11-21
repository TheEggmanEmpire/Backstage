package com.eggman.backstage.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**: the whole frame is fitted, touches use the same rectangle. */
class PlayerFitTest {
    @Test fun aWideDesktopIsLetterboxedInATallerBox() {
        // A Z Fold inner screen width, a 1/3-height box: 1920x1080 desktop into 1000x700.
        val r = PlayerFit.fit(1000, 700, 1920, 1080)
        assertEquals(PlayerFit.Rect(0, 68, 1000, 563), r)
        // The picture keeps the desktop's aspect ratio (within a pixel).
        assertEquals(1920.0 / 1080, r.width.toDouble() / r.height, 0.005)
    }

    @Test fun aDesktopTallerThanTheBoxIsPillarboxed() {
        val r = PlayerFit.fit(1000, 400, 1600, 1000)
        assertEquals(PlayerFit.Rect(180, 0, 640, 400), r)
    }

    @Test fun anExactlyMatchingBoxIsFilled() {
        assertEquals(PlayerFit.Rect(0, 0, 960, 540), PlayerFit.fit(960, 540, 1920, 1080))
        // Unknown desktop size: the whole box; empty box: nothing.
        assertEquals(PlayerFit.Rect(0, 0, 300, 200), PlayerFit.fit(300, 200, 0, 0))
        assertEquals(PlayerFit.Rect(0, 0, 0, 0), PlayerFit.fit(0, 200, 1920, 1080))
    }

    @Test fun touchesMapThroughTheFittedPicture() {
        val r = PlayerFit.fit(1000, 700, 1920, 1080) // (0,68) 1000x563
        assertEquals(0f to 0f, PlayerFit.toDesktop(0f, 68f, r, 1920, 1080))
        val centre = PlayerFit.toDesktop(500f, 68f + 563f / 2, r, 1920, 1080)!!
        assertEquals(960f, centre.first, 0.5f)
        assertEquals(540f, centre.second, 0.5f)
        // The last pixel row/column stays on the display.
        val corner = PlayerFit.toDesktop(999.9f, 630.9f, r, 1920, 1080)!!
        assertEquals(1919f, corner.first, 1f)
        assertEquals(1079f, corner.second, 1f)
        // On the black bars: not the desktop.
        assertNull(PlayerFit.toDesktop(500f, 10f, r, 1920, 1080))
        assertNull(PlayerFit.toDesktop(500f, 690f, r, 1920, 1080))
        // A drag that started on the picture is clamped to its edge.
        assertEquals(500f / 1000 * 1920 to 0f, PlayerFit.toDesktop(500f, 10f, r, 1920, 1080, clamp = true))
        assertEquals(1919f to 1079f, PlayerFit.toDesktop(5000f, 5000f, r, 1920, 1080, clamp = true))
    }

    @Test fun pillarboxedTouchesSkipTheSideBars() {
        val r = PlayerFit.fit(1000, 400, 1600, 1000) // (180,0) 640x400
        assertNull(PlayerFit.toDesktop(100f, 200f, r, 1600, 1000))
        val p = PlayerFit.toDesktop(180f + 320f, 200f, r, 1600, 1000)!!
        assertEquals(800f, p.first, 0.01f)
        assertEquals(500f, p.second, 0.01f)
    }
}
