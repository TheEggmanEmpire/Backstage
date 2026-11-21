package com.eggman.backstage.omp.engine

import com.eggman.backstage.agent.LoopListener
import com.eggman.backstage.llm.Part
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OmpTranscriptTest {
    private var tick = 0L
    private fun mapper(listener: LoopListener) = OmpEventMapper(listener, clockMs = { tick += 50; tick })
    private fun update(type: String, content: String = "", index: Int = 0) = JSONObject().put("type", "message_update").put("messageId", "m1")
        .put("assistantMessageEvent", JSONObject().put("type", type).put("contentIndex", index)
            .put(if (type.endsWith("_end")) "content" else "delta", content))

    @Test fun thinkingStreamsAndEndIsAuthoritativeAndBounded() {
        val seen = mutableListOf<Pair<String, Boolean>>()
        val mapper = mapper(object : LoopListener {
            override fun onThinking(text: String, streaming: Boolean) { seen += text to streaming }
        })
        mapper.accept(update("thinking_start"))
        mapper.accept(update("thinking_delta", "Check "))
        mapper.accept(update("thinking_delta", "files"))
        assertEquals("Check files" to true, seen.last())
        mapper.accept(update("thinking_end", "Checked files"))
        assertEquals("Checked files" to false, seen.last())
        mapper.accept(update("thinking_delta", "界".repeat(9000), 1))
        assertTrue(seen.last().first.toByteArray(Charsets.UTF_8).size <= 8192)
        assertTrue(seen.last().first.endsWith("界"))
    }

    @Test fun redactedAndSignatureOnlyThinkingNeverExposeProviderData() {
        val seen = mutableListOf<String>()
        val mapper = mapper(object : LoopListener {
            override fun onThinking(text: String, streaming: Boolean) { seen += text }
        })
        mapper.accept(JSONObject("""{"type":"message_end","messageId":"m1","message":{"role":"assistant","content":[{"type":"redactedThinking","data":"SECRET"}]}}"""))
        assertEquals("Thinking (hidden by provider)", seen.last())
        mapper.accept(JSONObject("""{"type":"message_end","messageId":"m2","message":{"role":"assistant","content":[{"type":"thinking","thinking":"","thinkingSignature":"ENCRYPTED"}]}}"""))
        assertEquals("Thinking (hidden by provider)", seen.last())
        assertFalse(seen.any { it.contains("SECRET") || it.contains("ENCRYPTED") })
    }

    @Test fun toolKindsSummarizeTheRelevantArgumentsOnOneLine() {
        val cases = listOf(
            "bash" to """{"command":"ls\n-p","reason":"wrong"}""",
            "read" to """{"path":"a.kt"}""", "edit" to """{"path":"a.kt","old_text":"wrong"}""",
            "write" to """{"path":"a.kt","content":"wrong"}""",
            "grep" to """{"pattern":"needle","path":"wrong"}""", "find" to """{"pattern":"*.kt"}""",
            "search" to """{"pattern":"query"}""", "task" to """{"agent":"worker","description":"Check files"}""",
            "subagent" to """{"agent":"worker","task":"Check files"}""", "custom" to """{"count":1,"value":"hello"}"""
        )
        val expected = listOf("bash: ls -p", "read: a.kt", "edit: a.kt", "write: a.kt", "grep: needle", "find: *.kt",
            "search: query", "task: worker · Check files", "subagent: worker · Check files", "custom")
        assertEquals(expected, cases.map { (name, args) -> OmpEventMapper.toolSummary(name, JSONObject(args)) })
        assertTrue(OmpEventMapper.toolSummary("bash", JSONObject().put("command", "x".repeat(1000))).length <= 240)
    }

    @Test fun finalAnswerIsLastMessageAndCallbacksPreserveEveryMessageOnce() {
        val finished = mutableListOf<String>()
        val mapper = mapper(object : LoopListener {
            override fun onAssistantText(text: String, final: Boolean) { if (final) finished += text }
        })
        val first = JSONObject("""{"role":"assistant","content":[{"type":"text","text":"First"}]}""")
        val last = JSONObject("""{"role":"assistant","content":[{"type":"text","text":"Final"}]}""")
        mapper.accept(JSONObject().put("type", "message_end").put("messageId", "m1").put("message", first))
        mapper.accept(JSONObject("""{"type":"tool_execution_start","toolName":"read","toolCallId":"t","args":{"path":"a"}}"""))
        mapper.accept(JSONObject().put("type", "message_end").put("messageId", "m2").put("message", last))
        mapper.accept(JSONObject().put("type", "agent_end").put("messages", org.json.JSONArray().put(first).put(last)))
        assertEquals(listOf("First", "Final"), finished)
        assertEquals("Final", mapper.outcome().summary)
    }

    @Test fun textBlocksAreAuthoritativeAndNarrationIsVisibleAnswerText() {
        val texts = mutableListOf<String>()
        val mapper = mapper(object : LoopListener { override fun onAssistantText(text: String, final: Boolean) { texts += text } })
        mapper.accept(update("text_delta", "draft"))
        mapper.accept(update("text_end", "corrected"))
        mapper.accept(update("text_start", index = 1))
        mapper.accept(update("text_delta", " second", 1))
        assertEquals("corrected second", mapper.answer)
        mapper.accept(JSONObject("""{"type":"message_end","messageId":"m1","message":{"role":"assistant","content":[{"type":"thinking","thinking":"Narration","narration":true},{"type":"text","text":"Answer"}]}}"""))
        assertEquals("Narration\nAnswer", texts.last())
    }

    @Test fun subagentProgressUpdatesOneStepWithDescriptionAndStatus() {
        val started = mutableListOf<String>(); val progress = mutableListOf<String>()
        val mapper = mapper(object : LoopListener {
            override fun onToolCall(call: Part.ToolCall, summary: String) { started += summary }
            override fun onToolProgress(call: Part.ToolCall, status: String) { progress += status }
        })
        mapper.accept(JSONObject("""{"type":"subagent_lifecycle","payload":{"id":"a","agent":"worker","index":0,"description":"Check files","status":"started"}}"""))
        mapper.accept(JSONObject("""{"type":"subagent_progress","payload":{"agent":"worker","index":0,"task":"Check files","progress":{"id":"a","status":"running"}}}"""))
        assertEquals(listOf("subagent: worker · Check files"), started)
        assertEquals(listOf("started", "running"), progress)
    }
    @Test fun overlappingSubagentsWithTheSameIndexKeepIndependentIdsAndResults() {
        val starts = mutableListOf<String>(); val updates = mutableListOf<Pair<String, String>>()
        val results = mutableListOf<Pair<String, String>>()
        val mapper = mapper(object : LoopListener {
            override fun onToolCall(call: Part.ToolCall, summary: String) { starts += call.id }
            override fun onToolProgress(call: Part.ToolCall, status: String) { updates += call.id to status }
            override fun onToolResult(call: Part.ToolCall, result: com.eggman.backstage.tools.ToolResult) { results += call.id to result.text }
        })
        fun lifecycle(id: String, status: String) = mapper.accept(JSONObject().put("type", "subagent_lifecycle")
            .put("payload", JSONObject().put("id", id).put("agent", "worker").put("index", 0).put("description", "Task $id").put("status", status)))
        fun progress(id: String, status: String) = mapper.accept(JSONObject().put("type", "subagent_progress")
            .put("payload", JSONObject().put("agent", "worker").put("index", 0).put("task", "Task $id")
                .put("progress", JSONObject().put("id", id).put("status", status).put("recentOutput", org.json.JSONArray().put("Output $id")))))
        lifecycle("a", "started"); lifecycle("b", "started")
        progress("b", "running"); progress("a", "running")
        progress("b", "completed"); lifecycle("b", "completed")
        progress("a", "failed"); lifecycle("a", "failed")
        assertEquals(listOf("subagent:a", "subagent:b"), starts)
        assertTrue(updates.contains("subagent:a" to "running"))
        assertTrue(updates.contains("subagent:b" to "running"))
        assertEquals(listOf("subagent:b" to "Output b", "subagent:a" to "Output a"), results)
    }

    @Test fun aLargeAnswerUsesBoundedPublicationsAndEndsWithTheExactText() {
        var now = 0L
        val texts = mutableListOf<String>()
        val mapper = OmpEventMapper(object : LoopListener {
            override fun onAssistantText(text: String, final: Boolean) { texts += text }
        }, clockMs = { now })
        val expected = "abcdefghij".repeat(20_480)
        expected.chunked(10).forEach { chunk -> now++; mapper.accept(update("text_delta", chunk)) }
        mapper.accept(update("text_end", expected))
        mapper.accept(JSONObject().put("type", "message_end").put("messageId", "m1")
            .put("message", JSONObject().put("role", "assistant").put("content", org.json.JSONArray()
                .put(JSONObject().put("type", "text").put("text", expected)))))
        assertEquals(expected, mapper.answer)
        assertEquals(expected, texts.last())
        assertTrue("Streaming published too often", texts.size <= 414)
        assertTrue(texts.size > 1)
    }

}
