package com.eggman.backstage.cursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.eggman.backstage.a11y.AgentAccessibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The agent cursor as an accessibility overlay window on the display the agent acts on (the desktop's virtual display,
 * so it shows in the popup, the in-app player and full screen; or the phone's display 0). The window never takes
 * touches or focus, is not announced by TalkBack, and is hidden while the agent captures that display. Screen reading
 * never sees it: listings drop accessibility overlays and Backstage's own windows.
 */
class CursorOverlay(private val app: Context) : CursorSink {
    private val main = Handler(Looper.getMainLooper())
    private val windows = HashMap<Int, Pair<WindowManager, CursorView>>()

    override fun animatorScale(): Float =
        runCatching { Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

    override fun render(state: CursorState) {
        main.post { apply(state) }
    }

    private fun apply(state: CursorState) {
        // Only the display the agent acts on shows a cursor.
        windows.keys.filter { it != state.displayId || !state.visible }.forEach(::remove)
        if (!state.visible) return
        val view = windows[state.displayId]?.second ?: add(state.displayId) ?: return
        view.show(state, CursorTiming.glideMs(animatorScale()), CursorTiming.animate(animatorScale()))
    }

    private fun add(displayId: Int): CursorView? = runCatching {
        val service = AgentAccessibility.instance?.service ?: return null
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        // The accessibility service's display context carries its window token: overlays on any display it can see.
        val wm = service.createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return null
        val size = Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }
        val view = CursorView(service, size.x, size.y)
        wm.addView(view, layoutParams())
        windows[displayId] = wm to view
        view
    }.getOrNull()

    private fun remove(displayId: Int) {
        val (wm, view) = windows.remove(displayId) ?: return
        runCatching { wm.removeViewImmediate(view) }
    }

    override suspend fun hideForCapture(displayId: Int) {
        val done = CompletableDeferred<Unit>()
        main.post {
            val view = windows[displayId]?.second
            if (view == null) done.complete(Unit) else {
                view.visibility = View.INVISIBLE
                // Two frames: the invisible state has reached the display's surface before the capture.
                view.postOnAnimation { view.postOnAnimation { done.complete(Unit) } }
            }
        }
        withTimeoutOrNull(250) { done.await() }
        delay(16)
    }

    override fun restoreAfterCapture(displayId: Int) {
        main.post { windows[displayId]?.second?.visibility = View.VISIBLE }
    }

    companion object {
        /** Full display, never touchable or focusable, drawn over everything, translucent. */
        fun layoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "Backstage cursor"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        const val VIOLET = 0xFF5B3CC4.toInt()
    }
}

/** Draws the pointer and the effect of the current action; time-based, redrawn only while something moves. */
internal class CursorView(context: Context, private val displayWidth: Int, private val displayHeight: Int) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CursorOverlay.VIOLET }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * density; strokeJoin = Paint.Join.ROUND }
    private val effect = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CursorOverlay.VIOLET; style = Paint.Style.STROKE; strokeWidth = 3f * density; strokeCap = Paint.Cap.ROUND }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 30, 24, 52) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * density }
    private val ease = DecelerateInterpolator()

    private var state = CursorState()
    private var from: CursorPoint? = null
    private var shownAt = 0L
    private var glideMs = 0L
    private var animated = true

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
    }

    fun show(next: CursorState, glide: Long, animate: Boolean) {
        from = current() ?: next.at
        state = next
        glideMs = glide
        animated = animate
        shownAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun map(p: CursorPoint) = CursorGeometry.toOverlay(p, displayWidth, displayHeight, width, height)

    /** Where the pointer is drawn: gliding from [from] to the target, then along a swipe. */
    private fun current(now: Long = SystemClock.uptimeMillis()): CursorPoint? {
        val target = state.at ?: return null
        val start = from ?: target
        val t = now - shownAt
        if (!animated || glideMs <= 0 || t >= glideMs) {
            if (state.action == CursorAction.SWIPE && state.path.size == 2 && animated) {
                val f = ((t - glideMs).toFloat() / state.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                val (a, b) = state.path
                return CursorPoint(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            }
            return if (state.action == CursorAction.SWIPE && !animated) state.path.lastOrNull() ?: target else target
        }
        val f = ease.getInterpolation(t.toFloat() / glideMs)
        return CursorPoint(start.x + (target.x - start.x) * f, start.y + (target.y - start.y) * f)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val raw = current(now) ?: return
        val p = map(raw)
        val since = now - shownAt - glideMs
        var moving = animated && (now - shownAt) < glideMs
        // Idle animations (typing caret blink, thinking dots) need ~12 fps, not a frame every vsync.
        var idleAnim = false
        when (state.action) {
            CursorAction.TAP -> if (since >= 0) {
                val f = if (animated) (since / 350f).coerceIn(0f, 1f) else 1f
                effect.alpha = (255 * (1 - f)).toInt().coerceAtLeast(if (animated) 0 else 160)
                canvas.drawCircle(p.x, p.y, (10 + 22 * f) * density, effect)
                moving = moving || f < 1f
            }
            CursorAction.LONG_PRESS -> {
                val f = if (!animated) 1f else (since.coerceAtLeast(0) / state.durationMs.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                effect.alpha = 230
                val r = 18 * density
                canvas.drawArc(RectF(p.x - r, p.y - r, p.x + r, p.y + r), -90f, 360f * f, false, effect)
                moving = moving || f < 1f
            }
            CursorAction.SWIPE -> if (state.path.size == 2) {
                val a = map(state.path[0])
                effect.alpha = 140
                canvas.drawLine(a.x, a.y, p.x, p.y, effect)
                moving = moving || (animated && since < state.durationMs)
            }
            CursorAction.TYPE -> {
                val on = !animated || (now / 450) % 2 == 0L
                if (on) canvas.drawRect(p.x + 14 * density, p.y - 9 * density, p.x + 16 * density, p.y + 9 * density, fill)
                idleAnim = animated
            }
            CursorAction.SCROLL -> drawChevrons(canvas, p, state.direction ?: "down")
            CursorAction.THINKING -> {
                for (i in 0 until 3) {
                    val angle = Math.toRadians((if (animated) (now % 1200) * 0.3 else 0.0) + i * 120.0)
                    canvas.drawCircle(p.x + 22 * density + (6 * density * Math.cos(angle)).toFloat(), p.y - 8 * density + (6 * density * Math.sin(angle)).toFloat(), 2.5f * density, fill)
                }
                idleAnim = animated
            }
            else -> Unit
        }
        drawPointer(canvas, p)
        state.label?.let { drawLabel(canvas, p, it) }
        if (moving) postInvalidateOnAnimation() else if (idleAnim) postInvalidateDelayed(83L)
    }

    private fun drawPointer(canvas: Canvas, p: CursorPoint) {
        val s = density
        val path = Path().apply {
            moveTo(p.x, p.y)
            lineTo(p.x, p.y + 20 * s)
            lineTo(p.x + 5.5f * s, p.y + 15 * s)
            lineTo(p.x + 9.5f * s, p.y + 23 * s)
            lineTo(p.x + 12.5f * s, p.y + 21.5f * s)
            lineTo(p.x + 8.5f * s, p.y + 13.5f * s)
            lineTo(p.x + 15 * s, p.y + 13.5f * s)
            close()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, outline)
    }

    private fun drawChevrons(canvas: Canvas, p: CursorPoint, direction: String) {
        val (dx, dy) = when (direction) { "up" -> 0f to -1f; "left" -> -1f to 0f; "right" -> 1f to 0f; else -> 0f to 1f }
        val s = 7 * density
        effect.alpha = 220
        for (i in 1..2) {
            val cx = p.x + dx * (18 + 9 * i) * density
            val cy = p.y + dy * (18 + 9 * i) * density
            // A "v" pointing along (dx, dy).
            canvas.drawLine(cx - dy * s - dx * s, cy + dx * s - dy * s, cx, cy, effect)
            canvas.drawLine(cx + dy * s - dx * s, cy - dx * s - dy * s, cx, cy, effect)
        }
    }

    private fun drawLabel(canvas: Canvas, p: CursorPoint, label: String) {
        val pad = 6 * density
        val w = text.measureText(label) + 2 * pad
        val h = text.textSize + 2 * pad
        var left = p.x + 18 * density
        var top = p.y + 22 * density
        if (left + w > width) left = (p.x - w - 6 * density).coerceAtLeast(0f)
        if (top + h > height) top = (p.y - h - 6 * density).coerceAtLeast(0f)
        canvas.drawRoundRect(RectF(left, top, left + w, top + h), h / 2, h / 2, bubble)
        canvas.drawText(label, left + pad, top + pad + text.textSize * 0.85f, text)
    }
}
