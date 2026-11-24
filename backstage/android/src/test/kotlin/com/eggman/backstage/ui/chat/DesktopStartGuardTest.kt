package com.eggman.backstage.ui.chat

import com.eggman.backstage.host.PlayDesktopPrerequisite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopStartGuardTest {
    @Test fun noPrerequisiteMissingStartsWithoutDialog() = assertNull(DesktopStartGuard.message(null))

    @Test fun accessibilityOffOffersAccessibilityAndAppDetailsWithRestrictedSteps() {
        val blocker = DesktopStartGuard.message(PlayDesktopPrerequisite.ACCESSIBILITY_OFF)!!
        assertEquals(listOf("Open Accessibility", "Open app details"), blocker.actions.map { it.label })
        assertTrue(blocker.message.contains("Allow restricted settings"))
        assertTrue(blocker.message.contains("tap Backstage-play once"))
    }

    @Test fun missingManagerLinksStellarAndShizukuPlus() =
        assertEquals(listOf("Get Stellar", "Get Shizuku Plus"),
            DesktopStartGuard.message(PlayDesktopPrerequisite.MANAGER_MISSING)!!.actions.map { it.label })

    @Test fun everyPrerequisiteHasAFixAction() = PlayDesktopPrerequisite.entries.forEach {
        assertTrue(it.name, DesktopStartGuard.message(it)!!.actions.isNotEmpty())
    }
}
