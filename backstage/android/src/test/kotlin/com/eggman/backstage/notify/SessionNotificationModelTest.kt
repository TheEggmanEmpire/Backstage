package com.eggman.backstage.notify

import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.notify.SessionMessage.Route
import com.eggman.backstage.notify.SessionNotificationModel.Action
import com.eggman.backstage.ui.Fixtures.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNotificationModelTest {
    @Test fun nothingRunningShowsNothing() {
        assertFalse(SessionNotificationModel.from(AgentState(), Presentation.NONE).show)
        assertFalse(SessionNotificationModel.from(AgentState(current = snapshot(status = TaskStatus.SUCCEEDED)), Presentation.NONE).show)
    }

    @Test fun idleDesktopOffersMessageStopAndPopup() {
        val hidden = SessionNotificationModel.from(AgentState(), Presentation.HIDDEN)
        assertTrue(hidden.show)
        assertEquals("Backstage desktop is running", hidden.title)
        assertTrue(hidden.text.contains("Desktop: running in the background"))
        assertEquals(listOf(Action.Message, Action.StopDesktop, Action.Present(Presentation.MINI)), hidden.actions)
        val mini = SessionNotificationModel.from(AgentState(), Presentation.MINI)
        assertEquals(Action.Present(Presentation.HIDDEN), mini.actions.last())
        assertEquals(Action.Present(Presentation.MINI), SessionNotificationModel.from(AgentState(), Presentation.IN_APP).actions.last())
    }

    @Test fun runningTaskShowsWhatTheBotIsDoing() {
        val s = snapshot(prompt = "Book a table", step = 3, maxSteps = 40, plan = listOf(
            PlanStep("Open app", "done"), PlanStep("Search", "in_progress"), PlanStep("Book", "pending"),
        ))
        val m = SessionNotificationModel.from(AgentState(current = s, queueSize = 1), Presentation.MINI)
        assertEquals("Backstage is working", m.title)
        assertTrue(m.text, m.text.startsWith("Book a table — Step 2/3: Search"))
        assertTrue(m.text.contains("1 queued"))
        assertEquals(3 to 40, m.progress)
        assertEquals(listOf(Action.Message, Action.StopTask, Action.Present(Presentation.HIDDEN)), m.actions)
        assertEquals("t1", m.taskId)
        assertFalse(Action.StopTask.requiresUnlock)
        assertTrue(Action.Message.requiresUnlock)
    }

    @Test fun pendingQuestionIsShown() {
        val s = snapshot(status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Which Tom?", false))
        val m = SessionNotificationModel.from(AgentState(current = s), Presentation.NONE)
        assertEquals("Backstage needs your answer", m.title)
        assertTrue(m.text.contains("Which Tom?"))
        assertEquals(TaskNotificationModel.questionKey(s), m.questionKey)
        assertEquals(listOf(Action.Message, Action.StopTask), m.actions)
    }

    @Test fun mcpOnlyCard() {
        val m = SessionNotificationModel.from(AgentState(), Presentation.NONE, mcpLine = "MCP server: x")
        assertTrue(m.show)
        assertEquals(listOf(Action.Message, Action.StopMcp), m.actions)
    }

    @Test fun messageRouting() {
        assertEquals(Route.NewTask("open maps"), SessionMessage.route(null, null, null, locked = false, text = " open maps "))
        assertEquals(Route.Drop, SessionMessage.route(null, null, null, locked = true, text = "open maps"))
        assertEquals(Route.Drop, SessionMessage.route(null, null, null, locked = false, text = "  "))
        val running = snapshot()
        assertEquals(Route.Instruct("t1", "use Firefox"), SessionMessage.route(running, "t1", null, false, "use Firefox"))
        assertEquals(Route.Resume("t1", "go on"), SessionMessage.route(running.copy(paused = true), "t1", null, false, "go on"))
        val waiting = snapshot(status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Which Tom?", false))
        val key = TaskNotificationModel.questionKey(waiting)
        assertEquals(Route.Answer("Tom A"), SessionMessage.route(waiting, "t1", key, false, "Tom A"))
        // Typed before the question is on the card: never an answer to a question the user did not see.
        assertEquals(Route.Instruct("t1", "Tom A"), SessionMessage.route(waiting, "t1", null, false, "Tom A"))
        assertEquals(Route.NewTask("again"), SessionMessage.route(snapshot(status = TaskStatus.FAILED), "t1", null, false, "again"))
    }
}
