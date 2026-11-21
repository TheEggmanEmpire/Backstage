package com.eggman.backstage.agent

import com.eggman.backstage.host.LockedModeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedModeCompatibilityTest {
    @Test fun fallbackReportIncludesActualFailuresWithoutPromisingLockedSupport() {
        val status = LockedModeStatus(false, false, null, 0, 0, 0, false, null, null, null,
            compatibilityProfile = "Pixel / Android 36 / phone only", displayFallback = "flags 0x40 refused")
        val report = com.eggman.backstage.agent.LockedModeReport.build(true, status, null, 100)
        assertTrue(report.summary.contains("tries"))
        assertEquals("Pixel / Android 36 / phone only", report.lines.first { it.label == "Compatibility profile" }.value)
        assertEquals("flags 0x40 refused", report.lines.first { it.label == "Display compatibility" }.value)
    }
}
