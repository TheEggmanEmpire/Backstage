@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.eggman.backstage.agent

import com.eggman.backstage.agent.api.*
import com.eggman.backstage.data.NonInteractiveConfirm
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.ScriptedLlm.Companion.call
import com.eggman.backstage.testutil.ScriptedLlm.Companion.text
import com.eggman.backstage.testutil.script
import com.eggman.backstage.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ApprovalLifecycleSecurityTest {
    private fun TestScope.controller(tool: Tool? = null, sourceCall: String = tool?.name ?: "ask_user"): Pair<AgentControllerImpl, FakeEnv> {
        val llm = ScriptedLlm(script(call(sourceCall, if (tool == null) """{"question":"Which one?"}""" else "{}"), text("done")))
        val env = FakeEnv(this, llm)
        val actual = if (tool == null) env else object : AgentEnvironment by env {
            override val registry = ToolRegistry.default().plus(listOf(tool))
        }
        return AgentControllerImpl(actual, StandardTestDispatcher(testScheduler)) to env
    }
    private fun approvalTool(action: String = "Run protected command?", approved: () -> Unit = {}) = object : Tool {
        override val name = "security_approval_probe"
        override val description = "Exercise an unconditional approval"
        override val schema = Schema.obj()
        override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
        override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
            if (ctx.hooks!!.confirmAlways(action)) {
                approved()
                return ToolResult("approved")
            }
            return ToolResult.error("Not done: the user did not approve this action. Do not retry it.")
        }
    }
    @Test fun approvalCannotExceedTheInAppHardCap() = runTest {
        var approved = false
        val (c, _) = controller(approvalTool("x".repeat(4_001)) { approved = true })
        val h = c.submit(TaskRequest("run", Source.CHAT))
        runCurrent()
        assertNotEquals(TaskStatus.WAITING_FOR_USER, c.state.value.current!!.status)
        h.await()
        assertFalse(approved)
    }
    @Test fun longApprovalCanBeReviewedInAppWithoutApprovingAnUnseenSuffix() = runTest {
        var approved = false
        val ending = " protected ending"
        val action = "x".repeat(4_000 - ending.length) + ending
        val (c, _) = controller(approvalTool(action) { approved = true })
        val h = c.submit(TaskRequest("run", Source.CHAT))
        runCurrent()
        assertEquals(TaskStatus.WAITING_FOR_USER, c.state.value.current!!.status)
        assertEquals(action, c.state.value.current!!.question!!.text)
        assertFalse(c.answerFromUi("Approve", h.id, action.take(400)))
        c.answer("Approve")
        runCurrent()
        assertFalse(approved)
        assertEquals(TaskStatus.WAITING_FOR_USER, c.state.value.current!!.status)
        assertTrue(c.answerFromUi("Approve", h.id, action))
        h.await()
        assertTrue(approved)
    }
    @Test fun scheduledRunCannotWaiveAlwaysEvenWithAllowPolicyAndAutoSettings() = runTest {
        var approved = false
        val (c, env) = controller(approvalTool { approved = true })
        env.settings.nonInteractiveConfirm = NonInteractiveConfirm.ALLOW
        env.settings.adbAutoApprove = true
        env.settings.ompAutoApprove = true
        env.settings.askTimeoutSec = 5
        val h = c.submit(TaskRequest("run it", Source.SCHEDULE, interactive = false))
        runCurrent()
        assertEquals(TaskStatus.WAITING_FOR_USER, c.state.value.current!!.status)
        assertFalse(approved)
        c.answer("approve")
        runCurrent()
        assertEquals(TaskStatus.WAITING_FOR_USER, c.state.value.current!!.status)
        val q = c.state.value.current!!.question!!.text
        assertTrue(c.answerFromUi("Approve", h.id, q))
        h.await()
        assertTrue(approved)
    }
    @Test fun unattendedDenyNeverWaitsForAlways() = runTest {
        var approved = false
        val (c, env) = controller(approvalTool { approved = true })
        env.settings.nonInteractiveConfirm = NonInteractiveConfirm.DENY
        c.submit(TaskRequest("run it", Source.SCHEDULE, interactive = false)).await()
        assertFalse(approved)
        assertNull(c.state.value.current!!.question)
    }
    @Test fun scheduledCreateAndUpdateCannotCreateTheirOwnAuthorization() = runTest {
        for (tool in listOf(CreateScheduledTaskTool(), UpdateScheduledTaskTool())) {
            val args = if (tool is CreateScheduledTaskTool)
                """{"description":"send my messages","cron_expression":"0 8 * * *"}"""
                else """{"id":"s1","description":"send my messages"}"""
            val llm = ScriptedLlm(script(call(tool.name, args), text("done")))
            val env = FakeEnv(this, llm)
            var writes = 0
            env.services.schedulerImpl = object : ScheduledTaskApi {
                override fun list() = listOf(ScheduledTaskInfo("s1", "old task", "0 8 * * *", true, null))
                override fun create(description: String, cron: String, source: String): ScheduledTaskInfo { writes++; return list().single() }
                override fun update(id: String, description: String?, cron: String?, enabled: Boolean?): ScheduledTaskInfo { writes++; return list().single() }
                override fun delete(id: String) = false
            }
            env.settings.nonInteractiveConfirm = NonInteractiveConfirm.ALLOW
            env.settings.adbAutoApprove = true
            env.settings.ompAutoApprove = true
            env.settings.askTimeoutSec = 1
            val c = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
            val h = c.submit(TaskRequest("run configured job", Source.SCHEDULE, interactive = false))
            runCurrent()
            assertEquals(TaskStatus.WAITING_FOR_USER, c.state.value.current!!.status)
            c.answer("approve")
            h.await()
            assertEquals(0, writes)
        }
    }
    @Test fun programmaticAnswersStillAnswerQuestions() = runTest {
        val (c, _) = controller()
        val h = c.submit(TaskRequest("help", Source.MCP))
        runCurrent()
        assertFalse(c.state.value.current!!.question!!.confirm)
        c.answer("blue")
        assertEquals(TaskStatus.SUCCEEDED, h.await().status)
    }
    @Test fun irreversibleApprovalRequiresUiWhenItIsRaised() = runTest {
        var approved = false
        val tool = object : Tool by approvalTool() {
            override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
                approved = ctx.hooks!!.confirmIrreversible("Send irreversible message?")
                return if (approved) ToolResult("sent") else ToolResult.error("Not done: the user did not approve this action.")
            }
        }
        val (c, _) = controller(tool)
        val h = c.submit(TaskRequest("send message", Source.CHAT))
        runCurrent()
        c.answer("yes")
        runCurrent()
        assertEquals(TaskStatus.WAITING_FOR_USER, c.state.value.current!!.status)
        val q = c.state.value.current!!.question!!.text
        assertFalse(c.answerFromUi("Approve", "wrong-task", q))
        assertFalse(c.answerFromUi("Approve", h.id, "stale question"))
        assertTrue(c.answerFromUi("Approve", h.id, q))
        h.await()
        assertTrue(approved)
    }
    @Test fun cancellationBeforeDispatchPublishesTerminalSnapshotAndIdle() = runTest {
        val (c, env) = controller()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.state.collect { state ->
                state.current?.takeIf { it.status == TaskStatus.QUEUED }?.let { c.cancel(it.id) }
            }
        }
        val h = c.submit(TaskRequest("cancel at dispatch", Source.CHAT))
        runCurrent()
        assertEquals(TaskStatus.CANCELLED, h.await().status)
        assertEquals(TaskStatus.CANCELLED, c.state.value.current!!.status)
        assertFalse(c.state.value.busy)
        assertEquals(false, env.busyChanges.last())
    }
    @Test fun executionLeaseCoversTasksAndExternalConfirmations() = runTest {
        val (c, _) = controller()
        val h = c.submit(TaskRequest("ask", Source.CHAT))
        runCurrent()
        assertNull(ExecutionLease.direct { "must not execute" })
        c.cancel(h.id); h.await(); runCurrent()
        assertEquals("free", ExecutionLease.direct { "free" })
        val external = async { c.confirmExternalAction("shell command") }
        runCurrent()
        assertNull(ExecutionLease.direct { "must not execute" })
        c.answer("approve")
        runCurrent()
        assertFalse(external.isCompleted)
        val current = c.state.value.current!!
        c.answerFromUi("Approve", current.id, current.question!!.text)
        assertTrue(external.await())
        runCurrent()
        assertEquals("free", ExecutionLease.direct { "free" })
    }
    @Test fun enqueueCannotOvertakeAnIdleCallback() = runTest {
        val idleEntered = CountDownLatch(1)
        val releaseIdle = CountDownLatch(1)
        val submitted = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val llm = ScriptedLlm(script(text("done"), call("ask_user", """{"question":"wait?"}""")))
        val env = FakeEnv(this, llm)
        val changes = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val wrapped = object : AgentEnvironment by env {
            override fun onBusyChanged(busy: Boolean) {
                changes += busy
                if (!busy && idleEntered.count > 0) {
                    idleEntered.countDown()
                    check(releaseIdle.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val c = AgentControllerImpl(wrapped, Dispatchers.Default)
        val first = c.submit(TaskRequest("first", Source.CHAT))
        first.await()
        assertTrue(idleEntered.await(5, TimeUnit.SECONDS))
        val thread = Thread {
            try { c.submit(TaskRequest("second", Source.CHAT)) }
            catch (t: Throwable) { failure.set(t) }
            finally { submitted.countDown() }
        }
        try {
            thread.start()
            assertFalse("enqueue overtook unfinished idle callback", submitted.await(100, TimeUnit.MILLISECONDS))
        } finally { releaseIdle.countDown() }
        assertTrue(submitted.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        assertEquals(true, changes.last())
        c.cancelAll()
        thread.join(5_000)
    }
}
