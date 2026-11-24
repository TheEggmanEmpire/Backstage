package com.eggman.backstage.a11y

import android.content.Context

internal object DistributionAccessibility {
    val status: String? get() = null
    val hostAvailable: Boolean get() = AccessibilityBridge.hostComponent() != null
    val screenAvailable: Boolean get() = true
    val companionCapabilities: Int get() = 0
    val companionOmp: Boolean get() = false
    fun gaps(): List<String> = AccessibilityBridge.currentGaps()
    fun connect(context: Context) = Unit
}
