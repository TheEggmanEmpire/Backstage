package com.eggman.backstage.omp.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A host request retains the task authority present when the socket reader received it. */
class OmpRpcEvent internal constructor(
    val frame: JSONObject,
    internal val generation: Long?,
    internal val bytes: Int,
)

/** A single reader always drains stdout, including while a host tool waits for approval. */
class OmpRpcClient(private val socket: Socket) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Mutex()
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    // Never suspend the reader on events: responses have an independent delivery path. Both the
    // number and combined serialized size of queued events are bounded; overflow breaks the task.
    private val frames = Channel<OmpRpcEvent>(256)
    val events: kotlinx.coroutines.channels.ReceiveChannel<OmpRpcEvent> get() = frames
    private val queuedBytes = AtomicLong()
    private val ready = CompletableDeferred<JSONObject>()
    private val lifecycle = Any()
    private data class ActiveTask(val generation: Long, val promptId: String)
    private var activeTask: ActiveTask? = null
    private var generation = 0L
    @Volatile var alive = true; private set
    private var maxFrameBytes = OmpJsonl.MAX_FRAME_BYTES
    init {
        scope.launch {
            try {
                val decoder = OmpFrameDecoder()
                val lines = OmpJsonl(socket.getInputStream(), onMalformed = {
                    if (decoder.assembling) throw OmpProtocolException("Malformed line interrupted OMP chunks")
                })
                while (isActive) {
                    val physical = lines.read() ?: throw OmpProtocolException("OMP bridge disconnected")
                    val frame = decoder.accept(physical) ?: continue
                    when (frame.optString("type")) {
                        "ready" -> ready.complete(frame)
                        "response" -> {
                            if (!frame.optBoolean("success", true) || frame.optJSONObject("data")?.optBoolean("agentInvoked", true) == false)
                                finishPrompt(frame.optString("id"))
                            // Later prompt failures may follow the admission ACK; forward those to the task.
                            val waiter = pending.remove(frame.optString("id"))
                            if (waiter != null) waiter.complete(frame) else if (!frame.optBoolean("success", true)) enqueue(frame)
                        }
                        "host_tool_call" -> {
                            val task = synchronized(lifecycle) { activeTask }
                            if (task == null) rejectHostCall(frame)
                            enqueue(frame, task.generation)
                        }
                        "host_tool_cancel" -> {
                            val task = synchronized(lifecycle) { activeTask }
                                ?: throw OmpProtocolException("OMP host tool cancellation outside an active task")
                            enqueue(frame, task.generation)
                        }
                        "prompt_result" -> {
                            // Retire authority on receipt, even if the task consumer is paused or behind.
                            finishPrompt(frame.optString("id"))
                            enqueue(frame)
                        }
                        else -> enqueue(frame)
                    }
                }
            } catch (t: Throwable) {
                alive = false
                synchronized(lifecycle) { activeTask = null }
                ready.completeExceptionally(t)
                pending.values.forEach { it.completeExceptionally(t) }
                frames.close(t)
            } finally { alive = false; socket.close() }
        }
    }

    fun beginTask(promptId: String): Long = synchronized(lifecycle) {
        check(alive) { "OMP bridge is closed" }
        check(activeTask == null) { "OMP task already active" }
        (++generation).also { activeTask = ActiveTask(it, promptId) }
    }

    fun endTask(taskGeneration: Long) = synchronized(lifecycle) {
        if (activeTask?.generation == taskGeneration) activeTask = null
    }

    private fun finishPrompt(promptId: String) = synchronized(lifecycle) {
        if (activeTask?.promptId == promptId) activeTask = null
    }

    /** Must be checked immediately before dispatch into the task-owned ToolContext. */
    suspend fun requireHostTask(event: OmpRpcEvent, taskGeneration: Long) {
        val allowed = synchronized(lifecycle) { alive && event.generation == taskGeneration && activeTask?.generation == taskGeneration }
        if (!allowed) {
            try {
                if (event.frame.optString("type") == "host_tool_call") rejectHostCall(event.frame)
                throw OmpProtocolException("OMP host request belongs to a finished task")
            } finally { close() }
        }
    }

    private suspend fun rejectHostCall(frame: JSONObject): Nothing {
        runCatching {
            send(JSONObject().put("type", "host_tool_result").put("id", frame.optString("id"))
                .put("isError", true).put("result", JSONObject().put("content", JSONArray().put(
                    JSONObject().put("type", "text").put("text", "Host tool request rejected: no active task authority.")))))
        }
        throw OmpProtocolException("OMP host tool call outside its active task")
    }

    private fun enqueue(frame: JSONObject, taskGeneration: Long? = null) {
        val size = frame.toString().toByteArray(Charsets.UTF_8).size
        if (queuedBytes.addAndGet(size.toLong()) > 8L * 1024 * 1024) {
            queuedBytes.addAndGet(-size.toLong())
            throw OmpProtocolException("OMP event buffer exceeds 8 MiB")
        }
        if (!frames.trySend(OmpRpcEvent(frame, taskGeneration, size)).isSuccess) {
            queuedBytes.addAndGet(-size.toLong())
            throw OmpProtocolException("OMP event buffer overflow")
        }
    }

    /** Also used by select's receive branch so every dequeue releases its byte budget. */
    fun consume(event: OmpRpcEvent): OmpRpcEvent {
        queuedBytes.addAndGet(-event.bytes.toLong())
        return event
    }

    suspend fun nextEvent(): OmpRpcEvent = consume(frames.receive())
    suspend fun next(): JSONObject = nextEvent().frame

    suspend fun initialize() {
        val r = withTimeoutOrNull(90_000) { ready.await() } ?: throw OmpProtocolException("omp did not start within 90 s")
        maxFrameBytes = r.optInt("maxFrameBytes", OmpJsonl.MAX_FRAME_BYTES).coerceIn(256, OmpJsonl.MAX_FRAME_BYTES)
        val versions = r.optJSONArray("supportedProtocolVersions")
        check(versions != null && (0 until versions.length()).any { versions.optInt(it) == 2 }) { "OMP protocol v2 required" }
        command("negotiate_protocol", JSONObject().put("protocolVersion", 2))
        command("set_subagent_subscription", JSONObject().put("level", "progress"))
    }
    suspend fun send(frame: JSONObject) = withContext(Dispatchers.IO) {
        val bytes = (frame.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > maxFrameBytes) throw OmpProtocolException("OMP input frame exceeds $maxFrameBytes bytes")
        writes.withLock {
            check(alive) { "OMP bridge is closed" }
            socket.getOutputStream().apply { write(bytes); flush() }
        }
    }
    suspend fun command(type: String, args: JSONObject = JSONObject(), id: String = "backstage_${ids.incrementAndGet()}"): JSONObject {
        val result = CompletableDeferred<JSONObject>()
        check(pending.putIfAbsent(id, result) == null)
        try {
            val frame = JSONObject(args.toString()).put("type", type).put("id", id)
            send(frame)
            val response = withTimeoutOrNull(30_000) { result.await() } ?: throw OmpProtocolException("OMP $type response timed out")
            if (!response.optBoolean("success")) throw OmpProtocolException("OMP $type failed: ${response.optString("error").take(500)}")
            return response
        } finally { pending.remove(id) }
    }
    override fun close() {
        alive = false
        synchronized(lifecycle) { activeTask = null }
        socket.close(); scope.cancel()
        val error = OmpProtocolException("OMP bridge closed")
        ready.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        frames.close(error)
    }
}
