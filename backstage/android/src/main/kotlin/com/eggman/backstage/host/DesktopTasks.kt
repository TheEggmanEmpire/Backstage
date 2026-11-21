package com.eggman.backstage.host

/**
 * One framework task (an app window) as the host's task manager reports it. [displayId] is the display the task is
 * on; [activityType] is WindowConfiguration's activity type (0 undefined, 1 standard app, 2 home, 3 recents,
 * 4 assistant, 5 dream).
 */
data class DesktopTask(
    val taskId: Int,
    val packageName: String,
    val label: String?,
    val displayId: Int,
    val activityType: Int = DesktopTaskPolicy.ACTIVITY_TYPE_STANDARD,
    val visible: Boolean = true,
    val focused: Boolean = false,
    /** The task's window bounds in its display's pixels, when the host reports them. */
    val bounds: TaskBounds? = null,
    /** WindowConfiguration windowing mode ([WindowModes]): 1 fullscreen, 5 freeform, 0 undefined (inherits the display's). */
    val windowingMode: Int = WindowModes.UNDEFINED,
) {
    val name: String get() = label?.takeIf { it.isNotBlank() } ?: packageName
}

/** Window bounds (left, top, right, bottom) in display pixels. Pure data, no android.graphics.Rect (JVM tests). */
data class TaskBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
    fun inside(o: TaskBounds): Boolean = left >= o.left && top >= o.top && right <= o.right && bottom <= o.bottom
    override fun toString() = "[$left,$top][$right,$bottom]"
}

/** WindowConfiguration windowing modes as the framework reports them. */
object WindowModes {
    const val UNDEFINED = 0
    const val FULLSCREEN = 1
    const val PINNED = 2
    const val FREEFORM = 5
    const val MULTI_WINDOW = 6

    fun label(mode: Int): String = when (mode) {
        FULLSCREEN -> "fullscreen"
        FREEFORM -> "freeform"
        PINNED -> "picture-in-picture"
        MULTI_WINDOW -> "split"
        else -> "default"
    }
}

/**
 * What the host does to one app task ([DesktopHostExtras.controlTask]). The host re-checks the task (still on the
 * stated display, an ordinary app task, not Backstage itself) right before it acts.
 */
enum class TaskOp {
    /** Fullscreen windowing mode on its display (on the desktop: the window fills the desktop, i.e. maximized). */
    FULLSCREEN,
    /** Freeform window at the given bounds (desktop: restore / place; phone: pop-up window). */
    FREEFORM,
    /** Sent behind the other windows of its display. */
    MINIMIZE,
    /** Moved from the phone's display to the desktop display (as a freeform window). */
    MOVE_TO_DESKTOP,
    /** Moved from the desktop display to the phone's display (fullscreen). */
    MOVE_TO_PHONE,
    /** Task removed (never force-stop). */
    CLOSE,
}

/**
 * Which tasks Backstage may close when it closes desktop windows (user request: "close all foreground apps" must
 * only close the DeX desktop's apps). Pure rules, applied by the library and again by the host right before a
 * task is removed:
 *
 * - only tasks ON the desktop display; the phone's display (0) is never a target, whatever a caller passes;
 * - only ordinary app tasks: never the desktop's home/launcher, the recents view, the assistant or a dream;
 * - never Backstage/Dextop itself (the desktop's own UI runs in its package).
 *
 * Closing is removeTask, never force-stop: Android keeps an app's process alive while the same app still has a
 * task in recents (e.g. on the phone), so an app open on both screens loses only its desktop window.
 */
object DesktopTaskPolicy {
    const val ACTIVITY_TYPE_UNDEFINED = 0
    const val ACTIVITY_TYPE_STANDARD = 1

    fun closable(task: DesktopTask, desktopDisplayId: Int, ownPackages: Set<String>): Boolean =
        desktopDisplayId != PHONE_DISPLAY &&
            task.displayId == desktopDisplayId &&
            task.packageName.isNotBlank() &&
            task.packageName !in ownPackages &&
            (task.activityType == ACTIVITY_TYPE_STANDARD || task.activityType == ACTIVITY_TYPE_UNDEFINED)

    /**
     * The tasks to close on [desktopDisplayId]: every closable one, or only those of [packages] when given. A task
     * reported for another display (a host bug, or a task that moved) is dropped here.
   */
    fun select(
        tasks: List<DesktopTask>,
        desktopDisplayId: Int,
        ownPackages: Set<String>,
        packages: Set<String>? = null,
    ): List<DesktopTask> = tasks
        .filter { closable(it, desktopDisplayId, ownPackages) }
        .filter { packages == null || it.packageName in packages }
        .distinctBy { it.taskId }

    /**
     * Whether Backstage may change [task]'s window on [displayId] (the desktop display or the phone's, for
     * window_control and launch repair): it must still be on that display, be an ordinary app task and not be
     * Backstage/Dextop itself. The host checks it again right before acting.
   */
    fun controllable(task: DesktopTask, displayId: Int, ownPackages: Set<String>): Boolean =
        displayId >= 0 &&
            task.displayId == displayId &&
            task.packageName.isNotBlank() &&
            task.packageName !in ownPackages &&
            (task.activityType == ACTIVITY_TYPE_STANDARD || task.activityType == ACTIVITY_TYPE_UNDEFINED)

    /** The window the user/agent is working in: the focused desktop task, else the top-most visible one. */
    fun focused(tasks: List<DesktopTask>, desktopDisplayId: Int, ownPackages: Set<String>): DesktopTask? {
        val candidates = select(tasks, desktopDisplayId, ownPackages)
        return candidates.firstOrNull { it.focused } ?: candidates.firstOrNull { it.visible }
    }

    private const val PHONE_DISPLAY = 0
}
