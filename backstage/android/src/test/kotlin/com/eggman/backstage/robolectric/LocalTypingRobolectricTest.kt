package com.eggman.backstage.robolectric

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.eggman.backstage.a11y.LocalAccessibilityBackend
import com.eggman.backstage.a11y.TextVerification
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
class LocalTypingRobolectricTest {
    private lateinit var controller: ServiceController<HostAccessibilityService>
    private lateinit var backend: LocalAccessibilityBackend
    @Before fun setup() { controller = A11yTrees.connectService(); backend = LocalAccessibilityBackend(controller.get()) }
    @After fun teardown() { controller.destroy() }

    private fun field(text: String = "") = A11yTrees.node(text = text, bounds = Rect(0, 0, 400, 100), editable = true, focused = true)

    @Test fun setTextIsReadBackAndVerified() {
        val field = field()
        assertTrue(backend.setText(field, 0, "new value"))
        assertEquals(TextVerification.VERIFIED, backend.textVerification(field, 0, "new value"))
    }
    @Test fun refusedSetTextUsesSensitiveSuppressedPasteAndRestoresTheClip() {
        val field = field("old")
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("saved", "previous clipboard"))
        var sensitivePaste = false
        shadowOf(field).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_PASTE) {
                val extras = clipboard.primaryClip!!.description.extras!!
                sensitivePaste = extras.getBoolean("android.content.extra.IS_SENSITIVE") && extras.getBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY")
            }
            action != AccessibilityNodeInfo.ACTION_SET_TEXT
        }
        assertTrue(backend.setText(field, 0, "new value"))
        assertTrue(sensitivePaste)
        assertEquals("previous clipboard", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"))
    }
    @Test fun clipboardStaysInstalledUntilDelayedReaderConsumesIt() = kotlinx.coroutines.runBlocking {
        val field = field()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("saved", "previous clipboard"))
        (shadowOf(field) as PrefetchAccessibilityNodeInfoShadow).pasteDelayMs = 100
        shadowOf(field).setOnPerformActionListener { action, _ -> action != AccessibilityNodeInfo.ACTION_SET_TEXT }
        val accepted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { backend.setText(field, 0, "delayed secret") }
        assertTrue(accepted)
        assertEquals("previous clipboard", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun refusedPasteStillRestoresTheClip() {
        val field = field()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("saved", "previous clipboard"))
        shadowOf(field).setOnPerformActionListener { _, _ -> false }
        assertFalse(backend.setText(field, 0, "secret"))
        assertEquals("previous clipboard", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }
}
