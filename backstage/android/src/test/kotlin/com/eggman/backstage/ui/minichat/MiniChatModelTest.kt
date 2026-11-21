package com.eggman.backstage.ui.minichat

import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.notify.SessionMessage
import com.eggman.backstage.notify.SessionMessage.Route
import com.eggman.backstage.notify.TaskNotificationModel
import com.eggman.backstage.ui.Fixtures.snapshot
import com.eggman.backstage.ui.chat.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniChatModelTest {
    private val waiting = snapshot(id = "t2", status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Which calendar?", confirm = false))

    @Test fun sendingRoutesLikeANotificationReply() {
        // What the panel shows is what the reply is routed against (SessionMessage.route, the notification's rules).
        fun routeOf(current: com.eggman.backstage.agent.api.TaskSnapshot?, locked: Boolean = false): Route {
            val posted = MiniChatModel.posted(current)
            return SessionMessage.route(current, posted.taskId, posted.questionKey, locked, "use Firefox")
        }
        assertEquals(Route.NewTask("use Firefox"), routeOf(null))
        assertEquals(Route.NewTask("use Firefox"), routeOf(snapshot(status = TaskStatus.SUCCEEDED)))
        assertEquals(Route.Instruct("t1", "use Firefox"), routeOf(snapshot(id = "t1")))
        assertEquals(Route.Answer("use Firefox"), routeOf(waiting))
        assertEquals(Route.Resume("t3", "use Firefox"), routeOf(snapshot(id = "t3").copy(paused = true)))
        // Locked: nothing is sent.
        assertEquals(Route.Drop, routeOf(snapshot(), locked = true))
        // A finished task posts nothing; a waiting one names its question.
        assertEquals(MiniChatModel.Posted(null, null), MiniChatModel.posted(snapshot(status = TaskStatus.FAILED)))
        assertEquals(MiniChatModel.Posted("t2", TaskNotificationModel.questionKey(waiting)), MiniChatModel.posted(waiting))
    }

    @Test fun hintAndNoticeSayWhatSendingDoes() {
        assertEquals("Give the agent a task…", MiniChatModel.hint(null))
        assertEquals("Give the agent a task…", MiniChatModel.hint(snapshot(status = TaskStatus.CANCELLED)))
        assertEquals("Answer the agent…", MiniChatModel.hint(waiting))
        assertEquals("Add an instruction for the running task…", MiniChatModel.hint(snapshot()))
        assertEquals("Add an instruction and resume…", MiniChatModel.hint(snapshot().copy(paused = true)))
        assertEquals(MiniChatModel.LOCKED_NOTICE, MiniChatModel.notice(Route.Drop, locked = true))
        assertNull(MiniChatModel.notice(Route.Drop, locked = false))
        assertEquals("Task started.", MiniChatModel.notice(Route.NewTask("x"), locked = false))
        assertEquals("Answer sent.", MiniChatModel.notice(Route.Answer("x"), locked = false))
        assertEquals("Instruction added to the running task.", MiniChatModel.notice(Route.Instruct("t", "x"), locked = false))
    }

    @Test fun statusAndQuestionComeFromTheCurrentTask() {
        assertEquals(MiniChatModel.IDLE_STATUS, MiniChatModel.status(AgentState()))
        val running = snapshot(step = 13, maxSteps = 80, lastAction = "Tap Compose")
        assertEquals("Step 13/80 · Tap Compose", MiniChatModel.status(AgentState(current = running)))
        assertEquals("Question: Which calendar?", MiniChatModel.status(AgentState(current = waiting)))
    }

    @Test fun recentConversationJoinsHistoryAndTheLiveTaskNewestLast() {
        val old = snapshot(id = "h1", prompt = "Check mail", status = TaskStatus.SUCCEEDED, result = "3 new mails")
        val failed = snapshot(id = "h2", prompt = "Book a table", status = TaskStatus.FAILED, result = "No network")
        val live = Transcript().onSnapshot(snapshot(id = "t1", prompt = "Open settings", step = 2, lastAction = "Tap Wi-Fi"))
        val lines = MiniChatModel.lines(listOf(failed, old), live)
        assertEquals(
            listOf(
                MiniChatModel.Line(Transcript.Kind.USER, "Check mail"),
                MiniChatModel.Line(Transcript.Kind.RESULT, "3 new mails"),
                MiniChatModel.Line(Transcript.Kind.USER, "Book a table"),
                MiniChatModel.Line(Transcript.Kind.ERROR, "No network"),
                MiniChatModel.Line(Transcript.Kind.USER, "Open settings"),
                MiniChatModel.Line(Transcript.Kind.ACTION, "Step 2: Tap Wi-Fi"),
            ),
            lines,
        )
        // The live task is not repeated from history once it finished; the list keeps only the newest lines.
        val done = live.onSnapshot(snapshot(id = "t1", prompt = "Open settings", status = TaskStatus.SUCCEEDED, result = "Wi-Fi is on"))
        val again = MiniChatModel.lines(listOf(snapshot(id = "t1", prompt = "Open settings", status = TaskStatus.SUCCEEDED, result = "Wi-Fi is on")), done)
        assertEquals(1, again.count { it.text == "Open settings" })
        assertEquals(3, MiniChatModel.lines(listOf(old, failed), done, max = 3).size)
    }

    @Test fun thePanelClosesWhenTheDesktopCoversItOrTheAppOpens() {
        assertTrue(MiniChatModel.keepOpen(Presentation.MINI))
        assertTrue(MiniChatModel.keepOpen(Presentation.HIDDEN))
        assertTrue(MiniChatModel.keepOpen(Presentation.NONE))
        assertFalse(MiniChatModel.keepOpen(Presentation.FULL))
        assertFalse(MiniChatModel.keepOpen(Presentation.IN_APP))
    }
}
