package com.eggman.backstage.host

import android.view.KeyEvent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure helpers shared by DesktopHost implementations (the Dextop glue) for synthetic input on a desktop display.
 * No Android runtime calls: only compile-time constants, so everything here is JVM-unit-testable.
 */
object DesktopInputMath {

    /** Points further than this outside the display are rejected rather than clamped (agent coordinate bugs). */
    const val EDGE_TOLERANCE_PX = 2f

    /** Default spacing of interpolated MOVE events (≈120 Hz, matches a real finger on a fast panel). */
    const val SWIPE_FRAME_MS = 8L

    /** One interpolated MOVE of a synthetic swipe: [offsetMs] after the DOWN event. */
    data class SwipeStep(val offsetMs: Long, val x: Float, val y: Float)

    /**
     * Maps a desktop-pixel coordinate onto the valid pixel grid [0, size - 1]. Points within [EDGE_TOLERANCE_PX]
     * outside the display are clamped (rounding from screenshots/scaling); anything else, NaN or infinite → null.
   */
    fun clampPoint(x: Float, y: Float, width: Int, height: Int): Pair<Float, Float>? {
        if (width <= 0 || height <= 0 || !x.isFinite() || !y.isFinite()) return null
        val maxX = (width - 1).toFloat()
        val maxY = (height - 1).toFloat()
        if (x < -EDGE_TOLERANCE_PX || y < -EDGE_TOLERANCE_PX || x > maxX + EDGE_TOLERANCE_PX || y > maxY + EDGE_TOLERANCE_PX) {
            return null
        }
        return x.coerceIn(0f, maxX) to y.coerceIn(0f, maxY)
    }

    /**
     * Interpolated MOVE events for a swipe lasting [durationMs]. Event times are spread evenly (never collapsed onto
     * one timestamp) so VelocityTracker sees a real fling; the last step lands exactly on the end point at
     * [durationMs]. The DOWN is at offset 0 (not included), the UP at the last step's offset.
   */
    fun swipePlan(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        frameMs: Long = SWIPE_FRAME_MS,
    ): List<SwipeStep> {
        val duration = durationMs.coerceIn(1L, 60_000L)
        val frame = frameMs.coerceAtLeast(1L)
        val count = max(2, ceil(duration.toDouble() / frame).toInt()).coerceAtMost(2_000)
        return (1..count).map { index ->
            val fraction = index.toFloat() / count
            SwipeStep(
                offsetMs = (duration * index + count / 2) / count,
                x = x1 + (x2 - x1) * fraction,
                y = y1 + (y2 - y1) * fraction,
            )
        }
    }

    /**
     * Mouse-wheel axis values for one scroll request, split into events of at most [maxStep] detents.
     * Contract: [vertical] > 0 scrolls down (content moves up) → Android AXIS_VSCROLL is negative;
     * [horizontal] > 0 scrolls right (content moves left) → AXIS_HSCROLL is positive.
     * Returns pairs of (AXIS_VSCROLL, AXIS_HSCROLL).
   */
    fun scrollSteps(vertical: Float, horizontal: Float, maxStep: Float = 1f, maxEvents: Int = 50): List<Pair<Float, Float>> {
        val v = if (vertical.isFinite()) vertical else 0f
        val h = if (horizontal.isFinite()) horizontal else 0f
        if (v == 0f && h == 0f) return emptyList()
        val step = if (maxStep.isFinite() && maxStep > 0f) maxStep else 1f
        val count = ceil(max(abs(v), abs(h)) / step).toInt().coerceIn(1, maxEvents)
        return List(count) { (-v / count) to (h / count) }
    }

    /**
     * Text that the VIRTUAL_KEYBOARD key character map cannot produce (anything but printable ASCII, newline, tab).
     * It is never pasted through the phone's clipboard: typing it is refused ([DesktopTyping]).
   */
    fun requiresPaste(text: String): Boolean = text.any { c -> !(c in ' '..'~' || c == '\n' || c == '\t') }

    /** Splits typed text into chunks so a single injection burst stays short (and can be cancelled between). */
    fun chunks(text: String, size: Int = 64): List<String> {
        if (text.isEmpty()) return emptyList()
        val chunk = size.coerceAtLeast(1)
        return (text.indices step chunk).map { text.substring(it, min(text.length, it + chunk)) }
    }

    /**
     * Modifier key codes to hold around a key press for [metaState], in press order (release in reverse).
     * Sending the modifiers as real key events (not only metaState) matches what a hardware keyboard produces.
   */
    fun modifierKeyCodes(metaState: Int): List<Int> = buildList {
        if (metaState and KeyEvent.META_CTRL_ON != 0) add(KeyEvent.KEYCODE_CTRL_LEFT)
        if (metaState and KeyEvent.META_SHIFT_ON != 0) add(KeyEvent.KEYCODE_SHIFT_LEFT)
        if (metaState and KeyEvent.META_ALT_ON != 0) add(KeyEvent.KEYCODE_ALT_LEFT)
        if (metaState and KeyEvent.META_META_ON != 0) add(KeyEvent.KEYCODE_META_LEFT)
    }

    /** Adds the left-side bit of every set modifier so apps checking either form see it. */
    fun normalizeMetaState(metaState: Int): Int {
        var meta = metaState
        if (meta and KeyEvent.META_CTRL_ON != 0) meta = meta or KeyEvent.META_CTRL_LEFT_ON
        if (meta and KeyEvent.META_SHIFT_ON != 0) meta = meta or KeyEvent.META_SHIFT_LEFT_ON
        if (meta and KeyEvent.META_ALT_ON != 0) meta = meta or KeyEvent.META_ALT_LEFT_ON
        if (meta and KeyEvent.META_META_ON != 0) meta = meta or KeyEvent.META_META_LEFT_ON
        return meta
    }

    /** One key event of a chord: [down] or up, the key, and the meta state a hardware keyboard reports with it. */
    data class KeyStroke(val down: Boolean, val keyCode: Int, val metaState: Int)

    /** Meta bits a modifier key sets while held (KEYCODE_CTRL_LEFT → CTRL_ON|CTRL_LEFT_ON...); 0 for other keys. */
    fun modifierBits(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
        KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
        KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON
        else -> 0
    }

    private const val MODIFIER_MASK = KeyEvent.META_CTRL_MASK or KeyEvent.META_SHIFT_MASK or KeyEvent.META_ALT_MASK or
        KeyEvent.META_META_MASK

    /**
     * The key events a hardware keyboard sends for [keyCode] with the modifiers in [metaState]: every modifier is
     * pressed as a real key (Ctrl, Shift, Alt, Meta in that order), then the key goes down and up, then the
     * modifiers are released in reverse order. Each event carries the meta state of the keys held at that moment: a
     * modifier's own bit is set on its down and cleared on its up, so `Meta+D` is META_LEFT down (META_ON), D down/up
     * (META_ON), META_LEFT up (0). A lone modifier ([keyCode] is itself a modifier) is a single press and release.
   */
    fun chordStrokes(keyCode: Int, metaState: Int): List<KeyStroke> {
        val meta = normalizeMetaState(metaState)
        val base = meta and MODIFIER_MASK.inv()
        val modifiers = modifierKeyCodes(meta).filter { (modifierBits(it) and modifierBits(keyCode)) == 0 }
        val out = ArrayList<KeyStroke>(modifiers.size * 2 + 2)
        var held = base
        for (m in modifiers) {
            held = held or modifierBits(m)
            out += KeyStroke(true, m, held)
        }
        val own = modifierBits(keyCode)
        out += KeyStroke(true, keyCode, held or own)
        out += KeyStroke(false, keyCode, held)
        for (m in modifiers.asReversed()) {
            held = held and modifierBits(m).inv()
            out += KeyStroke(false, m, held)
        }
        return out
    }

    /** Scales a point from a [fromWidth]×[fromHeight] image (e.g. a downscaled screenshot) to desktop pixels. */
    fun scalePoint(x: Float, y: Float, fromWidth: Int, fromHeight: Int, toWidth: Int, toHeight: Int): Pair<Float, Float>? {
        if (fromWidth <= 0 || fromHeight <= 0 || toWidth <= 0 || toHeight <= 0 || !x.isFinite() || !y.isFinite()) return null
        return (x * toWidth / fromWidth) to (y * toHeight / fromHeight)
    }

    /** Duration clamp for long presses: long enough for ViewConfiguration.getLongPressTimeout on every vendor. */
    fun longPressDuration(requestedMs: Long): Long = requestedMs.coerceIn(500L, 10_000L)

    /** Clamp for swipe durations; very short swipes become flings, very long ones drags. */
    fun swipeDuration(requestedMs: Long): Long = requestedMs.coerceIn(16L, 10_000L)

    /** Pixel distance of the swipe, rounded; used for logging/diagnostics. */
    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Int =
        kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).roundToInt()
}
