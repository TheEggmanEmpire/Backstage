package com.eggman.backstage.robolectric

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.eggman.backstage.a11y.AccessibilityBridge

/**
 * Stands in for Dextop's MirrorService, the app's one accessibility service: like the glue's
 * BackstageAccessibilityClient it hands its connection, events and disconnection to [AccessibilityBridge].
 */
class HostAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() = AccessibilityBridge.onConnected(this)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = AccessibilityBridge.onEvent(this, event)
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        AccessibilityBridge.onDisconnected(this)
        super.onDestroy()
    }
}
