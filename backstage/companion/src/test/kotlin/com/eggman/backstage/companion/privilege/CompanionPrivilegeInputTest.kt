package com.eggman.backstage.companion.privilege

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompanionPrivilegeInputTest {
    @Test fun acceptsFreeFormShellButRejectsScreenAndAccessibilityPaths() {
        assertNull(CompanionPrivilege.validateRequest("shell", "pm list packages"))
        assertNotNull(CompanionPrivilege.validateRequest("shell", "screencap -p /sdcard/s.png"))
        assertNotNull(CompanionPrivilege.validateRequest("shell", "settings put secure enabled_accessibility_services x/y"))
        assertNotNull(CompanionPrivilege.validateRequest("shell", "   "))
        assertNotNull(CompanionPrivilege.validateRequest("shell", "pm list packages\u200B"))
        assertNotNull(CompanionPrivilege.validateRequest("shell", "x".repeat(1_001)))
    }

    @Test fun installRequiresAnAbsolutePrintableApkPath() {
        assertNotNull(CompanionPrivilege.validateRequest("install", "relative.apk"))
        assertNull(CompanionPrivilege.validateRequest("install", "/definitely/not/a/real/file.apk"))
        assertNotNull(CompanionPrivilege.validateRequest("install", "/etc/hosts"))
        assertNotNull(CompanionPrivilege.validateRequest("install", "/sdcard/app\u200B.apk"))
        val apk = java.io.File.createTempFile("local-package", ".apk")
        try { assertNull(CompanionPrivilege.validateRequest("install", apk.absolutePath)) }
        finally { apk.delete() }
    }

    @Test fun uninstallAcceptsStrictPackageIdsOnly() {
        assertNull(CompanionPrivilege.validateRequest("uninstall", "com.example.app_2"))
        assertNotNull(CompanionPrivilege.validateRequest("uninstall", "com.example; id"))
        assertNotNull(CompanionPrivilege.validateRequest("uninstall", "com.example/Component"))
        assertNotNull(CompanionPrivilege.validateRequest("uninstall", "com."))
    }

    @Test fun shellScreenPathFilterIsCaseInsensitiveAndDoesNotRejectOrdinaryAdmin() {
        assertTrue(CompanionPrivilege.forbiddenShell("Screencap -p"))
        assertTrue(CompanionPrivilege.forbiddenShell("cmd accessibility"))
        assertTrue(CompanionPrivilege.forbiddenShell("input tap 400 600"))
        assertTrue(CompanionPrivilege.forbiddenShell("dumpsys window"))
        assertFalse(CompanionPrivilege.forbiddenShell("pm list packages"))
    }
}
