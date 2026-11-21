package com.eggman.backstage.robolectric

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.app.Notification
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.eggman.backstage.a11y.AccessibilityAutoEnable
import com.eggman.backstage.a11y.AccessibilityBridge
import com.eggman.backstage.a11y.AccessibilityEnabler
import com.eggman.backstage.a11y.AgentAccessibility
import com.eggman.backstage.a11y.NotificationFeed
import com.eggman.backstage.agent.ReadinessCheck
import com.eggman.backstage.agent.ReadinessInputs
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.testutil.FakeDesktopHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The app's ONE accessibility service (Dextop's MirrorService in the APK; [HostAccessibilityService] here) serves the
 * agent through [AccessibilityBridge]: connection, events, global actions, launch context and disconnection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi")
class AccessibilityBridgeRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() = RoboSupport.resetProcess()

    @After fun tearDown() {
        AgentAccessibility.instance?.service?.let(AccessibilityBridge::onDisconnected)
        DesktopHosts.current = null
        NotificationFeed.clear()
        RoboSupport.resetProcess()
    }

    @Test fun theHostServiceBecomesTheAgentsServiceAndLeavesWithIt() {
        assertNull(AgentAccessibility.instance)
        val host = A11yTrees.connectService()
        val agent = AgentAccessibility.instance
        assertNotNull(agent)
        agent!!
        assertSame(host.get(), agent.service)
        assertTrue(AgentAccessibility.connected.value)
        assertTrue(AccessibilityAutoEnable.connected())
        host.destroy()
        assertNull(AgentAccessibility.instance)
        assertFalse(AgentAccessibility.connected.value)
    }

    @Test fun aStaleServiceNeitherFeedsNorDisconnectsTheCurrentOne() {
        val old = A11yTrees.connectService()
        val current = A11yTrees.connectService()
        assertSame(current.get(), AgentAccessibility.instance!!.service)
        val settle = AgentAccessibility.instance!!.settleFor(0)
        AccessibilityBridge.onEvent(old.get(), event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, displayId = 0))
        assertEquals(0L, settle.eventCount)
        old.destroy() // the old service's late teardown
        assertSame(current.get(), AgentAccessibility.instance!!.service)
        current.destroy()
    }

    @Test fun eventsFeedSettleDetectionPerDisplayAndTheNotificationFeed() {
        val host = A11yTrees.connectService()
        val agent = AgentAccessibility.instance!!
        host.get().onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, displayId = 7))
        host.get().onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, displayId = 7))
        assertEquals(2L, agent.settleFor(7).eventCount)
        assertEquals(0L, agent.settleFor(0).eventCount)

        val n = Notification.Builder(app, "c").setContentTitle("Anna").setContentText("Lunch tomorrow?").build()
        host.get().onAccessibilityEvent(event(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED, pkg = "com.example.chat").apply { parcelableData = n })
        // The app's own notifications are never recorded.
        host.get().onAccessibilityEvent(event(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED, pkg = app.packageName).apply { parcelableData = n })
        val recent = NotificationFeed.recent()
        assertEquals(1, recent.size)
        assertEquals("com.example.chat", recent.single().packageName)
        assertEquals("Anna: Lunch tomorrow?", recent.single().text)
        host.get().onAccessibilityEvent(null) // tolerated
        host.destroy()
    }

    @Test fun globalActionsAndBackgroundStartsGoThroughTheHostService() {
        val host = A11yTrees.connectService()
        val agent = AgentAccessibility.instance!!
        // The notification shade close (session notification "Popup") and the phone executor's keys.
        assertTrue(agent.global(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE))
        assertTrue(agent.global(AccessibilityService.GLOBAL_ACTION_BACK))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE, AccessibilityService.GLOBAL_ACTION_BACK),
            shadowOf(host.get()).globalActionsPerformed)
        // Alarm/device-control intents start from the service's context (background activity starts).
        agent.service.startActivity(Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertEquals(Intent.ACTION_VIEW, shadowOf(host.get()).nextStartedActivity?.action)
        host.destroy()
    }

    @Test fun capabilitiesTheAgentNeedsAreChecked() {
        assertEquals(emptyList<String>(), AccessibilityBridge.missingCapabilities(null))
        val full = AccessibilityServiceInfo().apply {
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        setCapabilities(full, AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT or
            AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES or AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT)
        setAccessibilityTool(full, true)
        assertEquals(emptyList<String>(), AccessibilityBridge.missingCapabilities(full))
        // Without the accessibility-tool declaration only that is missing.
        setAccessibilityTool(full, false)
        assertEquals(listOf("accessibility tool"), AccessibilityBridge.missingCapabilities(full))
        // Upstream Dextop's own configuration: no gestures, no screenshots, no view ids, not an accessibility tool.
        val upstream = AccessibilityServiceInfo().apply { flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        setCapabilities(upstream, AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)
        assertEquals(listOf("gestures", "screenshots", "view ids", "accessibility tool"), AccessibilityBridge.missingCapabilities(upstream))
    }

    @Test fun theOneComponentToEnableIsTheHostsAndWithoutAHostThereIsNone() = runBlocking {
        assertNull(AccessibilityBridge.hostComponent())
        assertNull(AccessibilityAutoEnable.component(app))
        val outcome = AccessibilityAutoEnable.ensure(app)
        assertTrue(outcome is AccessibilityEnabler.Outcome.Failed)
        assertEquals(AccessibilityEnabler.NO_HOST, (outcome as AccessibilityEnabler.Outcome.Failed).reason)
        DesktopHosts.current = object : com.eggman.backstage.host.DesktopHost by FakeDesktopHost(), DesktopHostExtras {
            override fun setMiniStatus(text: String?) = Unit
            override fun setMiniShape(shape: String): Boolean = true
            override fun miniShape(): String = "rounded"
            override fun usePrivilegeProvider(provider: String): Boolean = false
            override fun desktopAccessibilityComponent(): String = "${app.packageName}/moe.n4tsu.dextop.MirrorService"
        }
        assertEquals("${app.packageName}/moe.n4tsu.dextop.MirrorService", AccessibilityAutoEnable.component(app))
        assertEquals("${app.packageName}/${AccessibilityBridge.RETIRED_AGENT_SERVICE}", AccessibilityAutoEnable.retiredComponent(app))
    }

    @Test fun readinessExplainsABuildWithoutTheService() {
        val base = ReadinessInputs(providerProblem = null, accessibilityEnabled = false, privilegedAvailable = false, hostPresent = false,
            desktopActive = false, directDisplay = true, screenOn = true, unlocked = true, sdkInt = 34)
        assertTrue(ReadinessCheck.compute(base.copy(accessibilityHost = false)).problems.contains(AccessibilityEnabler.NO_HOST))
        val gaps = ReadinessCheck.compute(base.copy(accessibilityEnabled = true, accessibilityGaps = listOf("gestures"))).problems
        assertTrue(gaps.toString(), gaps.any { "gestures" in it })
    }

    private fun event(type: Int, displayId: Int = 0, pkg: String = "com.example.mail"): AccessibilityEvent =
        AccessibilityEvent(type).apply {
            packageName = pkg
            runCatching { AccessibilityEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(this, displayId) }
        }

    private fun setCapabilities(info: AccessibilityServiceInfo, caps: Int) {
        AccessibilityServiceInfo::class.java.getDeclaredMethod("setCapabilities", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(info, caps)
    }

    /** Hidden setter (@TestApi) behind android:isAccessibilityTool. */
    private fun setAccessibilityTool(info: AccessibilityServiceInfo, tool: Boolean) {
        AccessibilityServiceInfo::class.java.getDeclaredMethod("setAccessibilityTool", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(info, tool)
    }
}
