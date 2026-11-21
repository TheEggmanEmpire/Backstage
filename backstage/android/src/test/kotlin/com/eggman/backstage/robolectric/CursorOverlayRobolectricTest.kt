package com.eggman.backstage.robolectric

import android.view.View
import android.view.WindowManager
import com.eggman.backstage.cursor.CursorOverlay
import com.eggman.backstage.cursor.CursorView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The agent cursor's window never takes touches or focus and is not announced by TalkBack. */
@RunWith(RobolectricTestRunner::class)
class CursorOverlayRobolectricTest {
    @Test fun theOverlayWindowIsNotTouchableNotFocusableAndAnAccessibilityOverlay() {
        val lp = CursorOverlay.layoutParams()
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, lp.type)
        assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, lp.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, lp.height)
        assertEquals("Backstage cursor", lp.title.toString())
        val view = CursorView(RuntimeEnvironment.getApplication(), 1920, 1080)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, view.importantForAccessibility)
        assertFalse(view.isClickable)
        assertFalse(view.isFocusable)
    }
}
