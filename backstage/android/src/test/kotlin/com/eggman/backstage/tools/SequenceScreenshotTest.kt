package com.eggman.backstage.tools

import com.eggman.backstage.agent.*
import com.eggman.backstage.exec.*
import com.eggman.backstage.llm.*
import com.eggman.backstage.omp.engine.OmpToolPolicy
import com.eggman.backstage.testutil.*
import com.eggman.backstage.testutil.ScriptedLlm.Companion.call
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SequenceScreenshotTest {
    private class Ctx(val exec: FakeExecutor, override val hooks: AgentHooks?) : ToolContext by TestToolContext(), PeekableContext {
        override val visionEnabled = true
        override val autoScreenshot = true
        override val stabilityMs = 0L
        override val currentTarget = TargetKind.PHONE
        override suspend fun executor(kind: TargetKind?) = exec
        override fun peek(kind: TargetKind) = exec
    }
    private fun config() = LoopConfig("SYS", 20, false, 1000, RetryPolicy(1), SensitiveActionGuard(false, false),
        ToolRegistry.Availability(desktop = false))
    private val sequence = """{"steps":[{"tool":"tap","args":{"x":10,"y":10}}]}"""

    @Test fun nativeSequenceAttachesScreenshotForItsFinalSparseScreen() = runTest {
        val exec = FakeExecutor().apply { capture = Screens.app() }
        val llm = ScriptedLlm(script(call("act_sequence", sequence), call("finish", """{"summary":"done"}""")))
        val loop = AgentLoop(llm, ToolRegistry.default(), config(), AgentLoopTest.User(), { Ctx(exec, it) })
        val messages = mutableListOf(Message.user("tap"))
        loop.run(messages)
        val result = messages.flatMap { it.toolResults }.single { it.name == "act_sequence" }
        assertNotNull(result.image)
        assertTrue(result.text.contains("no accessible elements"))
    }

    @Test fun ompSequenceAttachesScreenshotForItsFinalSparseScreen() = runTest {
        val exec = FakeExecutor().apply { capture = Screens.app() }
        val policy = OmpToolPolicy(ToolRegistry.default(), config(), AgentLoopTest.User(), { Ctx(exec, it) }, object : LoopListener {})
        val result = policy.execute(Part.ToolCall("sequence", "act_sequence", JSONObject(sequence)))
        assertNotNull(result.image)
        assertTrue(result.text.contains("no accessible elements"))
    }
}
