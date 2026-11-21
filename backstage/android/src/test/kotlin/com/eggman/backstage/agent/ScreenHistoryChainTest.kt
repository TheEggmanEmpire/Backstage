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

class ScreenHistoryChainTest {
    private val serializer = ScreenSerializer()
    private fun full(label: String, target: String = "phone") = serializer.serialize(Screens.buttons(label), target).text
    private fun diff(label: String, target: String = "phone") = ScreenSerializer.withChanges(
        serializer.serialize(Screens.buttons(label), target), "~ [0] Button \"$label\" unchecked → checked")
    private fun append(messages: MutableList<Message>, payload: String) {
        val id = "c${messages.size}"
        messages += Message(Role.ASSISTANT, listOf(Part.ToolCall(id, "tap", JSONObject())))
        messages += Message(Role.USER, listOf(Part.ToolResult(id, "Tapped\n\n$payload", name = "tap")))
    }
    private fun payloads(messages: List<Message>) = messages.flatMap { it.parts }.mapNotNull {
        when (it) { is Part.Text -> it.text; is Part.ToolResult -> it.text; else -> null }
    }
    private fun assertLatestChain(messages: List<Message>, label: String, diffs: Int) {
        val payloads = payloads(messages)
        val full = payloads.indexOfLast { it.contains("Elements [index]") && it.contains(label) }
        assertTrue("latest full missing: $payloads", full >= 0)
        assertEquals(diffs, payloads.drop(full + 1).count { it.contains("Changes since the last listing") })
    }

    @Test fun oneFullAndFiveDiffsSurviveBatchPruning() {
        val conversation = mutableListOf(Message.user("task"))
        append(conversation, full("BASE"))
        repeat(5) { append(conversation, diff("change $it")) }
        assertLatestChain(ContextManager(keepScreens = 2, collapseBatch = 4).prepare(conversation), "BASE", 5)
    }

    @Test fun multipleChainsCountFullListingsAndCollapseOldChainAsAUnit() {
        val conversation = mutableListOf(Message.user("task"))
        append(conversation, full("OLD BASE"))
        repeat(3) { append(conversation, diff("old change $it")) }
        append(conversation, full("NEW BASE"))
        repeat(3) { append(conversation, diff("new change $it")) }
        assertLatestChain(ContextManager(keepScreens = 2, collapseBatch = 4).prepare(conversation), "NEW BASE", 3)
        val pruned = ContextManager(keepScreens = 1).prepare(conversation)
        assertLatestChain(pruned, "NEW BASE", 3)
        assertFalse(payloads(pruned).any { it.contains("old change") })
    }

    @Test fun budgetPassesKeepFullBaselineAndEveryDependentDiff() {
        val conversation = mutableListOf(Message.user("task"))
        append(conversation, full("BASE " + "x".repeat(80)))
        repeat(5) { append(conversation, diff("change $it " + "y".repeat(700))) }
        val prepared = ContextManager(budgetChars = 100).prepare(conversation)
        assertLatestChain(prepared, "BASE", 5)
        assertTrue(payloads(prepared).filter { it.contains("Changes since") }.all { it.contains("y".repeat(700)) })
    }

    @Test fun initialRequestBaselineCannotBeShortenedWhileDiffsRemain() {
        val conversation = mutableListOf(Message.user("Task\n" + full("BASE")))
        repeat(5) { append(conversation, diff("change $it")) }
        val prepared = ContextManager(keepScreens = 1, budgetChars = 50).prepare(conversation)
        assertLatestChain(prepared, "BASE", 5)
        assertNotNull(HistoryCompressor.splitPoint(conversation))
    }

    @Test fun compressionKeepsBaselineTextWithoutPinningTheTail() = runBlocking {
        val conversation = mutableListOf(Message.user("task"), Message.assistant("earlier"), Message.user("more context"))
        append(conversation, full("BASE"))
        repeat(5) { append(conversation, diff("change $it")) }
        val split = HistoryCompressor.splitPoint(conversation)
        assertNotNull(split)
        val llm = ScriptedLlm(script(text("earlier summary")))
        assertTrue(HistoryCompressor.compress(llm, conversation))
        assertLatestChain(conversation, "BASE", 5)
        assertEquals(conversation.flatMap { it.toolCalls }.map { it.id }.toSet(), conversation.flatMap { it.toolResults }.map { it.callId }.toSet())
    }

    @Test fun eachDisplayKeepsItsOwnFullBaselineUnderBudgetPressure() {
        val conversation = mutableListOf(Message.user("task"))
        append(conversation, full("PHONE", "phone"))
        append(conversation, full("DESKTOP", "desktop"))
        repeat(4) { append(conversation, diff("phone $it", "phone")); append(conversation, diff("desktop $it", "desktop")) }
        val prepared = ContextManager(keepScreens = 1, budgetChars = 50).prepare(conversation)
        assertTrue(payloads(prepared).any { it.contains("Elements [index]") && it.contains("PHONE") })
        assertTrue(payloads(prepared).any { it.contains("Elements [index]") && it.contains("DESKTOP") })
        assertEquals(8, payloads(prepared).count { it.contains("Changes since") })
    }

    @Test fun budgetMayCollapseStandaloneScreensWhenNoDependentDiffRemains() {
        val conversation = mutableListOf(Message.user("task"))
        append(conversation, full("OLD"))
        append(conversation, diff("old change"))
        append(conversation, full("NEW"))
        val prepared = ContextManager(keepScreens = 2, budgetChars = 50).prepare(conversation)
        assertTrue(ContextManager.protectedScreenParts(conversation).isEmpty())
        assertFalse(payloads(prepared).any { it.contains("Changes since") })
    }
}
