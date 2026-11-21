package com.eggman.backstage.robolectric

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.eggman.backstage.a11y.LocalAccessibilityBackend
import com.eggman.backstage.a11y.TextInputSession
import com.eggman.backstage.exec.PhoneExecutor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowClipboardManager

@Implements(ClipboardManager::class)
class ClipboardReadRestrictionShadow : ShadowClipboardManager() {
    var hideNextRead = false
    var denyNextRead = false
    var clears = 0
    @Implementation override fun getPrimaryClip(): ClipData? {
        if (denyNextRead) { denyNextRead = false; throw SecurityException("Clipboard read refused") }
        if (hideNextRead) { hideNextRead = false; return null }
        return super.getPrimaryClip()
    }
    @Implementation override fun clearPrimaryClip() { clears++; super.clearPrimaryClip() }
}

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ClipboardReadRestrictionShadow::class])
class ClipboardRestorationRobolectricTest {
    private lateinit var service: ServiceController<HostAccessibilityService>
    private lateinit var backend: LocalAccessibilityBackend
    private lateinit var clipboard: ClipboardManager
    private lateinit var restricted: ClipboardReadRestrictionShadow
    @Before fun setup() {
        service = A11yTrees.connectService()
        backend = LocalAccessibilityBackend(service.get())
        clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        restricted = Shadow.extract(clipboard)
        clipboard.setPrimaryClip(ClipData.newPlainText("user", "user clipboard"))
    }
    @After fun teardown() { service.destroy() }
    private fun field() = A11yTrees.node(bounds = Rect(0, 0, 500, 100), editable = true, focused = true).also {
        shadowOf(it).setOnPerformActionListener { action, _ -> action != AccessibilityNodeInfo.ACTION_SET_TEXT }
    }
    @Test fun nullReadDoesNotClearNonSecretClipboard() {
        val field = field()
        restricted.hideNextRead = true
        assertTrue(backend.setText(field, 0, "ordinary value"))
        assertEquals(0, restricted.clears)
        assertEquals("ordinary value", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(backend.clipboardCouldNotBeRestored(0))
    }
    @Test fun deniedReadDoesNotClearNonSecretClipboard() {
        val field = field()
        restricted.denyNextRead = true
        assertTrue(backend.setText(field, 0, "ordinary value"))
        assertEquals(0, restricted.clears)
        assertTrue(backend.clipboardCouldNotBeRestored(0))
    }
    @Test fun unreadableClipIsClearedAfterExplicitSecretPaste() {
        val field = field()
        restricted.hideNextRead = true
        assertTrue(TextInputSession.onThread(secret = true) { backend.setText(field, 0, "private value") })
        assertEquals(1, restricted.clears)
        assertFalse(clipboard.hasPrimaryClip())
    }
    @Test fun actionResultIncludesClipboardRestorationNotice() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val field = field()
        val root = A11yTrees.node(bounds = Rect(0, 0, 1080, 1920), children = listOf(field))
        shadowOf(service.get()).setWindows(listOf(A11yTrees.window(12, root, Rect(0, 0, 1080, 1920))))
        val executor = PhoneExecutor(app, setOf(app.packageName))
        val element = executor.screen().elements.single { it.editable }
        restricted.hideNextRead = true
        val result = executor.setText(element, "ordinary value")
        assertTrue(result.ok)
        assertTrue(result.message.contains("Clipboard could not be restored"))
        assertEquals(0, restricted.clears)
    }
}
