package com.eggman.backstage.host

import android.graphics.Bitmap

/**
 * Home's in-app preview can come up black (the display keeps rendering into the background sink, or the player
 * Surface never receives a frame). The shell checks the player a few seconds after it attaches and repairs it in
 * steps: rebind the player first, then restart the desktop once, never while the agent is working on it.
 */
object PreviewHealth {
    const val CHECK_DELAY_MS = 3_000L

    enum class Action { NONE, REBIND, RESTART, GIVE_UP }

    /** A missing frame, or one whose sampled pixels are all (near) black, counts as a black preview. */
    fun blank(frame: Bitmap?): Boolean {
        if (frame == null) return true
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return true
        val steps = 12
        for (yi in 0 until steps) for (xi in 0 until steps) {
            val p = frame.getPixel(((xi + 0.5f) * w / steps).toInt().coerceIn(0, w - 1), ((yi + 0.5f) * h / steps).toInt().coerceIn(0, h - 1))
            if ((p shr 16 and 0xff) > 8 || (p shr 8 and 0xff) > 8 || (p and 0xff) > 8) return false
        }
        return true
    }

    /** [attempt] repairs already made for this preview; [restarted]: the desktop is already restarted this visit. */
    fun next(blank: Boolean, attempt: Int, restarted: Boolean, agentBusy: Boolean): Action = when {
        !blank -> Action.NONE
        attempt == 0 -> Action.REBIND
        !restarted && !agentBusy -> Action.RESTART
        else -> Action.GIVE_UP
    }
}
