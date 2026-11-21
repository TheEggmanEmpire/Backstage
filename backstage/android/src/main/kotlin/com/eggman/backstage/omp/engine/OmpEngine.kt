package com.eggman.backstage.omp.engine

import com.eggman.backstage.agent.*
import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.llm.Message
import com.eggman.backstage.llm.Part
import com.eggman.backstage.omp.OmpHarness
import com.eggman.backstage.omp.OmpRuntime
import com.eggman.backstage.omp.shellQuote
import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.tools.AgentHooks
import com.eggman.backstage.tools.ToolContext
import com.eggman.backstage.tools.ToolRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID

/** One process per controller, one omp session per live conversation. No action is replayed on failure. */
class OmpEngine(
    private val availability: () -> String? = { OmpHarness.startUnavailableReason() },
    private val connect: suspend (String) -> OmpRpcClient = { startBridge(it) },
) : Closeable {
    private var client: OmpRpcClient? = null
    private var session: Any? = null
    private var preambleAcknowledged = false
    private var model = ""
    private var taskActive = false
    var unavailableReason: String? = null; private set
    var plan: List<PlanStep> = emptyList(); private set

    /** Null means startup failed before submitting a prompt: the controller may use the native loop. */
    suspend fun run(
        conversationKey: Any,
        modelId: String,
        registry: ToolRegistry,
        config: LoopConfig,
        user: UserChannel,
        contextFactory: (AgentHooks) -> ToolContext,
        listener: LoopListener,
        messages: List<Message>,
        displayRedactor: OmpDisplayRedactor = OmpDisplayRedactor.DEFAULT,
    ): LoopOutcome? {
        plan = emptyList()
        com.eggman.backstage.tools.Observe.reset()
        val policy = OmpToolPolicy(registry, config, user, contextFactory, listener)
        val terminal = Channel<LoopOutcome>(1)
        val host = OmpHostTools(registry, config, policy) { terminal.trySend(it) }
        val fresh: Boolean
        val rpc: OmpRpcClient
        try {
            availability()?.let { unavailableReason = it; close(); return null }
            if (client?.alive != true || model != modelId || stale) {
                close()
                stale = false
                listener.onStatus("Starting omp ($modelId); the first start can take a minute…")
                rpc = connect(modelId); client = rpc; model = modelId
                rpc.initialize()
            } else rpc = client!!
            if (session !== conversationKey) {
                val result = rpc.command("new_session")
                if (result.optJSONObject("data")?.optBoolean("cancelled", false) == true)
                    throw OmpProtocolException("OMP new session cancelled")
                session = conversationKey
                preambleAcknowledged = false
            }
            fresh = !preambleAcknowledged
            rpc.command("set_host_tools", JSONObject().put("tools", host.definitions()))
            unavailableReason = null
        } catch (c: CancellationException) { close(); throw c }
        catch (e: Exception) {
            close(); unavailableReason = "OMP bridge unavailable (${e.message?.let(displayRedactor::redact)?.take(200) ?: "startup failed"})" + logTail(displayRedactor)
            return null
        }
        val mapper = OmpEventMapper(listener, redactor = displayRedactor)
        val promptId = "task_${UUID.randomUUID()}"
        val latest = messages.lastOrNull() ?: Message.user("")
        val prompt = if (!fresh) latest.text else OmpPrompt.first(host.preamble(config.system), messages.dropLast(1).takeLast(20)
            .map { it.role.name to it.text }, latest.text)
        val images = JSONArray().apply { latest.parts.filterIsInstance<Part.Image>().forEach {
            put(JSONObject().put("type", "image").put("mimeType", it.mediaType).put("data", it.base64))
        } }
        var taskGeneration: Long? = null
        try {
            return coroutineScope {
                // Tool jobs are children of this task, never the process-owned connection.
                val toolScope = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
                try {
                    listener.beforeStep()
                    val generation = rpc.beginTask(promptId)
                    taskGeneration = generation
                    val response = rpc.command("prompt", JSONObject().put("message", prompt).put("images", images), promptId)
                    preambleAcknowledged = true
                    taskActive = true
                    if (response.optJSONObject("data")?.optBoolean("agentInvoked", true) == false) return@coroutineScope mapper.outcome()
                    var turns = 0
                    var stopped: LoopOutcome? = null
                    var stopDeadline = Long.MAX_VALUE
                    var idle = false
                    // Real-clock watchdog (not the coroutine clock, which tests advance virtually): omp is stuck when it
                    // sends nothing for INACTIVITY_MS while no Backstage tool runs (key fetch, provider, crashed process).
                    val lastFrame = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
                    val stuck = Channel<Unit>(1)
                    toolScope.launch(Dispatchers.Default) {
                        while (isActive) {
                            delay(5_000)
                            if (host.busy) lastFrame.set(System.nanoTime())
                            else if (System.nanoTime() - lastFrame.get() >= INACTIVITY_MS * 1_000_000) { stuck.trySend(Unit); break }
                        }
                    }
                    while (true) {
                        val event = if (stopped != null) withTimeoutOrNull((stopDeadline - System.nanoTime() / 1_000_000).coerceAtLeast(1)) { rpc.nextEvent() }
                            ?: throw OmpProtocolException("OMP did not acknowledge abort")
                        else select<OmpRpcEvent?> {
                            rpc.events.onReceive { rpc.consume(it) }
                            terminal.onReceive { stopped = it; stopDeadline = System.nanoTime() / 1_000_000 + 5000; abort(rpc); null }
                            // No frame while no Backstage tool runs: omp is stuck (key fetch, provider, crashed process).
                            stuck.onReceive { idle = true; null }
                        }
                        if (idle) {
                            abort(rpc); close()
                            return@coroutineScope LoopOutcome(false, "omp stopped responding (nothing for ${INACTIVITY_MS / 1000} s)." + logTail(displayRedactor), turns, EndReason.LLM_ERROR)
                        }
                        if (event == null) continue
                        lastFrame.set(System.nanoTime())
                        val frame = event.frame
                        when (frame.optString("type")) {
                            "host_tool_call", "host_tool_cancel" -> {
                                rpc.requireHostTask(event, generation)
                                host.handle(frame, toolScope, rpc::send)
                            }
                            "turn_start" -> {
                                policy.beginTurn()
                                mapper.beforeTurn()
                                if (++turns > config.maxSteps) {
                                    abort(rpc)
                                    close()
                                    return@coroutineScope LoopOutcome(false, "Stopped after ${config.maxSteps} steps without finishing (safety limit).", turns, EndReason.STEP_LIMIT)
                                }
                            }
                            "prompt_result" -> if (frame.optString("id") == promptId) {
                                val outcome = when (frame.optString("status")) {
                                    "error" -> LoopOutcome(false, frame.optJSONObject("error")?.optString("message") ?: "OMP provider failed", turns, EndReason.LLM_ERROR)
                                    "aborted" -> stopped ?: policy.outcome ?: LoopOutcome(false, "OMP turn was aborted.", turns, EndReason.LLM_ERROR)
                                    "completed" -> policy.outcome ?: mapper.outcome()
                                    else -> throw OmpProtocolException("Invalid OMP prompt result status")
                                }
                                // Background work must not carry this task's authority into a later task.
                                if (!frame.optBoolean("sessionSettled", true)) { abort(rpc); close() }
                                return@coroutineScope outcome
                            }
                            "response" -> if (!frame.optBoolean("success", true) && frame.optString("id") == promptId)
                                throw OmpProtocolException(frame.optString("error", "OMP prompt failed"))
                            else -> mapper.accept(frame)
                        }
                        plan = policy.plan

                    }
                    @Suppress("UNREACHABLE_CODE") null
                } finally {
                    taskActive = false
                    plan = policy.plan
                    withContext(NonCancellable) { host.cancelAll() }
                    toolScope.cancel()
                }
            }
        } catch (c: CancellationException) {
            withContext(NonCancellable) {
                abort(rpc)
                val settled = runCatching { withTimeoutOrNull(2000) {
                    while (true) {
                        val f = rpc.next()
                        if (f.optString("type") == "prompt_result" && f.optString("id") == promptId)
                            return@withTimeoutOrNull f.optBoolean("sessionSettled", false)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                }.getOrDefault(false)
                if (settled != true) close()
            }
            throw c
        } catch (e: Exception) { close(); throw e }
        finally { taskGeneration?.let(rpc::endTask) }
    }
    private suspend fun abort(rpc: OmpRpcClient) {
        withTimeoutOrNull(2000) { runCatching { rpc.send(JSONObject().put("type", "abort")) } }
    }
    suspend fun instruct(message: String) {
        if (taskActive) client?.command("steer", JSONObject().put("message", message))
    }
    @Volatile private var stale = false
    /** A settings change happened: reconnect with fresh config on the next task (never mid-task). */
    fun invalidate() { stale = true }
    override fun close() { taskActive = false; client?.close(); client = null; session = null; preambleAcknowledged = false; model = "" }

    companion object {
        const val INACTIVITY_MS = 120_000L
        const val LOG = "${OmpRuntime.ROOT}/rpc-bridge.log"
        /** The last lines of the bridge/omp log for an error message; empty when unreadable. */
        suspend fun logTail(redactor: OmpDisplayRedactor): String = if (OmpHarness.runtime == null) "" else runCatching {
            val r = withContext(Dispatchers.IO) { withTimeoutOrNull(10_000) { Privileged.exec("sh", "-c", "tail -n 12 ${shellQuote(LOG)} 2>/dev/null") } }
            val text = r?.takeIf { it.ok }?.out?.trim().orEmpty()
            if (text.isEmpty()) "" else "\nomp log:\n" + redactor.redact(text).takeLast(1200)
        }.getOrDefault("")
        private suspend fun startBridge(model: String): OmpRpcClient {
            val runtime = checkNotNull(OmpHarness.runtime) { "OMP runtime unavailable" }
            runtime.writeBroker()
            val server = OmpRpcBridgeServer()
            try {
                // Detach outside proot: exiting a traced shell would kill its children via --kill-on-exit.
                val argv = listOf("/system/bin/sh", "${OmpRuntime.ROOT}/run.sh", OmpRuntime.ROOT, runtime.libDir,
                    "--", "bun", "${OmpRuntime.ROOT}/rpc-bridge.js", server.port.toString(), server.claim.code, "--model", model)
                val script = "test -r ${shellQuote("${OmpRuntime.ROOT}/rpc-bridge.js")} || exit 1; " +
                    ": > ${shellQuote(LOG)}; chmod 600 ${shellQuote(LOG)}; " +
                    "setsid " + argv.joinToString(" ", transform = ::shellQuote) + " </dev/null >>${shellQuote(LOG)} 2>&1 &"
                val result = withContext(Dispatchers.IO) { Privileged.exec("sh", "-c", script) }
                check(result.ok) { "OMP bridge launch failed; repair the harness" }
                return OmpRpcClient(server.awaitConnection())
            } finally { server.close() }
        }
    }
}

/**
 * The first prompt of an omp session. The user's request leads, clearly labelled, so the model never mistakes the
 * long Backstage preamble for the task (device report omp answered the preamble and never saw the message);
 * the request is repeated after the context so it is also the last thing read. Pure.
 */
internal object OmpPrompt {
    const val REPEAT_CHARS = 4_000
    fun first(preamble: String, history: List<Pair<String, String>>, request: String): String = buildString {
        append("USER REQUEST (this is the task; answer or carry it out):\n").append(request).append("\n\n")
        append("=== Backstage session context (reference for how to work, not the task) ===\n")
        append(preamble).append('\n')
        if (history.isNotEmpty()) {
            append("\nEarlier conversation (context data):\n")
            history.forEach { (role, text) -> append(role).append(": ").append(text.take(4000)).append('\n') }
        }
        append("=== End of context ===\n\n")
        append("USER REQUEST (repeated):\n").append(request.take(REPEAT_CHARS))
        if (request.length > REPEAT_CHARS) append("…")
    }
}
