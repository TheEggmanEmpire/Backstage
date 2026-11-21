package com.eggman.backstage.host

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The display a Dextop session is created with, computed the way Dextop's own home screen does before it calls the
 * native `start` (upstream `lib/app_shell.dart` NativeBridge.start + `_workspaceDisplayForMagnification`, and
 * MainActivity's `currentDeviceDisplayProfile`). The glue uses it to start a background (MINI/HIDDEN) desktop
 * headlessly with the user's saved choices, without bringing Dextop's activity to the front. Pure, so it is unit-tested.
 */
object DesktopStartProfile {
    data class Spec(val width: Int, val height: Int, val density: Int)

    /** Dextop's "device" profile: the phone's real size (landscape order) and its automatic density. */
    fun device(realWidth: Int, realHeight: Int, densityScale: Float): Spec = Spec(
        width = max(realWidth, realHeight),
        height = min(realWidth, realHeight),
        density = (160 + (densityScale.takeIf { it.isFinite() && it > 0f } ?: 1f) * 24).toInt().coerceIn(160, 320),
    )

    /**
     * Saved orientation mode ("automatic" | "portrait" | "landscape"; older builds stored only `home_portrait`) to
     * portrait or not. "automatic" follows the phone, as Dextop's home screen does.
   */
    fun portrait(mode: String?, legacyPortrait: Boolean?, phonePortrait: Boolean): Boolean = when (mode) {
        "automatic" -> phonePortrait
        "portrait" -> true
        "landscape" -> false
        else -> legacyPortrait ?: false
    }

    /** The profile oriented, then scaled by the workspace magnification (100..200 %), exactly as Dextop does. */
    fun compute(profile: Spec, portrait: Boolean, magnificationPercent: Int, magnifyWindowsWithDisplay: Boolean): Spec {
        // Display contexts on folds/connected displays can temporarily report zero metrics, and small phones can
        // have a side below Dextop's 480 px minimum. Repair only invalid profiles; saved valid choices stay exact.
        val source = if (profile.width > 0 && profile.height > 0) profile else Spec(1280, 720, 184)
        val longSide = max(source.width, source.height).coerceIn(480, 7680)
        val shortSide = min(source.width, source.height).coerceIn(480, 7680)
        val width = if (portrait) shortSide else longSide
        val height = if (portrait) longSide else shortSide
        val density = source.density.coerceIn(80, 640)
        val percent = magnificationPercent.coerceIn(100, 200)
        if (percent == 100) return Spec(width, height, density)
        val requestedScale = 100.0 / percent
        val shortest = min(width, height)
        // Android's overlay display adapter rejects modes below 120 dpi: the scale is capped as a unit.
        val minimumDensityScale = if (magnifyWindowsWithDisplay) 0.0 else 120.0 / density
        val applied = maxOf(requestedScale, 480.0 / shortest, minimumDensityScale).coerceIn(0.01, 1.0)
        val evenWidth = (width * applied).roundToInt().coerceIn(480, 7680) and 1.inv()
        val evenHeight = (height * applied).roundToInt().coerceIn(480, 7680) and 1.inv()
        val appliedScale = evenWidth.toDouble() / width
        val scaledDensity = if (magnifyWindowsWithDisplay) density.coerceIn(120, 640)
        else (density * appliedScale).roundToInt().coerceIn(120, 640)
        return Spec(evenWidth, evenHeight, scaledDensity)
    }

    /** Dextop's own bounds for a start request (MainActivity.startDisplay refuses anything else). */
    fun valid(spec: Spec): Boolean = spec.width in 480..7680 && spec.height in 480..7680 && spec.density in 80..640
}
