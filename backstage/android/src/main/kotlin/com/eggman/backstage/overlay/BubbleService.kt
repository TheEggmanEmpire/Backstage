package com.eggman.backstage.overlay

import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.eggman.backstage.notify.Notifications
import com.eggman.backstage.notify.NotifyChannels
import com.eggman.backstage.exec.Box
import com.eggman.backstage.exec.OverlayController
import com.eggman.backstage.exec.OverlayHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Floating agent bubble (TYPE_APPLICATION_OVERLAY): a small circle showing the agent's status. A tap opens the chat
 * sheet ([com.eggman.backstage.ui.minichat.MiniChatActivity], the same chat as the desktop popup's chat button, with
 * the keyboard an overlay window cannot reliably get); a long-press speaks a task. Dragging shows a remove target at the
 * bottom centre: the bubble is pulled onto it when close, and dropping it there hides the bubble (the setting stays on;
 * the notification, Settings or the Bubble widget show it again). Released elsewhere it slides to the nearer side.
 */
class BubbleService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private lateinit var wm: WindowManager
    private var view: ComposeView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        BASE_FLAGS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Backstage bubble"
        // x/y are screen coordinates (the same as the remove target's); BubbleGeometry keeps clear of the bars.
        fitInsetsTypes = 0
    }
    /** The remove target's window: added before the bubble (so the bubble draws above it), shown only while dragging. */
    private var targetView: ComposeView? = null
    private val targetParams = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Backstage bubble remove target"
        fitInsetsTypes = 0
    }
    /** The target is armed (the bubble sits on it); drives its look. */
    private val targetArmed = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val drag by lazy { BubbleDrag(android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()) }
    private var dragArea: BubbleGeometry.Area? = null
    private var dragTarget: BubbleGeometry.Target? = null
    private var onTargetNow = false
    private var longPressed = false
    private var snapAnimator: android.animation.ValueAnimator? = null
    private val longPress = Runnable {
        if (!drag.dragging) {
            longPressed = true
            view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            controller.quickVoice()
        }
    }
    /** Hidden for a capture / gesture of the agent ([OverlayHooks]) and while a chat sheet shows. */
    private var hiddenForAgent = false
    private var hiddenForChat = false
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("backstage_bubble", MODE_PRIVATE) }
    val controller = BubbleController(this)

    override fun onCreate() {
        savedState.performAttach()
        savedState.performRestore(null)
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Always enter the foreground first: callers may use startForegroundService even to hide.
        val ok = enterForeground()
        if (intent?.action == ACTION_HIDE || intent?.action == ACTION_STOP) {
            com.eggman.backstage.ui.settings.SettingsStores.get(this).bubbleEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ok || !Overlay.canDraw(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOW) {
            // Shown again on purpose (notification, widget): forget an earlier drag onto the remove target.
            Overlay.setDismissed(this, false)
        }
        if (view == null) attach()
        handleCommand(intent)
        return START_STICKY
    }

    private fun foregroundNotification() = Notifications.foreground(
        this, NotifyChannels.BUBBLE, "Backstage bubble", "Tap the bubble to chat with the agent; drag it to the X to hide it",
        listOf("Hide bubble" to android.app.PendingIntent.getService(
            this, 2, Intent(this, BubbleService::class.java).setAction(ACTION_HIDE),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )),
    )

    /**
     * startForeground (special use; untyped on Android 13). Returns false when it failed. the bubble
     * records nothing itself (voice runs in the chat sheet, a visible activity), so it does not take the microphone type.
   */
    private fun enterForeground(): Boolean {
        val notification = runCatching { foregroundNotification() }.getOrNull() ?: return false
        val type = fgsType(android.os.Build.VERSION.SDK_INT)
        return runCatching {
            if (type == null) startForeground(Notifications.ID_BUBBLE, notification)
            else startForeground(Notifications.ID_BUBBLE, notification, type)
        }.isSuccess
    }

    /** True while shown only for one interaction (assist/wake) although the bubble setting is off. */
    var transient = false
        private set

    private fun handleCommand(intent: Intent?) {
        val settings = com.eggman.backstage.ui.settings.SettingsStores.get(this)
        // Off in Settings, or dragged onto the remove target: shown only for this one interaction.
        val wanted = BubbleModel.wanted(settings.bubbleEnabled, Overlay.dismissed(this))
        when (intent?.action) {
            ACTION_SHOW -> {
                settings.bubbleEnabled = true
                transient = false
            }
            ACTION_SHOW_PROGRESS -> {
                // Shown only to follow one task (automation); removed again when it ends.
                transient = transient || !wanted
                if (transient) controller.hideAfterTaskId = intent.getStringExtra(EXTRA_TASK_ID)
            }
            ACTION_START_TASK -> {
                val task = intent.getStringExtra(EXTRA_TASK)?.trim().orEmpty()
                if (task.isEmpty()) return
                val source = runCatching { com.eggman.backstage.agent.api.Source.valueOf(intent.getStringExtra(EXTRA_SOURCE)!!) }
                    .getOrDefault(com.eggman.backstage.agent.api.Source.OVERLAY)
                val target = runCatching { com.eggman.backstage.agent.api.Target.valueOf(intent.getStringExtra(EXTRA_TARGET)!!) }
                    .getOrDefault(settings.defaultTarget)
                transient = transient || !wanted
                controller.startTask(task, source, target, autoHide = intent.getBooleanExtra(EXTRA_AUTO_HIDE, false))
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun area(): BubbleGeometry.Area {
        val m = wm.currentWindowMetrics
        val insets = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return BubbleGeometry.Area(m.bounds.width(), m.bounds.height(), insets.top, insets.bottom, insets.left, insets.right)
    }

    /** The bottom gesture-navigation strip (0 with three-button navigation): the remove target stays above it. */
    private fun gestureBottom(): Int = runCatching {
        val i = wm.currentWindowMetrics.windowInsets
        maxOf(i.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom, i.getInsets(WindowInsets.Type.systemGestures()).bottom)
    }.getOrDefault(0)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun attach() {
        val a = area()
        val size = bubbleSizePx()
        val saved = BubbleGeometry.Point(prefs.getInt("x", a.maxX - size), prefs.getInt("y", a.height / 3))
        val p = BubbleGeometry.clamp(saved, size, size, a)
        params.x = p.x
        params.y = p.y
        params.flags = BASE_FLAGS
        // The remove target first: windows of one type stack in the order they are added.
        val tv = composeView { DismissTarget(targetArmed) }.apply { visibility = View.GONE }
        targetParams.width = dp(TARGET_WINDOW_DP)
        targetParams.height = dp(TARGET_WINDOW_DP)
        runCatching { wm.addView(tv, targetParams) }.onSuccess { targetView = tv }
        val v = composeView { BubbleContent(controller, ::onTouch) }
        wm.addView(v, params)
        view = v
        running = true
        controller.start()
        com.eggman.backstage.entry.Widgets.updateBubble(this)
        OverlayHooks.controller = overlayController
        // A chat sheet covers the lower half of the screen: the bubble steps aside meanwhile.
        lifecycleScope.launch {
            com.eggman.backstage.ui.minichat.MiniChatActivity.showing.collect { showing ->
                hiddenForChat = showing
                applyVisibility()
            }
        }
    }

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@BubbleService)
        setViewTreeSavedStateRegistryOwner(this@BubbleService)
        setViewTreeViewModelStoreOwner(this@BubbleService)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent(content)
    }

    // ---- touch: tap, long-press, drag, drop on the remove target ----------------------------------

    /** The bubble's touches in screen coordinates (main thread). */
    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                longPressed = false
                onTargetNow = false
                drag.down(e.rawX, e.rawY, BubbleGeometry.Point(params.x, params.y))
                main.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressed) return true
                val wanted = drag.move(e.rawX, e.rawY) ?: return true
                main.removeCallbacks(longPress)
                dragTo(wanted)
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                val wasDrag = drag.up()
                val drop = onTargetNow
                endDrag()
                when {
                    longPressed -> Unit
                    !wasDrag -> controller.openChat()
                    drop -> dismissByDrag()
                    else -> dragEnded()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                val wasDrag = drag.up()
                endDrag()
                if (wasDrag) dragEnded()
            }
        }
        return true
    }

    /** Where the bubble window is (tests). */
    internal fun origin(): BubbleGeometry.Point = BubbleGeometry.Point(params.x, params.y)

    /** Where the remove target shows on this screen. */
    internal fun removeTarget(): BubbleGeometry.Target =
        BubbleGeometry.dismissTarget(area(), gestureBottom(), dp(TARGET_RADIUS_DP), dp(TARGET_MARGIN_DP))

    /** The finger asks for origin [wanted]: follow it, or sit on the remove target inside its pull. */
    private fun dragTo(wanted: BubbleGeometry.Point) {
        val v = view ?: return
        val size = bubbleSizePx()
        val a = dragArea ?: area().also { dragArea = it }
        val t = dragTarget ?: removeTarget().also { dragTarget = it; showTarget(it) }
        val placed = BubbleGeometry.dragPlacement(wanted, size, a, t, dp(TARGET_PULL_DP))
        if (placed.onTarget != onTargetNow) {
            onTargetNow = placed.onTarget
            targetArmed.value = onTargetNow
            controller.overTarget.value = onTargetNow
            // A tick when the bubble snaps onto the target (and when it is pulled off again).
            v.performHapticFeedback(if (onTargetNow) android.view.HapticFeedbackConstants.VIRTUAL_KEY else android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        params.x = placed.point.x
        params.y = placed.point.y
        runCatching { wm.updateViewLayout(v, params) }
    }

    private fun showTarget(t: BubbleGeometry.Target) {
        val tv = targetView ?: return
        targetParams.x = t.cx - targetParams.width / 2
        targetParams.y = t.cy - targetParams.height / 2
        tv.visibility = View.VISIBLE
        runCatching { wm.updateViewLayout(tv, targetParams) }
    }

    private fun endDrag() {
        dragArea = null
        dragTarget = null
        targetArmed.value = false
        controller.overTarget.value = false
        targetView?.let { tv ->
            tv.visibility = View.GONE
            runCatching { wm.updateViewLayout(tv, targetParams) }
        }
        onTargetNow = false
    }

    /**
     * Dropped on the remove target: the bubble goes away until it is shown again. The setting stays on; a notification
     * offers to show it again (as do Settings and the Bubble widget).
   */
    fun dismissByDrag() {
        view?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        Overlay.setDismissed(this, true)
        runCatching { android.widget.Toast.makeText(this, BubbleModel.HIDDEN_TOAST, android.widget.Toast.LENGTH_SHORT).show() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        Overlay.notifyBubbleHidden(this)
        stopSelf()
    }

    /**
     * Lets the agent core get the bubble out of the way: hidden + touch-through while the phone screen is
     * captured and while PHONE gestures land on it ([OverlayHooks]); moved by the move_floating_widget tool.
   */
    private val overlayController = object : OverlayController {
        override suspend fun hide() {
            withContext(Dispatchers.Main) { setHidden(true) }
            // Let the compositor drop the hidden window before the screenshot / gesture.
            delay(HIDE_SETTLE_MS)
        }

        override fun restore() {
            main.post { setHidden(false) }
        }

        override fun bounds(): Box? {
            val v = view ?: return null
            if (!v.isAttachedToWindow || v.visibility != View.VISIBLE) return null
            val w = if (v.width > 0) v.width else bubbleSizePx()
            val h = if (v.height > 0) v.height else bubbleSizePx()
            return Box(params.x, params.y, params.x + w, params.y + h)
        }

        override fun moveTo(corner: String): Boolean {
            val c = corner.trim().uppercase()
            if (c !in setOf("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT")) return false
            main.post { moveToCorner(c) }
            return true
        }
    }

    private fun moveToCorner(corner: String) {
        val v = view ?: return
        val size = bubbleSizePx()
        val a = area()
        val margin = (4 * resources.displayMetrics.density).toInt()
        val x = if (corner.endsWith("LEFT")) a.minX + margin else a.maxX - size - margin
        val y = if (corner.startsWith("TOP")) a.minY + margin else a.maxY - size - margin
        val p = BubbleGeometry.clamp(BubbleGeometry.Point(x, y), size, size, a)
        params.x = p.x
        params.y = p.y
        runCatching { wm.updateViewLayout(v, params) }
        prefs.edit().putInt("x", p.x).putInt("y", p.y).apply()
    }

    fun bubbleSizePx(): Int = (BUBBLE_DP * resources.displayMetrics.density).toInt()

    /** Released away from the remove target: slide to the nearer side (a tick when it lands) and remember the place. */
    fun dragEnded(animate: Boolean = true) {
        val v = view ?: return
        val size = bubbleSizePx()
        val p = BubbleGeometry.snapToEdge(BubbleGeometry.Point(params.x, params.y), size, area(), dp(EDGE_MARGIN_DP))
        prefs.edit().putInt("x", p.x).putInt("y", p.y).apply()
        snapAnimator?.cancel()
        if (!animate || (p.x == params.x && p.y == params.y)) {
            params.x = p.x
            params.y = p.y
            runCatching { wm.updateViewLayout(v, params) }
            return
        }
        val fromX = params.x
        val fromY = params.y
        snapAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                params.x = fromX + ((p.x - fromX) * f).toInt()
                params.y = fromY + ((p.y - fromY) * f).toInt()
                if (v.isAttachedToWindow) runCatching { wm.updateViewLayout(v, params) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled || !v.isAttachedToWindow) return
                    params.x = p.x
                    params.y = p.y
                    runCatching { wm.updateViewLayout(v, params) }
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
            })
            start()
        }
    }

    /** Main thread. While hidden the window is also touch-through, so injected taps reach the app below. */
    private fun setHidden(hidden: Boolean) {
        hiddenForAgent = hidden
        applyVisibility()
    }

    private fun applyVisibility() {
        val hidden = hiddenForAgent || hiddenForChat
        view?.let { v ->
            if (hidden && drag.dragging) {
                drag.up()
                endDrag()
            }
            v.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            params.flags = if (hidden) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm.updateViewLayout(v, params) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dragEnded(animate = false)
    }

    override fun onDestroy() {
        if (OverlayHooks.controller === overlayController) OverlayHooks.controller = null
        main.removeCallbacks(longPress)
        snapAnimator?.cancel()
        controller.dispose()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        targetView?.let { runCatching { wm.removeView(it) } }
        targetView = null
        running = false
        runCatching { com.eggman.backstage.entry.Widgets.updateBubble(this) }
        viewModelStore.clear()
        super.onDestroy()
    }

    companion object {
        const val ACTION_HIDE = "com.eggman.backstage.overlay.HIDE"
        /** Internal command protocol (the service is not exported). */
        const val ACTION_SHOW = "com.eggman.backstage.overlay.SHOW"
        const val ACTION_STOP = "com.eggman.backstage.overlay.STOP"
        const val ACTION_START_TASK = "com.eggman.backstage.overlay.START_TASK"
        const val ACTION_SHOW_PROGRESS = "com.eggman.backstage.overlay.SHOW_PROGRESS"

        /** The foreground-service type: special use on Android 14+, null = the untyped call (Android 13). */
        @android.annotation.SuppressLint("InlinedApi") // SPECIAL_USE is only used when sdk >= 34
        fun fgsType(sdk: Int): Int? = if (sdk >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else null
        const val EXTRA_TASK_ID = "task_id"

        fun showProgressIntent(context: android.content.Context, taskId: String): Intent =
            Intent(context, BubbleService::class.java).setAction(ACTION_SHOW_PROGRESS).putExtra(EXTRA_TASK_ID, taskId)
        const val EXTRA_TASK = "task"
        const val EXTRA_SOURCE = "bs_origin"
        const val EXTRA_AUTO_HIDE = "bs_close_when_done"
        const val EXTRA_TARGET = "target"

        /** Whether the bubble window is attached (drives the toggle widget). */
        @Volatile
        var running = false
            private set

        fun startTaskIntent(
            context: android.content.Context,
            task: String,
            source: com.eggman.backstage.agent.api.Source,
            autoHide: Boolean,
            target: com.eggman.backstage.agent.api.Target? = null,
        ): Intent = Intent(context, BubbleService::class.java).setAction(ACTION_START_TASK)
            .putExtra(EXTRA_TASK, task).putExtra(EXTRA_SOURCE, source.name).putExtra(EXTRA_AUTO_HIDE, autoHide)
            .putExtra(EXTRA_TARGET, target?.name)
        const val BUBBLE_DP = 56
        /** Remove target: 64 dp circle, its window leaves room to grow; the bubble is pulled on within 96 dp. */
        const val TARGET_RADIUS_DP = 32
        private const val TARGET_WINDOW_DP = 96
        private const val TARGET_PULL_DP = 96
        /** Above the navigation bar / gesture strip. */
        private const val TARGET_MARGIN_DP = 32
        private const val EDGE_MARGIN_DP = 4
        private const val SNAP_MS = 220L
        /** Lets the compositor drop the hidden bubble before the screenshot is taken. */
        private const val HIDE_SETTLE_MS = 48L
        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }
}
