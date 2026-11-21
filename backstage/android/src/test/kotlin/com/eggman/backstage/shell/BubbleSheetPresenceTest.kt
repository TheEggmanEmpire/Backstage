package com.eggman.backstage.shell

import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.shell.ShellPresence.Cause
import com.eggman.backstage.shell.ShellPresence.Decision
import com.eggman.backstage.shell.ShellPresence.Sheet
import com.eggman.backstage.shell.ShellPresence.SheetOpening
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * the bubble's chat shows the DeX popup next to it when the current target is DeX desktop or Auto, never with
 * the Phone target; while a chat sheet is on screen, switching to the phone hides the popup and switching back shows it.
 */
class BubbleSheetPresenceTest {
    private fun open(
        presentation: Presentation,
        chip: Target = Target.DESKTOP,
        running: Target? = null,
        peek: Boolean = false,
        canStart: Boolean = true,
        locked: Boolean = false,
        startBlocked: Boolean = false,
    ): SheetOpening = ShellPresence.onBubbleChatOpened(
        presentation, ShellPresence.phoneMode(chip, running), peek, canStart, locked, startBlocked,
    )

    @Test fun openingTheBubblesChatWithADexTargetShowsThePopup() {
        for (chip in listOf(Target.DESKTOP, Target.AUTO)) {
            // A background desktop comes up as the popup; a missing one is started as the popup.
            assertEquals(SheetOpening(Decision.Present(Presentation.MINI)), open(Presentation.HIDDEN, chip))
            assertEquals(SheetOpening(Decision.StartMini), open(Presentation.NONE, chip))
            // Already on screen: left alone.
            for (p in listOf(Presentation.MINI, Presentation.FULL, Presentation.IN_APP)) {
                assertEquals(SheetOpening(Decision.Nothing), open(p, chip))
            }
        }
        // A task running on DeX counts as a DeX target even with the Phone chip.
        assertEquals(SheetOpening(Decision.Present(Presentation.MINI)), open(Presentation.HIDDEN, Target.PHONE, running = Target.DESKTOP))
    }

    @Test fun withThePhoneTargetNothingIsShownOrStarted() {
        for (p in Presentation.values()) {
            assertEquals(SheetOpening(Decision.Nothing), open(p, Target.PHONE))
            // A task the message put on the phone, too.
            assertEquals(SheetOpening(Decision.Nothing), open(p, Target.DESKTOP, running = Target.PHONE))
        }
    }

    @Test fun aDesktopThatCannotStartSaysWhyAndTheChatStillOpens() {
        assertEquals(SheetOpening(Decision.Nothing, ShellPresence.SHEET_NO_ACCESS), open(Presentation.NONE, canStart = false))
        assertEquals(SheetOpening(Decision.Nothing, ShellPresence.SHEET_LOCKED), open(Presentation.NONE, locked = true))
        // Right after the user stopped the desktop: not started again, nothing to explain.
        assertEquals(SheetOpening(Decision.Nothing), open(Presentation.NONE, startBlocked = true))
        // A result the bubble opened by itself never moves the desktop.
        assertEquals(SheetOpening(Decision.Nothing), open(Presentation.HIDDEN, peek = true))
        assertEquals(SheetOpening(Decision.Nothing), open(Presentation.NONE, peek = true))
    }

    @Test fun whileASheetShowsTheChipHidesAndShowsThePopup() {
        for (sheet in listOf(Sheet.BUBBLE, Sheet.POPUP)) for (popOut in listOf(true, false)) {
            // Phone chip: the popup goes to the background (the session is kept).
            assertEquals(Decision.Present(Presentation.HIDDEN),
                ShellPresence.onPhoneModeChanged(Presentation.MINI, true, Cause.CHIP, appVisible = false, popOut = popOut, sheet = sheet))
            // DeX/Auto chip again: the popup comes back at once, also with "Pop out when leaving" off.
            assertEquals(Decision.Present(Presentation.MINI),
                ShellPresence.onPhoneModeChanged(Presentation.HIDDEN, false, Cause.CHIP, appVisible = false, popOut = popOut, sheet = sheet))
            assertEquals(Decision.Nothing,
                ShellPresence.onPhoneModeChanged(Presentation.MINI, false, Cause.CHIP, appVisible = false, popOut = popOut, sheet = sheet))
            assertEquals(Decision.Nothing,
                ShellPresence.onPhoneModeChanged(Presentation.FULL, false, Cause.CHIP, appVisible = false, popOut = popOut, sheet = sheet))
        }
        // The bubble's sheet starts a missing desktop for a DeX chip (as opening it with that chip would); the popup's
        // sheet always has a session.
        assertEquals(Decision.StartMini,
            ShellPresence.onPhoneModeChanged(Presentation.NONE, false, Cause.CHIP, appVisible = false, popOut = true, sheet = Sheet.BUBBLE))
        assertEquals(Decision.Nothing,
            ShellPresence.onPhoneModeChanged(Presentation.NONE, false, Cause.CHIP, appVisible = false, popOut = true, sheet = Sheet.POPUP))
        // Without a sheet: the rules (pop out only with the setting).
        assertEquals(Decision.Nothing,
            ShellPresence.onPhoneModeChanged(Presentation.HIDDEN, false, Cause.CHIP, appVisible = false, popOut = false))
        // A sheet over the open app: the app's rule (the player takes it).
        assertEquals(Decision.Present(Presentation.IN_APP),
            ShellPresence.onPhoneModeChanged(Presentation.HIDDEN, false, Cause.CHIP, appVisible = true, popOut = true, sheet = Sheet.BUBBLE))
    }

    @Test fun theAgentSwitchingScreensWhileASheetShowsMovesThePopupToo() {
        for (sheet in listOf(Sheet.BUBBLE, Sheet.POPUP)) {
            // The running task moved to the phone: hidden; back to DeX: shown at once.
            assertEquals(Decision.Present(Presentation.HIDDEN),
                ShellPresence.onPhoneModeChanged(Presentation.MINI, true, Cause.TASK_TARGET, appVisible = false, popOut = false, sheet = sheet))
            assertEquals(Decision.Present(Presentation.MINI),
                ShellPresence.onPhoneModeChanged(Presentation.HIDDEN, false, Cause.TASK_TARGET, appVisible = false, popOut = false, sheet = sheet))
            // The agent never makes the sheet start a desktop (it starts its own when it needs one).
            assertEquals(Decision.Nothing,
                ShellPresence.onPhoneModeChanged(Presentation.NONE, false, Cause.TASK_TARGET, appVisible = false, popOut = true, sheet = sheet))
            // A phone task that ended: nothing moves until the next event .
            assertEquals(Decision.Nothing,
                ShellPresence.onPhoneModeChanged(Presentation.HIDDEN, false, Cause.TASK_ENDED, appVisible = false, popOut = true, sheet = sheet))
        }
    }
}
