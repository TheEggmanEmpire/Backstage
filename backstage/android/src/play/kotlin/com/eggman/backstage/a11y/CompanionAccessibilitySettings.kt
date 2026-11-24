package com.eggman.backstage.a11y

import android.os.Bundle
import com.eggman.backstage.data.Settings

internal data class CompanionAccessibilitySettings(
    val autoEnable: Boolean,
    val pauseForDetector: Boolean,
    val recheckSeconds: Int,
    val detectorPackages: String,
) {
    fun toBundle() = Bundle().apply {
        putBoolean("autoEnable", autoEnable)
        putBoolean("pauseForDetector", pauseForDetector)
        putInt("recheckSeconds", recheckSeconds)
        putString("detectorPackages", detectorPackages)
    }

    companion object {
        fun from(settings: Settings) = CompanionAccessibilitySettings(
            settings.autoEnableAccessibility,
            settings.detectorPause,
            settings.detectorPauseDelayMs / 1000,
            settings.detectorPackages,
        )
    }
}
