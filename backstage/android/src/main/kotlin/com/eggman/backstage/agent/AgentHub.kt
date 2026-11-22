package com.eggman.backstage.agent

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import android.graphics.Bitmap
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.exec.Executor
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.init.Backstage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/*
 * COMPATIBILITY LAYER for the prototype UI (ui/, automation/) that predates the AgentController
 * contract. New surfaces must use com.eggman.backstage.agent.api.Agents.controller. This file can
 * be deleted once no surface references AgentHub/ChatItem anymore.
 */

/** Legacy name kept for the prototype UI; same type as the contract's plan step. */
typealias PlanStep = com.eggman.backstage.agent.api.PlanStep

sealed interface ChatItem {
    val id: Long
    data class User(override val id: Long, val text: String) : ChatItem
    data class Assistant(override val id: Long, val text: String) : ChatItem
    data class Action(override val id: Long, val tool: String, val detail: String, val result: String) : ChatItem
    data class Status(override val id: Long, val text: String, val error: Boolean = false) : ChatItem
}

data class LegacyQuestion(val text: String, val confirm: Boolean)

data class LegacyAgentState(
    val items: List<ChatItem> = emptyList(),
    val running: Boolean = false,
    val plan: List<PlanStep> = emptyList(),
    val question: LegacyQuestion? = null,
    val displayId: Int? = null,
    val displayLabel: String? = null,
    val step: Int = 0,
    val tokens: Int = 0,
)

/** Frame source shim for the prototype's live preview. */
class LegacyFrames {
    suspend fun capture(): Bitmap? = if (PLAY_DISTRIBUTION) {
            DesktopHosts.current?.geometry()?.displayId?.let {
                com.eggman.backstage.a11y.AgentAccessibility.instance?.screenshot(it)
            }
        } else runCatching { DesktopHosts.current?.captureFrame() }.getOrNull()
}

class LegacyDevice(private val exec: Executor) {
    val description: String get() = exec.description
    val displayId: Int get() = exec.displayId
    val display: LegacyFrames? = if (exec.kind == TargetKind.DESKTOP) LegacyFrames() else null
    suspend fun launch(packageName: String): String = exec.launchApp(packageName).message
    suspend fun tap(x: Float, y: Float): Boolean = exec.tap(x, y).ok
}

@Deprecated("Use Agents.controller (agent.api.AgentController)")
object AgentHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller get() = Backstage.controller

    val state: StateFlow<LegacyAgentState> by lazy {
        combine(controller.state, controller.transcript) { s, items ->
            val cur = s.current
            val running = cur?.status == TaskStatus.RUNNING || cur?.status == TaskStatus.WAITING_FOR_USER
            val exec = Backstage.environment.peekExecutor(TargetKind.DESKTOP) ?: Backstage.environment.peekExecutor(TargetKind.PHONE)
            LegacyAgentState(
                items = items.map { it.toChatItem() },
                running = running,
                plan = if (running) cur?.plan.orEmpty() else emptyList(),
                question = cur?.question?.takeIf { cur.status == TaskStatus.WAITING_FOR_USER }?.let { LegacyQuestion(it.text, it.confirm) },
                displayId = exec?.displayId,
                displayLabel = exec?.description,
                step = cur?.step ?: 0,
            )
        }.stateIn(scope, SharingStarted.Eagerly, LegacyAgentState())
    }

    private fun TranscriptItem.toChatItem(): ChatItem = when (kind) {
        TranscriptItem.Kind.USER -> ChatItem.User(id, text)
        TranscriptItem.Kind.ASSISTANT -> ChatItem.Assistant(id, text)
        TranscriptItem.Kind.ACTION -> ChatItem.Action(id, text, "", detail)
        TranscriptItem.Kind.STATUS -> ChatItem.Status(id, text)
        TranscriptItem.Kind.ERROR -> if (detail.isNotEmpty()) ChatItem.Action(id, text, "", detail) else ChatItem.Status(id, text, error = true)
    }

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (controller.state.value.current?.status == TaskStatus.WAITING_FOR_USER) { controller.answer(t); return }
        controller.submit(TaskRequest(t, Source.CHAT, waiverText = t))
    }

    fun answer(text: String) = controller.answer(text)
    fun stop() = controller.cancel(null)
    fun clear() { controller.cancel(null); controller.newConversation() }

    suspend fun device(): LegacyDevice {
        val env = Backstage.environment
        val kind = if (DesktopHosts.current != null) TargetKind.DESKTOP else TargetKind.PHONE
        return LegacyDevice(runCatching { env.executor(kind) }.getOrElse { env.executor(TargetKind.PHONE) })
    }

    fun currentDevice(): LegacyDevice? {
        val env = Backstage.environment
        return (env.peekExecutor(TargetKind.DESKTOP) ?: env.peekExecutor(TargetKind.PHONE))?.let { LegacyDevice(it) }
    }

    fun releaseDisplay() = Unit
}
