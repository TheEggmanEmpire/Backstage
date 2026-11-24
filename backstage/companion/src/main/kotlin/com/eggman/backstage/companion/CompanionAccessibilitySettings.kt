package com.eggman.backstage.companion

import android.os.Bundle

internal data class CompanionAccessibilitySettings(
    val autoEnable: Boolean,
    val pauseForDetector: Boolean,
    val recheckSeconds: Int,
    val detectorPackages: Set<String>,
) {
    companion object {
        private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*$")
        private const val MAX_PACKAGES = 64
        private const val MAX_PACKAGE_TEXT = 4_096

        fun from(bundle: Bundle): CompanionAccessibilitySettings? {
            if (!bundle.containsKey("autoEnable") || !bundle.containsKey("pauseForDetector") ||
                !bundle.containsKey("recheckSeconds") || !bundle.containsKey("detectorPackages")) return null
            val autoEnable = bundle.get("autoEnable") as? Boolean ?: return null
            val pause = bundle.get("pauseForDetector") as? Boolean ?: return null
            val seconds = bundle.get("recheckSeconds") as? Int ?: return null
            val rawPackages = bundle.get("detectorPackages") as? String ?: return null
            if (rawPackages.length > MAX_PACKAGE_TEXT || seconds !in 1..60) return null
            val packages = rawPackages.split(':', ',', '\n').map(String::trim).filter(String::isNotEmpty)
            if (packages.size > MAX_PACKAGES || packages.any { !packageName.matches(it) }) return null
            return CompanionAccessibilitySettings(autoEnable, pause, seconds, packages.toSet())
        }
    }
}
