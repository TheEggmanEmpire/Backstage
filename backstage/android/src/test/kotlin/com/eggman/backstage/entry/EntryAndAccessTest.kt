package com.eggman.backstage.entry

import com.eggman.backstage.agent.api.AgentController
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.Readiness
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskHandle
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.entry.EntryRouter.Action
import com.eggman.backstage.entry.EntryRouter.Incoming
import com.eggman.backstage.entry.EntryRouter.Screen
import com.eggman.backstage.ui.Fixtures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryAndAccessTest {

    @Test fun assistAndVoiceCommandArmMicrophone() {
        assertEquals(Action.VoiceDraft, EntryRouter.route(Incoming(EntryRouter.ACTION_ASSIST)))
        assertEquals(Action.VoiceDraft, EntryRouter.route(Incoming(EntryRouter.ACTION_VOICE_COMMAND)))
    }

    @Test fun shareTextPrefillsWithAttachment() {
        val a = EntryRouter.route(Incoming(EntryRouter.ACTION_SEND, sendText = "https://x.y/article", sendSubject = "Read this"))
        a as Action.Prefill
        assertTrue(a.text.endsWith("Read this\nhttps://x.y/article"))
        assertEquals("shared_text" to "Read this\nhttps://x.y/article", a.attachment)
        assertEquals(Action.None, EntryRouter.route(Incoming(EntryRouter.ACTION_SEND, sendText = "  ")))
    }

    @Test fun processTextPrefillsQuoted() {
        val a = EntryRouter.route(Incoming(EntryRouter.ACTION_PROCESS_TEXT, processText = " Bonjour ")) as Action.Prefill
        assertEquals("“Bonjour”\n", a.text)
        val long = EntryRouter.route(Incoming(EntryRouter.ACTION_PROCESS_TEXT, processText = "x".repeat(10_000))) as Action.Prefill
        assertTrue(long.text.length < EntryRouter.MAX_PREFILL + 10)
    }

    @Test fun deepLinksAreUiOnly() {
        assertEquals(Action.Open(Screen.CHAT), EntryRouter.routeDeepLink("backstage://agent"))
        assertEquals(Action.Open(Screen.SETTINGS), EntryRouter.routeDeepLink("backstage://agent/settings"))
        assertEquals(Action.Open(Screen.DESKTOP), EntryRouter.routeDeepLink("backstage://agent/Desktop/"))
        assertEquals(Action.VoiceDraft, EntryRouter.routeDeepLink("backstage://agent/voice")) // external: no auto-submit
        assertEquals(Action.Prefill("Open maps & go"), EntryRouter.routeDeepLink("backstage://agent?prompt=Open+maps+%26+go"))
        assertEquals(Action.Open(Screen.CHAT), EntryRouter.routeDeepLink("backstage://agent/unknown"))
        assertEquals(Action.None, EntryRouter.routeDeepLink("https://evil.example/agent"))
        assertEquals(Action.Open(Screen.CHAT), EntryRouter.route(Incoming(EntryRouter.ACTION_VIEW, dataUri = null)))
    }

    @Test fun internalExtras() {
        assertEquals(Action.Voice, EntryRouter.route(Incoming(null, voiceExtra = true, internal = true)))
        assertEquals(Action.Open(Screen.SCHEDULES), EntryRouter.route(Incoming(EntryRouter.ACTION_MAIN, screenExtra = "schedules", internal = true)))
        assertEquals(Action.None, EntryRouter.route(Incoming(EntryRouter.ACTION_MAIN)))
    }

    private class FakeController : AgentController {
        val submitted = mutableListOf<TaskRequest>()
        val cancelled = mutableListOf<String?>()
        val answers = mutableListOf<String>()
        override val state: StateFlow<AgentState> = MutableStateFlow(AgentState(current = Fixtures.snapshot()))
        override fun submit(request: TaskRequest): TaskHandle {
            submitted += request
            return object : TaskHandle {
                override val id = "id${submitted.size}"
                override suspend fun await(): TaskSnapshot = Fixtures.snapshot(id = id)
            }
        }
        override fun answer(text: String) { answers += text }
        override fun cancel(taskId: String?) { cancelled += taskId }
        override fun readiness() = Readiness()
        override suspend fun history(limit: Int) = emptyList<TaskSnapshot>()
    }

    @After fun reset() { AgentAccess.override = null }

    @Test fun accessWithoutCoreIsNullSafe() {
        AgentAccess.override = null
        // Agents.controller is never installed in unit tests.
        assertNull(AgentAccess.controller())
        assertNull(AgentAccess.submit(TaskRequest("x", Source.CHAT)))
        assertNull(AgentAccess.state().value.current)
        AgentAccess.cancel(); AgentAccess.answer("x") // no crash
    }

    @Test fun accessDelegatesAndTrims() {
        val fake = FakeController()
        AgentAccess.override = fake
        assertNotNull(AgentAccess.submit(TaskRequest("  hello  ", Source.TILE)))
        assertNull(AgentAccess.submit(TaskRequest("   ", Source.TILE)))
        assertEquals(listOf("hello"), fake.submitted.map { it.prompt })
        AgentAccess.cancel("t"); AgentAccess.answer("yes")
        assertEquals(listOf<String?>("t"), fake.cancelled)
        assertEquals(listOf("yes"), fake.answers)
        assertEquals("t1", AgentAccess.state().value.current?.id)
    }
}
