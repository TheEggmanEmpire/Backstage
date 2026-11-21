package com.eggman.backstage.shell

import org.junit.Assert.*
import org.junit.Test

class ShellNavigationTest {
    @Test fun onlyKnownPagesCanSelectAFlutterDestination() {
        assertEquals(ShellPage.HOME, ShellPage.parse("home"))
        assertEquals(ShellPage.LINUX, ShellPage.parse("linux"))
        assertEquals(ShellPage.SETTINGS, ShellPage.parse("settings"))
        for (value in listOf(null, "", "ai", "Home", " settings", "unknown")) assertNull(ShellPage.parse(value))
        assertEquals(listOf(0, 2, 3), ShellPage.entries.map { it.navigationIndex })
        assertEquals(listOf("home", "linux", "settings"), ShellPage.entries.map { it.wireName })
    }
    @Test fun wideNavigationMatchesFlutterThresholdAndDesktopLandscape() {
        assertFalse(shellWideLayout(839, false, true))
        assertTrue(shellWideLayout(840, false, false))
        assertTrue(shellWideLayout(600, true, true))
        assertFalse(shellWideLayout(600, true, false))
    }
}
