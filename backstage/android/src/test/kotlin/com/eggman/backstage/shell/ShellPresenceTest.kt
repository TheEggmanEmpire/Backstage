package com.eggman.backstage.shell

import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.shell.ShellPresence.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellPresenceTest {
    @Test fun openingTheAppTakesThePopupIntoTheApp() {
        assertEquals(ShellPresence.Decision.Nothing, ShellPresence.onAppVisible(Presentation.MINI, true, true))
        // Also after the headphones button sent it to the background.
        assertEquals(ShellPresence.Decision.Nothing, ShellPresence.onAppVisible(Presentation.HIDDEN, true, true))
        assertEquals(Decision.Nothing, ShellPresence.onAppVisible(Presentation.IN_APP, true, true))
    }

    @Test fun automaticHomePreviewNeverPopsOutOnLeave() {
        assertEquals(Decision.Nothing, ShellPresence.onAppLeft(Presentation.HIDDEN, true, keepHidden = true))
        assertEquals(Decision.Present(Presentation.HIDDEN), ShellPresence.onAppLeft(Presentation.IN_APP, true, keepHidden = true))
        assertEquals(Decision.Nothing, ShellPresence.onAppLeft(Presentation.FULL, true, keepHidden = true))
    }

    @Test fun fullScreenIsLeftAlone() {
        assertEquals(Decision.Nothing, ShellPresence.onAppVisible(Presentation.FULL, true, true))
        assertEquals(Decision.Nothing, ShellPresence.onAppLeft(Presentation.FULL, true))
        assertEquals(Decision.Nothing, ShellPresence.onAppLeft(Presentation.FULL, false))
    }

    @Test fun openingStartsTheDesktopOnlyWhenAllowed() {
        assertEquals(Decision.StartInApp, ShellPresence.onAppVisible(Presentation.NONE, startOnOpen = true, canStart = true))
        assertEquals(Decision.Nothing, ShellPresence.onAppVisible(Presentation.NONE, startOnOpen = false, canStart = true))
        assertEquals(Decision.Nothing, ShellPresence.onAppVisible(Presentation.NONE, startOnOpen = true, canStart = false))
    }

    @Test fun leavingPopsOutAlsoAfterBackground() {
        assertEquals(Decision.Present(Presentation.MINI), ShellPresence.onAppLeft(Presentation.IN_APP, popOut = true))
        assertEquals(Decision.Present(Presentation.MINI), ShellPresence.onAppLeft(Presentation.HIDDEN, popOut = true))
        assertEquals(Decision.Nothing, ShellPresence.onAppLeft(Presentation.MINI, popOut = true))
        assertEquals(Decision.Nothing, ShellPresence.onAppLeft(Presentation.NONE, popOut = true))
    }

    @Test fun popOutSettingOffKeepsTheDesktopInTheBackground() {
        assertEquals(Decision.Present(Presentation.HIDDEN), ShellPresence.onAppLeft(Presentation.IN_APP, popOut = false))
        assertEquals(Decision.Nothing, ShellPresence.onAppLeft(Presentation.HIDDEN, popOut = false))
    }

    @Test fun startWhileAwayPopsUpStartWhileInsidePlaysInTheApp() {
        assertEquals(Decision.Present(Presentation.MINI), ShellPresence.onSessionStarted(Presentation.IN_APP, appVisible = false))
        assertEquals(Decision.Nothing, ShellPresence.onSessionStarted(Presentation.MINI, appVisible = false))
        assertEquals(Decision.Nothing, ShellPresence.onSessionStarted(Presentation.MINI, appVisible = true))
        assertEquals(Decision.Nothing, ShellPresence.onSessionStarted(Presentation.FULL, appVisible = true))
    }
}
