package com.eggman.backstage.ui.chat

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.PlayerFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** the chat's player shows the whole desktop letterboxed and maps touches through the same rectangle. */
@RunWith(RobolectricTestRunner::class)
class ChatPlayerViewRobolectricTest {
    private class Extras : DesktopHostExtras {
        val touches = mutableListOf<Triple<Int, Float, Float>>()
        override fun setMiniStatus(text: String?) = Unit
        override fun setMiniShape(shape: String) = true
        override fun miniShape() = DesktopHostExtras.SHAPE_ROUNDED
        override fun usePrivilegeProvider(provider: String) = false
        override fun desktopAccessibilityComponent(): String? = null
        override fun playerTouch(action: Int, x: Float, y: Float): Boolean { touches += Triple(action, x, y); return true }
    }

    private fun layout(view: View, w: Int, h: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    private fun event(action: Int, x: Float, y: Float): MotionEvent =
        SystemClock.uptimeMillis().let { MotionEvent.obtain(it, it, action, x, y, 0) }

    @Test fun theSurfaceSitsAtTheFittedPictureAndTouchesUseIt() {
        val extras = Extras()
        val view = ChatPlayerView(RuntimeEnvironment.getApplication(), extras)
        view.setDesktopSize(1920, 1080)
        layout(view, 1000, 700)
        val expected = PlayerFit.fit(1000, 700, 1920, 1080)
        assertEquals(expected, view.picture)
        val s = view.surfaceView
        assertEquals(listOf(expected.left, expected.top, expected.right, expected.bottom), listOf(s.left, s.top, s.right, s.bottom))

        // A tap on the black bar is not the desktop's.
        assertFalse(view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 500f, 20f)))
        assertTrue(extras.touches.isEmpty())
        // A drag that starts on the picture: mapped to desktop pixels, clamped at the edge.
        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 500f, expected.top + expected.height / 2f)))
        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 2000f, expected.top + expected.height / 2f)))
        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_UP, 2000f, -50f)))
        assertEquals(listOf(PlayerFit.DOWN, PlayerFit.MOVE, PlayerFit.UP), extras.touches.map { it.first })
        assertEquals(960f, extras.touches[0].second, 1f)
        assertEquals(540f, extras.touches[0].third, 1f)
        assertEquals(1919f, extras.touches[1].second, 0.01f)
        assertEquals(0f, extras.touches[2].third, 0.01f)

        // A new desktop size re-fits the picture.
        view.setDesktopSize(1080, 1920)
        layout(view, 1000, 700)
        assertEquals(PlayerFit.fit(1000, 700, 1080, 1920), view.picture)
        assertEquals(view.picture.width, view.surfaceView.width)
    }
}
