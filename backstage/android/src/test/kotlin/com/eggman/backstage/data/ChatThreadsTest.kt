package com.eggman.backstage.data

import com.eggman.backstage.agent.AgentControllerImpl
import com.eggman.backstage.agent.FakeEnv
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.llm.Role
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.ScriptedLlm.Companion.text
import com.eggman.backstage.testutil.script
import com.eggman.backstage.ui.chat.ChatThreadTexts
import com.eggman.backstage.ui.chat.Transcript
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

/** chat threads: create, switch, continue with context, persist. */
class ChatThreadsTest {
    private var clock = 1_000L
    private fun store(file: File? = null, max: Int = 100) = FileChatThreadStore(file, { clock++ }, { max })

    private fun exchange(s: ChatThreadStore, id: String, task: String, msg: String, result: String) =
        s.appendTask(id, task, listOf(ThreadEntry("user", msg, clock, source = "CHAT"), ThreadEntry("action", "tap Send", step = 1),
            ThreadEntry("result", result)), 1, "desktop", clock++)

    @Test fun createSwitchRenameDelete() {
        val s = store()
        val first = s.currentId.value
        assertTrue(s.threads.value.isEmpty()) // an empty thread is not listed
        assertEquals(first, s.newThread()) // the empty current thread is reused
        exchange(s, first, "t1", "Open the browser", "Opened Samsung Internet.")
        val second = s.newThread()
        assertNotEquals(first, second)
        exchange(s, second, "t2", "Set a timer", "Timer set.")
        assertEquals(listOf(second, first), s.threads.value.map { it.id }) // newest first
        val t = s.get(first)!!
        assertEquals("Open the browser", t.displayTitle)
        assertEquals(1, t.messageCount)
        assertEquals(1, t.steps)
        assertEquals("desktop", t.target)
        assertTrue(ChatThreadTexts.subtitle(t, ZoneId.of("UTC")).contains("DeX desktop · 1 message · 1 step"))
        assertTrue(s.select(first))
        assertEquals(first, s.currentId.value)
        s.rename(first, "  Browser   test ")
        assertEquals("Browser test", s.get(first)!!.displayTitle)
        assertTrue(ChatThreadTexts.matches(s.get(second)!!, "timer"))
        s.delete(first)
        assertNull(s.get(first))
        assertNotEquals(first, s.currentId.value) // deleting the current thread starts a new one
        // The same task is never recorded twice.
        exchange(s, second, "t2", "Set a timer", "Timer set.")
        assertEquals(1, s.get(second)!!.messageCount)
    }

    @Test fun threadsSurviveProcessDeathAndAreLimited() {
        val file = Files.createTempDirectory("threads").resolve("chat_threads.json").toFile()
        val a = store(file, max = 5)
        val ids = (1..7).map { i -> a.newThread().also { exchange(a, it, "t$i", "message $i", "result $i") } }
        a.select(ids[3])
        a.rename(ids[3], "Kept")
        // A new process reads the same file.
        val b = store(file, max = 5)
        assertEquals(ids[3], b.currentId.value)
        assertEquals("Kept", b.get(ids[3])!!.displayTitle)
        assertEquals(5, b.threads.value.size) // the least recently active threads went first
        assertNull(b.get(ids[0]))
        assertEquals(listOf("user", "action", "result"), b.get(ids[6])!!.entries.map { it.kind })
        // The transcript shown when it is reopened.
        val tr = Transcript.fromThread(b.get(ids[6]))
        assertEquals(listOf(Transcript.Kind.USER, Transcript.Kind.ACTION, Transcript.Kind.RESULT), tr.entries.map { it.kind })
    }

    @Test fun seedAlternatesAndStartsWithTheUser() {
        val t = ChatThread("x", entries = listOf(
            ThreadEntry("result", "orphan"),
            ThreadEntry("user", "a"), ThreadEntry("question", "which one?"), ThreadEntry("answer", "the red one"),
            ThreadEntry("result", "done"), ThreadEntry("user", "b"), ThreadEntry("error", "no network"),
        ))
        val m = ThreadContext.seedMessages(t)
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), m.map { it.role })
        assertEquals("a", m[0].text)
        assertTrue(m[3].text.contains("done"))
        assertTrue(m[5].text.contains("no network"))
    }

    /** Open an old thread, send: the message joins that thread and the agent reads its history. */
    @Test fun reopenedThreadContinuesWithItsHistory() = runTest {
        val llm = ScriptedLlm(script(text("Opened Samsung Internet."), text("Searched for weather."), text("Timer set."), text("Bookmarked it.")))
        val env = FakeEnv(this, llm)
        val s = store()
        env.threadStore = s
        val c = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        val old = s.currentId.value
        assertEquals(TaskStatus.SUCCEEDED, c.submit(TaskRequest("open the browser", Source.CHAT, attachments = mapOf("thread" to old))).await().status)
        c.submit(TaskRequest("search the weather", Source.CHAT, attachments = mapOf("thread" to old))).await()
        // A new chat: the agent does not see the old one.
        val fresh = s.newThread()
        c.submit(TaskRequest("set a timer", Source.POPUP_CHAT, attachments = mapOf("thread" to fresh))).await()
        assertEquals(1, llm.requests[2].messages.size)
        // Reopen the old thread and type: it continues with the old messages and results.
        assertTrue(s.select(old))
        val snap = c.submit(TaskRequest("bookmark that page", Source.CHAT, attachments = mapOf("thread" to old))).await()
        assertEquals(old, snap.request.attachments["thread"])
        val texts = llm.requests[3].messages.map { it.text }
        assertTrue(texts.first().startsWith("open the browser"))
        assertTrue(texts.any { it.contains("Opened Samsung Internet.") })
        assertTrue(texts.any { it.contains("search the weather") })
        assertTrue(texts.last().startsWith("bookmark that page"))
        assertFalse(texts.any { it.contains("set a timer") })
        assertFalse(texts.first().contains("[thread]")) // the control attachment is not shown to the model
        val thread = s.get(old)!!
        assertEquals(listOf("open the browser", "search the weather", "bookmark that page"), thread.entries.filter { it.kind == "user" }.map { it.text })
        assertEquals("Bookmarked it.", thread.entries.last().text)
        assertEquals(listOf("set a timer"), s.get(fresh)!!.entries.filter { it.kind == "user" }.map { it.text })
    }

    @Test fun voiceAndNotificationRepliesJoinTheCurrentThread() = runTest {
        val llm = ScriptedLlm(script(text("one"), text("two")))
        val env = FakeEnv(this, llm)
        val s = store()
        env.threadStore = s
        val c = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        c.submit(TaskRequest("first", Source.VOICE)).await()
        val snap = c.submit(TaskRequest("second", Source.NOTIFICATION)).await()
        assertEquals(s.currentId.value, snap.request.attachments["thread"])
        assertEquals(2, s.get(s.currentId.value)!!.messageCount)
        assertEquals(3, llm.requests[1].messages.size) // first, its result, second
        // Automation never joins a chat thread.
        c.submit(TaskRequest("third", Source.TASKER, interactive = false)).await()
        assertEquals(2, s.get(s.currentId.value)!!.messageCount)
    }
}
