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

    /** A missing frame, or one that is black throughout ([com.eggman.backstage.agent.DesktopHealth.isBlank]), counts as a black preview. */
    fun blank(frame: Bitmap?): Boolean = frame == null || frame.width <= 0 || frame.height <= 0 ||
        com.eggman.backstage.agent.DesktopHealth.isBlank(frame)

    /** [attempt] repairs already made for this preview; [restarted]: the desktop is already restarted this visit. */
    fun next(blank: Boolean, attempt: Int, restarted: Boolean, agentBusy: Boolean): Action = when {
        !blank -> Action.NONE
        attempt == 0 -> Action.REBIND
        !restarted && !agentBusy -> Action.RESTART
        else -> Action.GIVE_UP
    }
}
