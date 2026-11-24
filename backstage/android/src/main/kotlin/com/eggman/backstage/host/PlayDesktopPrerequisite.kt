package com.eggman.backstage.host

import com.eggman.backstage.privileged.ShizukuState

enum class PlayDesktopPrerequisite {
    MANAGER_MISSING, MANAGER_STOPPED, MANAGER_PERMISSION, ACCESSIBILITY_OFF;

    companion object {
        fun check(privilege: ShizukuState, accessibilityEnabled: () -> Boolean): PlayDesktopPrerequisite? = when (privilege) {
            ShizukuState.NOT_INSTALLED -> MANAGER_MISSING
            ShizukuState.NOT_RUNNING -> MANAGER_STOPPED
            ShizukuState.PERMISSION_NEEDED -> MANAGER_PERMISSION
            ShizukuState.READY -> ACCESSIBILITY_OFF.takeUnless { accessibilityEnabled() }
        }
    }
}
