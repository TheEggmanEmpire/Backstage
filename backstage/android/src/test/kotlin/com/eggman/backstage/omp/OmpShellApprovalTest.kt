package com.eggman.backstage.omp

import com.eggman.backstage.agent.AgentControllerImpl
import com.eggman.backstage.agent.FakeEnv
import com.eggman.backstage.agent.api.Answers
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.data.Provider
import com.eggman.backstage.data.NonInteractiveConfirm
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.ScriptedLlm.Companion.call
import com.eggman.backstage.testutil.ScriptedLlm.Companion.text
import com.eggman.backstage.testutil.script
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OmpShellApprovalTest {
    @Test fun approvalUsesTheExistingQuestionChannelWithoutAProviderOrReadiness() = runTest {
        val env = FakeEnv(this, ScriptedLlm(script())).apply {
            settings.setApiKey(Provider.ANTHROPIC, "")
            settings.confirmSensitive = false
            settings.nonInteractiveConfirm = NonInteractiveConfirm.ALLOW
            a11y = false; screenOn = false
        }
        val controller = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        val approval = async { controller.confirmExternalAction("Run Termux as shell: id") }
        runCurrent()
        val question = controller.state.value.current!!
        assertEquals(TaskStatus.WAITING_FOR_USER, question.status)
        assertEquals("Run Termux as shell: id", question.question!!.text)
        assertTrue(question.question.confirm)
        assertEquals(listOf("Approve", "Deny"), question.question.choices)
        assertFalse(approval.isCompleted)
        assertTrue(env.configs.isEmpty())
        // an external shell approval is a person-only confirmation: only the in-app UI path approves it.
        controller.answer(Answers.APPROVE); runCurrent()
        assertFalse(approval.isCompleted)
        assertTrue(controller.answerFromUi(Answers.APPROVE, question.id, question.question!!.text))
        assertTrue(approval.await())
        runCurrent()
        assertNull(controller.state.value.current!!.question)
        assertEquals(listOf(true, false), env.busyChanges)
        val denied = async { controller.confirmExternalAction("Run Termux as shell: false") }
        runCurrent(); controller.answer(Answers.DENY)
        assertFalse(denied.await())
    }
    @Test fun externalPromptQueuesBehindAgentApprovalWithoutReplacingIt() = runTest {
        val llm = ScriptedLlm(script(call("confirm_action", """{"action":"agent action"}"""), text("Done")))
        val env = FakeEnv(this, llm)
        val controller = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        val agent = controller.submit(TaskRequest("agent task", Source.CHAT))
        runCurrent()
        assertEquals(TaskStatus.WAITING_FOR_USER, controller.state.value.current!!.status)
        val original = controller.state.value.current!!.question
        val shell = async { controller.confirmExternalAction("Termux command") }
        runCurrent()
        assertEquals(original, controller.state.value.current!!.question)
        assertEquals(1, controller.state.value.queueSize)
        controller.answer(Answers.APPROVE)
        agent.await(); runCurrent()
        assertEquals("Termux command", controller.state.value.current!!.question!!.text)
        controller.answer(Answers.DENY)
        assertFalse(shell.await())
    }
    @Test fun cancelledAndTimedOutPromptsAreClearedAndNeverAutoApproved() = runTest {
        val controller = AgentControllerImpl(FakeEnv(this, ScriptedLlm(script())), StandardTestDispatcher(testScheduler))
        val cancelled = async { controller.confirmExternalAction("cancel me") }
        runCurrent(); cancelled.cancel(); runCurrent()
        assertNull(controller.state.value.current!!.question)
        assertEquals(TaskStatus.CANCELLED, controller.state.value.current!!.status)
        val timedOut = async { controller.confirmExternalAction("timeout") }
        runCurrent(); advanceTimeBy(AgentControllerImpl.CONFIRM_WAIT_MS); runCurrent()
        assertFalse(timedOut.await())
        assertNull(controller.state.value.current!!.question)
    }
    @Test fun cancellingAQueuedShellApprovalLeavesTheAgentQuestionAlone() = runTest {
        val llm = ScriptedLlm(script(call("confirm_action", """{"action":"agent action"}""")))
        val controller = AgentControllerImpl(FakeEnv(this, llm), StandardTestDispatcher(testScheduler))
        val agent = controller.submit(TaskRequest("agent task", Source.CHAT))
        runCurrent()
        val question = controller.state.value.current!!.question
        val shell = async { controller.confirmExternalAction("queued shell") }
        runCurrent(); shell.cancel(); runCurrent()
        assertEquals(0, controller.state.value.queueSize)
        assertEquals(agent.id, controller.state.value.current!!.id)
        assertEquals(question, controller.state.value.current!!.question)
        controller.answer(Answers.DENY)
        agent.await()
    }
}
