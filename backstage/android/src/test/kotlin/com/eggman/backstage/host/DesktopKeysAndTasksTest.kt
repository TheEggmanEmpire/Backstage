package com.eggman.backstage.host

import android.view.KeyEvent
import com.eggman.backstage.host.DesktopInputMath.KeyStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chord encoding (real modifier key events) and the rules for which desktop tasks may be closed. */
class DesktopKeysAndTasksTest {
    private val meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    private val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    @Test fun metaChordPressesTheModifierAsARealKey() {
        assertEquals(
            listOf(
                KeyStroke(true, KeyEvent.KEYCODE_META_LEFT, meta),
                KeyStroke(true, KeyEvent.KEYCODE_D, meta),
                KeyStroke(false, KeyEvent.KEYCODE_D, meta),
                KeyStroke(false, KeyEvent.KEYCODE_META_LEFT, 0),
            ),
            DesktopInputMath.chordStrokes(KeyEvent.KEYCODE_D, KeyEvent.META_META_ON),
        )
    }

    @Test fun eachEventCarriesTheModifiersHeldAtThatMoment() {
        val strokes = DesktopInputMath.chordStrokes(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertEquals(
            listOf(
                KeyStroke(true, KeyEvent.KEYCODE_CTRL_LEFT, ctrl),
                KeyStroke(true, KeyEvent.KEYCODE_SHIFT_LEFT, ctrl or shift),
                KeyStroke(true, KeyEvent.KEYCODE_T, ctrl or shift),
                KeyStroke(false, KeyEvent.KEYCODE_T, ctrl or shift),
                KeyStroke(false, KeyEvent.KEYCODE_SHIFT_LEFT, ctrl),
                KeyStroke(false, KeyEvent.KEYCODE_CTRL_LEFT, 0),
            ),
            strokes,
        )
        // Every key that goes down comes up again, modifiers in reverse order.
        assertEquals(strokes.filter { it.down }.map { it.keyCode }.toSet(), strokes.filter { !it.down }.map { it.keyCode }.toSet())
    }

    @Test fun altF4AndFourModifiersAndLockBits() {
        val altF4 = DesktopInputMath.chordStrokes(KeyEvent.KEYCODE_F4, KeyEvent.META_ALT_ON)
        assertEquals(KeyStroke(true, KeyEvent.KEYCODE_ALT_LEFT, alt), altF4.first())
        assertEquals(KeyStroke(true, KeyEvent.KEYCODE_F4, alt), altF4[1])
        assertEquals(KeyStroke(false, KeyEvent.KEYCODE_ALT_LEFT, 0), altF4.last())

        val all = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON
        val strokes = DesktopInputMath.chordStrokes(KeyEvent.KEYCODE_S, all)
        assertEquals(10, strokes.size)
        assertEquals(
            listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_META_LEFT),
            strokes.take(4).map { it.keyCode },
        )
        assertEquals(ctrl or shift or alt or meta, strokes[4].metaState)
        assertEquals(0, strokes.last().metaState)

        // A held lock state (caps lock) stays on every event; the modifiers come and go around it.
        val caps = KeyEvent.META_CAPS_LOCK_ON
        val withCaps = DesktopInputMath.chordStrokes(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or caps)
        assertTrue(withCaps.all { (it.metaState and caps) != 0 })
        assertEquals(caps, withCaps.last().metaState)
    }

    @Test fun aLoneModifierIsOnePressAndRelease() {
        assertEquals(
            listOf(KeyStroke(true, KeyEvent.KEYCODE_META_LEFT, meta), KeyStroke(false, KeyEvent.KEYCODE_META_LEFT, 0)),
            DesktopInputMath.chordStrokes(KeyEvent.KEYCODE_META_LEFT, 0),
        )
        // A modifier given both as key and as meta bit is not pressed twice.
        assertEquals(2, DesktopInputMath.chordStrokes(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_ON).size)
        assertEquals(0, DesktopInputMath.modifierBits(KeyEvent.KEYCODE_A))
        assertEquals(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON, DesktopInputMath.modifierBits(KeyEvent.KEYCODE_ALT_RIGHT))
    }

    @Test fun plainKeyHasNoModifierEvents() {
        assertEquals(
            listOf(KeyStroke(true, KeyEvent.KEYCODE_ENTER, 0), KeyStroke(false, KeyEvent.KEYCODE_ENTER, 0)),
            DesktopInputMath.chordStrokes(KeyEvent.KEYCODE_ENTER, 0),
        )
    }

    // ---- which tasks may be closed ----------------------------------------------------------------------------

    private val desktop = 7
    private val own = setOf("com.eggman.backstage")
    private val tasks = listOf(
        DesktopTask(1, "com.whatsapp", "WhatsApp", displayId = 0), // the phone's WhatsApp
        DesktopTask(2, "com.android.chrome", "Chrome", displayId = 0, focused = true), // the phone's front app
        DesktopTask(11, "com.whatsapp", "WhatsApp", displayId = desktop),
        DesktopTask(12, "com.google.android.gm", "Gmail", displayId = desktop, focused = true),
        DesktopTask(13, "com.sec.android.app.launcher", "Home", displayId = desktop, activityType = 2),
        DesktopTask(14, "com.eggman.backstage", "Backstage", displayId = desktop),
        DesktopTask(15, "com.sec.android.app.launcher", "Recents", displayId = desktop, activityType = 3),
        DesktopTask(16, "", null, displayId = desktop),
        DesktopTask(17, "org.example.notes", "Notes", displayId = desktop, activityType = 0, visible = false),
    )

    @Test fun onlyAppTasksOfTheDesktopDisplayAreClosable() {
        val ids = DesktopTaskPolicy.select(tasks, desktop, own).map { it.taskId }
        assertEquals(listOf(11, 12, 17), ids)
        // Never the phone's display, whatever a caller passes as the "desktop".
        assertTrue(DesktopTaskPolicy.select(tasks, 0, own).isEmpty())
        assertFalse(DesktopTaskPolicy.closable(tasks[0], 0, own))
        // One app: only its desktop task, not its phone task.
        assertEquals(listOf(11), DesktopTaskPolicy.select(tasks, desktop, own, setOf("com.whatsapp")).map { it.taskId })
        assertEquals(12, DesktopTaskPolicy.focused(tasks, desktop, own)?.taskId)
        assertEquals(11, DesktopTaskPolicy.focused(tasks.map { it.copy(focused = false) }, desktop, own)?.taskId)
        assertNull(DesktopTaskPolicy.focused(tasks.filter { it.displayId == 0 }, desktop, own))
        assertEquals("Notes", tasks.last().name)
        assertEquals("", tasks[7].name)
    }
}
