package com.eggman.backstage.omp.engine

import com.eggman.backstage.agent.*
import com.eggman.backstage.llm.Part
import com.eggman.backstage.tools.ToolResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OmpEventsTest {
    @Test fun deltasToolStepsAndTerminalMessagesMapToTheExistingListener() {
        val texts = mutableListOf<String>(); val starts = mutableListOf<String>(); val results = mutableListOf<Boolean>()
        val mapper = OmpEventMapper(object : LoopListener {
            override fun onAssistantText(text: String, final: Boolean) { texts += text }
            override fun onToolCall(call: Part.ToolCall) { starts += call.name }
            override fun onToolResult(call: Part.ToolCall, result: ToolResult) { results += result.isError }
        })
        mapper.accept(JSONObject("""{"type":"message_start","messageId":"m1","message":{"role":"assistant"}}"""))
        for (s in listOf("Hello", " world")) mapper.accept(JSONObject().put("type", "message_update").put("messageId", "m1")
            .put("assistantMessageEvent", JSONObject().put("type", "text_delta").put("delta", s)))
        mapper.accept(JSONObject("""{"type":"tool_execution_start","toolName":"bash","toolCallId":"t1","args":{"command":"ls"}}"""))
        mapper.accept(JSONObject("""{"type":"tool_execution_end","toolName":"bash","toolCallId":"t1","result":{"content":[{"type":"text","text":"done"}]},"isError":false}"""))
        mapper.accept(JSONObject("""{"type":"agent_end","messages":[],"isTerminal":true,"yielded":true}"""))
        assertEquals(listOf("Hello", "Hello world"), texts)
        assertEquals(listOf("bash"), starts); assertEquals(listOf(false), results)
        assertEquals("Hello world", mapper.answer)
        assertEquals(EndReason.ANSWERED, mapper.outcome().reason)
    }
    @Test fun providerErrorsAndNonYieldEndsCannotBecomeSuccess() {
        val mapper = OmpEventMapper(object : LoopListener {})
        mapper.accept(JSONObject("""{"type":"agent_end","yielded":false,"messages":[]}"""))
        assertFalse(mapper.yielded)
        mapper.accept(JSONObject("""{"type":"message_end","message":{"role":"assistant","stopReason":"error","errorMessage":"provider offline","content":[]}}"""))
        assertFalse(mapper.outcome().success)
    }
}
