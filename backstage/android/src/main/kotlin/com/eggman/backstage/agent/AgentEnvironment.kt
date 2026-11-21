package com.eggman.backstage.agent

import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.data.Settings
import com.eggman.backstage.data.TaskHistoryStore
import com.eggman.backstage.exec.Executor
import com.eggman.backstage.exec.TargetContext
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.llm.LlmClient
import com.eggman.backstage.llm.ProviderConfig
import com.eggman.backstage.tools.DeviceServices
import com.eggman.backstage.tools.ToolRegistry
import java.time.ZoneId
import java.util.Locale

/**
 * Everything the controller needs from the platform. [AndroidAgentEnvironment] is the production
 * implementation; unit tests provide fakes (LLM, executors, history) so the whole task lifecycle
 * runs on the JVM.
 */
interface AgentEnvironment {
    val settings: Settings
    val registry: ToolRegistry
    val services: DeviceServices
    val history: TaskHistoryStore

    fun llm(config: ProviderConfig): LlmClient

    fun targetContext(request: TaskRequest, conversationTarget: TargetKind?): TargetContext

    /** Executor for [kind]; starts the desktop when needed. Throws IllegalStateException with a readable reason. */
    suspend fun executor(kind: TargetKind): Executor

    /** Existing executor without starting anything (null when none yet). */
    fun peekExecutor(kind: TargetKind): Executor?

    fun readinessInputs(): ReadinessInputs

    /** Called when the controller becomes busy/idle (start/stop the foreground service). */
    fun onBusyChanged(busy: Boolean) {}

    /** Gives the platform a chance to prepare before a device task (wake screen, re-assert accessibility). */
    suspend fun beforeTask(request: TaskRequest) {}
    /** Open the AI provider's connection in the background at task start (off in tests). */
    val prewarmLlmConnection: Boolean get() = false

    fun now(): Long = System.currentTimeMillis()
    fun zone(): ZoneId = ZoneId.systemDefault()
    fun locale(): Locale = Locale.getDefault()
    fun deviceName(): String = ""

    /** Our own packages (overlay, app UI): never shown to the model. */
    fun ownPackages(): Set<String> = emptySet()

    /** What the device is ([DeviceContext]); null when unknown. Read once per task. */
    fun deviceFacts(): DeviceFacts? = null

    /** Chat threads: chat tasks are recorded in, and continue, the thread they belong to. null = none. */
    val threads: com.eggman.backstage.data.ChatThreadStore? get() = null

    /** Tools of connected remote MCP servers for this task (empty when none are configured). */
    suspend fun remoteTools(): List<com.eggman.backstage.tools.Tool> = emptyList()
}
