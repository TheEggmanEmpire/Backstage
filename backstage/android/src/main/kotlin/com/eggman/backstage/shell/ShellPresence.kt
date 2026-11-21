package com.eggman.backstage.shell

import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.host.DesktopHost.Presentation

/**
 * Pure rules for where the running desktop shows, driven by whether Backstage's own UI (Home or the Agent tab) is on
 * the phone screen (requirements E, G, H; unit-tested in ShellPresenceTest):
 *
 * - app visible: Home's visibility owner borrows HIDDEN for its preview. MINI/FULL stay explicit; Chat owns its
 *   own player. IN_APP without a player is parked on patch 0001's sink.
 * - app left: IN_APP and HIDDEN pop out as the mini window (the "Pop out when leaving the app" setting, default on);
 *   with the setting off an in-app desktop goes to the background instead. An automatic Home preview restores its
 *   original HIDDEN and suppresses pop-out, unless an explicit presentation request released that ownership.
 * - a desktop that starts while the app is not visible starts as the popup; one that starts while it is visible plays
 *   in the app.
 * - phone mode ([phoneMode]: the Phone chip, or a running task that works on the phone): the app does not use
 *   DeX. A running desktop goes to the background (HIDDEN) and stays there: no popup over the phone (the agent works
 *   there), no pop-out when leaving, no player in the app, no start on open. The desktop is never stopped. Switching
 *   back (DeX desktop/Auto chip, or a task that works on DeX) resumes the normal rules at once.
 */
object ShellPresence {
    sealed interface Decision {
        data object Nothing : Decision
        data class Present(val presentation: Presentation) : Decision
        /** Start the desktop straight into the app (the "Start desktop when Backstage opens" setting). */
        data object StartInApp : Decision
        /** Start the desktop as the popup (the bubble's chat opened, or its chip switched, with a DeX target). */
        data object StartMini : Decision
    }

    /** Which chat sheet ([com.eggman.backstage.ui.minichat.MiniChatActivity]) is on screen. */
    enum class Sheet {
        NONE,
        /** Opened from the popup's chat button. */
        POPUP,
        /** Opened from the floating bubble (or the assistant entry). */
        BUBBLE,
    }

    /** Opening the bubble's chat sheet: what happens to the desktop, and a line the sheet shows when DeX cannot come. */
    data class SheetOpening(val decision: Decision, val notice: String? = null)

    const val SHEET_NO_ACCESS = "DeX desktop not started: connect Dextop's wireless debugging (or Shizuku) first. The chat works without it."
    const val SHEET_LOCKED = "Unlock the phone to show the DeX desktop."
    const val SHEET_START_FAILED = "The DeX desktop did not start in time. Check its setup on the Desktop tab."

    /**
     * The user opened the floating bubble's chat (tap, long-press, assistant entry; ). With a DeX desktop or Auto
     * target (not [phoneMode]) the desktop shows as the popup next to the sheet: a background desktop (HIDDEN) comes up
     * as MINI, a missing one is started as MINI when it can be ([canStart]: shell access; never while [locked], never
     * right after the user stopped it, [startBlocked]); otherwise the sheet says why and the chat works on. A desktop
     * already on screen (MINI, FULL, IN_APP) is left alone. Phone target: nothing is shown or started. A result the bubble
     * opened by itself ([peek]) never moves the desktop. The popup keeps clear of the sheet and the keyboard by itself
     * (the sheet reports its top edge, BackstageMiniObstacles).
   */
    fun onBubbleChatOpened(
        presentation: Presentation,
        phoneMode: Boolean,
        peek: Boolean,
        canStart: Boolean,
        locked: Boolean,
        startBlocked: Boolean,
    ): SheetOpening = when {
        peek || phoneMode -> SheetOpening(Decision.Nothing)
        presentation == Presentation.HIDDEN -> SheetOpening(Decision.Present(Presentation.MINI))
        presentation != Presentation.NONE -> SheetOpening(Decision.Nothing)
        startBlocked -> SheetOpening(Decision.Nothing)
        locked -> SheetOpening(Decision.Nothing, SHEET_LOCKED)
        !canStart -> SheetOpening(Decision.Nothing, SHEET_NO_ACCESS)
        else -> SheetOpening(Decision.StartMini)
    }

    /**
     * [keepBackground]: the app is opened with the popup's "Open Backstage" button, which sends the desktop to
     * the background on purpose: Home may borrow it while visible; the Agent tab owns its separate player. [phoneMode]  wins over both: the desktop stays in the background.
   */
    fun onAppVisible(
        presentation: Presentation,
        startOnOpen: Boolean,
        canStart: Boolean,
        keepBackground: Boolean = false,
        phoneMode: Boolean = false,
    ): Decision = when {
        phoneMode -> toBackground(presentation)
        keepBackground -> if (presentation == Presentation.MINI) Decision.Present(Presentation.HIDDEN) else Decision.Nothing
        else -> onAppVisibleNormally(presentation, startOnOpen, canStart)
    }

    private fun onAppVisibleNormally(presentation: Presentation, startOnOpen: Boolean, canStart: Boolean): Decision = when (presentation) {
        Presentation.MINI, Presentation.HIDDEN -> Decision.Nothing
        Presentation.NONE -> if (startOnOpen && canStart) Decision.StartInApp else Decision.Nothing
        Presentation.FULL, Presentation.IN_APP -> Decision.Nothing
    }

    fun onAppLeft(presentation: Presentation, popOut: Boolean, phoneMode: Boolean = false, keepHidden: Boolean = false): Decision =
        if (keepHidden && (presentation == Presentation.HIDDEN || presentation == Presentation.IN_APP)) toBackground(presentation)
        else if (phoneMode) toBackground(presentation) else onAppLeftNormally(presentation, popOut)

    private fun onAppLeftNormally(presentation: Presentation, popOut: Boolean): Decision = when (presentation) {
        Presentation.IN_APP -> Decision.Present(if (popOut) Presentation.MINI else Presentation.HIDDEN)
        Presentation.HIDDEN -> if (popOut) Decision.Present(Presentation.MINI) else Decision.Nothing
        Presentation.MINI, Presentation.FULL, Presentation.NONE -> Decision.Nothing
    }

    /** A session just appeared (previous presentation NONE). */
    fun onSessionStarted(presentation: Presentation, appVisible: Boolean, phoneMode: Boolean = false): Decision = when {
        phoneMode -> toBackground(presentation)
        // Home owns automatic HIDDEN previews; MINI/FULL remain explicit choices.
        appVisible -> Decision.Nothing
        !appVisible && presentation == Presentation.IN_APP -> Decision.Present(Presentation.MINI)
        else -> Decision.Nothing
    }

    /** Phone mode: whatever shows (popup, full screen, in the app) goes to the background; nothing is started or stopped. */
    private fun toBackground(presentation: Presentation): Decision = when (presentation) {
        Presentation.FULL, Presentation.MINI, Presentation.IN_APP -> Decision.Present(Presentation.HIDDEN)
        Presentation.HIDDEN, Presentation.NONE -> Decision.Nothing
    }

    /** What changed phone mode ([onPhoneModeChanged]). */
    enum class Cause {
        /** The user picked a chip (in-app chat, popup chat, Settings' default target). */
        CHIP,
        /** A task started, or the running task moved to another screen. */
        TASK_TARGET,
        /** The running task ended (the chip decides again). */
        TASK_ENDED,
    }

    /**
     * Phone mode: the Phone chip is the current target, or the running task works on the phone. The running
     * task's own target wins over the chip: a task that works on DeX (the message said "on DeX") is a DeX task even with
     * the Phone chip, and a task the message put "on my phone" is a phone task even with the DeX chip.
   */
    fun phoneMode(chip: Target, runningTarget: Target?): Boolean = when (runningTarget) {
        Target.PHONE -> true
        Target.DESKTOP -> false
        Target.AUTO, null -> chip == Target.PHONE
    }

    /** The screen the active (queued, running, waiting or paused) task works on, once resolved; null otherwise. */
    fun runningTarget(task: TaskSnapshot?): Target? = task
        ?.takeIf { it.status == TaskStatus.RUNNING || it.status == TaskStatus.WAITING_FOR_USER }
        ?.resolvedTarget

    /**
     * Phone mode just switched. On: the desktop goes to the background. Off because the user chose DeX/Auto or a DeX task
     * started: the normal rules apply at once (the chat/Home player takes it while the app is visible, otherwise the
     * popup pops out when *Pop out when leaving* is on). Off because a phone task ended: nothing moves until the next
     * event (the popup does not jump over the app the agent just opened).
     *
     * [sheet]: while a chat sheet is the surface on screen (not over the app), switching back shows the popup
     * at once, whatever *Pop out when leaving* says: HIDDEN (and a parked IN_APP) -> MINI; with the bubble's sheet a
     * chip switch to DeX/Auto also starts a missing desktop as the popup (like opening the sheet with that target).
   */
    fun onPhoneModeChanged(
        presentation: Presentation,
        phoneMode: Boolean,
        cause: Cause,
        appVisible: Boolean,
        popOut: Boolean,
        sheet: Sheet = Sheet.NONE,
    ): Decision = when {
        phoneMode -> toBackground(presentation)
        cause == Cause.TASK_ENDED -> Decision.Nothing
        appVisible && (presentation == Presentation.HIDDEN || presentation == Presentation.MINI) -> Decision.Present(Presentation.IN_APP)
        appVisible -> Decision.Nothing
        sheet != Sheet.NONE -> when (presentation) {
            Presentation.HIDDEN, Presentation.IN_APP -> Decision.Present(Presentation.MINI)
            Presentation.NONE -> if (sheet == Sheet.BUBBLE && cause == Cause.CHIP) Decision.StartMini else Decision.Nothing
            Presentation.MINI, Presentation.FULL -> Decision.Nothing
        }
        else -> onAppLeftNormally(presentation, popOut)
    }
}
