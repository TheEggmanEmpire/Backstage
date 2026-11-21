package com.eggman.backstage.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopHosts

/**
 * Where the app's ONE accessibility service plugs the agent in.
 *
 * Backstage declares no accessibility service of its own: Dextop's MirrorService (the desktop engine) is the only one,
 * configured with everything the agent needs (backstage/overlay/res/xml/mirror_accessibility_service.xml). The Dextop
 * glue forwards that service's connection, every accessibility event and its disconnection here; the agent then reads
 * and acts through [AgentAccessibility.instance]. Without a host (the standalone library) nothing calls in, so the agent
 * has no accessibility at all and says so (readiness, executors) instead of falling back to a second service.
 *
 * Any thread; the platform calls the host on its main thread.
 */
object AccessibilityBridge {
    private const val TAG = "BackstageA11y"

    /** The separate agent service of Backstage ; removed in (its stale settings entry is dropped). */
    const val RETIRED_AGENT_SERVICE = "com.eggman.backstage.a11y.AgentAccessibilityService"

    /** The host's service connected (onServiceConnected): the agent may use it while connected. */
    fun onConnected(service: AccessibilityService) {
        AgentAccessibility.instance = AgentAccessibility(service)
        AgentAccessibility._connected.value = true
        val gaps = missingCapabilities(runCatching { service.serviceInfo }.getOrNull())
        if (gaps.isNotEmpty()) Log.w(TAG, "the accessibility service lacks what the agent needs: ${gaps.joinToString()}")
        runCatching { AccessibilityAutoEnable.onServiceConnected(service.applicationContext ?: service) }
    }

    /** Every event the host's service receives (onAccessibilityEvent), before the host's own handling. */
    fun onEvent(service: AccessibilityService, event: AccessibilityEvent?) {
        if (event == null) return
        // A detector app came to the front: it must not see the service (DetectorPause).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val ctx = service.applicationContext ?: service
            runCatching { DetectorPause.onForeground(ctx, event.packageName?.toString()) }
        }
        val agent = AgentAccessibility.instance?.takeIf { it.service === service } ?: return
        runCatching { agent.onEvent(event) }
    }

    /** The host's service is unbound or destroyed: the agent loses its eyes and hands until it connects again. */
    fun onDisconnected(service: AccessibilityService) {
        if (AgentAccessibility.instance?.service !== service) return
        AgentAccessibility.instance = null
        AgentAccessibility._connected.value = false
        runCatching { AccessibilityAutoEnable.onServiceGone(service.applicationContext ?: service) }
    }

    /** Flattened component of the host's accessibility service, or null when this build has none. */
    fun hostComponent(): String? =
        runCatching { (DesktopHosts.current as? DesktopHostExtras)?.desktopAccessibilityComponent() }.getOrNull()

    /** Gaps for [ReadinessInputs][com.eggman.backstage.agent.ReadinessInputs] (empty while not connected). */
    fun currentGaps(): List<String> = AgentAccessibility.instance?.let { missingCapabilities(runCatching { it.service.serviceInfo }.getOrNull()) }.orEmpty()

    /**
     * What the agent needs from the service's configuration and [info] does not grant: reading windows on every display
     * (window content, interactive windows), view ids, gestures, screenshots and the accessibility-tool declaration
     * (without it Android 14+ hides views marked accessibilityDataSensitive). Null info (not connected) = no gaps.
   */
    fun missingCapabilities(info: AccessibilityServiceInfo?): List<String> {
        if (info == null) return emptyList()
        val caps = info.capabilities
        val flags = info.flags
        return buildList {
            if (caps and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT == 0) add("window content")
            if (caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0) add("gestures")
            if (caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0) add("screenshots")
            if (flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) add("interactive windows")
            if (flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS == 0) add("view ids")
            if (!info.isAccessibilityTool) add("accessibility tool")
        }
    }
}
