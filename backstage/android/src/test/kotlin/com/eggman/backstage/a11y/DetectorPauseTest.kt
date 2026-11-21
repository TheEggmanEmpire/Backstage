package com.eggman.backstage.a11y

import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorPauseTest {
    private val paypay = Settings.DEFAULT_DETECTORS
    private val detectors = setOf(paypay)

    // ---- decide: not paused, enter only for a detector ------------------------------------

    @Test fun entersForADetector() {
        assertEquals(DetectorPause.Next.ENTER, DetectorPause.decide(paypay, null, 1_000, detectors))
    }

    @Test fun doesNothingForAnotherApp() {
        assertEquals(DetectorPause.Next.NONE, DetectorPause.decide("com.example.other", null, 1_000, detectors))
    }

    @Test fun doesNothingWithoutAForegroundPackage() {
        assertEquals(DetectorPause.Next.NONE, DetectorPause.decide(null, null, 1_000, detectors))
    }

    @Test fun doesNothingWithAnEmptyDetectorList() {
        assertEquals(DetectorPause.Next.NONE, DetectorPause.decide(paypay, null, 1_000, emptySet()))
    }

    // ---- decide: paused, postpone while the detector is still there ------------------------

    @Test fun postponesWhileTheDetectorStaysInFront() {
        assertEquals(DetectorPause.Next.POSTPONE, DetectorPause.decide(paypay, 0, 5_000, detectors))
    }

    @Test fun exitsWhenTheDetectorLeft() {
        assertEquals(DetectorPause.Next.EXIT, DetectorPause.decide("com.example.other", 0, 5_000, detectors))
    }

    // Unknown foreground (no usage access): an early exit would show the detector the service again; the cap ends it.
    @Test fun keepsThePauseWhileTheForegroundIsUnknown() {
        assertEquals(DetectorPause.Next.POSTPONE, DetectorPause.decide(null, 0, 5_000, detectors))
        assertEquals(DetectorPause.Next.EXIT, DetectorPause.decide(null, 0, DetectorPause.MAX_PAUSE_MS, detectors))
    }

    @Test fun theTickNeverSleepsPastTheCap() {
        assertEquals(5_000L, DetectorPause.nextDelay(5_000, 0, 10_000))
        assertEquals(1_000L, DetectorPause.nextDelay(59_000, 0, DetectorPause.MAX_PAUSE_MS - 1_000))
        assertEquals(0L, DetectorPause.nextDelay(5_000, 0, DetectorPause.MAX_PAUSE_MS + 5))
    }

    @Test fun exitsAtTheCap() {
        assertEquals(DetectorPause.Next.EXIT, DetectorPause.decide(paypay, 0, DetectorPause.MAX_PAUSE_MS, detectors))
    }

    @Test fun postponesJustBelowTheCap() {
        assertEquals(
            DetectorPause.Next.POSTPONE,
            DetectorPause.decide(paypay, 0, DetectorPause.MAX_PAUSE_MS - 1, detectors),
        )
    }

    // ---- settings ---------------------------------------------------------------------------

    @Test fun pauseIsOnByDefaultForTheDefaultDetectorOnly() {
        val s = Settings(MemoryStore())
        assertTrue(s.detectorPause)
        assertEquals(Settings.DEFAULT_DETECTORS, s.detectorPackages)
        assertEquals(setOf(paypay), s.detectorPackageSet())
    }

    @Test fun pauseCanBeSwitchedOff() {
        val s = Settings(MemoryStore())
        s.detectorPause = false
        assertFalse(s.detectorPause)
    }

    @Test fun delayIsClampedOnWrite() {
        val s = Settings(MemoryStore())
        assertEquals(5_000, s.detectorPauseDelayMs)
        s.detectorPauseDelayMs = 500
        assertEquals(1_000, s.detectorPauseDelayMs)
        s.detectorPauseDelayMs = 90_000
        assertEquals(60_000, s.detectorPauseDelayMs)
    }

    @Test fun storedDelayIsClampedOnRead() {
        val s = Settings(MemoryStore(mapOf("a11y_pause_delay_ms" to "10")))
        assertEquals(1_000, s.detectorPauseDelayMs)
    }

    @Test fun packagesSplitOnColonCommaAndNewline() {
        val s = Settings(MemoryStore())
        s.detectorPackages = " a.b : c.d , e.f\ng.h \n\n"
        assertEquals(setOf("a.b", "c.d", "e.f", "g.h"), s.detectorPackageSet())
    }

    @Test fun anEmptyPackageListYieldsNoDetectors() {
        val s = Settings(MemoryStore())
        s.detectorPackages = " , \n"
        assertTrue(s.detectorPackageSet().isEmpty())
    }
}
