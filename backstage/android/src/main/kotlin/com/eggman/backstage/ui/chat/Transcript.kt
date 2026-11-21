package com.eggman.backstage.ui.chat

import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus

/**
 * Builds the chat transcript from the stream of [TaskSnapshot]s (the contract only exposes the
 * latest action/question, so the log is accumulated here). Pure and deterministic.
 */
data class Transcript(
    val entries: List<Entry> = emptyList(),
    private val taskId: String? = null,
    private val lastAction: String? = null,
    /** [TaskSnapshot.shownActions] of the last step line (repeated identical actions are lines of their own). */
    private val lastShown: Int = 0,
    private val lastQuestion: String? = null,
    private val lastStatus: TaskStatus? = null,
    private val nextId: Long = 1,
    /** Tasks whose lines came from the thread store: their snapshots add nothing. */
    private val storedTasks: Set<String> = emptySet(),
    /** Source line identities let completed entries survive subsequent token updates unchanged. */
    private val projectedLines: Map<Long, com.eggman.backstage.agent.api.TaskChatLine> = emptyMap(),
) {
    enum class Kind { USER, ACTION, QUESTION, ANSWER, RESULT, ERROR, INFO }

    data class Entry(val id: Long, val kind: Kind, val text: String, val taskId: String?, val source: Source? = null, val step: Int = 0,
        val thinking: String? = null, val thinkingActive: Boolean = false, val detail: String = "", val liveId: Long? = null, val singleLine: Boolean = false)

    private fun add(kind: Kind, text: String, task: String?, source: Source? = null, step: Int = 0): Transcript =
        copy(entries = (entries + Entry(nextId, kind, text, task, source, step)).takeLast(MAX_ENTRIES), nextId = nextId + 1)

    fun onAnswer(text: String): Transcript = add(Kind.ANSWER, text, taskId)

    fun info(text: String): Transcript = add(Kind.INFO, text, taskId)

    fun clear(): Transcript = Transcript(nextId = nextId, taskId = taskId, lastAction = lastAction, lastShown = lastShown, lastQuestion = lastQuestion, lastStatus = lastStatus)

    fun onSnapshot(s: TaskSnapshot?): Transcript {
        if (s == null) return this
        if (s.id in storedTasks && s.id != taskId) return this
        var t = this
        if (s.id != taskId) {
            t = t.copy(taskId = s.id, lastAction = null, lastShown = 0, lastQuestion = null, lastStatus = null, projectedLines = emptyMap())
                .add(Kind.USER, s.request.prompt, s.id, s.request.source)
        }
        s.chatLines?.let { lines ->
            // Reconcile the complete ordered omp log. StateFlow may conflate many token/tool updates.
            val old = t.entries.filter { it.taskId == s.id && it.liveId != null }.associateBy { it.liveId }
            var next = t.nextId
            val projected = lines.mapNotNull { line ->
                val kind = when (line.kind) {
                    "assistant" -> Kind.RESULT
                    "action" -> Kind.ACTION
                    "question" -> Kind.QUESTION
                    "answer" -> Kind.ANSWER
                    "error" -> Kind.ERROR
                    "info" -> Kind.INFO
                    else -> return@mapNotNull null
                }
                val previous = old[line.id]
                val active = line.thinkingActive && s.status in setOf(TaskStatus.RUNNING, TaskStatus.WAITING_FOR_USER)
                if (previous != null && t.projectedLines[line.id] === line && previous.thinkingActive == active) previous
                else Entry(previous?.id ?: next++, kind, line.text, s.id, step = line.step,
                    thinking = line.thinking, thinkingActive = active, detail = line.detail, liveId = line.id, singleLine = true)
            }
            val kept = t.entries.filterNot { it.taskId == s.id && it.kind != Kind.USER }
            t = t.copy(entries = (kept + projected).takeLast(MAX_ENTRIES), nextId = next, lastStatus = s.status, projectedLines = lines.associateBy { it.id })
            val terminal = when (s.status) {
                TaskStatus.SUCCEEDED -> Kind.RESULT
                TaskStatus.FAILED -> Kind.ERROR
                TaskStatus.CANCELLED -> Kind.INFO
                else -> null
            }
            if (terminal != null && !(terminal == Kind.RESULT && projected.lastOrNull { it.kind == Kind.RESULT && it.text.isNotBlank() }?.text == s.result)) {
                t = t.add(terminal, s.result ?: if (terminal == Kind.RESULT) "Done." else "Stopped.", s.id)
            }
            return t
        }
        // One numbered line per shown action, numbered like the stored thread, including
        // non-mutating tools independently of the action budget.
        if (s.lastAction != null && (s.shownActions > t.lastShown || (s.shownActions == 0 && s.lastAction != t.lastAction))) {
            t = t.copy(lastAction = s.lastAction, lastShown = s.shownActions).add(Kind.ACTION, s.lastAction, s.id,
                step = if (s.shownActions > 0) s.shownActions else s.step)
        }
        val q = s.question?.takeIf { s.status == TaskStatus.WAITING_FOR_USER }
        if (q != null && q.text != t.lastQuestion) {
            t = t.copy(lastQuestion = q.text).add(Kind.QUESTION, q.text, s.id)
        } else if (q == null && t.lastQuestion != null && s.status != TaskStatus.WAITING_FOR_USER) {
            t = t.copy(lastQuestion = null)
        }
        if (s.status != t.lastStatus) {
            t = t.copy(lastStatus = s.status)
            when (s.status) {
                TaskStatus.SUCCEEDED -> t = t.add(Kind.RESULT, s.result ?: "Done.", s.id)
                TaskStatus.FAILED -> t = t.add(Kind.ERROR, s.result ?: "The task failed.", s.id)
                TaskStatus.CANCELLED -> t = t.add(Kind.INFO, s.result ?: "Stopped.", s.id)
                else -> Unit
            }
        }
        return t
    }

    companion object {
        const val MAX_ENTRIES = 500

        /** A thread's stored lines (reopening a chat shows its whole transcript). */
        fun fromThread(thread: com.eggman.backstage.data.ChatThread?): Transcript {
            if (thread == null) return Transcript()
            var id = 1L
            val entries = thread.entries.mapNotNull { e ->
                val kind = when (e.kind) {
                    "user" -> Kind.USER
                    "answer" -> Kind.ANSWER
                    "action" -> Kind.ACTION
                    "question" -> Kind.QUESTION
                    "result", "assistant" -> Kind.RESULT
                    "error" -> Kind.ERROR
                    "info" -> Kind.INFO
                    else -> return@mapNotNull null
                }
                val source = e.source?.let { s -> Source.values().firstOrNull { it.name == s } }
                Entry(id++, kind, e.text, e.taskId.ifBlank { null }, source, e.step, thinking = e.thinking, detail = e.detail.orEmpty(), singleLine = e.detail != null)
            }.takeLast(MAX_ENTRIES)
            return Transcript(entries = entries, nextId = id, storedTasks = thread.taskIds.toSet())
        }
    }
}
