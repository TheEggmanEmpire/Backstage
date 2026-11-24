package com.eggman.backstage.a11y

import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityWindowInfo
import com.eggman.backstage.a11y.companion.CaptureCodec
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.companion.IAgentCallback
import com.eggman.backstage.companion.IAgentCompanion
import com.eggman.backstage.exec.*
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.testutil.FakeDesktopHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RemoteSecretProtocolCompatibilityTest {
    private val updateHint = "Update Backstage Agent to enter secrets safely."

    @Test fun knownOlderProtocolReportsUpdateAndNeverUsesPlainText() = runBlocking {
        val endpoint = Endpoint(version = 0)
        assertUpdateFailure(endpoint, captureFirst = false)
    }
    @Test fun sameProtocolButUnsupportedActionWithFocusedInputReportsUpdate() = runBlocking {
        assertUpdateFailure(Endpoint())
    }
    @Test fun unavailableVersionStillUsesFocusedInputEvidence() = runBlocking {
        assertUpdateFailure(Endpoint(versionUnavailable = true))
    }
    @Test fun missingInputDoesNotFalselyDemandAnUpdate() = runBlocking {
        val endpoint = Endpoint(focused = false)
        val result = phoneType(endpoint)
        assertFalse(result.ok)
        assertFalse(result.message.contains("updated Backstage Agent"))
    }
    @Test fun supportedSecretActionStillReportsSuccess() = runBlocking {
        val endpoint = Endpoint(acceptSecret = true)
        val result = phoneType(endpoint)
        assertTrue(result.message, result.ok)
        assertEquals(listOf(CompanionProtocol.SET_SECRET_TEXT), endpoint.actions)
    }
    @Test fun desktopDoesNotFallBackToHostTypingWhenCompanionNeedsUpdate() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val endpoint = Endpoint()
        val backend = backend(endpoint)
        assertTrue(backend.capture(7, 1920, 1080).windows.single().root!!.focused)
        val previous = AgentAccessibility.instance
        try {
            AgentAccessibility.instance = AgentAccessibility(RemoteAccessibilityContext(app), backend)
            var hostTyped = false
            val host = object : DesktopHost by FakeDesktopHost() {
                override suspend fun typeText(text: String): Boolean { hostTyped = true; return true }
            }
            val executor = DesktopExecutor(host, DesktopHost.Geometry(7, 1920, 1080, 160), setOf(app.packageName))
            val result = TextInputSession.withSession(secret = true) { executor.setText(null, "private") }
            assertFalse(result.ok)
            assertEquals(updateHint, result.message)
            assertFalse(hostTyped)
            assertEquals(listOf(CompanionProtocol.SET_SECRET_TEXT), endpoint.actions)
        } finally { AgentAccessibility.instance = previous }
    }
    private fun backend(endpoint: Endpoint) = RemoteAccessibilityBackend(endpoint, {}) { fd ->
        // The codec stays real; Java descriptor IO avoids Robolectric's unsupported native poll.
        ParcelFileDescriptor.AutoCloseInputStream(fd).use(CaptureCodec::read)
    }
    private suspend fun assertUpdateFailure(endpoint: Endpoint, captureFirst: Boolean = true) {
        val result = phoneType(endpoint, captureFirst)
        assertFalse(result.ok)
        assertEquals(updateHint, result.message)
        assertEquals(listOf(CompanionProtocol.SET_SECRET_TEXT), endpoint.actions)
    }
    private suspend fun phoneType(endpoint: Endpoint, captureFirst: Boolean = true): ActionResult {
        val app = RuntimeEnvironment.getApplication()
        val backend = backend(endpoint)
        if (captureFirst) assertEquals(endpoint.focused, backend.capture(0, 1080, 1920).windows.single().root!!.focused)
        val previous = AgentAccessibility.instance
        try {
            AgentAccessibility.instance = AgentAccessibility(RemoteAccessibilityContext(app), backend)
            val executor = PhoneExecutor(app, setOf(app.packageName))
            return TextInputSession.withSession(secret = true) { executor.setText(null, "private") }
        } finally { AgentAccessibility.instance = previous }
    }
    private class Endpoint(
        val focused: Boolean = true,
        val version: Int = CompanionProtocol.VERSION,
        val versionUnavailable: Boolean = false,
        val acceptSecret: Boolean = false,
    ) : IAgentCompanion.Stub() {
        override fun updateAccessibilitySettings(settings: android.os.Bundle) = false
        override fun ompRequest(request: android.os.Bundle) = android.os.Bundle()
        override fun metadata() = android.os.Bundle()
        override fun privilegedRequest(request: android.os.Bundle) = android.os.Bundle()
        val actions = mutableListOf<Int>()
        override fun protocolVersion(): Int {
            if (versionUnavailable) throw UnsupportedOperationException("Version unavailable")
            return version
        }
        override fun status() = CompanionProtocol.READY
        override fun subscribe(callback: IAgentCallback) = Unit
        override fun unsubscribe(callback: IAgentCallback) = Unit
        override fun capabilityGaps(): Array<String> = emptyArray()
        override fun trackedDisplays() = intArrayOf(0, 7)
        override fun windowsOn(displayId: Int): MutableList<AccessibilityWindowInfo> = mutableListOf()
        override fun capture(displayId: Int, width: Int, height: Int): ParcelFileDescriptor {
            val file = File.createTempFile("remote-input", ".capture", RuntimeEnvironment.getApplication().cacheDir).apply { deleteOnExit() }
            val root = UiNode(editable = true, focused = focused, bounds = Box(0, 0, width, 100))
            val capture = ScreenCapture(displayId, width, height, listOf(UiWindow(1, WindowKind.APPLICATION, root = root)))
            file.outputStream().use { CaptureCodec.write(capture, it) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun screenshot(displayId: Int): ParcelFileDescriptor? = null
        override fun nodeAction(handle: Long, action: Int, displayId: Int, text: String?): Boolean {
            if (action == CompanionProtocol.CLIPBOARD_UNRESTORED) return false
            actions += action
            return action == CompanionProtocol.SET_SECRET_TEXT && acceptSecret
        }
        override fun imeEnter(displayId: Int) = false
        override fun globalAction(action: Int) = false
        override fun gesture(requestId: Long, displayId: Int, points: FloatArray, durationMs: Long) = Unit
    }
}
