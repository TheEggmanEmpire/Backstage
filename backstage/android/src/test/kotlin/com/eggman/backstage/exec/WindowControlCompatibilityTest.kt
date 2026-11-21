package com.eggman.backstage.exec

import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopTask
import com.eggman.backstage.host.TaskBounds
import com.eggman.backstage.host.TaskOp
import com.eggman.backstage.host.WindowModes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

/** Stock Android can accept a binder request while keeping its fullscreen/split-screen task policy. */
class WindowControlCompatibilityTest {
    private class Host(val mode: Int, val loseTaskList: Boolean = false) : DesktopHostExtras {
        private var acted = false
        override fun setMiniStatus(text: String?) = Unit
        override fun setMiniShape(shape: String) = false
        override fun miniShape() = "rounded"
        override fun usePrivilegeProvider(provider: String) = false
        override fun desktopAccessibilityComponent(): String? = null
        override fun desktopTasks(): List<DesktopTask>? = if (acted && loseTaskList) null else listOf(
            DesktopTask(1, "org.example.app", "App", 7, focused = true, windowingMode = mode,
                bounds = TaskBounds(100, 100, 700, 600)),
        )
        override fun phoneTasks() = emptyList<DesktopTask>()
        override fun controlTask(taskId: Int, displayId: Int, op: TaskOp, bounds: TaskBounds?): Boolean {
            acted = true
            return true // accepted transport is not proof of freeform or placement
        }
    }

    @Test fun aSplitScreenTaskDoesNotCountAsARestoredDesktopWindow() = runTest {
        val control = WindowController(Host(6), Triple(7, 1920, 1080), null, emptySet(), false, verifyMs = 0)
        assertFalse(control.control(WindowAction.RESTORE, TargetKind.DESKTOP, null, null, PopupPosition.CENTER, emptyList()).ok)
    }

    @Test fun aLostTaskListCannotVerifyFullscreenOrPlacement() = runTest {
        val control = WindowController(Host(WindowModes.FREEFORM, true), Triple(7, 1920, 1080), null, emptySet(), false, verifyMs = 0)
        assertFalse(control.control(WindowAction.MAXIMIZE, TargetKind.DESKTOP, null, null, PopupPosition.CENTER, emptyList()).ok)
    }

    @Test fun aFreeformTaskWhoseBoundsWereIgnoredIsNotReportedAsPlaced() = runTest {
        val control = WindowController(Host(WindowModes.FREEFORM), Triple(7, 1920, 1080), null, emptySet(), false, verifyMs = 0)
        assertFalse(control.control(WindowAction.POPUP, TargetKind.DESKTOP, null, TaskBounds(200, 150, 900, 750), PopupPosition.CENTER, emptyList()).ok)
    }
}
