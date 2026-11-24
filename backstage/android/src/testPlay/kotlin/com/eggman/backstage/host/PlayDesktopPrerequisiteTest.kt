package com.eggman.backstage.host

import com.eggman.backstage.privileged.ShizukuState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayDesktopPrerequisiteTest {
    @Test fun privilegeProblemsTakePrecedenceOverAccessibility() {
        val expected = mapOf(
            ShizukuState.NOT_INSTALLED to PlayDesktopPrerequisite.MANAGER_MISSING,
            ShizukuState.NOT_RUNNING to PlayDesktopPrerequisite.MANAGER_STOPPED,
            ShizukuState.PERMISSION_NEEDED to PlayDesktopPrerequisite.MANAGER_PERMISSION,
        )
        for ((state, blocker) in expected) {
            assertEquals(blocker, PlayDesktopPrerequisite.check(state) { error("Accessibility checked before privilege") })
        }
    }

    @Test fun disabledAccessibilityBlocksDesktopWithWorkingPrivilege() {
        assertEquals(PlayDesktopPrerequisite.ACCESSIBILITY_OFF, PlayDesktopPrerequisite.check(ShizukuState.READY) { false })
    }

    @Test fun desktopNeedsOnlyWorkingPrivilegeAndDesktopAccessibility() {
        assertNull(PlayDesktopPrerequisite.check(ShizukuState.READY) { true })
    }
}
