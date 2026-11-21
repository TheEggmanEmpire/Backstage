package moe.n4tsu.dextop

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.eggman.backstage.a11y.AccessibilityBridge

/** MirrorService supplies desktop and agent accessibility through [AccessibilityBridge]. */
internal object BackstageAccessibilityClient : MirrorService.Backstage.AccessibilityClient {
    fun register(context: Context) {
        MirrorService.Backstage.setAccessibilityClient(this)
    }

    override fun onConnected(service: AccessibilityService) = AccessibilityBridge.onConnected(service)

    override fun onEvent(service: AccessibilityService, event: AccessibilityEvent) = AccessibilityBridge.onEvent(service, event)

    override fun onDisconnected(service: AccessibilityService) = AccessibilityBridge.onDisconnected(service)
}
