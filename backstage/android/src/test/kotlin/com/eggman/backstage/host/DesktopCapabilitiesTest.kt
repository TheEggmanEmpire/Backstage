package com.eggman.backstage.host

import org.junit.Assert.*
import org.junit.Test

class DesktopCapabilitiesTest {
    private val base = 1 or 2 or 8
    private val touch = 64
    private val decor = 512
    private val trusted = 1024
    private val focus = LockedModeStatus.OWN_FOCUS
    private val rich = focus or LockedModeStatus.OWN_DISPLAY_GROUP or LockedModeStatus.ALWAYS_UNLOCKED
    private val quiet = LockedModeStatus.STEAL_TOP_FOCUS_DISABLED
    private val upstream = intArrayOf(base or touch or decor or trusted, base or decor or trusted, base or trusted, base)
    private fun profile(maker: String = "Google", model: String = "Pixel 8", sdk: Int = 36, external: Int = 0) =
        DesktopCapabilities.detect(DesktopCapabilities.Facts(maker, model, sdk, false, external))

    @Test fun familyDoesNotInventExternalDisplayOrFreeformSupport() {
        val pixel = profile()
        assertEquals(DesktopCapabilities.Family.PIXEL, pixel.family)
        assertTrue(pixel.nativeDesktop)
        assertFalse(pixel.freeformAdvertised)
        assertFalse(pixel.externalDisplayPresent)
        assertTrue(profile(external = 1).externalDisplayPresent)
        assertFalse(profile(sdk = 35).nativeDesktop)
        assertEquals(DesktopCapabilities.Family.GENERIC, profile(model = "sdk_gphone64_x86_64").family)
        assertEquals(DesktopCapabilities.Family.SAMSUNG, profile(" SAMSUNG ", "SM-S918B").family)
        assertEquals(DesktopCapabilities.Family.GENERIC, profile("motorola", "edge").family)
        val unknown = DesktopCapabilities.detect(DesktopCapabilities.Facts(null, null, 33, true, -1))
        assertEquals(DesktopCapabilities.Family.GENERIC, unknown.family)
        assertFalse(unknown.externalDisplayPresent)
        assertTrue(unknown.freeformAdvertised)
        assertTrue(unknown.description.contains("compat desktop"))
        assertTrue(pixel.description.contains("phone only"))
        assertTrue(profile(external = 1).description.contains("connected display"))
    }

    @Test fun samsungKeepsItsExistingRungsExactly() {
        val extras = intArrayOf(rich or quiet, focus or quiet, rich, focus)
        assertArrayEquals((extras.map { upstream[0] or it } + upstream.toList()).toIntArray(),
            DesktopCapabilities.flagLadder(profile("Samsung", "SM-S918B"), upstream, extras))
    }

    @Test fun pixelRetriesLockFlagsAcrossBasesBeforeDroppingLockedMode() {
        val rungs = DesktopCapabilities.flagLadder(profile(), upstream, intArrayOf(rich or quiet, focus or quiet, rich, focus)).toList()
        assertEquals(upstream[0] or rich or quiet, rungs.first())
        assertTrue(rungs.indexOf(base or trusted or rich or quiet) < rungs.indexOf(base or decor or trusted or focus or quiet))
        assertEquals(base, rungs.last())
        assertEquals(rungs.distinct(), rungs)
        assertTrue(rungs.filter { it and trusted == 0 }.all { it and (rich or quiet or decor) == 0 })
    }

    @Test fun undecoratedNonSamsungDisplaysRetainOnlyTrustedRungs() {
        val publicTrustedOnly = intArrayOf(base or touch or trusted, base or trusted)
        for (p in listOf(profile("other", "phone"), profile(sdk = 35))) {
            val rungs = DesktopCapabilities.flagLadder(p, publicTrustedOnly, intArrayOf(focus))
            assertEquals(publicTrustedOnly[0] or focus, rungs.first())
            assertTrue(rungs.contains(base or trusted or focus))
            assertTrue(rungs.all { it and trusted != 0 })
        }
    }

    @Test fun noOptInNeverAddsLockFlagsAndPrivateDisplaysAreUntouched() {
        val rungs = DesktopCapabilities.flagLadder(profile(), upstream, intArrayOf(focus))
        assertTrue(rungs.all { it and (LockedModeStatus.OWN_DISPLAY_GROUP or LockedModeStatus.ALWAYS_UNLOCKED) == 0 })
        val private = intArrayOf(2 or 8 or touch or trusted)
        assertArrayEquals(private, DesktopCapabilities.flagLadder(profile(), private, intArrayOf()))
        assertArrayEquals(intArrayOf(), DesktopCapabilities.flagLadder(profile(), intArrayOf(), intArrayOf()))
    }


    @Test fun explicitNoneAndAndroid13DoNotInventOwnFocusOrUnlock() {
        for (p in listOf(profile(), profile("other", "phone"))) {
            assertArrayEquals(upstream, DesktopCapabilities.flagLadder(p, upstream, intArrayOf()))
            val group = LockedModeStatus.OWN_DISPLAY_GROUP or LockedModeStatus.ALWAYS_UNLOCKED
            val rungs = DesktopCapabilities.flagLadder(p.copy(sdk = 33), upstream, intArrayOf(group))
            assertTrue(rungs.all { it and (focus or quiet) == 0 })
            assertTrue(rungs.size <= 24)
        }
    }

    @Test fun defaultOrInvalidGeometryIsNeverADesktop() {
        assertEquals(DesktopHost.Geometry(7, 1920, 1080, 184), DesktopDisplayPolicy.geometry(7, 1920, 1080, 184))
        for (id in listOf(-1, 0)) assertNull(DesktopDisplayPolicy.geometry(id, 1920, 1080, 184))
        assertNull(DesktopDisplayPolicy.geometry(7, 0, 1080, 184))
        assertNull(DesktopDisplayPolicy.geometry(7, 1920, -1, 184))
        assertNull(DesktopDisplayPolicy.geometry(7, 1920, 1080, 0))
    }

    @Test fun queuedInputNeedsTheSameLiveOwnedDisplay() {
        assertTrue(DesktopDisplayPolicy.inputTarget(7, 7))
        assertFalse(DesktopDisplayPolicy.inputTarget(7, null))
        assertFalse(DesktopDisplayPolicy.inputTarget(7, 8))
        assertFalse(DesktopDisplayPolicy.inputTarget(0, 0))
        assertFalse(DesktopDisplayPolicy.inputTarget(-1, -1))
    }
}
