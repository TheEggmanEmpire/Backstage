package com.eggman.backstage.agent

import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.llm.*
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.script
import com.eggman.backstage.tools.*
import com.eggman.backstage.exec.TargetKind
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentLoopLimitRegressionTest {
    private val user = object : UserChannel {
        override suspend fun ask(question: String, choices: List<String>): String? = null
        override suspend fun confirm(action: String) = true
        override fun planChanged(steps: List<PlanStep>) = Unit
    }

    @Test fun exhaustedBudgetPairsEveryCallAndLeavesTheChatReusable() = runBlocking {
        var executions = 0
        val mutate = object : Tool {
            override val name = "mutate"
            override val description = "A counted side effect"
            override val schema = Schema.obj()
            override val targets = emptySet<TargetKind>()
            override val mutating = true
            override suspend fun run(ctx: ToolContext, args: Args) = ToolResult("changed").also { executions++ }
        }
        val initial = ScriptedLlm.call("mutate")
        val exhausted = ScriptedLlm.calls(listOf("mutate" to "{}", "mutate" to "{}"))
        val llm = ScriptedLlm(script(initial, exhausted))
        val registry = ToolRegistry(listOf(mutate))
        val config = LoopConfig("SYS", 1, false, 1000, RetryPolicy(maxAttempts = 1),
            SensitiveActionGuard(enabled = false, preAuthorized = false),
            ToolRegistry.Availability(desktop = false, overlay = false, scheduler = false))
        fun loop(client: LlmClient) = AgentLoop(client, registry, config, user, { TestToolContext(hooks = it) })
        val conversation = mutableListOf(Message.user("perform two actions"))
        assertEquals(EndReason.STEP_LIMIT, loop(llm).run(conversation).reason)
        assertEquals(1, executions)
        val results = conversation.flatMap { it.toolResults }
        assertEquals(listOf(initial.message.toolCalls.single().id) + exhausted.message.toolCalls.map { it.id }, results.map { it.callId })
        assertTrue(results.takeLast(2).all { it.isError })
        assertTrue(results[1].text.contains("safety limit"))
        assertTrue(results[2].text.contains("Skipped"))
        conversation += Message.user("what happened?")
        val followup = object : LlmClient {
            override val provider = llm.provider
            override val model = "strict-call-result-provider"
            override suspend fun complete(request: LlmRequest): LlmReply {
                val callIds = request.messages.flatMap { it.toolCalls }.map { it.id }
                val resultIds = request.messages.flatMap { it.toolResults }.map { it.callId }
                assertEquals("Every stored call must have exactly one result", callIds, resultIds)
                return ScriptedLlm.text("one action completed")
            }
        }
        assertEquals(EndReason.ANSWERED, loop(followup).run(conversation).reason)
    }
}
