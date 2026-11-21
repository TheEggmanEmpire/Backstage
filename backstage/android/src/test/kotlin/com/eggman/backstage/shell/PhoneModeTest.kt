package com.eggman.backstage.shell

import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.notify.SessionNotificationModel
import com.eggman.backstage.shell.ShellPresence.Cause
import com.eggman.backstage.shell.ShellPresence.Decision
import com.eggman.backstage.ui.Fixtures.snapshot
import com.eggman.backstage.ui.chat.DesktopPaneRules
import com.eggman.backstage.ui.chat.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "after asking it to work in phone mode the DeX popup still shows". In phone mode the app does not
 * use DeX: the desktop stays in the background (never stopped), no popup, no pop-out, no chat player.
 */
class PhoneModeTest {
    private val showing = listOf(Presentation.FULL, Presentation.MINI, Presentation.IN_APP)

    @Test fun phoneModeIsThePhoneChipOrARunningPhoneTask() {
        assertTrue(ShellPresence.phoneMode(Target.PHONE, null))
        assertFalse(ShellPresence.phoneMode(Target.DESKTOP, null))
        assertFalse(ShellPresence.phoneMode(Target.AUTO, null))
        // The running task's screen wins over the chip ("on my phone" with the DeX chip, "on DeX" with the Phone chip).
        assertTrue(ShellPresence.phoneMode(Target.DESKTOP, Target.PHONE))
        assertTrue(ShellPresence.phoneMode(Target.AUTO, Target.PHONE))
        assertFalse(ShellPresence.phoneMode(Target.PHONE, Target.DESKTOP))
    }

    @Test fun theRunningTargetComesFromTheActiveTaskOnly() {
        assertNull(ShellPresence.runningTarget(null))
        assertEquals(Target.PHONE, ShellPresence.runningTarget(snapshot(resolvedTarget = Target.PHONE)))
        assertEquals(Target.PHONE, ShellPresence.runningTarget(snapshot(status = TaskStatus.WAITING_FOR_USER, resolvedTarget = Target.PHONE)))
        assertEquals(Target.DESKTOP, ShellPresence.runningTarget(snapshot(resolvedTarget = Target.DESKTOP)))
        // Not resolved yet, queued, or finished: the chip decides.
        assertNull(ShellPresence.runningTarget(snapshot(resolvedTarget = null)))
        assertNull(ShellPresence.runningTarget(snapshot(status = TaskStatus.QUEUED, resolvedTarget = Target.PHONE)))
        for (end in listOf(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            assertNull(ShellPresence.runningTarget(snapshot(status = end, resolvedTarget = Target.PHONE)))
        }
    }

    @Test fun enteringPhoneModeSendsTheDesktopToTheBackground() {
        for (cause in Cause.values()) for (visible in listOf(true, false)) {
            for (p in showing) {
                assertEquals(Decision.Present(Presentation.HIDDEN), ShellPresence.onPhoneModeChanged(p, true, cause, visible, popOut = true))
            }
            // Already in the background, or no session: nothing (never started, never stopped).
            assertEquals(Decision.Nothing, ShellPresence.onPhoneModeChanged(Presentation.HIDDEN, true, cause, visible, popOut = true))
            assertEquals(Decision.Nothing, ShellPresence.onPhoneModeChanged(Presentation.NONE, true, cause, visible, popOut = true))
        }
    }

    @Test fun inPhoneModeTheAppDoesNotShowDex() {
        // Opening the app: no player, no start on open, a popup goes to the background.
        for (p in showing) assertEquals(Decision.Present(Presentation.HIDDEN), ShellPresence.onAppVisible(p, true, true, phoneMode = true))
        assertEquals(Decision.Nothing, ShellPresence.onAppVisible(Presentation.HIDDEN, true, true, phoneMode = true))
        assertEquals(Decision.Nothing, ShellPresence.onAppVisible(Presentation.NONE, startOnOpen = true, canStart = true, phoneMode = true))
        // Leaving the app: no pop-out over the phone the agent works on.
        assertEquals(Decision.Nothing, ShellPresence.onAppLeft(Presentation.HIDDEN, popOut = true, phoneMode = true))
        assertEquals(Decision.Present(Presentation.HIDDEN), ShellPresence.onAppLeft(Presentation.IN_APP, popOut = true, phoneMode = true))
        // A session that starts meanwhile stays in the background too.
        assertEquals(Decision.Present(Presentation.HIDDEN), ShellPresence.onSessionStarted(Presentation.MINI, appVisible = false, phoneMode = true))
        assertEquals(Decision.Present(Presentation.HIDDEN), ShellPresence.onSessionStarted(Presentation.IN_APP, appVisible = true, phoneMode = true))
        assertEquals(Decision.Nothing, ShellPresence.onSessionStarted(Presentation.HIDDEN, appVisible = true, phoneMode = true))
    }

    @Test fun switchingBackToDexResumesTheNormalRules() {
        for (cause in listOf(Cause.CHIP, Cause.TASK_TARGET)) {
            // In the app: the player takes it.
            assertEquals(Decision.Present(Presentation.IN_APP), ShellPresence.onPhoneModeChanged(Presentation.HIDDEN, false, cause, appVisible = true, popOut = true))
            // Away from the app (popup chat, notification): it pops out, unless the setting is off.
            assertEquals(Decision.Present(Presentation.MINI), ShellPresence.onPhoneModeChanged(Presentation.HIDDEN, false, cause, appVisible = false, popOut = true))
            assertEquals(Decision.Nothing, ShellPresence.onPhoneModeChanged(Presentation.HIDDEN, false, cause, appVisible = false, popOut = false))
            // Nothing is started by the switch.
            assertEquals(Decision.Nothing, ShellPresence.onPhoneModeChanged(Presentation.NONE, false, cause, appVisible = true, popOut = true))
        }
        // A phone task that ended (DeX chip) leaves the desktop where it is until the next event.
        assertEquals(Decision.Nothing, ShellPresence.onPhoneModeChanged(Presentation.HIDDEN, false, Cause.TASK_ENDED, appVisible = false, popOut = true))
        assertEquals(Decision.Nothing, ShellPresence.onPhoneModeChanged(Presentation.HIDDEN, false, Cause.TASK_ENDED, appVisible = true, popOut = true))
        //...then the normal rules apply again.
        assertEquals(ShellPresence.Decision.Nothing, ShellPresence.onAppVisible(Presentation.HIDDEN, true, true, phoneMode = false))
        assertEquals(Decision.Present(Presentation.MINI), ShellPresence.onAppLeft(Presentation.HIDDEN, popOut = true, phoneMode = false))
    }

    @Test fun theChatHidesItsPlayerInPhoneMode() {
        assertFalse(DesktopPaneRules.visible(Target.PHONE, hostPresent = true))
        assertFalse(DesktopPaneRules.visible(Target.DESKTOP, hostPresent = true, phoneMode = true))
        assertFalse(DesktopPaneRules.visible(Target.AUTO, hostPresent = true, phoneMode = true))
        assertTrue(DesktopPaneRules.visible(Target.AUTO, hostPresent = true, phoneMode = false))
    }

    @Test fun theNotificationSaysDexIsInTheBackgroundAndOffersToShowIt() {
        val running = AgentState(snapshot(resolvedTarget = Target.PHONE, lastAction = "launch app Calculator"))
        val model = SessionNotificationModel.from(running, Presentation.HIDDEN, target = Target.DESKTOP, phoneMode = true)
        assertTrue(model.text.contains(SessionNotificationModel.PHONE_MODE_LINE))
        assertTrue(model.actions.contains(SessionNotificationModel.Action.Present(Presentation.MINI)))
        // Outside phone mode the usual line.
        val normal = SessionNotificationModel.from(running, Presentation.HIDDEN, phoneMode = false)
        assertTrue(normal.text.contains("Desktop: running in the background"))
        // Shown again by the user in phone mode: the real presentation is reported.
        assertTrue(SessionNotificationModel.from(running, Presentation.MINI, phoneMode = true).text.contains("Desktop: popup window"))
    }
}

/** the chat player takes the desktop by itself; step lines are numbered like the stored thread. */
class ChatPlayerAndStepsTest {
    @Test fun theChatTakesTheDesktopWhileTheAgentTabIsOnScreen() {
        assertEquals(Presentation.IN_APP, DesktopPaneRules.autoShow(Presentation.MINI, onScreen = true, userMovedIt = false))
        // Also right after "Open Backstage" sent it to the background.
        assertEquals(Presentation.IN_APP, DesktopPaneRules.autoShow(Presentation.HIDDEN, onScreen = true, userMovedIt = false))
        // Full screen, already here, or no session: nothing.
        assertNull(DesktopPaneRules.autoShow(Presentation.FULL, onScreen = true, userMovedIt = false))
        assertNull(DesktopPaneRules.autoShow(Presentation.IN_APP, onScreen = true, userMovedIt = false))
        assertNull(DesktopPaneRules.autoShow(Presentation.NONE, onScreen = true, userMovedIt = false))
        // The tab is not on screen, or the user moved it away with the pane's own buttons.
        assertNull(DesktopPaneRules.autoShow(Presentation.HIDDEN, onScreen = false, userMovedIt = false))
        assertNull(DesktopPaneRules.autoShow(Presentation.MINI, onScreen = true, userMovedIt = true))
    }

    @Test fun stepLinesAreNumberedFromOneWithoutLoopControl() {
        // The controller counts shown actions (finish/plan/questions are none): "1. desktop launch app calculator".
        var t = Transcript()
        t = t.onSnapshot(snapshot(step = 0, shownActions = 0))
        t = t.onSnapshot(snapshot(step = 0, lastAction = "desktop launch app calculator", shownActions = 1))
        t = t.onSnapshot(snapshot(step = 1, lastAction = "desktop launch app calculator", shownActions = 1))
        // The same action again is a line of its own.
        t = t.onSnapshot(snapshot(step = 1, lastAction = "get screen", shownActions = 2))
        t = t.onSnapshot(snapshot(step = 1, lastAction = "get screen", shownActions = 3))
        t = t.onSnapshot(snapshot(status = TaskStatus.SUCCEEDED, step = 1, lastAction = "get screen", shownActions = 3, result = "Opened"))
        val actions = t.entries.filter { it.kind == Transcript.Kind.ACTION }
        assertEquals(listOf("desktop launch app calculator", "get screen", "get screen"), actions.map { it.text })
        assertEquals(listOf(1, 2, 3), actions.map { it.step })
    }
}
