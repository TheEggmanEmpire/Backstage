package com.eggman.backstage.ui.chat

import com.eggman.backstage.agent.api.TaskChatLine
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.data.ChatThread
import com.eggman.backstage.data.ChatThreadCodec
import com.eggman.backstage.data.ThreadEntry
import com.eggman.backstage.ui.Fixtures.snapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OmpChatTranscriptTest {
    @Test fun conflatedSnapshotRetainsOrderedThinkingToolsAndMessagesWithoutDuplicates() {
        val lines = listOf(TaskChatLine(1, "assistant", "Checking", thinking = "Plan"),
            TaskChatLine(2, "action", "read: a.kt", detail = "tail", step = 1), TaskChatLine(3, "assistant", "Final"))
        val s = snapshot(status = TaskStatus.SUCCEEDED, result = "Final").copy(chatLines = lines, thinking = "Plan")
        val t = Transcript().onSnapshot(s).onSnapshot(s)
        assertEquals(listOf("Checking", "read: a.kt", "Final"), t.entries.drop(1).map { it.text })
        assertEquals("Plan", t.entries[1].thinking)
        assertFalse(t.entries[1].thinkingActive)
        assertEquals("tail", t.entries[2].detail)
    }

    @Test fun oldJsonHasNoThinkingAndNewFieldsRoundTrip() {
        val old = JSONObject("""{"current":"x","threads":[{"id":"x","entries":[{"k":"result","t":"old"}]}]}""")
        assertNull(ChatThreadCodec.decode(old).first.single().entries.single().thinking)
        val thread = ChatThread("x", entries = listOf(ThreadEntry("assistant", "answer", thinking = "reasoning"),
            ThreadEntry("action", "bash: ls", detail = "result tail")))
        val restored = ChatThreadCodec.decode(ChatThreadCodec.encode(listOf(thread), "x")).first.single()
        assertEquals(thread, restored)
        val entries = Transcript.fromThread(restored).entries
        assertEquals("reasoning", entries.first().thinking)
        assertFalse(entries.first().thinkingActive)
        assertEquals("result tail", entries.last().detail)
    }

    @Test fun nativeSnapshotsStillUseTheExistingActionAndResultPath() {
        val t = Transcript().onSnapshot(snapshot(lastAction = "Tap", step = 1))
            .onSnapshot(snapshot(status = TaskStatus.SUCCEEDED, lastAction = "Tap", step = 1, result = "Done"))
        assertEquals(listOf(Transcript.Kind.USER, Transcript.Kind.ACTION, Transcript.Kind.RESULT), t.entries.map { it.kind })
        assertTrue(t.entries.all { it.thinking == null && it.detail.isEmpty() })
    }
    @Test fun unchangedCompletedEntriesAreReusedAcrossStreamingUpdates() {
        val completed = TaskChatLine(1, "action", "read: a.kt", detail = "done", step = 1)
        val live = TaskChatLine(2, "assistant", "First")
        val initial = snapshot().copy(chatLines = listOf(completed, live))
        val before = Transcript().onSnapshot(initial)
        val after = before.onSnapshot(initial.copy(chatLines = listOf(completed, live.copy(text = "First second"))))
        assertSame(before.entries[1], after.entries[1])
        assertNotSame(before.entries[2], after.entries[2])
    }

}
