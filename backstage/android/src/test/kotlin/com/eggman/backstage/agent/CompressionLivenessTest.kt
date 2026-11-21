package com.eggman.backstage.agent

import com.eggman.backstage.exec.ScreenSerializer
import com.eggman.backstage.llm.Message
import com.eggman.backstage.llm.Part
import com.eggman.backstage.llm.Role
import com.eggman.backstage.testutil.Screens
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.ScriptedLlm.Companion.text
import com.eggman.backstage.testutil.script
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompressionLivenessTest {
    private val serializer = ScreenSerializer()
    private fun append(messages: MutableList<Message>, payload: String, name: String = "search") {
        val id = "call${messages.size}"
        messages += Message(Role.ASSISTANT, listOf(Part.ToolCall(id, name, JSONObject())))
        messages += Message(Role.USER, listOf(Part.ToolResult(id, payload, name = name)))
    }

    @Test fun initialStandaloneScreenDoesNotDisableCompression() = runBlocking {
        val screen = serializer.serialize(Screens.buttons("Initial screen"), "phone")
        val messages = mutableListOf(Message.user("Find information\nCurrent screen:\n${screen.text}"))
        repeat(20) { append(messages, "search result $it") }
        assertNotNull(HistoryCompressor.splitPoint(messages))
        val llm = ScriptedLlm(script(text("Research so far")))
        assertTrue(HistoryCompressor.compress(llm, messages))
        assertEquals(1, llm.requests.size)
    }

    @Test fun initialFullWithDiffsStillAllowsCompressionAndRetainsTheWholeChain() = runBlocking {
        val screen = serializer.serialize(Screens.buttons("Initial screen"), "phone")
        val messages = mutableListOf(Message.user("Task\nCurrent screen:\n${screen.text}"))
        repeat(5) { append(messages, ScreenSerializer.withChanges(screen, "~ [0] Text \"change $it\""), "tap") }
        val llm = ScriptedLlm(script(text("Earlier actions")))
        assertNotNull(HistoryCompressor.splitPoint(messages))
        assertTrue(HistoryCompressor.compress(llm, messages))
        val payload = messages.flatMap { it.parts }.mapNotNull { when (it) {
            is Part.Text -> it.text; is Part.ToolResult -> it.text; else -> null
        } }.joinToString("\n")
        assertTrue(payload.contains("Elements [index]"))
        repeat(5) { assertTrue(payload, payload.contains("change $it")) }
        assertEquals(messages.flatMap { it.toolCalls }.map { it.id }.toSet(), messages.flatMap { it.toolResults }.map { it.callId }.toSet())
    }

    @Test fun aLaterScreenChainCannotPinTwentyNonScreenTurnsInTheTail() = runBlocking {
        val screen = serializer.serialize(Screens.buttons("Baseline"), "phone")
        val messages = mutableListOf(Message.user("Task"), Message.assistant("Earlier answer"), Message.user("Continue"))
        append(messages, screen.text, "get_screen")
        append(messages, ScreenSerializer.withChanges(screen, "~ [0] Button \"Changed\""), "tap")
        repeat(20) { append(messages, "search result $it") }
        val previousSize = messages.size
        assertTrue(HistoryCompressor.compress(ScriptedLlm(script(text("Summary"))), messages))
        assertTrue(messages.size < previousSize / 2)
        val payload = messages.flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        assertTrue(payload.contains("Elements [index]"))
        assertTrue(payload.contains("Changes since"))
    }

    @Test fun repeatedCompressionKeepsOneCopyOfEveryRequiredScreenChange() = runBlocking {
        val screen = serializer.serialize(Screens.buttons("Baseline"), "phone")
        val messages = mutableListOf(Message.user("Task\n${screen.text}"))
        repeat(5) { append(messages, ScreenSerializer.withChanges(screen, "~ [0] Button \"change $it\""), "tap") }
        repeat(20) { append(messages, "search result $it") }
        val llm = ScriptedLlm(script(text("First summary"), text("Second summary")))
        assertTrue(HistoryCompressor.compress(llm, messages))
        repeat(5) { append(messages, "later search result $it") }
        assertTrue(HistoryCompressor.compress(llm, messages))
        val payload = messages.flatMap { it.parts }.mapNotNull { when (it) {
            is Part.Text -> it.text; is Part.ToolResult -> it.text; else -> null
        } }.joinToString("\n")
        repeat(5) { assertEquals(1, Regex("change $it").findAll(payload).count()) }
        assertEquals(1, Regex("Elements \\[index\\]").findAll(payload).count())
    }
}
