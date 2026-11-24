package com.eggman.backstage.companion

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompanionAccessibilitySettingsTest {
    @Test fun acceptsAndNormalizesTheCompanionAccessibilitySettings() {
        val settings = CompanionAccessibilitySettings.from(Bundle().apply {
            putBoolean("autoEnable", true)
            putBoolean("pauseForDetector", false)
            putInt("recheckSeconds", 7)
            putString("detectorPackages", "com.example.one, com.example.two\ncom.example.one")
        })

        assertEquals(setOf("com.example.one", "com.example.two"), settings?.detectorPackages)
        assertTrue(settings?.autoEnable == true)
        assertFalse(settings?.pauseForDetector == true)
        assertEquals(7, settings?.recheckSeconds)
    }

    @Test fun rejectsIncompleteOrOutOfRangeAccessibilitySettings() {
        assertNull(CompanionAccessibilitySettings.from(Bundle()))
        assertNull(CompanionAccessibilitySettings.from(validBundle().apply { putInt("recheckSeconds", 0) }))
        assertNull(CompanionAccessibilitySettings.from(validBundle().apply { putInt("recheckSeconds", 61) }))
        assertNull(CompanionAccessibilitySettings.from(validBundle().apply { putString("detectorPackages", "com.good;settings put secure x") }))
    }

    private fun validBundle() = Bundle().apply {
        putBoolean("autoEnable", false)
        putBoolean("pauseForDetector", true)
        putInt("recheckSeconds", 5)
        putString("detectorPackages", "com.example.detector")
    }
}
