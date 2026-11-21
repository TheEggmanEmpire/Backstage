package com.eggman.backstage.host

import com.eggman.backstage.host.DesktopStartProfile.Spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The headless background start must create the same display Dextop's own home screen would. */
class DesktopStartProfileTest {
    @Test fun deviceProfileIsLandscapeOrderedWithTheAutomaticDensity() {
        assertEquals(Spec(2340, 1080, 232), DesktopStartProfile.device(1080, 2340, 3.0f))
        assertEquals(Spec(2340, 1080, 320), DesktopStartProfile.device(2340, 1080, 7.0f))   // capped
        assertEquals(Spec(1280, 720, 184), DesktopStartProfile.device(720, 1280, 1.0f))
    }

    @Test fun orientationFollowsTheSavedMode() {
        assertTrue(DesktopStartProfile.portrait("portrait", null, phonePortrait = false))
        assertFalse(DesktopStartProfile.portrait("landscape", true, phonePortrait = true))
        assertTrue(DesktopStartProfile.portrait("automatic", null, phonePortrait = true))
        assertFalse(DesktopStartProfile.portrait("automatic", null, phonePortrait = false))
        // Builds before automatic orientation stored only home_portrait; neither means landscape.
        assertTrue(DesktopStartProfile.portrait(null, true, phonePortrait = false))
        assertFalse(DesktopStartProfile.portrait(null, null, phonePortrait = true))
    }

    @Test fun noMagnificationKeepsTheOrientedProfile() {
        val p = Spec(1920, 1080, 240)
        assertEquals(Spec(1920, 1080, 240), DesktopStartProfile.compute(p, portrait = false, 100, false))
        assertEquals(Spec(1080, 1920, 240), DesktopStartProfile.compute(p, portrait = true, 100, false))
        assertEquals(Spec(1920, 1080, 240), DesktopStartProfile.compute(p, portrait = false, 50, false)) // clamped to 100
    }

    @Test fun magnificationScalesSizeAndDensityTogetherLikeDextop() {
        val p = Spec(1920, 1080, 240)
        // 150 %: scale 2/3 -> 1280x720, density 240 * (1280/1920) = 160.
        assertEquals(Spec(1280, 720, 160), DesktopStartProfile.compute(p, false, 150, false))
        // 200 %: the 120 dpi floor caps the scale at 0.5 -> 960x540, 120 dpi.
        assertEquals(Spec(960, 540, 120), DesktopStartProfile.compute(p, false, 200, false))
        // Magnifying windows with the display keeps the profile's density.
        assertEquals(Spec(960, 540, 240), DesktopStartProfile.compute(p, false, 200, true))
        // The 480 px short side caps the scale (853 made even).
        assertEquals(Spec(852, 480, 240), DesktopStartProfile.compute(Spec(1280, 720, 240), false, 200, true))
        // Odd results are made even.
        assertEquals(0, DesktopStartProfile.compute(Spec(2340, 1080, 232), false, 130, false).width % 2)
    }

    @Test fun startBoundsMatchDextopsOwnCheck() {
        assertTrue(DesktopStartProfile.valid(Spec(1920, 1080, 240)))
        assertFalse(DesktopStartProfile.valid(Spec(1920, 1080, 700)))
        assertFalse(DesktopStartProfile.valid(Spec(400, 1080, 240)))
    }

    @Test fun smallAndMissingPhoneMetricsStillProduceAStartableDesktop() {
        for (spec in listOf(
            DesktopStartProfile.device(320, 480, 1f),
            DesktopStartProfile.device(0, 0, Float.NaN),
            Spec(300, 400, 0),
            Spec(16000, 9000, 960),
        )) {
            for (percent in listOf(100, 150, 200)) {
                val computed = DesktopStartProfile.compute(spec, false, percent, true)
                assertTrue("$spec at $percent -> $computed", DesktopStartProfile.valid(computed))
            }
        }
    }
}
