package com.eggman.backstage.omp.engine

import com.eggman.backstage.agent.EndReason
import com.eggman.backstage.agent.LoopListener
import com.eggman.backstage.agent.LoopOutcome
import com.eggman.backstage.agent.api.chatTail
import com.eggman.backstage.llm.Part
import com.eggman.backstage.llm.Usage
import com.eggman.backstage.tools.ToolResult
import org.json.JSONObject

/** Projects omp's open-record events onto the controller's transcript/snapshot callbacks. */
class OmpEventMapper(
    private val listener: LoopListener,
    private val isHostTool: (String) -> Boolean = { it.startsWith(OmpHostTools.PREFIX) },
    private val redactor: OmpDisplayRedactor = OmpDisplayRedactor.DEFAULT,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var answerText = ""
    val answer: String get() = if (textDirty) redactor.redact(textContent("")) else answerText
    var yielded = false; private set
    private var messageId = ""
    private class Block(val kind: String, text: String = "", val narration: Boolean = false, var hidden: Boolean = false) {
        val text = StringBuilder(text)
    }
    private var textDirty = false
    private var thinkingDirty = false
    private var thinkingStreaming = false
    private var lastPublicationMs: Long? = null
    private val blocks = sortedMapOf<Int, Block>()
    private var lastThinking: Pair<String, Boolean>? = null
    private val meteredMessages = mutableSetOf<String>()
    private val endedMessages = mutableSetOf<String>()
    private val seenContent = mutableSetOf<String>()
    private var error: String? = null
    private val tools = mutableMapOf<String, Part.ToolCall>()
    private var steps = 0
    private val subagents = mutableMapOf<String, Part.ToolCall>()
    private val finishedSubagents = mutableSetOf<String>()
    suspend fun beforeTurn() { listener.beforeStep() }

    fun accept(frame: JSONObject) {
        when (frame.optString("type")) {
            "message_start" -> if (frame.optJSONObject("message")?.optString("role") == "assistant") startMessage(frame.optString("messageId"))
            "message_update" -> {
                if (frame.optJSONObject("message")?.optString("role", "assistant") != "assistant" && frame.has("message")) return
                val event = frame.optJSONObject("assistantMessageEvent") ?: return
                val id = frame.optString("messageId")
                if (id.isNotEmpty() && id != messageId) startMessage(id)
                val type = event.optString("type")
                if (type == "start") blocks.clear()
                val index = event.optInt("contentIndex")
                val metadata = event.optJSONObject("block") ?: event.optJSONObject("partial")?.optJSONArray("content")?.optJSONObject(index)
                when (type) {
                    "text_start", "thinking_start" -> {
                        blocks[index] = Block(if (type == "text_start") "text" else "thinking", narration = metadata?.optBoolean("narration") == true)
                        if (type == "thinking_start") { thinkingDirty = true; emitThinking(true, force = true) }
                    }
                    "text_delta", "thinking_delta", "text_end", "thinking_end" -> {
                        val thinking = type.startsWith("thinking")
                        val block = blocks.getOrPut(index) { Block(if (thinking) "thinking" else "text", narration = metadata?.optBoolean("narration") == true) }
                        if (type.endsWith("_end")) { block.text.setLength(0); block.text.append(event.optString("content")) }
                        else block.text.append(event.optString("delta"))
                        block.hidden = thinking && block.text.isEmpty() && !metadata?.optString("thinkingSignature").isNullOrEmpty()
                        if (thinking && !block.narration) {
                            boundThinking()
                            thinkingDirty = true
                            emitThinking(!type.endsWith("_end"), force = type.endsWith("_end"))
                        } else {
                            textDirty = true
                            emitText(false, force = type.endsWith("_end"))
                        }
                    }
                }
                // Redacted blocks have no dedicated AssistantMessageEvent variant.
                event.optJSONObject("partial")?.optJSONArray("content")?.let { content ->
                    for (i in 0 until content.length()) if (content.optJSONObject(i)?.optString("type") == "redactedThinking") {
                        blocks[i] = Block("thinking", hidden = true)
                        thinkingDirty = true
                        emitThinking(type != "done" && type != "error")
                    }
                }
            }
            "message_end" -> frame.optJSONObject("message")?.let { m ->
                if (m.optString("role") != "assistant") return
                val id = frame.optString("messageId")
                if (id.isNotEmpty() && !endedMessages.add(id)) return
                if (id.isNotEmpty() && id != messageId) startMessage(id)
                message(m, meteredMessages.add(id))
            }
            "agent_end" -> {
                flush()
                yielded = frame.optBoolean("yielded", frame.optBoolean("isTerminal", true))
                frame.optJSONArray("messages")?.let { a -> (0 until a.length()).forEach { i ->
                    a.optJSONObject(i)?.let { m ->
                        if (m.optString("role") == "assistant" && contentKey(m) !in seenContent) {
                            startMessage("")
                            message(m, false)
                        }
                    }
                } }
            }
            "tool_execution_start" -> {
                flush()
                val name = frame.optString("toolName")
                if (!isHostTool(name)) {
                    val call = Part.ToolCall(frame.optString("toolCallId"), name, frame.optJSONObject("args") ?: JSONObject())
                    tools[call.id] = call; steps++; listener.onToolCall(call, toolSummary(name, call.args, redactor))
                }
            }
            "tool_execution_end" -> tools.remove(frame.optString("toolCallId"))?.let { call ->
                listener.onToolResult(call, ToolResult(redactor.redact(contentText(frame.optJSONObject("result"))), isError = frame.optBoolean("isError")))
            }
            "subagent_lifecycle", "subagent_progress" -> {
                val payload = frame.optJSONObject("payload") ?: return
                val progress = payload.optJSONObject("progress")
                val uniqueId = if (frame.optString("type") == "subagent_lifecycle") payload.optString("id") else progress?.optString("id").orEmpty()
                val id = uniqueId.ifBlank { payload.optString("agent", "subagent") + "#" + payload.optInt("index") }
                val status = progress?.optString("status")?.takeIf { it.isNotBlank() } ?: payload.optString("status", "working")
                if (id in finishedSubagents) {
                    // Unique IDs are never a new run. Only legacy, ID-less slots may be reused.
                    if (uniqueId.isNotBlank() || status != "started") return
                    finishedSubagents.remove(id)
                    subagents.remove(id)
                }
                val call = subagents.getOrPut(id) {
                    val args = JSONObject().put("agent", payload.optString("agent", "subagent"))
                        .put("description", payload.optString("description").ifBlank { progress?.optString("description").orEmpty().ifBlank { payload.optString("task") } })
                    Part.ToolCall("subagent:$id", "subagent", args).also {
                        steps++; listener.onToolCall(it, toolSummary(it.name, args, redactor))
                    }
                }
                listener.onToolProgress(call, oneLine(redactor.redact(status)))
                if (status in setOf("completed", "failed", "aborted") && finishedSubagents.add(id)) {
                    val output = progress?.optJSONArray("recentOutput")?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } }.orEmpty()
                    listener.onToolResult(call, ToolResult(redactor.redact(output.ifBlank { status }), isError = status != "completed"))
                }
            }
            "rpc_frame_error" -> error = "OMP event exceeded the transport limit"
            "notice" -> if (frame.optString("level") == "error") error = redactor.redact(frame.optString("message"))
        }
    }

    private fun startMessage(id: String) {
        flush()
        listener.onAssistantMessageStart()
        messageId = id
        blocks.clear()
        textDirty = false
        thinkingDirty = false
        lastThinking = null
        lastPublicationMs = null
    }

    private fun textContent(separator: String) = blocks.values.filter { it.kind == "text" || it.narration }
        .joinToString(separator) { it.text.toString() }

    private fun publicationDue(force: Boolean): Boolean {
        val now = clockMs()
        val last = lastPublicationMs
        if (!force && last != null && now - last < 50) return false
        lastPublicationMs = now
        return true
    }

    private fun emitText(final: Boolean, force: Boolean = false) {
        if (!textDirty || !publicationDue(force)) return
        val text = redactor.redact(textContent(""))
        if (text.isNotBlank()) {
            answerText = text
            if (lastThinking?.second == true) emitThinking(false, force = true)
            listener.onAssistantText(text, final)
        }
        textDirty = false
    }

    private fun boundThinking() {
        // Reasoning never grows with the answer: trim retained buffers on append, before publication.
        var remaining = 8192
        blocks.values.reversed().filter { it.kind == "thinking" && !it.narration }.forEach {
            val bounded = chatTail(it.text.toString(), remaining)
            if (bounded.length != it.text.length) { it.text.setLength(0); it.text.append(bounded) }
            remaining = (remaining - bounded.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        }
    }

    private fun emitThinking(streaming: Boolean, force: Boolean = false) {
        thinkingStreaming = streaming
        if (!thinkingDirty && lastThinking?.second == streaming) return
        val thinking = blocks.values.filter { it.kind == "thinking" && !it.narration }
        if (thinking.isEmpty() || !publicationDue(force)) return
        boundThinking()
        val text = chatTail(redactor.redact(thinking.joinToString("\n") { if (it.hidden) HIDDEN_THINKING else it.text.toString() }), 8192)
        val value = text to (streaming && blocks.values.none { (it.kind == "text" || it.narration) && it.text.isNotEmpty() })
        if (value != lastThinking) { lastThinking = value; listener.onThinking(text, value.second) }
        thinkingDirty = false
    }

    /** Flush at every boundary, including streams with no message_end, so coalescing never loses their tail. */
    private fun flush() {
        if (thinkingDirty) emitThinking(thinkingStreaming, force = true)
        if (textDirty) emitText(false, force = true)
    }

    private fun message(m: JSONObject, meter: Boolean) {
        seenContent += contentKey(m)
        blocks.clear()
        m.optJSONArray("content")?.let { a -> for (i in 0 until a.length()) {
            val b = a.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> blocks[i] = Block("text", b.optString("text"))
                "thinking" -> blocks[i] = Block("thinking", b.optString("thinking"), b.optBoolean("narration"),
                    b.optString("thinking").isEmpty() && b.optString("thinkingSignature").isNotEmpty())
                "redactedThinking" -> blocks[i] = Block("thinking", hidden = true)
            }
        } }
        thinkingDirty = true
        emitThinking(false, force = true)
        val text = redactor.redact(textContent("\n"))
        textDirty = false
        if (text.isNotBlank()) { answerText = text; listener.onAssistantText(text, true) }
        if (m.optString("stopReason") in setOf("error", "aborted")) error = redactor.redact(m.optString("errorMessage", "OMP turn failed"))
        m.optJSONObject("usage")?.takeIf { meter }?.let { u -> listener.onUsage(m.optString("model"), Usage(
            inputTokens = u.optLong("input"), outputTokens = u.optLong("output"), cacheReadTokens = u.optLong("cacheRead"), cacheWriteTokens = u.optLong("cacheWrite"))) }
    }

    private fun contentKey(m: JSONObject): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(m.optJSONArray("content")?.toString().orEmpty().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun outcome(): LoopOutcome {
        flush()
        return if (error != null) LoopOutcome(false, error!!, steps, EndReason.LLM_ERROR)
            else LoopOutcome(true, answer.ifBlank { "Done." }, steps, EndReason.ANSWERED)
    }

    companion object {
        const val HIDDEN_THINKING = "Thinking (hidden by provider)"
        private fun oneLine(text: String) = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 240) it.take(239) + "…" else it }
        fun toolSummary(name: String, args: JSONObject, redactor: OmpDisplayRedactor = OmpDisplayRedactor.DEFAULT): String {
            fun string(vararg keys: String) = keys.firstNotNullOfOrNull { (args.opt(it) as? String)?.takeIf(String::isNotBlank) }.orEmpty()
            val detail = when (name.lowercase()) {
                "bash" -> string("command")
                "read", "edit", "write" -> string("path", "file_path", "file")
                "grep", "find", "search" -> string("pattern", "query")
                "task", "subagent" -> listOf(string("agent"), string("description", "task")).filter(String::isNotBlank).joinToString(" · ")
                else -> ""
            }
            return oneLine(redactor.redact(if (detail.isBlank()) name else "$name: $detail"))
        }
        fun contentText(result: JSONObject?): String {
            val a = result?.optJSONArray("content") ?: return ""
            return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.takeIf { it.optString("type") == "text" }?.optString("text") }.joinToString("\n")
        }
    }
}
