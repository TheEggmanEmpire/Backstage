package com.eggman.backstage.omp.engine

import com.eggman.backstage.agent.LoopConfig
import com.eggman.backstage.llm.Part
import com.eggman.backstage.tools.PlanArgs
import com.eggman.backstage.tools.ToolRegistry
import com.eggman.backstage.tools.ToolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Namespaced to avoid omp built-in collisions. Native names enter every guard and registry call. */
class OmpHostTools(registry: ToolRegistry, private val config: LoopConfig, private val policy: OmpToolPolicy,
    private val onOutcome: suspend (com.eggman.backstage.agent.LoopOutcome) -> Unit = {},
) {
    private val offered = registry.forAgent(config.availability).associateBy { PREFIX + it.name }
    private val calls = java.util.Collections.synchronizedMap(mutableMapOf<String, Job>())
    /** A Backstage tool (possibly waiting on the user) is running: omp is legitimately quiet. */
    val busy: Boolean get() = synchronized(calls) { calls.values.any { it.isActive } }
    private val seen = mutableSetOf<String>()
    private val cancelled = mutableSetOf<String>()
    private val serial = Mutex()
    fun nativeName(wireName: String): String? = offered[wireName]?.name
    fun definitions(): JSONArray = JSONArray().apply {
        offered.forEach { (wire, tool) ->
            val spec = tool.spec()
            val schema = JSONObject(spec.schema.toString())
            if (config.planMode && tool.mutating && tool.targets.isNotEmpty()) schema.optJSONObject("properties")?.put("plan", PlanArgs.schema)
            put(JSONObject().put("name", wire).put("label", tool.name.replace('_', ' ')).put("description", spec.description)
                .put("parameters", schema).put("loadMode", "essential"))
        }
    }
    fun preamble(system: String): String {
        val names = offered.values.map { it.name }.sortedByDescending { it.length }
        val matcher = Regex("\\b(" + names.joinToString("|") { Regex.escape(it) } + ")\\b")
        return matcher.replace(system) { PREFIX + it.value } +
            "\n\nBackstage host tools use the backstage_ prefix. Use them for Android device actions so target gates and approvals apply. " +
            "omp built-in shell/edit/task tools are for requested coding work; do not use them to bypass device restrictions."
    }
    /** Reader-safe: launch execution, then keep receiving cancellations and events. */
    fun handle(frame: JSONObject, scope: CoroutineScope, send: suspend (JSONObject) -> Unit) {
        if (frame.optString("type") == "host_tool_cancel") {
            val target = frame.optString("targetId")
            synchronized(calls) {
                if (target !in seen && cancelled.size < 4096) cancelled += target
                calls[target]?.cancel(CancellationException("OMP cancelled host tool"))
            }
            return
        }
        val id = frame.optString("id")
        require(id.isNotBlank() && id.length <= 256) { "Invalid host tool request id" }
        synchronized(calls) {
            if (!seen.add(id)) return // Never replay an effect for a duplicate request.
            check(seen.size <= 16_384) { "Too many host tool requests" }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var result: ToolResult
                try {
                    result = serial.withLock {
                        currentCoroutineContext().ensureActive()
                        if (synchronized(calls) { id in cancelled }) ToolResult.error("Cancelled before execution")
                        else {
                            val name = nativeName(frame.optString("toolName"))
                            val args = frame.optJSONObject("arguments")
                            if (name == null) ToolResult.error("Tool is not available for this task")
                            else if (args == null) ToolResult.error("Tool arguments must be an object")
                            else policy.execute(Part.ToolCall(frame.optString("toolCallId", id), name, args))
                        }
                    }
                } catch (_: CancellationException) {
                    result = ToolResult.error("Host tool cancelled; any action already performed was not undone.")
                } catch (t: Exception) { result = ToolResult.error(t.message ?: "Host tool failed") }
                try {
                    withContext(NonCancellable) {
                        val response = resultFrame(id, result)
                        try { send(response) } catch (e: OmpProtocolException) {
                            if (result.image == null) throw e
                            send(resultFrame(id, ToolResult.error("Screenshot exceeds the OMP transport limit; use a smaller capture.")))
                        }
                    }
                    policy.outcome?.let { onOutcome(it) }
                } finally { synchronized(calls) { calls.remove(id) } }
            }
            calls[id] = job; job.start()
        }
    }
    suspend fun cancelAll() {
        val jobs = synchronized(calls) { calls.values.toList() }
        jobs.forEach { it.cancel() }; jobs.joinAll()
    }
    companion object {
        const val PREFIX = "backstage_"
        fun resultFrame(id: String, result: ToolResult): JSONObject {
            val content = JSONArray().put(JSONObject().put("type", "text").put("text", result.text))
            result.image?.let { content.put(JSONObject().put("type", "image").put("mimeType", it.mediaType).put("data", it.base64)) }
            return JSONObject().put("type", "host_tool_result").put("id", id)
                .put("result", JSONObject().put("content", content)).put("isError", result.isError)
        }
    }
}
