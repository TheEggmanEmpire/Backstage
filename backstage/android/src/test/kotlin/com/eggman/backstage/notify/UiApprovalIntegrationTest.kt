@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.eggman.backstage.notify

import com.eggman.backstage.agent.AgentControllerImpl
import com.eggman.backstage.agent.AgentEnvironment
import com.eggman.backstage.agent.FakeEnv
import com.eggman.backstage.agent.api.*
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.surfaces.FakeAgent
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.ScriptedLlm.Companion.call
import com.eggman.backstage.testutil.ScriptedLlm.Companion.text
import com.eggman.backstage.testutil.script
import com.eggman.backstage.tools.*
import com.eggman.backstage.ui.Fixtures.snapshot
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class UiApprovalIntegrationTest {
    @After fun clearOverride() { AgentAccess.override = null }

    private fun TestScope.controller(question: String): AgentControllerImpl {
        val tool = object : Tool {
            override val name = "ui_approval_probe"
            override val description = "Approval integration probe"
            override val schema = Schema.obj()
            override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
            override suspend fun run(ctx: ToolContext, args: Args): ToolResult =
                if (ctx.hooks!!.confirmAlways(question)) ToolResult("approved")
                else ToolResult.error("Not done: the user did not approve this action.")
        }
        val env = FakeEnv(this, ScriptedLlm(script(call(tool.name), text("done"))))
        val wrapped = object : AgentEnvironment by env { override val registry = ToolRegistry.default().plus(listOf(tool)) }
        return AgentControllerImpl(wrapped, StandardTestDispatcher(testScheduler)).also { AgentAccess.override = it }
    }

    @Test fun trustedUiAdapterApprovesTheCompleteQuestionWhileProgrammaticAnswersCannot() = runTest {
        val question = "x".repeat(1_950) + " delete the original files"
        val c = controller(question)
        val h = c.submit(TaskRequest("perform requested action", Source.CHAT))
        runCurrent()
        AgentAccess.answer(Answers.APPROVE)
        runCurrent()
        assertEquals(TaskStatus.WAITING_FOR_USER, c.state.value.current!!.status)
        assertFalse(AgentAccess.answerFromUi(Answers.APPROVE, h.id, question.take(400)))
        assertFalse(AgentAccess.answerFromUi(Answers.APPROVE, "another-task", question))
        assertTrue(AgentAccess.answerFromUi(Answers.APPROVE, h.id, question))
        assertEquals(TaskStatus.SUCCEEDED, h.await().status)
    }

    @Test fun taskNotificationApprovesOnlyItsCompleteDisplayedQuestion() = runTest {
        val question = "Send this specific message?"
        val c = controller(question)
        val h = c.submit(TaskRequest("send", Source.CHAT))
        runCurrent()
        val current = c.state.value.current!!
        val key = TaskNotificationModel.questionKey(current)
        val shown = TaskNotificationModel.from(current).text
        assertEquals(question, shown)
        assertFalse(Notifications.answerFromNotification(h.id, key, null, Answers.APPROVE))
        assertFalse(Notifications.answerFromNotification(h.id, key, "Different question", Answers.APPROVE))
        assertFalse(Notifications.answerFromNotification(h.id, "stale-key", shown, Answers.APPROVE))
        assertTrue(Notifications.answerFromNotification(h.id, key, shown, Answers.APPROVE))
        assertEquals(TaskStatus.SUCCEEDED, h.await().status)
    }

    @Test fun longTaskNotificationCannotApproveAndInAppReviewStillCan() = runTest {
        val question = "x".repeat(1_000) + " protected ending"
        val c = controller(question)
        val h = c.submit(TaskRequest("long approval", Source.CHAT))
        runCurrent()
        val current = c.state.value.current!!
        val model = TaskNotificationModel.from(current)
        val key = TaskNotificationModel.questionKey(current)
        assertFalse(TaskNotificationModel.Action.Approve in model.actions)
        assertFalse(Notifications.answerFromNotification(h.id, key, model.text, Answers.APPROVE))
        assertFalse(Notifications.answerFromNotification(h.id, key, question, Answers.APPROVE))
        assertEquals(TaskStatus.WAITING_FOR_USER, c.state.value.current!!.status)
        assertTrue(AgentAccess.answerFromUi(Answers.APPROVE, h.id, question))
        assertEquals(TaskStatus.SUCCEEDED, h.await().status)
    }

    @Test fun sessionNotificationBindsToTheActualQuestionSliceAfterItsPromptPrefix() = runTest {
        val question = "x".repeat(350)
        val c = controller(question)
        val h = c.submit(TaskRequest("p".repeat(80), Source.CHAT))
        runCurrent()
        val model = SessionNotificationModel.from(c.state.value, Presentation.HIDDEN)
        assertTrue(question.length <= TaskNotificationModel.MAX_TEXT)
        assertNotEquals(question, model.displayedQuestion)
        assertTrue(model.text.contains("Open Backstage to review"))
        assertFalse(Notifications.answerFromNotification(model.taskId, model.questionKey, model.displayedQuestion, Answers.APPROVE))
        assertEquals(TaskStatus.WAITING_FOR_USER, c.state.value.current!!.status)
        assertTrue(AgentAccess.answerFromUi(Answers.APPROVE, h.id, question))
        assertEquals(TaskStatus.SUCCEEDED, h.await().status)
    }

    @Test fun aShortSessionQuestionCanBeApprovedFromItsDisplayedSlice() = runTest {
        val question = "Approve this exact command?"
        val c = controller(question)
        val h = c.submit(TaskRequest("short task", Source.CHAT))
        runCurrent()
        val model = SessionNotificationModel.from(c.state.value, Presentation.HIDDEN)
        assertEquals(question, model.displayedQuestion)
        assertTrue(Notifications.answerFromNotification(model.taskId, model.questionKey, model.displayedQuestion, Answers.APPROVE))
        assertEquals(TaskStatus.SUCCEEDED, h.await().status)
    }

    @Test fun longOrdinaryQuestionsStillAcceptNotificationReplies() {
        val current = snapshot(status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("q".repeat(800), false))
        val fake = FakeAgent(AgentState(current = current))
        AgentAccess.override = fake
        assertTrue(Notifications.answerFromNotification(current.id, TaskNotificationModel.questionKey(current),
            TaskNotificationModel.from(current).text, "blue"))
        assertEquals(listOf("blue"), fake.answers)
    }

    @Test fun embedderUiAdapterStillBindsTheTaskAndCompleteQuestion() {
        val current = snapshot(status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Approve this?", true))
        val fake = FakeAgent(AgentState(current = current))
        AgentAccess.override = fake
        assertFalse(AgentAccess.answerFromUi(Answers.APPROVE, "other", "Approve this?"))
        assertFalse(AgentAccess.answerFromUi(Answers.APPROVE, current.id, "Approve"))
        assertTrue(fake.answers.isEmpty())
        assertTrue(AgentAccess.answerFromUi(Answers.APPROVE, current.id, "Approve this?"))
        assertEquals(listOf(Answers.APPROVE), fake.answers)
    }
}
