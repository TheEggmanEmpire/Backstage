package com.eggman.backstage.overlay

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Pure placement math for the floating bubble (pixels, origin top-left). */
object BubbleGeometry {

    data class Area(val width: Int, val height: Int, val top: Int = 0, val bottom: Int = 0, val left: Int = 0, val right: Int = 0) {
        val minX get() = left
        val maxX get() = width - right
        val minY get() = top
        val maxY get() = height - bottom
    }

    data class Point(val x: Int, val y: Int)

    fun clamp(p: Point, w: Int, h: Int, area: Area): Point = Point(
        p.x.coerceIn(area.minX, maxOf(area.minX, area.maxX - w)),
        p.y.coerceIn(area.minY, maxOf(area.minY, area.maxY - h)),
    )

    /** After a drag: stick to the nearer left/right edge, keep the vertical position. */
    fun snapToEdge(p: Point, size: Int, area: Area, margin: Int): Point {
        val c = clamp(p, size, size, area)
        val centerX = c.x + size / 2
        val leftDist = centerX - area.minX
        val rightDist = area.maxX - centerX
        val x = if (leftDist <= rightDist) area.minX + margin else area.maxX - size - margin
        return clamp(Point(x, c.y), size, size, area)
    }

    /** Is the bubble docked on the right half of the screen? */
    fun onRightSide(p: Point, size: Int, area: Area): Boolean = p.x + size / 2 > (area.minX + area.maxX) / 2

    /** Proportional position restore after rotation / resolution change. */
    fun rescale(p: Point, from: Area, to: Area, size: Int): Point {
        if (from.width <= 0 || from.height <= 0) return clamp(p, size, size, to)
        val fx = p.x.toFloat() / from.width
        val fy = p.y.toFloat() / from.height
        return clamp(Point((fx * to.width).roundToInt(), (fy * to.height).roundToInt()), size, size, to)
    }

    /** Distinguish a tap from a drag (touch slop). */
    fun isDrag(dx: Float, dy: Float, slop: Float): Boolean = abs(dx) > slop || abs(dy) > slop

    // ---- drag to remove ----------------------------------------------------------------------

    /** The remove target: a circle of [radius] around ([cx], [cy]). */
    data class Target(val cx: Int, val cy: Int, val radius: Int)

    /**
     * The remove target sits at the bottom centre, above the navigation bar and above the gesture-navigation strip
     * ([gestureBottom]: the bottom system-gesture inset), [margin] clear of both.
   */
    fun dismissTarget(area: Area, gestureBottom: Int, radius: Int, margin: Int): Target {
        val bottomEdge = area.height - maxOf(area.bottom, gestureBottom) - margin
        return Target((area.minX + area.maxX) / 2, maxOf(area.minY + radius, bottomEdge - radius), radius)
    }

    /** Bubble origin whose centre lies on the target's centre. */
    fun onTarget(target: Target, size: Int): Point = Point(target.cx - size / 2, target.cy - size / 2)

    /** Is the bubble (origin [p]) close enough to be pulled onto the target? [pull] = magnet radius around its centre. */
    fun inMagnet(p: Point, size: Int, target: Target, pull: Int): Boolean =
        hypot((p.x + size / 2 - target.cx).toDouble(), (p.y + size / 2 - target.cy).toDouble()) <= pull

    /** Where the bubble is drawn while the finger holds it at [p], and whether it is on the remove target. */
    data class Placement(val point: Point, val onTarget: Boolean)

    /**
     * While dragging: inside the magnet the bubble jumps onto the target (and is removed if dropped there); outside it
     * follows the finger, kept on screen.
   */
    fun dragPlacement(p: Point, size: Int, area: Area, target: Target, pull: Int): Placement =
        if (inMagnet(p, size, target, pull)) Placement(onTarget(target, size), true)
        else Placement(clamp(p, size, size, area), false)
}

/**
 * Finger tracking for the bubble in screen coordinates (MotionEvent raw x/y), so moving the window under the finger
 * never feeds back into the drag. A touch that stays within [slop] is a tap.
 */
class BubbleDrag(private val slop: Float) {
    private var downX = 0f
    private var downY = 0f
    private var origin = BubbleGeometry.Point(0, 0)

    /** The finger left the touch slop since [down]. */
    var dragging = false
        private set

    fun down(rawX: Float, rawY: Float, windowOrigin: BubbleGeometry.Point) {
        downX = rawX
        downY = rawY
        origin = windowOrigin
        dragging = false
    }

    /** The window origin the finger asks for, or null while the touch is still a tap. */
    fun move(rawX: Float, rawY: Float): BubbleGeometry.Point? {
        val dx = rawX - downX
        val dy = rawY - downY
        if (!dragging && !BubbleGeometry.isDrag(dx, dy, slop)) return null
        dragging = true
        return BubbleGeometry.Point(origin.x + dx.roundToInt(), origin.y + dy.roundToInt())
    }

    /** The finger lifted: true when it is a drag (not a tap). */
    fun up(): Boolean = dragging.also { dragging = false }
}
