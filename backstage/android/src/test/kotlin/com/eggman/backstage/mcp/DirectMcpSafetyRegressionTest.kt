package com.eggman.backstage.mcp

import com.eggman.backstage.agent.*
import com.eggman.backstage.agent.api.*
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.script
import com.eggman.backstage.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectMcpSafetyRegressionTest {
    // Keep the published controller state idle: the lease alone must stop calls during task startup.
    private val idle = object : AgentController {
        override val state = MutableStateFlow(AgentState())
        override fun submit(request: TaskRequest): TaskHandle = error("not used")
        override fun answer(text: String) = Unit
        override fun cancel(taskId: String?) = Unit
        override fun readiness() = Readiness()
        override suspend fun history(limit: Int) = emptyList<TaskSnapshot>()
    }
    private fun TestScope.env() = FakeEnv(this, ScriptedLlm(script(ScriptedLlm.text("done"))))
    private fun tools(env: AgentEnvironment) = BackstageMcpTools({ idle }, env)
    private fun tap(target: String? = null) = JSONObject().put("x", 10).put("y", 20).apply { target?.let { put("target", it) } }
    private fun assertBusy(result: McpCallResult) {
        assertTrue(result.isError)
        assertEquals("Backstage is busy with a task or another MCP tool call; try again when it is done", result.content.single().text)
    }

    @Test fun phoneTapIsRefusedForExplicitTargetAndImplicitFallbackWithoutAnyInput() = runTest {
        val env = env()
        env.settings.externalPhoneControl = false
        val tools = tools(env)
        for (target in listOf("phone", null)) {
            val result = tools.call("tap", tap(target))
            assertTrue(result.isError)
            assertTrue(result.content.single().text!!, result.content.single().text!!.contains("Remote callers may not control the phone screen"))
            assertTrue("No input may precede refusal", env.phone.actions.isEmpty())
        }
        env.settings.externalPhoneControl = true
        val context = McpToolContext(env)
        assertSame(env.phone, context.executor(TargetKind.PHONE))
        // Gate is checked again even when the context already resolved to the phone.
        env.settings.externalPhoneControl = false
        val result = env.registry.execute(context, "tap", tap())
        assertTrue(result.isError)
        assertTrue(env.phone.actions.isEmpty())
    }

    @Test fun runningDesktopStillResolvesWhenExternalPhoneAccessIsDisabled() = runTest {
        val env = env()
        env.settings.externalPhoneControl = false
        env.hostPresent = true
        env.desktopActive = true
        val context = McpToolContext(env)
        context.executor()
        assertEquals(TargetKind.DESKTOP, context.currentTarget)
    }

    @Test fun directCallRefusesAgentLeaseEvenBeforeBusyStateIsPublished() = runTest {
        val env = env()
        val tools = tools(env)
        ExecutionLease.agent {
            assertFalse(idle.state.value.busy)
            assertBusy(tools.call("tap", tap("phone")))
            assertTrue(env.phone.actions.isEmpty())
            // This observation does not replace the agent's listing and is allowed during a task.
            assertFalse(tools.call("get_screen_state", JSONObject()).isError)
        }
    }

    @Test fun concurrentDirectCallIsRefusedAndAgentWaitsForTheFirstCall() = runTest {
        val base = env()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val blocking = object : Tool {
            override val name = "blocking_input"
            override val description = "Suspending side effect"
            override val schema = Schema.obj()
            override val targets = emptySet<TargetKind>()
            override val mutating = true
            override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
                runs++
                entered.complete(Unit)
                release.await()
                return ToolResult("completed")
            }
        }
        val env = object : AgentEnvironment by base { override val registry = ToolRegistry(listOf(blocking)) }
        val tools = tools(env)
        val first = async { tools.call("blocking_input", JSONObject()) }
        entered.await()
        assertBusy(tools.call("blocking_input", JSONObject()))
        assertEquals(1, runs)
        var agentStarted = false
        val agent = async { ExecutionLease.agent { agentStarted = true } }
        runCurrent()
        assertFalse(agentStarted)
        release.complete(Unit)
        assertFalse(first.await().isError)
        agent.await()
        assertTrue(agentStarted)
        assertEquals(1, runs)
    }

    @Test fun cancelledDirectCallReleasesTheLease() = runTest {
        val base = env()
        val entered = CompletableDeferred<Unit>()
        val blocking = object : Tool {
            override val name = "blocking_input"
            override val description = "Suspending side effect"
            override val schema = Schema.obj()
            override val targets = emptySet<TargetKind>()
            override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val env = object : AgentEnvironment by base { override val registry = ToolRegistry(listOf(blocking)) }
        val pending = launch { tools(env).call("blocking_input", JSONObject()) }
        entered.await()
        pending.cancelAndJoin()
        assertEquals("free", ExecutionLease.direct { "free" })
    }
}
