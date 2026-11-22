package com.eggman.backstage.companion

import android.os.Parcel
import com.eggman.backstage.a11y.companion.CaptureCodec
import com.eggman.backstage.a11y.companion.RemoteNodeHandle
import com.eggman.backstage.exec.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class CompanionWireTest {
    @Test fun aCaptureRoundTripPreservesEveryFieldAndTheRemoteHandle() {
        val node = UiNode(className = "EditText", text = "こんにちは", contentDescription = "label", hint = "hint",
            resourceId = "pkg:id/field", packageName = "pkg", stateDescription = "checked", bounds = Box(1, 2, 30, 40),
            clickable = true, longClickable = true, editable = true, scrollable = true, checkable = true, checked = true,
            selected = true, focusable = true, focused = true, enabled = false, visible = false,
            handle = RemoteNodeHandle(9102), children = listOf(UiNode(text = "child", handle = RemoteNodeHandle(9103))))
        val capture = ScreenCapture(7, 1920, 1080, listOf(UiWindow(42, WindowKind.APPLICATION, "Title", "pkg", 9,
            Box(0, 0, 1920, 1080), true, true, node)), 123456)
        val bytes = ByteArrayOutputStream().also { CaptureCodec.write(capture, it) }.toByteArray()
        assertEquals(capture, CaptureCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test fun passwordTextIsMaskedEvenIfAnAlternateCaptureSourceForgotToMaskIt() {
        val capture = ScreenCapture(0, 2, 2, listOf(UiWindow(1, WindowKind.APPLICATION,
            root = UiNode(text = "secret", password = true))))
        val bytes = ByteArrayOutputStream().also { CaptureCodec.write(capture, it) }.toByteArray()
        val actual = CaptureCodec.read(ByteArrayInputStream(bytes))
        assertEquals(UiNode.PASSWORD_MASK, actual.windows.single().root!!.text)
        assertFalse(String(bytes, Charsets.UTF_8).contains("secret"))
    }

    @Test fun truncatedOversizedAndUnknownWireDataAreRejected() {
        val bytes = ByteArrayOutputStream().also { CaptureCodec.write(ScreenCapture(0, 1, 1, emptyList()), it) }.toByteArray()
        assertTrue(runCatching { CaptureCodec.read(ByteArrayInputStream(bytes.copyOf(6))) }.isFailure)
        assertTrue(runCatching { CaptureCodec.read(ByteArrayInputStream(bytes.clone().apply { this[0] = 0 })) }.isFailure)
        val huge = UiNode(text = "x".repeat(CaptureCodec.MAX_STRING_BYTES + 1))
        assertTrue(runCatching { CaptureCodec.write(ScreenCapture(0, 1, 1,
            listOf(UiWindow(0, WindowKind.APPLICATION, root = huge))), ByteArrayOutputStream()) }.isFailure)
    }

    @Test fun eventParcelRoundTripPreservesDisplayNotificationAndStateData() {
        val expected = CompanionEvent(2048, 7, "pkg", "Chat", "Hello", 123456)
        val parcel = Parcel.obtain()
        try {
            expected.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            assertEquals(expected, CompanionEvent.CREATOR.createFromParcel(parcel))
            assertEquals(0, parcel.dataAvail())
        } finally { parcel.recycle() }
    }
}
