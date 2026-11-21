package com.eggman.backstage.omp

import com.eggman.backstage.tools.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OmpToolsTest {
    private var auto = false
    private var calls = 0
    private fun tool(missing: String? = null) = OmpSubagentTool(
        autoApprove = { auto }, unavailable = { missing },
        start = { _, _, _, wait -> calls++; JSONObject().put("job_id", "abcd1234").put("wait", wait) },
        defaultModel = { "openai/test" },
    )
    private fun run(t: Tool, hooks: AgentHooks? = null) = runBlocking {
        t.run(TestToolContext(hooks = hooks), Args(JSONObject().put("task", "Inspect the device").put("wait_seconds", 999)))
    }
    @Test fun noHooksAndDenialNeverStartJob() {
        assertTrue(run(tool()).isError)
        val hooks = RecordingHooks(approve = false)
        assertTrue(run(tool(), hooks).isError)
        assertTrue(hooks.asked.single().contains("as shell"))
        assertEquals(0, calls)
    }
    @Test fun approvalAndExplicitAutoAllowJobAndClampWait() {
        assertFalse(run(tool(), RecordingHooks()).isError)
        val t = tool()
        auto = true
        val deny = RecordingHooks(approve = false)
        assertEquals(600, run(t, deny).data!!.getInt("wait"))
        assertTrue(deny.asked.isEmpty())
        auto = false
        assertTrue(run(t).isError)
        assertEquals(2, calls)
    }
    @Test fun approvalUsesConfirmAlwaysEvenWhenOrdinaryConfirmationWouldAllow() {
        val hooks = object : AgentHooks {
            var asked = false
            override fun onPlan(steps: List<com.eggman.backstage.agent.api.PlanStep>) = Unit
            override suspend fun ask(question: String, choices: List<String>): String? = null
            override suspend fun confirm(action: String): Boolean = error("Must use confirmAlways")
            override suspend fun confirmAlways(action: String): Boolean { asked = true; return false }
            override fun onFinish(summary: String, success: Boolean) = Unit
        }
        assertTrue(run(tool(), hooks).isError)
        assertTrue(hooks.asked)
        assertEquals(0, calls)
    }
    @Test fun unavailableNeverAsksAndAllToolsMutate() {
        val hooks = RecordingHooks()
        assertTrue(run(tool("Install OMP first"), hooks).text.contains("Install OMP"))
        assertTrue(hooks.asked.isEmpty())
        val registry = ToolRegistry.default()
        listOf("omp_subagent", "omp_subagent_status", "omp_subagent_cancel").forEach { assertTrue(registry[it]!!.mutating) }
        assertFalse(registry.forAgent(ToolRegistry.Availability(false, overlay = false)).any { it.name.startsWith("omp_") })
    }
}
