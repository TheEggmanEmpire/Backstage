package com.eggman.backstage.agent

import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.testutil.FakeDesktopHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** what the agent knows about the device. */
class DeviceContextTest {
    private val fold = DeviceFacts(
        maker = "Samsung", model = "SM-F956B", android = "15", sdk = 35, oneUi = "7.0",
        widthPx = 2504, heightPx = 2256, densityDpi = 420, orientation = "landscape",
        insetTop = 110, insetBottom = 63, navigation = "gestures", darkMode = true, fontScale = 1.1f,
        languages = listOf("de-DE", "en-US"), keyboardLanguages = listOf("de-DE"),
        foldKind = FoldKind.BOOK, fold = FoldState.UNFOLDED,
    )

    @Test fun foldStatesFromTheScreenShapeAndTheHinge() {
        // Galaxy Z Fold: the main screen is near-square, the cover screen tall and narrow.
        assertEquals(FoldState.UNFOLDED, FoldStates.from(FoldKind.BOOK, 2504, 2256))
        assertEquals(FoldState.UNFOLDED, FoldStates.from(FoldKind.BOOK, 2256, 2504))
        assertEquals(FoldState.FOLDED, FoldStates.from(FoldKind.BOOK, 968, 2376))
        assertEquals(FoldState.HALF_OPEN, FoldStates.from(FoldKind.BOOK, 2504, 2256, hingeAngle = 95f))
        assertEquals(FoldState.UNFOLDED, FoldStates.from(FoldKind.BOOK, 2504, 2256, hingeAngle = 178f))
        // Galaxy Z Flip: the other way round.
        assertEquals(FoldState.UNFOLDED, FoldStates.from(FoldKind.FLIP, 1080, 2640))
        assertEquals(FoldState.FOLDED, FoldStates.from(FoldKind.FLIP, 748, 720))
        assertNull(FoldStates.from(null, 1080, 2400))
        assertEquals(FoldKind.BOOK, FoldStates.kindOf("SM-F956B", false))
        assertEquals(FoldKind.FLIP, FoldStates.kindOf("SM-F741B", false))
        assertNull(FoldStates.kindOf("SM-S928B", false))
        assertEquals(FoldKind.BOOK, FoldStates.kindOf("Pixel Fold", true))
    }

    @Test fun thePromptCarriesThePhoneAndDesktopBlocksCompactly() {
        val host = FakeDesktopHost(active = true)
        val desktop = DeviceContext.desktopFacts(host, "Samsung DeX")!!
        val p = SystemPrompt.build(PromptContext(TargetKind.DESKTOP, null, true, false, true, true, true, 0L, ZoneId.of("UTC"), Locale.ENGLISH,
            deviceBlock = DeviceContext.deviceBlock(fold), desktopBlock = DeviceContext.desktopBlock(desktop)))
        assertTrue(p.contains("## This phone"))
        assertTrue(p.contains("Samsung SM-F956B (foldable), Android 15 (API 35), One UI 7.0."))
        assertTrue(p.contains("2504x2256 px = 954x859 dp, 420 dpi (x2.625), landscape; unfolded: the main screen."))
        assertTrue(p.contains("origin at the top left of the screen named in [state]"))
        assertTrue(p.contains("top 110, bottom 63"))
        assertTrue(p.contains("navigation: gestures; dark mode; font scale 1.1; languages: de-DE, en-US; keyboard: de-DE."))
        assertTrue(p.contains("## The DeX desktop"))
        assertTrue(p.contains("Display 7: 1920x1080 px = 1920x1080 dp, 160 dpi, landscape; environment: Samsung DeX; shown: in the popup."))
        val extra = DeviceContext.deviceBlock(fold).length + DeviceContext.desktopBlock(desktop).length
        assertTrue("about 400 tokens at most: $extra chars", extra < 1_600)
        // Nothing without facts or a desktop.
        assertEquals("", DeviceContext.deviceBlock(null))
        assertNull(DeviceContext.desktopFacts(FakeDesktopHost(active = false), null))
        host.presentation = DesktopHost.Presentation.HIDDEN
        assertTrue(DeviceContext.desktopBlock(DeviceContext.desktopFacts(host, null)).contains("shown: in the background"))
    }

    @Test fun theDeviceLineNamesOnlyChanges() {
        var now = mapOf("keyboard" to "hidden", "battery" to DeviceStateDiff.battery(57, false), "network" to "Wi-Fi")
        DeviceStateDiff.source = { now }
        try {
            DeviceStateDiff.reset()
            assertEquals("[device] keyboard: hidden; battery: 55%; network: Wi-Fi", DeviceStateDiff.line())
            DeviceStateDiff.commit()
            assertNull(DeviceStateDiff.line())
            now = now + ("keyboard" to "shown on the phone") + ("battery" to DeviceStateDiff.battery(56, false))
            // captures that never reach the model (settle polls, guard re-reads) do not swallow the change
            assertEquals("[device] keyboard: shown on the phone", DeviceStateDiff.line())
            assertEquals("[device] keyboard: shown on the phone", DeviceStateDiff.line())
            DeviceStateDiff.commit()
            assertNull(DeviceStateDiff.line())
            now = now + ("battery" to DeviceStateDiff.battery(80, true))
            assertEquals("[device] battery: 80%, charging", DeviceStateDiff.line())
            DeviceStateDiff.commit()
            // A new task starts from scratch.
            DeviceStateDiff.reset()
            assertTrue(DeviceStateDiff.line()!!.contains("network: Wi-Fi"))
        } finally {
            DeviceStateDiff.source = null
            DeviceStateDiff.reset()
        }
        assertFalse(DeviceStateDiff.diff(mapOf("a" to "1"), mapOf("a" to "1")) != null)
    }
}
