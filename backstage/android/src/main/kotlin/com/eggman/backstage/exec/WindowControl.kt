package com.eggman.backstage.exec

import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopTask
import com.eggman.backstage.host.DesktopTaskPolicy
import com.eggman.backstage.host.TaskBounds
import com.eggman.backstage.host.TaskOp
import com.eggman.backstage.host.WindowModes
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/** How launch_app opens an app's window. */
enum class LaunchWindow {
    /** Phone: fullscreen; desktop: a normal desktop window. */
    DEFAULT,
    /** Phone: fullscreen (never a floating window); desktop: maximized inside the desktop. */
    FULLSCREEN,
    /** A small floating window: phone pop-up inside the safe area; desktop: a centred window. */
    POPUP,
    /** Desktop: the window fills the desktop (the desktop itself is not changed); phone: fullscreen. */
    MAXIMIZED;

    companion object {
        fun parse(value: String?): LaunchWindow? = when (value?.trim()?.lowercase()?.replace('-', '_')) {
            null, "", "default", "normal" -> DEFAULT
            "fullscreen", "full_screen", "full" -> FULLSCREEN
            "popup", "pop_up", "window", "floating", "freeform" -> POPUP
            "maximized", "maximised", "maximize", "maximise" -> MAXIMIZED
            else -> null
        }
    }
}

/** Corner or centre of a phone pop-up window. */
enum class PopupPosition {
    CENTER, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

    companion object {
        fun parse(value: String?): PopupPosition? = when (value?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
            null, "", "center", "centre", "middle" -> CENTER
            "top_left" -> TOP_LEFT
            "top_right" -> TOP_RIGHT
            "bottom_left" -> BOTTOM_LEFT
            "bottom_right" -> BOTTOM_RIGHT
            else -> null
        }
    }
}

/**
 * Window rules for the phone's own screen. Phone apps open fullscreen; a pop-up window
 * only when the user asks for one, and then always inside the safe area (below the status bar, above the
 * navigation bar).
 */
object PhoneWindows {
    const val POPUP_WIDTH = 0.6
    const val POPUP_HEIGHT = 0.45

    /** A pop-up window of [POPUP_WIDTH] x [POPUP_HEIGHT] of the safe area at [position], [margin] px from its edges. */
    fun popupBounds(safe: TaskBounds, position: PopupPosition = PopupPosition.CENTER, margin: Int = 0): TaskBounds {
        val w = (safe.width * POPUP_WIDTH).toInt().coerceAtLeast(1)
        val h = (safe.height * POPUP_HEIGHT).toInt().coerceAtLeast(1)
        val m = margin.coerceIn(0, minOf((safe.width - w) / 2, (safe.height - h) / 2).coerceAtLeast(0))
        val left = when (position) {
            PopupPosition.CENTER -> safe.left + (safe.width - w) / 2
            PopupPosition.TOP_LEFT, PopupPosition.BOTTOM_LEFT -> safe.left + m
            PopupPosition.TOP_RIGHT, PopupPosition.BOTTOM_RIGHT -> safe.right - m - w
        }
        val top = when (position) {
            PopupPosition.CENTER -> safe.top + (safe.height - h) / 2
            PopupPosition.TOP_LEFT, PopupPosition.TOP_RIGHT -> safe.top + m
            PopupPosition.BOTTOM_LEFT, PopupPosition.BOTTOM_RIGHT -> safe.bottom - m - h
        }
        return TaskBounds(left, top, left + w, top + h)
    }

    /** [bounds] moved (and if needed shrunk) so it lies inside [area]. */
    fun clampInto(bounds: TaskBounds, area: TaskBounds): TaskBounds {
        val w = bounds.width.coerceIn(1, area.width.coerceAtLeast(1))
        val h = bounds.height.coerceIn(1, area.height.coerceAtLeast(1))
        val left = bounds.left.coerceIn(area.left, (area.right - w).coerceAtLeast(area.left))
        val top = bounds.top.coerceIn(area.top, (area.bottom - h).coerceAtLeast(area.top))
        return TaskBounds(left, top, left + w, top + h)
    }

    /**
     * A phone task the user (and the agent) cannot control normally: a freeform window that is not fully inside the
     * safe area, e.g. a desktop-sized window whose caption bar lies behind the status bar.
   */
    fun misplaced(task: DesktopTask, safe: TaskBounds?): Boolean {
        if (task.windowingMode != WindowModes.FREEFORM) return false
        val b = task.bounds ?: return true
        return safe == null || !b.inside(safe)
    }
}

/** Desktop window rules: what "maximized" means and where a restored window goes. */
object DesktopWindowRules {
    /** Fullscreen mode on the desktop display, or freeform bounds covering at least 90% of it. */
    fun maximized(task: DesktopTask, width: Int, height: Int): Boolean {
        if (task.windowingMode == WindowModes.FULLSCREEN) return true
        val b = task.bounds ?: return false
        return b.width.toLong() * b.height >= 0.9 * width.toLong() * height
    }

    /** A centred window of 70% x 70% of the desktop: the size a restored window gets when its old size is unknown. */
    fun defaultWindow(width: Int, height: Int, fraction: Double = 0.7): TaskBounds {
        val w = (width * fraction).toInt()
        val h = (height * fraction).toInt()
        val l = (width - w) / 2
        val t = (height - h) / 2
        return TaskBounds(l, t, l + w, t + h)
    }
}

/** Where an app's window is after a launch ([LaunchCheck.where]). */
sealed class LaunchPlace {
    data class Desktop(val task: DesktopTask) : LaunchPlace()
    data class Phone(val task: DesktopTask) : LaunchPlace()
    /** The host cannot list tasks (no privilege): nothing can be verified. */
    object Unknown : LaunchPlace()
    /** No window of the app on either screen (yet). */
    object NotFound : LaunchPlace()
}

/**
 * Where a launched app ended up. A window on the desktop display wins; else a visible task on the phone; pure, unit-tested.
 */
object LaunchCheck {
    fun where(packageName: String, desktopDisplayId: Int, desktop: List<DesktopTask>?, phone: List<DesktopTask>?): LaunchPlace {
        if (desktop == null && phone == null) return LaunchPlace.Unknown
        desktop?.firstOrNull { it.displayId == desktopDisplayId && it.packageName == packageName }?.let { return LaunchPlace.Desktop(it) }
        phone?.firstOrNull { it.displayId == PHONE_DISPLAY && it.packageName == packageName && it.visible }?.let { return LaunchPlace.Phone(it) }
        return if (desktop == null) LaunchPlace.Unknown else LaunchPlace.NotFound
    }

    const val PHONE_DISPLAY = 0
}

/** window_control's actions. */
enum class WindowAction {
    MAXIMIZE, RESTORE, MINIMIZE, CLOSE, FULLSCREEN, POPUP, MOVE_TO_DESKTOP, MOVE_TO_PHONE;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(value: String?): WindowAction? = when (value?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
            "maximize", "maximise", "maximized" -> MAXIMIZE
            "restore", "unmaximize", "normal" -> RESTORE
            "minimize", "minimise", "hide" -> MINIMIZE
            "close" -> CLOSE
            "fullscreen", "full_screen" -> FULLSCREEN
            "popup", "pop_up", "window", "floating" -> POPUP
            "move_to_desktop", "to_desktop" -> MOVE_TO_DESKTOP
            "move_to_phone", "to_phone" -> MOVE_TO_PHONE
            else -> null
        }
    }
}

/**
 * Controls app windows on both screens through the host's task control ([DesktopHostExtras.controlTask]): maximize,
 * restore, minimize, close, fullscreen / pop-up on the phone, and moving a window between the screens. Every change is
 * verified by reading the task list again. Acting on the phone (or moving a window there) needs [phoneAllowed];
 * moving a window from the phone to the desktop is the recovery for a launch that landed on the phone and is always
 * allowed.
 */
class WindowController(
    private val extras: DesktopHostExtras?,
    /** The running desktop (display id, width, height), or null without a session. */
    private val desktop: Triple<Int, Int, Int>?,
    /** The phone's safe area (below the status bar, above the navigation bar) in display-0 pixels. */
    private val phoneSafe: TaskBounds?,
    private val ownPackages: Set<String>,
    private val phoneAllowed: Boolean,
    private val desktopAllowed: Boolean = true,
    private val pollMs: Long = 200,
    private val verifyMs: Long = 1_600,
) {
    data class Target(val task: DesktopTask, val onPhone: Boolean)

    fun desktopTasks(): List<DesktopTask>? {
        val d = desktop ?: return null
        return extras?.let { runCatching { it.desktopTasks() }.getOrNull() }?.let { DesktopTaskPolicy.select(it, d.first, ownPackages) }
    }

    fun phoneTasks(): List<DesktopTask>? =
        extras?.let { runCatching { it.phoneTasks() }.getOrNull() }
            ?.filter { it.displayId == LaunchCheck.PHONE_DISPLAY && it.packageName.isNotBlank() && it.packageName !in ownPackages }
            ?.filter { it.activityType == DesktopTaskPolicy.ACTIVITY_TYPE_STANDARD || it.activityType == DesktopTaskPolicy.ACTIVITY_TYPE_UNDEFINED }
            ?.distinctBy { it.taskId }

    /** The window [action] applies to: [app]'s task (by name/package) or the focused one, on [screen] or where it fits. */
    fun find(action: WindowAction, screen: TargetKind?, app: String?, installed: List<AppEntry>): Target? {
        val onDesktop = desktopTasks().orEmpty()
        val onPhone = phoneTasks().orEmpty()
        val order: List<Boolean> = when {
            screen == TargetKind.PHONE -> listOf(true)
            screen == TargetKind.DESKTOP -> listOf(false)
            action == WindowAction.MOVE_TO_DESKTOP -> listOf(true)
            action == WindowAction.MOVE_TO_PHONE -> listOf(false)
            desktop != null -> listOf(false, true)
            else -> listOf(true)
        }
        for (phone in order) {
            val tasks = if (phone) onPhone else onDesktop
            val pick = if (app.isNullOrBlank()) {
                tasks.firstOrNull { it.focused } ?: tasks.firstOrNull { it.visible }
            } else {
                val packages = DesktopWindowCloser.packagesFor(app, tasks, installed)
                tasks.firstOrNull { it.packageName in packages }
            }
            if (pick != null) return Target(pick, phone)
        }
        return null
    }

    suspend fun control(action: WindowAction, screen: TargetKind?, app: String?, bounds: TaskBounds?, position: PopupPosition, installed: List<AppEntry>): ActionResult {
        val host = extras ?: return ActionResult.fail(UNAVAILABLE)
        if (desktopTasks() == null && phoneTasks() == null) return ActionResult.fail(UNAVAILABLE)
        if (screen == TargetKind.DESKTOP && desktop == null) return ActionResult.fail("The desktop is not running.")
        val target = find(action, screen, app, installed) ?: return ActionResult.fail(notFound(app, screen))
        val task = target.task
        val name = task.name
        val touchesPhone = (target.onPhone && action != WindowAction.MOVE_TO_DESKTOP) || action == WindowAction.MOVE_TO_PHONE
        if (touchesPhone && !phoneAllowed) return ActionResult.fail(PHONE_GATED)
        val touchesDesktop = (!target.onPhone && action != WindowAction.MOVE_TO_PHONE) || action == WindowAction.MOVE_TO_DESKTOP
        if (touchesDesktop && !desktopAllowed) return ActionResult.fail(DESKTOP_GATED)
        return if (target.onPhone) phone(host, action, task, name, bounds, position) else desktop(host, action, task, name, bounds)
    }

    private suspend fun desktop(host: DesktopHostExtras, action: WindowAction, task: DesktopTask, name: String, bounds: TaskBounds?): ActionResult {
        val (displayId, w, h) = desktop ?: return ActionResult.fail("The desktop is not running.")
        val area = TaskBounds(0, 0, w, h)
        return when (action) {
            WindowAction.MAXIMIZE, WindowAction.FULLSCREEN -> {
                if (DesktopWindowRules.maximized(task, w, h)) return ActionResult.ok("$name already fills the desktop. $DESKTOP_KEPT")
                task.bounds?.takeIf { task.windowingMode == WindowModes.FREEFORM && !it.isEmpty }?.let { remembered[task.taskId] = it }
                var done = runCatching { host.controlTask(task.taskId, displayId, TaskOp.FULLSCREEN) }.getOrDefault(false) &&
                    await(false, task.taskId) { DesktopWindowRules.maximized(it, w, h) } != null
                if (!done) {
                    // Some desktops refuse fullscreen tasks: a freeform window as large as the desktop instead.
                    done = runCatching { host.controlTask(task.taskId, displayId, TaskOp.FREEFORM, area) }.getOrDefault(false) &&
                        await(false, task.taskId) { DesktopWindowRules.maximized(it, w, h) } != null
                }
                if (done) ActionResult.ok("$name is maximized: its window fills the desktop. $DESKTOP_KEPT")
                else ActionResult.fail("Could not maximize $name on the desktop. Try desktop_shortcut toggle_maximize (where available) or the maximize button in its title bar; do not change desktop_presentation for this.")
            }
            WindowAction.RESTORE, WindowAction.POPUP -> {
                val wanted = PhoneWindows.clampInto(
                    bounds ?: (if (action == WindowAction.RESTORE) remembered[task.taskId] else null)
                        ?: DesktopWindowRules.defaultWindow(w, h, if (action == WindowAction.POPUP) 0.5 else 0.7),
                    area,
                )
                val ok = runCatching { host.controlTask(task.taskId, displayId, TaskOp.FREEFORM, wanted) }.getOrDefault(false) &&
                    await(false, task.taskId) { it.windowingMode == WindowModes.FREEFORM && it.bounds == wanted } != null
                if (ok) ActionResult.ok("$name is now a window at $wanted on the desktop. $DESKTOP_KEPT")
                else ActionResult.fail("Could not resize $name's window on the desktop.")
            }
            WindowAction.MINIMIZE -> {
                val ok = runCatching { host.controlTask(task.taskId, displayId, TaskOp.MINIMIZE) }.getOrDefault(false)
                if (ok) ActionResult.ok("$name is minimized on the desktop (launch_app brings it back).")
                else ActionResult.fail("Could not minimize $name on the desktop.")
            }
            WindowAction.CLOSE -> {
                val ok = runCatching { host.closeDesktopTask(task.taskId) }.getOrDefault(false)
                if (ok) ActionResult.ok("Closed $name's window on the desktop. Apps on the phone were not touched.")
                else ActionResult.fail("Could not close $name on the desktop (use the window's own close button).")
            }
            WindowAction.MOVE_TO_DESKTOP -> ActionResult.ok("$name is already on the desktop.")
            WindowAction.MOVE_TO_PHONE -> {
                val ok = runCatching { host.controlTask(task.taskId, displayId, TaskOp.MOVE_TO_PHONE) }.getOrDefault(false) &&
                    await(true, task.taskId) { it.windowingMode != WindowModes.FREEFORM } != null
                if (ok) ActionResult.ok("Moved $name from the desktop to the phone (fullscreen).")
                else ActionResult.fail("Could not move $name to the phone.")
            }
        }
    }

    private suspend fun phone(host: DesktopHostExtras, action: WindowAction, task: DesktopTask, name: String, bounds: TaskBounds?, position: PopupPosition): ActionResult {
        val phone = LaunchCheck.PHONE_DISPLAY
        return when (action) {
            WindowAction.MAXIMIZE, WindowAction.FULLSCREEN, WindowAction.RESTORE -> {
                if (task.windowingMode != WindowModes.FREEFORM) return ActionResult.ok("$name is already fullscreen on the phone.")
                val ok = runCatching { host.controlTask(task.taskId, phone, TaskOp.FULLSCREEN) }.getOrDefault(false) &&
                    await(true, task.taskId) { it.windowingMode != WindowModes.FREEFORM } != null
                if (ok) ActionResult.ok("$name is fullscreen on the phone again.")
                else ActionResult.fail("Could not make $name fullscreen on the phone.")
            }
            WindowAction.POPUP -> {
                val safe = phoneSafe ?: return ActionResult.fail("The phone's safe area is unknown; cannot place a pop-up window.")
                val wanted = bounds?.let { PhoneWindows.clampInto(it, safe) } ?: PhoneWindows.popupBounds(safe, position)
                val ok = runCatching { host.controlTask(task.taskId, phone, TaskOp.FREEFORM, wanted) }.getOrDefault(false) &&
                    await(true, task.taskId) { it.windowingMode == WindowModes.FREEFORM } != null
                if (ok) ActionResult.ok("$name is a pop-up window at $wanted on the phone (inside the status and navigation bars).")
                else ActionResult.fail("Could not open $name as a pop-up window on the phone.")
            }
            WindowAction.MINIMIZE -> {
                val ok = runCatching { host.controlTask(task.taskId, phone, TaskOp.MINIMIZE) }.getOrDefault(false)
                if (ok) ActionResult.ok("$name was sent to the back on the phone.") else ActionResult.fail("Could not minimize $name on the phone.")
            }
            WindowAction.CLOSE -> {
                val ok = runCatching { host.controlTask(task.taskId, phone, TaskOp.CLOSE) }.getOrDefault(false)
                if (ok) ActionResult.ok("Closed $name on the phone.") else ActionResult.fail("Could not close $name on the phone.")
            }
            WindowAction.MOVE_TO_PHONE -> ActionResult.ok("$name is already on the phone.")
            WindowAction.MOVE_TO_DESKTOP -> {
                val d = desktop ?: return ActionResult.fail("The desktop is not running; desktop_start starts it.")
                val ok = runCatching {
                    host.controlTask(task.taskId, phone, TaskOp.MOVE_TO_DESKTOP, bounds ?: DesktopWindowRules.defaultWindow(d.second, d.third))
                }.getOrDefault(false) && await(false, task.taskId) { true } != null
                if (ok) ActionResult.ok("Moved $name from the phone to the desktop (display ${d.first}).")
                else ActionResult.fail("Could not move $name to the desktop. It is still on the phone: do not operate it there; tell the user.")
            }
        }
    }

    /** The task [taskId] on the phone ([onPhone]) or the desktop once [check] holds, polling up to [verifyMs]. */
    private suspend fun await(onPhone: Boolean, taskId: Int, check: (DesktopTask) -> Boolean): DesktopTask? {
        var waited = 0L
        while (true) {
            val tasks = if (onPhone) phoneTasks() else desktopTasks()
            // A binder accepting a request is not proof that stock Android applied its windowing mode or bounds.
            // Losing privilege/the display during verification must not turn into a fabricated successful task.
            if (tasks == null) return null
            tasks.firstOrNull { it.taskId == taskId }?.takeIf(check)?.let { return it }
            if (waited >= verifyMs) return null
            delay(pollMs)
            waited += pollMs
        }
    }

    private fun notFound(app: String?, screen: TargetKind?): String {
        val where = when (screen) { TargetKind.PHONE -> "on the phone"; TargetKind.DESKTOP -> "on the desktop"; null -> "on either screen" }
        val open = desktopTasks().orEmpty().map { "${it.name} (desktop)" } + phoneTasks().orEmpty().take(3).map { "${it.name} (phone)" }
        return (if (app.isNullOrBlank()) "No focused app window $where." else "No window of \"$app\" $where.") +
            (if (open.isEmpty()) "" else " Open: ${open.joinToString()}.") + " window_state lists every window."
    }

    companion object {
        /** Bounds a desktop window had before it is maximized, for restore (per task id; process lifetime). */
        val remembered = ConcurrentHashMap<Int, TaskBounds>()

        const val UNAVAILABLE = "Window control needs Shizuku or wireless debugging (the task list is unavailable). On the " +
            "desktop use the window's own title-bar buttons or desktop_shortcut instead."
        const val DESKTOP_GATED = "Not done: this task works on the phone screen (the user chose Phone), so the DeX " +
            "desktop is off-limits for it."
        const val DESKTOP_KEPT = "The desktop itself is shown as before (desktop_presentation was not changed)."
        const val PHONE_GATED = "Not done: this task works on the desktop only, so the phone's screen and its apps are " +
            "off-limits. Use the desktop, or ask the user to switch the target to Phone."
    }
}

/**
 * One line of state in front of every screen listing ([A11yExecutorBase]): which screen the agent acts on, how the
 * desktop is shown, the front app of each screen, and the window mode of each listed window. Pure.
 */
object ScreenStateLine {
    fun task(t: DesktopTask): String = buildString {
        append(t.name)
        append(" [").append(WindowModes.label(t.windowingMode))
        t.bounds?.let { append(' ').append(it) }
        if (t.focused) append(", focused")
        if (!t.visible) append(", minimized")
        append(']')
    }

    fun desktop(
        displayId: Int, width: Int, height: Int, presentation: String,
        desktopTasks: List<DesktopTask>?, phoneFront: DesktopTask?,
    ): String = buildString {
        append("[state] You act on: the DeX desktop (display ").append(displayId).append(", ").append(width).append('x').append(height)
        append("), shown on the phone as ").append(presentationWords(presentation)).append('.')
        if (desktopTasks != null) {
            append(" Desktop windows: ")
            append(if (desktopTasks.isEmpty()) "none" else desktopTasks.take(8).joinToString("; ", transform = ::task))
            append('.')
        }
        phoneFront?.let { append(" Phone screen (display 0, not yours): ").append(it.name).append(" in front.") }
    }

    fun phone(width: Int, height: Int, phoneTasks: List<DesktopTask>?, safe: TaskBounds?, desktopRunning: Boolean): String = buildString {
        append("[state] You act on: the phone's own screen (display 0, ").append(width).append('x').append(height).append(").")
        val front = phoneTasks?.firstOrNull { it.visible }
        if (front != null) append(" Front app: ").append(task(front)).append('.')
        phoneTasks?.filter { PhoneWindows.misplaced(it, safe) }?.forEach {
            append(" WARNING: ").append(it.name).append(" is a floating window outside the safe area (its title bar may be ")
                .append("under the status bar): fix it with window_control action fullscreen instead of tapping it.")
        }
        if (desktopRunning) append(" A DeX desktop also runs (separate display).")
    }

    fun presentationWords(p: String): String = when (p.lowercase()) {
        "mini" -> "a popup window"
        "hidden" -> "nothing (background)"
        "full" -> "full screen"
        "in_app" -> "the player inside the Backstage app"
        else -> p.lowercase()
    }
}

/**
 * How the phone opens apps.
 * [windowingMode] and [correction] are pure; [options] builds the platform options.
 */
object PhoneLaunch {
    /** Fullscreen for every phone launch; freeform only for an explicit pop-up. */
    fun windowingMode(popup: Boolean): Int = if (popup) WindowModes.FREEFORM else WindowModes.FULLSCREEN

    /** What the launched phone task still needs: FULLSCREEN for a floating window nobody asked for, FREEFORM for a
     *  pop-up that is not one (or not inside the safe area), null when it is right. */
    fun correction(task: DesktopTask, popup: Boolean, safe: TaskBounds?): TaskOp? = when {
        !popup && task.windowingMode == WindowModes.FREEFORM -> TaskOp.FULLSCREEN
        popup && (task.windowingMode != WindowModes.FREEFORM || PhoneWindows.misplaced(task, safe)) -> TaskOp.FREEFORM
        else -> null
    }

    /**
     * Launch options for display [displayId]: the windowing mode is set explicitly (a hidden API, reached by reflection;
     * Backstage exempts hidden APIs at start-up) and launch bounds only for a pop-up.
   */
    fun options(displayId: Int, popupBounds: TaskBounds?): android.app.ActivityOptions {
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        runCatching {
            android.app.ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, windowingMode(popupBounds != null))
        }
        popupBounds?.let { options.setLaunchBounds(android.graphics.Rect(it.left, it.top, it.right, it.bottom)) }
        return options
    }
}
