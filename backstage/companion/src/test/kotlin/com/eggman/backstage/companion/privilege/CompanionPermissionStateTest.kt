package com.eggman.backstage.companion.privilege

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionPermissionStateTest {
    @Test fun permissionStatusRequiresALiveBinder() {
        assertEquals(CompanionPermissionState.MISSING, CompanionPermissionState.from(false, false, false))
        assertEquals(CompanionPermissionState.STOPPED, CompanionPermissionState.from(true, false, true))
        assertEquals(CompanionPermissionState.NEEDS_PERMISSION, CompanionPermissionState.from(true, true, false))
        assertEquals(CompanionPermissionState.GRANTED, CompanionPermissionState.from(true, true, true))
        assertEquals(CompanionPermissionState.GRANTED, CompanionPermissionState.from(false, true, true))
    }
}
