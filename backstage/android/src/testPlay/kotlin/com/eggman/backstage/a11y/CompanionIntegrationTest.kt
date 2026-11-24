package com.eggman.backstage.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.a11y.companion.RemoteNodeHandle
import com.eggman.backstage.agent.ReadinessCheck
import com.eggman.backstage.agent.ReadinessInputs
import com.eggman.backstage.companion.IAgentCallback
import com.eggman.backstage.companion.IAgentCompanion
import com.eggman.backstage.companion.CompanionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CompanionIntegrationTest {
    @Test fun oldCompanionRequiresUpdateAndAdditiveVersionsStayCompatible() {
        assertFalse(CompanionProtocol.supports(0))
        assertFalse(CompanionProtocol.supports(1))
        assertTrue(CompanionProtocol.supports(2))
        assertTrue(CompanionProtocol.supports(3))
    }

    @Test fun accessibilitySettingsCarryOnlyTheCompanionPreferences() {
        val preferences = com.eggman.backstage.data.Settings(com.eggman.backstage.data.MemoryStore())
        assertFalse(preferences.autoEnableAccessibility)
        assertFalse(preferences.detectorPause)
        preferences.autoEnableAccessibility = true
        preferences.detectorPause = true
        preferences.detectorPauseDelayMs = 13_000
        preferences.detectorPackages = "example.detector"
        preferences.accessibilityConsent = true

        val bundle = CompanionAccessibilitySettings.from(preferences).toBundle()
        assertEquals(setOf("autoEnable", "pauseForDetector", "recheckSeconds", "detectorPackages"), bundle.keySet())
        assertTrue(bundle.getBoolean("autoEnable"))
        assertTrue(bundle.getBoolean("pauseForDetector"))
        assertEquals(13, bundle.getInt("recheckSeconds"))
        assertEquals("example.detector", bundle.getString("detectorPackages"))
        assertFalse(preferences.mayAutoEnableAccessibility)
    }

    @Test fun theRemoteCompatibilityContextHasNoLocalScreenConnectionButStillStartsNormalIntents() {
        val app = RuntimeEnvironment.getApplication()
        val context = RemoteAccessibilityContext(app)
        val agent = AgentAccessibility(context, RemoteAccessibilityBackend(Endpoint()) {})
        assertEquals(app.packageName, agent.service.packageName)
        assertNull(agent.service.rootInActiveWindow)
        val windows = agent.service.windowsOnAllDisplays
        assertTrue((0 until windows.size()).all { windows.valueAt(it).isEmpty() })
        agent.service.startActivity(Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertEquals(Intent.ACTION_VIEW, shadowOf(app).nextStartedActivity.action)
    }

    @Test fun anUnknownHandleNeverBecomesFocusedTypingAndNodeActionsUseTheRemoteId() {
        val endpoint = Endpoint()
        val backend = RemoteAccessibilityBackend(endpoint) {}
        assertFalse(backend.setText(Any(), 7, "do not type"))
        assertFalse(backend.setText(RemoteNodeHandle(0), 7, "do not type"))
        assertFalse(backend.setText(RemoteNodeHandle(-1), 7, "do not type"))
        assertFalse(backend.setText(null, 7, "x".repeat(32769)))
        assertEquals(0, endpoint.actions)
        assertTrue(backend.setText(null, 7, "focused"))
        assertEquals(0L, endpoint.lastHandle)
        assertTrue(backend.click(RemoteNodeHandle(52), false))
        assertEquals(52L, endpoint.lastHandle)
        backend.disconnect()
        assertFalse(backend.click(RemoteNodeHandle(52), false))
        assertEquals(2, endpoint.actions)
    }

    @Test fun secretTypingFlagSurvivesTheRemoteBoundary() {
        val endpoint = Endpoint()
        val backend = RemoteAccessibilityBackend(endpoint) {}
        assertTrue(TextInputSession.onThread(secret = true) { backend.setText(null, 7, "private") })
        assertEquals(com.eggman.backstage.a11y.companion.CompanionProtocol.SET_SECRET_TEXT, endpoint.lastAction)
    }

    @Test fun readinessShowsTheCompanionCauseEvenWhenAccessibilityIsDisconnectedAndPrivilegeIsAvailable() {
        val i = ReadinessInputs(null, false, true, true, false, true, true, true, 36,
            companionStatus = "Accessibility backend is disconnected")
        val problems = ReadinessCheck.compute(i).problems
        assertTrue(problems.contains("Accessibility backend is disconnected"))
        assertFalse(problems.any { it.contains("switch it on automatically") })
    }

    @Test fun remoteEventsFeedTheCorrectDisplayAndIgnoreTheMainAppsPhoneWindowsAndNotifications() {
        val app = RuntimeEnvironment.getApplication()
        val agent = AgentAccessibility(RemoteAccessibilityContext(app), RemoteAccessibilityBackend(Endpoint()) {})
        NotificationFeed.clear()
        try {
            agent.onRemoteEvent(CompanionEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, 7, "chat"))
            agent.onRemoteEvent(CompanionEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, 0, app.packageName))
            assertEquals(1L, agent.settleFor(7).eventCount)
            assertEquals(0L, agent.settleFor(0).eventCount)
            agent.onRemoteEvent(CompanionEvent(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED, 0, "chat", "Chat", "Hello", 123))
            agent.onRemoteEvent(CompanionEvent(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED, 0, app.packageName, "Backstage", "Ignore", 124))
            assertEquals(listOf("Hello"), NotificationFeed.recent().map { it.text })
            // The main app also draws the desktop: its events there must still reset that display's settle window.
            agent.onRemoteEvent(CompanionEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, 7, app.packageName))
            assertEquals(2L, agent.settleFor(7).eventCount)
        } finally { NotificationFeed.clear() }
    }

    private class Endpoint : IAgentCompanion.Stub() {
        override fun updateAccessibilitySettings(settings: android.os.Bundle) = false
        override fun ompRequest(request: android.os.Bundle) = android.os.Bundle()
        override fun metadata() = android.os.Bundle()
        override fun privilegedRequest(request: android.os.Bundle) = android.os.Bundle()
        var actions = 0
        var lastHandle = -1L
        override fun protocolVersion() = CompanionProtocol.VERSION
        override fun status() = CompanionProtocol.READY
        override fun subscribe(callback: IAgentCallback) = Unit
        override fun unsubscribe(callback: IAgentCallback) = Unit
        override fun capabilityGaps(): Array<String> = emptyArray()
        override fun trackedDisplays() = intArrayOf(0, 7)
        override fun windowsOn(displayId: Int): MutableList<AccessibilityWindowInfo> = mutableListOf()
        override fun capture(displayId: Int, width: Int, height: Int): ParcelFileDescriptor? = null
        override fun screenshot(displayId: Int): ParcelFileDescriptor? = null
        var lastAction = 0
        override fun nodeAction(handle: Long, action: Int, displayId: Int, text: String?): Boolean {
            if (action == com.eggman.backstage.a11y.companion.CompanionProtocol.CLIPBOARD_UNRESTORED) return false
            actions++; lastHandle = handle; lastAction = action; return true
        }
        override fun imeEnter(displayId: Int) = true
        override fun globalAction(action: Int) = true
        override fun gesture(requestId: Long, displayId: Int, points: FloatArray, durationMs: Long) = Unit
    }
}
