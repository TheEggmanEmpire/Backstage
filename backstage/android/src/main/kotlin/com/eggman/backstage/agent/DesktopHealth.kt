package com.eggman.backstage.agent

import android.graphics.Bitmap
import com.eggman.backstage.host.DesktopHost

/**
 * Detects a desktop that runs but shows nothing: its display reports active while every frame is black, so no
 * window can be seen or operated. A stop and start of the desktop brings the picture back.
 */
object DesktopHealth {
    /** A pixel whose brightest channel is at or below this counts as black. */
    private const val DARK_CHANNEL = 12

    /** Share of sampled pixels that must be black for the frame to count as blank. */
    private const val BLANK_SHARE = 0.995

    /** Pixels sampled along each axis; enough to see a wallpaper, a window or a taskbar. */
    private const val GRID = 48

    /** Whether [pixels] (ARGB, row-major, [width]×[height]) are a blank black frame. Pure. */
    fun isBlank(pixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return false
        var dark = 0
        var total = 0
        for (gy in 0 until GRID) {
            val y = ((gy + 0.5) * height / GRID).toInt().coerceIn(0, height - 1)
            for (gx in 0 until GRID) {
                val x = ((gx + 0.5) * width / GRID).toInt().coerceIn(0, width - 1)
                val p = pixels[y * width + x]
                val brightest = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                if (brightest <= DARK_CHANNEL) dark++
                total++
            }
        }
        return dark >= total * BLANK_SHARE
    }

    /** Whether [frame] is a blank black frame. Hardware bitmaps are copied once to read their pixels. */
    fun isBlank(frame: Bitmap): Boolean {
        val readable = if (frame.config == Bitmap.Config.HARDWARE) frame.copy(Bitmap.Config.ARGB_8888, false) ?: return false else frame
        return try {
            val pixels = IntArray(readable.width * readable.height)
            readable.getPixels(pixels, 0, readable.width, 0, 0, readable.width, readable.height)
            isBlank(pixels, readable.width, readable.height)
        } finally {
            if (readable !== frame) readable.recycle()
        }
    }

    /**
     * Whether the running desktop on [host] shows only black. [capture] grabs one frame of the desktop display;
     * false when the desktop is not running or no frame could be taken (nothing to judge).
     */
    suspend fun blank(host: DesktopHost, capture: suspend () -> Bitmap?): Boolean {
        if (!runCatching { host.sessionActive() }.getOrDefault(false)) return false
        val frame = runCatching { capture() }.getOrNull() ?: return false
        return try { isBlank(frame) } catch (_: RuntimeException) { false } finally { if (!frame.isRecycled) frame.recycle() }
    }
}
