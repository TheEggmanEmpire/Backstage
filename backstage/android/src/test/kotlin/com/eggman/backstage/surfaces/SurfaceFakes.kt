package com.eggman.backstage.surfaces

import com.eggman.backstage.agent.api.AgentController
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.Readiness
import com.eggman.backstage.agent.api.TaskHandle
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.Settings
import com.eggman.backstage.ui.Fixtures
import com.eggman.backstage.ui.settings.CoreSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory agent for surface tests: records calls; finished tasks complete with [finishWith]. */
class FakeAgent(initial: AgentState = AgentState()) : AgentController {
    override val state = MutableStateFlow(initial)
    val submitted = mutableListOf<TaskRequest>()
    val answers = mutableListOf<String>()
    val cancelled = mutableListOf<String?>()
    var finishWith: (TaskRequest, String) -> TaskSnapshot = { r, id ->
        Fixtures.snapshot(id = id, prompt = r.prompt, status = TaskStatus.SUCCEEDED, source = r.source, result = "done")
    }

    override fun submit(request: TaskRequest): TaskHandle {
        submitted += request
        val id = "task${submitted.size}"
        return object : TaskHandle {
            override val id = id
            override suspend fun await(): TaskSnapshot = finishWith(request, id)
        }
    }

    override fun answer(text: String) { answers += text }
    override fun cancel(taskId: String?) { cancelled += taskId }
    val instructed = mutableListOf<Pair<String?, String>>()
    override fun instruct(taskId: String?, instruction: String): Boolean { instructed += taskId to instruction; return true }
    override fun readiness() = Readiness()
    override suspend fun history(limit: Int) = emptyList<TaskSnapshot>()
}

/** Settings backed by memory (no Android keystore in JVM tests). */
fun memorySettings(): CoreSettingsStore = CoreSettingsStore(Settings(MemoryStore()))
