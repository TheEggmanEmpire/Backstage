package com.eggman.backstage.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordCoreTest {

    @Test fun normalize() {
        assertEquals("hey backstage", WakePhrase.normalize("  Hey,  BACKSTAGE! "))
        assertEquals("hallo bühne", WakePhrase.normalize("Hallo Bühne"))
        assertEquals("ola computador", WakePhrase.fold("Olá computador"))
        assertTrue(WakePhrase.matches("hallo buhne", "Hallo Bühne"))
        assertTrue(WakePhrase.matches("hallo bühne", "hallo buhne"))
    }

    /** Fake engine: records every session and whether it is stopped (a stopped session frees its recogniser). */
    private class FakeEngine : WakeEngine {
        inner class Session(val onText: (String?) -> Unit, val onError: (Throwable?) -> Unit) : WakeSession {
            var stopped = 0
            override fun stop() { stopped++ }
        }
        val sessions = mutableListOf<Session>()
        override fun start(onText: (String?) -> Unit, onError: (Throwable?) -> Unit): WakeSession =
            Session(onText, onError).also { sessions += it }
    }

    private class FakeScheduler {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delay: Long, block: () -> Unit): () -> Unit {
            val e = delay to block
            pending += e
            return { pending.remove(e) }
        }
        fun runNext(): Long = pending.removeAt(0).let { it.second(); it.first }
    }

    @Test fun everyStoppedSessionIsReleasedAndOnlyOneRuns() {
        val engine = FakeEngine()
        val loop = WakeLoop({ engine }, { 0L }, { _, _ -> {} }, onText = {}, onRetry = {})
        assertTrue(loop.start())
        assertTrue(loop.start()) // already listening: no second session
        assertEquals(1, engine.sessions.size)
        loop.stop()
        assertEquals(1, engine.sessions[0].stopped)
        repeat(3) { loop.start(); loop.stop() } // screen off/on cycles
        assertEquals(4, engine.sessions.size)
        assertTrue(engine.sessions.all { it.stopped == 1 })
    }

    @Test fun errorsRestartWithBackoffThenGiveUp() {
        val engine = FakeEngine()
        val sched = FakeScheduler()
        var now = 0L
        val statuses = mutableListOf<WakeLoop.Status>()
        lateinit var loop: WakeLoop
        loop = WakeLoop({ engine }, { now }, sched::schedule, onText = {}, onRetry = { loop.start() }, onStatus = { statuses += it })
        loop.start()
        val delays = mutableListOf<Long>()
        repeat(3) {
            engine.sessions.last().onError(RuntimeException("busy"))
            assertEquals("failed session released", 1, engine.sessions.last().stopped)
            assertFalse(loop.start()) // no restart while backing off
            now += 1_000
            delays += sched.runNext()
        }
        assertEquals(listOf(2_000L, 4_000L, 6_000L), delays)
        engine.sessions.last().onError(RuntimeException("busy")) // 4th consecutive error
        assertTrue(loop.gaveUp)
        assertTrue(statuses.last() is WakeLoop.Status.GaveUp)
        assertTrue(sched.pending.isEmpty())
        assertFalse(loop.start())
        loop.reset() // user re-arms
        assertTrue(loop.start())
        // Callbacks of a stopped session are ignored.
        val old = engine.sessions[0]
        old.onError(RuntimeException("late"))
        assertTrue(loop.listening)
    }

    @Test fun restartCounterResetsAfterQuietPeriod() {
        val p = WakeRestartPolicy()
        assertEquals(2_000L, p.onError(0))
        assertEquals(4_000L, p.onError(5_000))
        assertEquals(2_000L, p.onError(40_000)) // 35 s quiet: counting starts again
        assertEquals(4_000L, p.onError(41_000))
        assertEquals(6_000L, p.onError(42_000))
        assertEquals(null, p.onError(43_000))
    }

    @Test fun recognizedTextFromVoskJson() {
        assertEquals("hey backstage", WakePhrase.recognizedText("""{"text" : "hey backstage"}"""))
        assertEquals("hey", WakePhrase.recognizedText("""{"partial" : "hey"}"""))
        assertEquals("", WakePhrase.recognizedText("garbage"))
        assertEquals("", WakePhrase.recognizedText(null))
    }

    @Test fun matching() {
        assertTrue(WakePhrase.matches("hey backstage", "Hey Backstage"))
        assertTrue(WakePhrase.matches("[unk] hey backstage [unk]", "hey backstage"))
        assertFalse(WakePhrase.matches("backstage hey", "hey backstage"))
        assertFalse(WakePhrase.matches("hey", "hey backstage"))
        assertFalse(WakePhrase.matches("hey [unk] backstage", "hey backstage"))
        assertFalse(WakePhrase.matches("anything", ""))
    }

    @Test fun trailingCommand() {
        assertEquals("open youtube", WakePhrase.trailingCommand("ok hey backstage open youtube", "hey backstage"))
        assertEquals("", WakePhrase.trailingCommand("hey backstage", "hey backstage"))
        assertEquals("", WakePhrase.trailingCommand("hi", "hey backstage"))
    }

    @Test fun phraseValidation() {
        assertTrue(WakePhrase.validate("hey computer").isEmpty())
        assertFalse(WakePhrase.validate("").isEmpty())
        assertFalse(WakePhrase.validate("one two three four five").isEmpty())
        assertFalse(WakePhrase.validate("hey").isEmpty())
        assertTrue(WakePhrase.validate("jarvisbot").isEmpty())
    }

    private fun inputs(
        enabled: Boolean = true, model: Boolean = true, mic: Boolean = true, screenOn: Boolean = true, charging: Boolean = false,
        pauseOff: Boolean = true, onlyCharging: Boolean = false, busy: Boolean = false, call: Boolean = false,
    ) = WakeWordPolicy.Inputs(enabled, model, mic, screenOn, charging, pauseOff, onlyCharging, busy, call)

    @Test fun policy() {
        assertTrue(WakeWordPolicy.decide(inputs()).listen)
        assertFalse(WakeWordPolicy.decide(inputs(enabled = false)).listen)
        assertFalse(WakeWordPolicy.decide(inputs(mic = false)).listen)
        assertFalse(WakeWordPolicy.decide(inputs(model = false)).listen)
        assertFalse(WakeWordPolicy.decide(inputs(screenOn = false)).listen)
        assertTrue(WakeWordPolicy.decide(inputs(screenOn = false, pauseOff = false)).listen)
        assertFalse(WakeWordPolicy.decide(inputs(onlyCharging = true)).listen)
        assertTrue(WakeWordPolicy.decide(inputs(onlyCharging = true, charging = true)).listen)
        assertFalse(WakeWordPolicy.decide(inputs(busy = true)).listen)
        assertFalse(WakeWordPolicy.decide(inputs(call = true)).listen)
        assertEquals("Microphone permission missing", WakeWordPolicy.decide(inputs(mic = false, model = false)).reason)
    }

    @Test fun pausesWhileLockedByDefaultAndOnlyStopWorksLocked() {
        val locked = inputs().copy(locked = true)
        // Screen on with the keyguard showing: a bystander could speak, so listening pauses by default.
        assertFalse(WakeWordPolicy.decide(locked).listen)
        assertEquals("Paused while the phone is locked", WakeWordPolicy.decide(locked).reason)
        assertTrue(WakeWordPolicy.decide(locked.copy(pauseWhenLocked = false)).listen)
        assertTrue(WakeWordPolicy.decide(inputs().copy(locked = false)).listen)

        val confirm = com.eggman.backstage.ui.Fixtures.snapshot(status = com.eggman.backstage.agent.api.TaskStatus.WAITING_FOR_USER,
            question = com.eggman.backstage.agent.api.PendingQuestion("Send the code to +44…?", true))
        val yes = VoiceCommand.classify("yes", confirm)
        assertEquals(VoiceCommand.Answer(com.eggman.backstage.agent.api.Answers.APPROVE), yes)
        assertFalse(VoiceCommand.allowedWhileLocked(yes))
        assertFalse(VoiceCommand.allowedWhileLocked(VoiceCommand.classify("read my notifications", null)))
        assertTrue(VoiceCommand.allowedWhileLocked(VoiceCommand.classify("stop", confirm)))
    }

    @Test fun speakableStripsMarkdownAndUrls() {
        assertEquals("Done. See link for details", Speaker.speakable("**Done.** See https://x.y/z for `details`"))
        val long = Speaker.speakable("word ".repeat(400), maxChars = 50)
        assertTrue(long.length <= 51 && long.endsWith("…"))
    }

    @Test fun catalogIsConsistent() {
        VoskModelCatalog.models.forEach {
            assertTrue(it.url.startsWith("https://alphacephei.com/vosk/models/"))
            assertTrue(it.url.endsWith(it.id + ".zip"))
            assertEquals(64, it.sha256.length)
            assertTrue(it.sizeBytes in 1_000_000..200_000_000)
        }
        assertEquals("vosk-model-small-de-0.15", VoskModelCatalog.forLanguage("de-AT").id)
        assertEquals("vosk-model-small-en-us-0.15", VoskModelCatalog.forLanguage("ja-JP").id)
    }
}
