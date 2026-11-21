package com.eggman.backstage.voice

import android.content.Context
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.SurfaceScope
import com.eggman.backstage.ui.settings.SettingsStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Which reply of a task to read aloud, if any (pure, so the decision table is unit-tested). */
object SpokenReplies {
    /** Tasks started by speech are answered by speech. */
    val VOICE_SOURCES = setOf(Source.VOICE, Source.WAKE_WORD, Source.ASSIST)

    data class Utterance(val key: String, val text: String)

    /**
     * The next thing to say for [s]: its final result, or a question (each one once). Only for voice-started tasks
     * ([VOICE_SOURCES], or ids a surface marked because the user dictated the prompt) and only when [enabled].
   */
    fun next(s: TaskSnapshot?, enabled: Boolean, voiceTaskIds: Set<String>, spoken: Set<String>, locked: Boolean = false): Utterance? {
        // Never read results or questions (codes, messages, amounts) aloud to whoever holds a locked phone.
        if (s == null || !enabled || locked) return null
        if (s.request.source !in VOICE_SOURCES && s.id !in voiceTaskIds) return null
        val u = when (s.status) {
            TaskStatus.SUCCEEDED, TaskStatus.FAILED -> s.result?.takeIf { it.isNotBlank() }?.let { Utterance("${s.id}:done", it) }
            TaskStatus.WAITING_FOR_USER -> s.question?.let { Utterance("${s.id}:q:${it.text.hashCode()}", it.text) }
            else -> null
        }
        return u?.takeIf { it.key !in spoken }
    }

    /** A different task started: whatever is still being read belongs to the old one. */
    fun interrupts(previousId: String?, s: TaskSnapshot?): Boolean =
        s != null && s.id != previousId && (s.status == TaskStatus.RUNNING || s.status == TaskStatus.QUEUED)
}

/**
 * Reads replies aloud for the whole process, whether or not any screen is open: wake-word tasks usually run with
 * the app closed. Started once at process start (SurfacesInitProvider), like the task notifier.
 */
object ReplySpeaker {
    private const val MAX_REMEMBERED = 64
    private val voiceTasks = LinkedHashSet<String>()
    private val spoken = LinkedHashSet<String>()
    private var job: Job? = null

    /** A surface submitted [taskId] with a dictated prompt: its replies are spoken too. */
    @Synchronized
    fun markVoice(taskId: String) {
        voiceTasks += taskId
        trim(voiceTasks)
    }

    @Synchronized
    private fun snapshotOfMarks(): Set<String> = voiceTasks.toSet()

    @Synchronized
    private fun remember(key: String) {
        spoken += key
        trim(spoken)
    }

    @Synchronized
    private fun spokenKeys(): Set<String> = spoken.toSet()

    private fun trim(set: LinkedHashSet<String>) {
        while (set.size > MAX_REMEMBERED) set.remove(set.first())
    }

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = SurfaceScope.launch {
            while (AgentAccess.controller() == null) delay(500)
            var lastId: String? = null
            AgentAccess.state().map { it.current }.distinctUntilChanged().collect { s ->
                if (SpokenReplies.interrupts(lastId, s)) Speaker.stop()
                if (s != null) lastId = s.id
                val locked = app.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked == true
                val u = SpokenReplies.next(s, SettingsStores.get(app).speakReplies, snapshotOfMarks(), spokenKeys(), locked)
                    ?: return@collect
                remember(u.key)
                // TextToSpeech is created and driven from the main thread.
                withContext(Dispatchers.Main) { Speaker.speak(app, u.text) }
            }
        }
    }
}
