package com.eggman.backstage.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.PlayerFit

/**
 * The chat's live desktop player: a SurfaceView laid out at the fitted picture rectangle ([PlayerFit.fit]) with
 * the desktop's own size as its buffer size (SurfaceHolder.setFixedSize), so the VirtualDisplay renders the full frame
 * and the view scales it down whole, letterboxed on black like Home's player. Touches on the picture are mapped with
 * the same rectangle ([PlayerFit.toDesktop]) and forwarded ([DesktopHostExtras.playerTouch]). The Surface is handed to
 * the host while it exists and taken back before it is destroyed.
 */
@SuppressLint("ViewConstructor")
internal class ChatPlayerView(context: Context, private val extras: DesktopHostExtras) : FrameLayout(context) {
    val surfaceView = SurfaceView(context)
    var desktopWidth = 0
        private set
    var desktopHeight = 0
        private set
    /** Where the picture sits in this view (view pixels); also the touch mapping. */
    var picture = PlayerFit.Rect(0, 0, 0, 0)
        private set
    private var touching = false

    init {
        setBackgroundColor(Color.BLACK)
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { runCatching { extras.setChatPlayerSurface(h.surface) } }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(h: SurfaceHolder) { runCatching { extras.setChatPlayerSurface(null) } }
        })
    }

    /** The desktop display's size in pixels: the Surface's buffer size and the picture's aspect ratio. */
    fun setDesktopSize(width: Int, height: Int) {
        if (width == desktopWidth && height == desktopHeight) return
        desktopWidth = width
        desktopHeight = height
        if (width > 0 && height > 0) surfaceView.holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val h = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
        picture = PlayerFit.fit(w, h, desktopWidth, desktopHeight)
        surfaceView.measure(
            MeasureSpec.makeMeasureSpec(picture.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(picture.height, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        picture = PlayerFit.fit(right - left, bottom - top, desktopWidth, desktopHeight)
        surfaceView.layout(picture.left, picture.top, picture.right, picture.bottom)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> PlayerFit.DOWN
            MotionEvent.ACTION_MOVE -> PlayerFit.MOVE
            MotionEvent.ACTION_UP -> PlayerFit.UP
            MotionEvent.ACTION_CANCEL -> PlayerFit.CANCEL
            else -> return touching
        }
        if (action == PlayerFit.DOWN) {
            // Only a touch that starts on the picture (not on the black bars) goes to the desktop.
            val point = PlayerFit.toDesktop(event.x, event.y, picture, desktopWidth, desktopHeight) ?: return false
            touching = true
            parent?.requestDisallowInterceptTouchEvent(true)
            runCatching { extras.playerTouch(action, point.first, point.second) }
            return true
        }
        if (!touching) return false
        if (action == PlayerFit.UP || action == PlayerFit.CANCEL) touching = false
        val point = PlayerFit.toDesktop(event.x, event.y, picture, desktopWidth, desktopHeight, clamp = true) ?: return true
        runCatching { extras.playerTouch(action, point.first, point.second) }
        return true
    }
}
