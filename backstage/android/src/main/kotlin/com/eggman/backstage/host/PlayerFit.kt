package com.eggman.backstage.host

import kotlin.math.roundToInt

/**
 * Where the desktop picture sits in a player box, and which desktop pixel a touch on it means. Pure,
 * unit-tested in PlayerFitTest.
 *
 * The whole desktop frame is fitted into the box keeping its aspect ratio and centred (letterbox / pillarbox), the way
 * Home's player and the popup show it. The chat's player lays its SurfaceView out at [fit] and gives the Surface the
 * desktop's own size as buffer size, so the VirtualDisplay renders the full frame 1:1 into the buffer and the view only
 * scales it. Touches go through [toDesktop] with the same rectangle.
 */
object PlayerFit {
    const val DOWN = 0
    const val MOVE = 1
    const val UP = 2
    const val CANCEL = 3

    data class Rect(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
        fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    }

    /** The largest rectangle with the desktop's aspect ratio inside [boxWidth]x[boxHeight], centred. */
    fun fit(boxWidth: Int, boxHeight: Int, desktopWidth: Int, desktopHeight: Int): Rect {
        if (boxWidth <= 0 || boxHeight <= 0) return Rect(0, 0, 0, 0)
        if (desktopWidth <= 0 || desktopHeight <= 0) return Rect(0, 0, boxWidth, boxHeight)
        // Compare boxW/boxH with deskW/deskH without rounding: boxW*deskH vs boxH*deskW.
        return if (boxWidth.toLong() * desktopHeight <= boxHeight.toLong() * desktopWidth) {
            // Box is narrower: full width, bars above and below.
            val h = (boxWidth.toDouble() * desktopHeight / desktopWidth).roundToInt().coerceIn(1, boxHeight)
            Rect(0, (boxHeight - h) / 2, boxWidth, h)
        } else {
            val w = (boxHeight.toDouble() * desktopWidth / desktopHeight).roundToInt().coerceIn(1, boxWidth)
            Rect((boxWidth - w) / 2, 0, w, boxHeight)
        }
    }

    /**
     * A point in the box ([x], [y] in box pixels) as desktop display pixels. Outside the picture: null, unless [clamp]
     * (a drag that started on the picture keeps going at its edge).
   */
    fun toDesktop(x: Float, y: Float, rect: Rect, desktopWidth: Int, desktopHeight: Int, clamp: Boolean = false): Pair<Float, Float>? {
        if (rect.width <= 0 || rect.height <= 0 || desktopWidth <= 0 || desktopHeight <= 0) return null
        if (!clamp && !rect.contains(x, y)) return null
        val dx = ((x - rect.left) * desktopWidth / rect.width).coerceIn(0f, desktopWidth - 1f)
        val dy = ((y - rect.top) * desktopHeight / rect.height).coerceIn(0f, desktopHeight - 1f)
        return dx to dy
    }
}
