package com.eggman.backstage.surfaces

import android.content.pm.ServiceInfo
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.EntryRouter
import com.eggman.backstage.overlay.BubbleService
import com.eggman.backstage.ui.Fixtures.snapshot
import com.eggman.backstage.voice.SpeechCapture
import com.eggman.backstage.voice.Speaker
import com.eggman.backstage.voice.SpokenReplies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Pure decision functions behind the surfaces (JVM only, no Robolectric). */
class SurfacesLogicTest {

    @Test fun onlyOurOwnTileOrWidgetAutoSubmitsSpeech() {
        assertTrue(EntryRouter.autoSubmits(EntryRouter.Action.Voice))
        // ASSIST, VOICE_COMMAND, deep links: the permission grant in between does not change that.
        assertFalse(EntryRouter.autoSubmits(EntryRouter.Action.VoiceDraft))
        assertFalse(EntryRouter.autoSubmits(EntryRouter.Action.Open(EntryRouter.Screen.CHAT)))
        assertFalse(EntryRouter.autoSubmits(EntryRouter.route(EntryRouter.Incoming(EntryRouter.ACTION_ASSIST))))
        assertFalse(EntryRouter.autoSubmits(EntryRouter.routeDeepLink("backstage://agent/voice")))
    }

    @Test fun repliesAreSpokenForVoiceTasksOnceEach() {
        val done = snapshot(id = "a", status = TaskStatus.SUCCEEDED, source = Source.WAKE_WORD, result = "It is 21 degrees")
        val u = SpokenReplies.next(done, enabled = true, voiceTaskIds = emptySet(), spoken = emptySet())!!
        assertEquals("It is 21 degrees", u.text)
        assertNull(SpokenReplies.next(done, true, emptySet(), setOf(u.key)))       // read once
        assertNull(SpokenReplies.next(done, enabled = false, emptySet(), emptySet())) // setting off
        assertNull(SpokenReplies.next(done, true, emptySet(), emptySet(), locked = true)) // never aloud on a locked phone
        // A typed chat task stays silent, unless its prompt is dictated (marked by the surface).
        val chat = done.copy(request = done.request.copy(source = Source.CHAT))
        assertNull(SpokenReplies.next(chat, true, emptySet(), emptySet()))
        assertEquals(u.text, SpokenReplies.next(chat, true, setOf("a"), emptySet())!!.text)
        // Each new question once; cancelled tasks and running steps say nothing.
        val q1 = snapshot(id = "b", status = TaskStatus.WAITING_FOR_USER, source = Source.VOICE, question = PendingQuestion("Send it?", true))
        val k1 = SpokenReplies.next(q1, true, emptySet(), emptySet())!!
        assertEquals("Send it?", k1.text)
        val q2 = q1.copy(question = PendingQuestion("Which Tom?", false))
        assertEquals("Which Tom?", SpokenReplies.next(q2, true, emptySet(), setOf(k1.key))!!.text)
        assertNull(SpokenReplies.next(q1.copy(status = TaskStatus.CANCELLED), true, emptySet(), emptySet()))
        assertNull(SpokenReplies.next(q1.copy(status = TaskStatus.RUNNING), true, emptySet(), emptySet()))
        // A different task starting interrupts what is being read.
        assertTrue(SpokenReplies.interrupts("a", snapshot(id = "c", status = TaskStatus.RUNNING)))
        assertFalse(SpokenReplies.interrupts("c", snapshot(id = "c", status = TaskStatus.RUNNING)))
        assertFalse(SpokenReplies.interrupts("a", snapshot(id = "c", status = TaskStatus.SUCCEEDED)))
    }

    @Test fun bubbleRunsAsSpecialUseWithoutTheMicrophone() {
        // voice runs in the chat sheet (a visible activity), so the bubble never takes the microphone type.
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, BubbleService.fgsType(35))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, BubbleService.fgsType(34))
        assertNull(BubbleService.fgsType(33))
    }

    @Test fun ttsFallsBackToUsEnglish() {
        val de = Locale.GERMANY
        assertEquals(de, Speaker.chooseLocale(de) { TextToSpeech.LANG_COUNTRY_AVAILABLE })
        assertEquals(de, Speaker.chooseLocale(de) { TextToSpeech.LANG_AVAILABLE })
        assertEquals(Locale.US, Speaker.chooseLocale(de) { TextToSpeech.LANG_MISSING_DATA })
        assertEquals(Locale.US, Speaker.chooseLocale(de) { TextToSpeech.LANG_NOT_SUPPORTED })
        assertEquals(0.9f, Speaker.RATE)
        assertEquals(1.0f, Speaker.PITCH)
    }

    @Test fun onDeviceRecognizerFallsBackWhenItLacksTheLanguage() {
        assertTrue(SpeechCapture.shouldRetryOffDevice(true, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertTrue(SpeechCapture.shouldRetryOffDevice(true, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertFalse(SpeechCapture.shouldRetryOffDevice(false, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertFalse(SpeechCapture.shouldRetryOffDevice(true, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertFalse(SpeechCapture.shouldRetryOffDevice(true, SpeechRecognizer.ERROR_NO_MATCH))
    }

    @Test fun mcpSnippetsLookLikeWhatUsersTypeAndCheckWithInitialize() {
        val all = com.eggman.backstage.ui.settings.McpClientSnippets.all("192.168.1.20", 8485, "tok")
        all.forEach { assertFalse(it.id, it.body.contains("\\/")) }
        assertTrue(all.first { it.id == "cursor" }.body.contains("http://192.168.1.20:8485/mcp"))
        val curl = all.first { it.id == "curl" }.body
        assertTrue(curl.contains("\"method\":\"initialize\""))
    }

    @Test fun mcpToolChoicesMatchTheServerAndPersist() {
        val names = com.eggman.backstage.ui.settings.McpToolChoices.recommended.map { it.first }.toSet()
        assertEquals(com.eggman.backstage.mcp.BackstageMcpTools.DEFAULT_ENABLED, names)
        assertTrue(com.eggman.backstage.ui.settings.McpToolChoices.WAKE.first in com.eggman.backstage.mcp.BackstageMcpTools.HIGH_LEVEL)
        val store = memorySettings()
        assertEquals(names, store.mcpEnabledTools)
        store.mcpEnabledTools = setOf("execute_task")
        store.mcpExtendedTools = true
        assertEquals(setOf("execute_task"), store.mcpEnabledTools)
        assertTrue(store.mcpExtendedTools)
    }
}
