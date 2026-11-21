package com.eggman.backstage.ui.desktop

/** Maps touches on the letterboxed (fit-centre) preview image back to desktop display pixels. */
object PreviewMapper {

    data class Fit(val scale: Float, val offsetX: Float, val offsetY: Float, val drawnW: Float, val drawnH: Float)

    fun fit(viewW: Float, viewH: Float, imgW: Int, imgH: Int): Fit? {
        if (viewW <= 0f || viewH <= 0f || imgW <= 0 || imgH <= 0) return null
        val scale = minOf(viewW / imgW, viewH / imgH)
        val w = imgW * scale
        val h = imgH * scale
        return Fit(scale, (viewW - w) / 2f, (viewH - h) / 2f, w, h)
    }

    /**
     * @return display coordinates, or null when the touch is on the letterbox bars.
     * [frameW]/[frameH] is the captured frame size, [displayW]/[displayH] the desktop display size
     * (frames may be downscaled).
   */
    fun toDisplay(
        x: Float, y: Float, viewW: Float, viewH: Float,
        frameW: Int, frameH: Int, displayW: Int, displayH: Int,
    ): Pair<Float, Float>? {
        val f = fit(viewW, viewH, frameW, frameH) ?: return null
        val ix = (x - f.offsetX) / f.scale
        val iy = (y - f.offsetY) / f.scale
        // 1 px tolerance absorbs float rounding at the image edges.
        if (ix < -1f || iy < -1f || ix > frameW + 1f || iy > frameH + 1f) return null
        val sx = displayW.toFloat() / frameW
        val sy = displayH.toFloat() / frameH
        return (ix * sx).coerceIn(0f, displayW - 1f) to (iy * sy).coerceIn(0f, displayH - 1f)
    }
}
