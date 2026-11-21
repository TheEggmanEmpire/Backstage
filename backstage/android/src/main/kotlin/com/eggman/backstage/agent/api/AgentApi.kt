package com.eggman.backstage.agent.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract between the agent core (package com.eggman.backstage.agent.*) and the
 * surfaces (chat UI, voice, overlay bubble, QS tile, ASSIST/share, Tasker/Locale plugin,
 * MacroDroid, broadcast API, scheduler, MCP execute_task). Surfaces ONLY use this API.
 */

/** Where the agent acts. DESKTOP = the Dextop desktop display (background / mini window); PHONE = display 0 (grouped). */
enum class Target { AUTO, DESKTOP, PHONE }

/** Who started a task — used for UI routing, confirmation policy and history. */
/** Where a task came from. POPUP_CHAT: the chat panel opened from the desktop popup (before, NOTIFICATION). */
enum class Source { CHAT, VOICE, WAKE_WORD, OVERLAY, TILE, ASSIST, SHARE, TASKER, BROADCAST, SCHEDULE, MCP, NOTIFICATION, WIDGET, POPUP_CHAT }

data class TaskRequest(
    val prompt: String,
    val source: Source,
    val target: Target = Target.AUTO,
    /** Plan mode override; null = user setting. */
    val planMode: Boolean? = null,
    /** Free-form context attached by the surface (shared text, Tasker variables...). */
    val attachments: Map<String, String> = emptyMap(),
    /** Non-interactive callers (Tasker, MCP, schedule) must not block on ask_user forever. */
    val interactive: Boolean = true,
    val timeoutMs: Long? = null,
    /**
     * Text the phone's user wrote themselves for this request (typed or spoken text, a quick action or schedule they
     * saved in the UI, the signed Tasker template without its %variables). Only this text may waive confirmations
     * ("…without asking"); [prompt] never does, because it can carry other people's text (SMS, shared or selected
     * text, Tasker variables, model-written schedules). null = nothing the user wrote, so no waiver.
   */
    val waiverText: String? = null,
)

enum class TaskStatus { QUEUED, RUNNING, WAITING_FOR_USER, SUCCEEDED, FAILED, CANCELLED }

data class PlanStep(val title: String, val status: String /* pending|in_progress|done|skipped */)

/** Ordered engine transcript; snapshots carry the full list so conflated updates lose no completed lines. */
data class TaskChatLine(
    val id: Long,
    val kind: String,
    val text: String,
    val detail: String = "",
    val step: Int = 0,
    val thinking: String? = null,
    val thinkingActive: Boolean = false,
)

/** UTF-8 tail without cutting a code point (reasoning: 8 KB; tool output: 2 KB). */
internal fun chatTail(text: String, bytes: Int): String {
    val data = text.toByteArray(Charsets.UTF_8)
    if (data.size <= bytes) return text
    var start = data.size - bytes
    while (start < data.size && (data[start].toInt() and 0xc0) == 0x80) start++
    return String(data, start, data.size - start, Charsets.UTF_8)
}

data class TaskSnapshot(
    val id: String,
    val request: TaskRequest,
    val status: TaskStatus,
    val step: Int,
    val maxSteps: Int,
    val plan: List<PlanStep>,
    val lastAction: String?,
    val question: PendingQuestion?,
    val result: String?,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    /** The task is held between steps by [AgentController.pause] (status stays RUNNING). */
    val paused: Boolean = false,
    /**
     * The screen the task works on: DESKTOP or PHONE once the target is resolved (and after the agent switched
     * screens), null before. Where the desktop shows follows it ([com.eggman.backstage.shell.ShellPresence.phoneMode]).
   */
    val resolvedTarget: Target? = null,
    /**
     * How many actions the step list shows for this task: every tool call except loop control (plan, questions,
     * finish), counted from 1 like the stored thread numbers them. The chat numbers its step lines with it.
   */
    val shownActions: Int = 0,
    /** Where the task's time went (local only): AI, actions, screen settling; null before the first step. */
    val timing: TaskTiming? = null,
    /** Current omp reasoning; absent for the native engine and providers without visible reasoning. */
    val thinking: String? = null,
    val thinkingActive: Boolean = false,
    /** Optional richer transcript; null preserves the native snapshot renderer. */
    val chatLines: List<TaskChatLine>? = null,
)

/**
 * A question the running task waits on. For [confirm] questions surfaces answer with exactly
 * [Answers.APPROVE] or [Answers.DENY] (buttons, notification actions, broadcast/Tasker "approve"/"deny");
 * free text is interpreted strictly (anything with a negation denies). [choices] are display labels.
 */
data class PendingQuestion(val text: String, val confirm: Boolean, val choices: List<String> = emptyList())

/** Canonical answer vocabulary for confirm questions (see [PendingQuestion]). */
object Answers {
    const val APPROVE = "approve"
    const val DENY = "deny"
}

data class AgentState(
    val current: TaskSnapshot? = null,
    val queueSize: Int = 0,
    val ready: Readiness = Readiness(),
) {
    /** A task is queued, running (also paused) or waiting for the user: a new start would have to wait. */
    val busy: Boolean
        get() = queueSize > 0 || current?.status.let { it == TaskStatus.RUNNING || it == TaskStatus.WAITING_FOR_USER || it == TaskStatus.QUEUED }
}

/** Device/agent readiness, also returned by MCP get_device_status. */
data class Readiness(
    val providerConfigured: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val privilegedAvailable: Boolean = false,
    val desktopActive: Boolean = false,
    val screenOn: Boolean = true,
    val unlocked: Boolean = true,
    val problems: List<String> = emptyList(),
)

interface TaskHandle {
    val id: String
    /** Suspends until the task reaches a terminal status. */
    suspend fun await(): TaskSnapshot
}

interface AgentController {
    val state: StateFlow<AgentState>
    fun submit(request: TaskRequest): TaskHandle
    /** Answers the current question; for confirm questions send [Answers.APPROVE] / [Answers.DENY]. */
    fun answer(text: String)
    fun cancel(taskId: String? = null)
    /** Cancels the running task and every queued one (the Stop controls). */
    fun cancelAll() = cancel(null)
    /**
     * Holds the running task (null = whichever runs) before its next step, e.g. while the wake word listens.
     * Time spent paused does not count towards task time limits. Returns false when nothing is paused.
   */
    fun pause(taskId: String? = null): Boolean = false
    /** Lets a paused task continue. Returns false when nothing is paused. */
    fun resume(taskId: String? = null): Boolean = false
    /**
     * Lets a paused task continue with an extra [instruction] from the user: the same task and
     * conversation go on, and the model reads the instruction before its next step. A blank instruction is a plain
     * [resume]. Returns false when nothing is paused (the instruction is then not used).
   */
    fun resume(taskId: String?, instruction: String?): Boolean = resume(taskId)
    /**
     * Adds an [instruction] from the user to the running (or queued, already current) task without pausing it: the model
     * reads it before its next step, like a [resume] instruction. Returns false when [taskId] is not the current task.
   */
    fun instruct(taskId: String?, instruction: String): Boolean = false
    fun readiness(): Readiness
    /** Persistent task history (most recent first). */
    suspend fun history(limit: Int = 50): List<TaskSnapshot>
}

/** Installed by the agent core at process start (BackstageInitProvider). */
object Agents {
    @Volatile
    lateinit var controller: AgentController
}
