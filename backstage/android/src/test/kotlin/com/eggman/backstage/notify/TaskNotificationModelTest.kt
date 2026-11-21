package com.eggman.backstage.notify

import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.notify.TaskNotificationModel.Action
import com.eggman.backstage.ui.Fixtures.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskNotificationModelTest {

    @Test fun runningShowsPlanStepProgressAndStop() {
        val m = TaskNotificationModel.from(snapshot(step = 3, maxSteps = 40, plan = listOf(
            PlanStep("Open app", "done"), PlanStep("Search", "in_progress"), PlanStep("Send", "pending"),
        )))
        assertEquals(NotifyChannels.STATUS, m.channel)
        assertTrue(m.ongoing)
        assertEquals("Step 2/3: Search", m.text)
        assertEquals(3 to 40, m.progress)
        assertEquals(listOf(Action.Stop), m.actions)
        assertFalse(m.alert)
    }

    @Test fun runningWithoutPlanShowsLastAction() {
        assertEquals("tap(Send)", TaskNotificationModel.from(snapshot(lastAction = "tap(Send)")).text)
    }

    @Test fun confirmQuestionOffersApproveDeny() {
        val m = TaskNotificationModel.from(snapshot(status = TaskStatus.WAITING_FOR_USER,
            question = PendingQuestion("Send the message to Tom?", confirm = true)))
        assertEquals(NotifyChannels.QUESTIONS, m.channel)
        assertEquals(listOf(Action.Approve, Action.Deny, Action.Stop), m.actions)
        assertTrue(m.alert)
    }

    @Test fun freeQuestionOffersReplyAndChoicesWithinThreeActions() {
        val free = TaskNotificationModel.from(snapshot(status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Which Tom?", false)))
        assertEquals(listOf(Action.Reply, Action.Stop), free.actions)
        val choices = TaskNotificationModel.from(snapshot(status = TaskStatus.WAITING_FOR_USER,
            question = PendingQuestion("Which Tom?", false, listOf("Tom A", "Tom B", "Tom C"))))
        assertEquals(listOf(Action.Choice("Tom A"), Action.Choice("Tom B"), Action.Reply), choices.actions)
    }

    @Test fun terminalStates() {
        val ok = TaskNotificationModel.from(snapshot(status = TaskStatus.SUCCEEDED, result = "Sent", source = Source.WAKE_WORD))
        assertEquals(NotifyChannels.RESULTS, ok.channel)
        assertFalse(ok.ongoing)
        assertEquals("Sent", ok.text)
        assertTrue(ok.alert)
        // Recurring automations must not ring on every run.
        listOf(Source.TASKER, Source.BROADCAST, Source.SCHEDULE, Source.MCP).forEach {
            assertFalse(it.name, TaskNotificationModel.from(snapshot(status = TaskStatus.SUCCEEDED, source = it)).alert)
            assertFalse(it.name, TaskNotificationModel.from(snapshot(status = TaskStatus.FAILED, source = it)).alert)
        }
        assertFalse(TaskNotificationModel.from(snapshot(status = TaskStatus.SUCCEEDED, source = Source.CHAT)).alert)
        assertTrue(TaskNotificationModel.from(snapshot(status = TaskStatus.FAILED)).title.startsWith("Failed"))
        val long = TaskNotificationModel.from(snapshot(status = TaskStatus.SUCCEEDED, result = "x".repeat(2000)))
        assertEquals(TaskNotificationModel.MAX_TEXT, long.text.length)
    }

    @Test fun resultPostingPolicy() {
        val chatDone = snapshot(status = TaskStatus.SUCCEEDED, source = Source.CHAT)
        assertFalse(TaskNotifier.shouldPostResult(chatDone, appVisible = true, notifyResults = true))
        assertTrue(TaskNotifier.shouldPostResult(chatDone, appVisible = false, notifyResults = true))
        assertFalse(TaskNotifier.shouldPostResult(chatDone, appVisible = false, notifyResults = false))
        val voice = snapshot(status = TaskStatus.SUCCEEDED, source = Source.WAKE_WORD)
        assertTrue(TaskNotifier.shouldPostResult(voice, appVisible = true, notifyResults = true))
        assertFalse(TaskNotifier.shouldPostResult(snapshot(status = TaskStatus.RUNNING), false, true))
        assertFalse(TaskNotifier.shouldPostResult(snapshot(status = TaskStatus.CANCELLED, source = Source.CHAT), false, true))
        // Scheduled runs get exactly one result notification (the scheduler's own).
        assertFalse(TaskNotifier.shouldPostResult(snapshot(status = TaskStatus.SUCCEEDED, source = Source.SCHEDULE), false, true))
    }

    @Test fun answerButtonsOnlyAnswerTheirOwnQuestion() {
        val q1 = snapshot(id = "t1", status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Send to Bob?", true))
        val key1 = TaskNotificationModel.questionKey(q1)
        assertTrue(TaskNotificationModel.answerApplies(q1, "t1", key1))
        // The question timed out and a new confirmation appeared: the old Approve button must not approve it.
        val q2 = q1.copy(question = PendingQuestion("Delete all photos?", true))
        assertFalse(TaskNotificationModel.answerApplies(q2, "t1", key1))
        // Another task, or nothing waiting any more.
        assertFalse(TaskNotificationModel.answerApplies(q1.copy(id = "t2"), "t1", key1))
        assertFalse(TaskNotificationModel.answerApplies(q1.copy(status = TaskStatus.RUNNING), "t1", key1))
        assertFalse(TaskNotificationModel.answerApplies(null, "t1", key1))
        assertFalse(TaskNotificationModel.answerApplies(q1, null, key1))
        assertEquals(null, TaskNotificationModel.questionKey(snapshot(status = TaskStatus.RUNNING)))
    }
}
