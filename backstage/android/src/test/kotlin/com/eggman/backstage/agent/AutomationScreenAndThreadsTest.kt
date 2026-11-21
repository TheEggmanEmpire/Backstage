package com.eggman.backstage.agent

import com.eggman.backstage.agent.api.Readiness
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.data.FileChatThreadStore
import com.eggman.backstage.data.ThreadEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationScreenAndThreadsTest {
    @Test fun automationsRestartARunningDesktopButChatsDoNot() {
        assertTrue(DesktopRefresh.wantedFor(TaskRequest("x", Source.SCHEDULE, interactive = false), enabled = true, sessionActive = true))
        assertTrue(DesktopRefresh.wantedFor(TaskRequest("x", Source.TASKER, interactive = false), enabled = true, sessionActive = true))
        assertFalse(DesktopRefresh.wantedFor(TaskRequest("x", Source.CHAT), enabled = true, sessionActive = true))
        assertFalse(DesktopRefresh.wantedFor(TaskRequest("x", Source.SCHEDULE, interactive = false), enabled = false, sessionActive = true))
        assertFalse(DesktopRefresh.wantedFor(TaskRequest("x", Source.SCHEDULE, interactive = false), enabled = true, sessionActive = false))
    }

    @Test fun lockAndScreenOffAreNotSetupProblems() {
        val r = Readiness(problems = listOf(ReadinessCheck.DEVICE_LOCKED, ReadinessCheck.SCREEN_OFF, "AI provider not configured: x"))
        assertEquals(listOf("AI provider not configured: x"), ReadinessCheck.setupProblems(r))
    }

    @Test fun automationThreadIsListedButDoesNotBecomeCurrent() {
        val store = FileChatThreadStore(null)
        val current = store.currentId.value
        val id = store.newAutomationThread("Scheduled: open the news", ThreadEntry("info", "Scheduled run started", 1))
        assertNotEquals(current, id)
        assertEquals(current, store.currentId.value)
        assertTrue(store.threads.value.any { it.id == id && it.displayTitle == "Scheduled: open the news" })
    }
}
