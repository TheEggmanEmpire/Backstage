package com.eggman.backstage.agent

import com.eggman.backstage.llm.Message
import com.eggman.backstage.llm.Part
import com.eggman.backstage.exec.ScreenDiff

/**
 * Keeps the conversation sent to the model small and current:
 *  - only the newest [keepImages] screenshots are kept (older images are replaced by a note);
 *  - only the newest [keepScreens] full screen listings are kept; older listings in tool results
 *    collapse to their first line (the action outcome);
 *  - when the estimate still exceeds [budgetChars], older tool results and assistant texts are
 *    shortened further. Messages are never removed, so tool_call/tool_result pairs stay intact.
 */
/**
 * When the prompt approaches the model's context window, the middle of the conversation is
 * replaced by an LLM-written summary of the progress so far. The original request and the most
 * recent turns stay verbatim; tool_call/tool_result pairs are never split.
 */
object HistoryCompressor {
    const val KEEP_TAIL = 6

    /** Index of the first kept tail message: an assistant turn, so results stay with their calls. */
    fun splitPoint(messages: List<Message>, keepTail: Int = KEEP_TAIL): Int? {
        if (messages.size < keepTail + 3) return null
        var k = messages.size - keepTail
        while (k > 1 && messages[k].role != com.eggman.backstage.llm.Role.ASSISTANT) k--
        if (k <= 1) return null
        // Keep the original tail boundary. compress carries required screen text across it without pinning history.
        return k
    }

    fun render(messages: List<Message>): String = buildString {
        for (m in messages) {
            for (p in m.parts) when (p) {
                is Part.Text -> if (p.text.isNotBlank()) append(if (m.role == com.eggman.backstage.llm.Role.USER) "User: " else "Agent: ").append(p.text.take(1_000)).append('\n')
                is Part.ToolCall -> append("Action: ").append(p.name).append(' ').append(com.eggman.backstage.tools.TypeTextTool.redactedArguments(p.name, p.args).toString().take(300)).append('\n')
                is Part.ToolResult -> append("Result: ").append(ContextManager.firstLine(p.text, 300)).append('\n')
                is Part.Image -> Unit
            }
        }
    }

    /**
     * Saves durable facts about the user found in [messages] to long-term memory before they are summarized
     * away. Returns how many are stored; any failure is tolerated (returns 0).
   */
    suspend fun flushToMemory(
        llm: com.eggman.backstage.llm.LlmClient,
        messages: List<Message>,
        memory: com.eggman.backstage.tools.MemoryService,
        maxFacts: Int = 5,
    ): Int {
        val notes = render(messages).take(40_000)
        if (notes.isBlank()) return 0
        val reply = try {
            llm.complete(
                com.eggman.backstage.llm.LlmRequest(
                    system = "You read an assistant's working notes and keep only lasting facts about its user that would help with later " +
                        "requests: preferences, people and how they relate to the user, places, accounts in use, routines. Write each fact as " +
                        "one plain sentence on its own line, at most $maxFacts lines. If there is nothing of that kind, reply NONE.",
                    messages = listOf(Message.user(notes)),
                    maxTokens = 512,
                    toolChoice = com.eggman.backstage.llm.ToolChoice.NONE,
                ),
            ).message.text
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return 0
        }
        val facts = reply.lines().map { it.trim().trimStart('-', '*', '•').trim() }
            .filter { it.length in 8..300 && !it.equals("NONE", ignoreCase = true) }
            .distinct().take(maxFacts)
        var saved = 0
        for (f in facts) {
            val known = runCatching { memory.search(f, 3).any { it.content.equals(f, ignoreCase = true) } }.getOrDefault(false)
            if (!known && runCatching { memory.save(f, SOURCE_FLUSH) }.isSuccess) saved++
        }
        return saved
    }

    const val SOURCE_FLUSH = "auto: before history compression"

    /**
     * Returns true when the conversation is compressed. Failures leave it untouched. [anchor] is the message
     * kept verbatim in front (the request being worked on; in a multi-turn chat that is not the first turn).
   */
    suspend fun compress(llm: com.eggman.backstage.llm.LlmClient, conversation: MutableList<Message>, keepTail: Int = KEEP_TAIL, anchor: Int = 0): Boolean {
        val k = splitPoint(conversation, keepTail) ?: return false
        val keepAnchor = anchor in 0 until k
        val middle = conversation.subList(0, k).filterIndexed { i, _ -> if (keepAnchor) i != anchor else true }
        val summary = try {
            llm.complete(
                com.eggman.backstage.llm.LlmRequest(
                    system = "You compress an agent's working history. Summarize what has been done, what was found on screen, " +
                        "which steps of the task are complete, and anything still pending — concise, factual, no speculation.",
                    messages = listOf(Message.user(render(middle).take(60_000))),
                    maxTokens = 1_024,
                    toolChoice = com.eggman.backstage.llm.ToolChoice.NONE,
                ),
            ).message.text.trim()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (summary.isBlank()) return false
        val tail = conversation.subList(k, conversation.size).toList()
        val kept = if (keepAnchor) conversation[anchor] else null
        val retainedScreens = ContextManager.protectedScreenParts(conversation)
            .filter { (mi, _) -> mi < k && (!keepAnchor || mi != anchor) }
            .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
            .mapNotNull { (mi, pi) ->
                val text = when (val part = conversation[mi].parts[pi]) {
                    is Part.Text -> part.text
                    is Part.ToolResult -> part.text
                    else -> null
                }
                text?.let { value -> ContextManager.SCREEN.find(value)?.let { Part.Text(value.substring(it.range.first)) } }
            }
        conversation.clear()
        kept?.let { conversation += it }
        conversation += Message.user("Summary of the earlier steps (the full history was condensed to save context):\n$summary")
            .let { it.copy(parts = it.parts + retainedScreens) }
        conversation += tail
        // The task's registered secrets remain in scope; only the model's observation chain is compressed.
        com.eggman.backstage.tools.Observe.invalidate()
        return true
    }
}

class ContextManager(
    private val keepScreens: Int = 2,
    /** Number of recent screenshots kept. */
    private val keepImages: Int = 2,
    private val budgetChars: Int = 240_000,
    /**
     * Older listings are collapsed in batches of this many: a collapsed listing never changes again and the
     * collapsed set grows only every [collapseBatch] steps, so the conversation prefix stays byte-identical across most
     * steps and the provider's prompt cache keeps hitting. 1 = collapse every step (the previous behaviour).
   */
    private val collapseBatch: Int = 1,
) {
    fun prepare(messages: List<Message>): List<Message> {
        val withImages = stripOldImages(messages)
        val withScreens = collapseOldScreens(withImages)
        // Always a snapshot: the caller keeps mutating the live conversation.
        return enforceBudget(withScreens).toList()
    }

    /** After a task in a multi-turn chat ends, drop its bulky observations entirely (also the screen sent with the request). */
    fun compactFinished(messages: List<Message>): List<Message> = messages.map { m ->
        if (m.parts.none { it is Part.ToolResult || it is Part.Image || (it is Part.Text && SCREEN.containsMatchIn(it.text)) }) m
        else m.copy(parts = m.parts.mapNotNull { p ->
            when (p) {
                is Part.Image -> null
                is Part.ToolResult -> p.copy(text = firstLine(p.text, 200), image = null)
                is Part.Text -> if (SCREEN.containsMatchIn(p.text)) Part.Text(collapseScreen(p.text)) else p
                else -> p
            }
        }.ifEmpty { listOf(Part.Text("(earlier observations omitted)")) })
    }

    private fun stripOldImages(messages: List<Message>): List<Message> {
        val withImages = messages.indices.filter { messages[it].hasImage }
        if (withImages.size <= keepImages) return messages
        val keep = withImages.takeLast(keepImages).toSet()
        return messages.mapIndexed { i, m ->
            if (i in keep || !m.hasImage) m
            else m.copy(parts = m.parts.mapNotNull { p ->
                when (p) {
                    is Part.Image -> Part.Text("[older screenshot removed]")
                    is Part.ToolResult -> if (p.image != null) p.copy(text = p.text + "\n[older screenshot removed]", image = null) else p
                    else -> p
                }
            })
        }
    }

    private fun collapseOldScreens(messages: List<Message>): List<Message> {
        val hits = screenParts(messages)
        val full = hits.filter { !it.diff }
        val protected = protectedScreenParts(messages)
        val candidates = full.filter { it.position !in protected }
        val excess = (full.size - keepScreens.coerceAtLeast(1)).coerceIn(0, candidates.size)
        val batch = collapseBatch.coerceAtLeast(1)
        val oldFull = candidates.take(excess / batch * batch).map { it.position }.toSet()
        // A full listing and the diffs that follow it are one retention unit for that display.
        val collapsing = HashMap<String, Boolean>()
        val collapse = hits.filter { hit ->
            if (!hit.diff) collapsing[hit.screen] = hit.position in oldFull
            collapsing[hit.screen] == true && hit.position !in protected
        }.map { it.position }.toSet()
        if (collapse.isEmpty()) return messages
        return messages.mapIndexed { mi, m ->
            if (collapse.none { it.first == mi }) m
            else m.copy(parts = m.parts.mapIndexed { pi, p ->
                when {
                    (mi to pi) !in collapse -> p
                    p is Part.ToolResult -> p.copy(text = collapseScreen(p.text))
                    p is Part.Text -> Part.Text(collapseScreen(p.text))
                    else -> p
                }
            })
        }
    }

    private fun enforceBudget(messages: List<Message>): List<Message> {
        var current = messages
        if (size(current) <= budgetChars) return current
        // Pass 1: shorten old results, collapsing superseded screen chains together even across the short tail.
        val protect = (current.size - 3).coerceAtLeast(0)
        val screenChain = protectedScreenParts(current)
        val oldScreens = screenParts(current).map { it.position }.toSet() - screenChain
        current = current.mapIndexed { i, m ->
            m.copy(parts = m.parts.mapIndexed { pi, p ->
                when {
                    (i to pi) in oldScreens && p is Part.ToolResult -> p.copy(text = collapseScreen(p.text))
                    (i to pi) in oldScreens && p is Part.Text -> Part.Text(collapseScreen(p.text))
                    i < protect && p is Part.ToolResult && (i to pi) !in screenChain -> p.copy(text = firstLine(p.text, 300))
                    else -> p
                }
            })
        }
        if (size(current) <= budgetChars) return current
        // Pass 2: shorten long texts in the middle of the conversation (keep the first user request).
        current = current.mapIndexed { i, m ->
            if (i == 0 || i >= protect) m else m.copy(parts = m.parts.mapIndexed { pi, p ->
                if (p is Part.Text && p.text.length > 600 && (i to pi) !in screenChain) Part.Text(p.text.take(600) + " …") else p
            })
        }
        return current
    }

    companion object {
        /** First line of the screen header produced by ScreenSerializer. */
        val SCREEN = Regex("(?m)^Screen (desktop|phone) \\(display ")

        private data class ScreenPart(val position: Pair<Int, Int>, val screen: String, val diff: Boolean)

        private fun screenParts(messages: List<Message>): List<ScreenPart> = buildList {
            messages.forEachIndexed { mi, message -> message.parts.forEachIndexed { pi, part ->
                val text = when (part) { is Part.Text -> part.text; is Part.ToolResult -> part.text; else -> null }
                val header = text?.let { SCREEN.find(it) }
                if (text != null && header != null) {
                    val display = text.substring(header.range.last + 1).substringBefore(',').substringBefore(')').trim()
                    add(ScreenPart(mi to pi, "${header.groupValues[1]}:$display", text.lineSequence().any { it == ScreenDiff.MARKER }))
                }
            } }
        }

        /** Protect full listings only while a dependent diff is retained. Standalone screens may be summarized. */
        internal fun protectedScreenParts(messages: List<Message>): Set<Pair<Int, Int>> {
            val hits = screenParts(messages)
            val latest = hits.filter { !it.diff }.associateBy { it.screen }
                .filterValues { base -> hits.any { hit -> hit.diff && hit.screen == base.screen &&
                    (hit.position.first > base.position.first ||
                        (hit.position.first == base.position.first && hit.position.second > base.position.second)) } }
            return hits.filter { hit ->
                val base = latest[hit.screen]?.position
                base != null && (hit.position.first > base.first ||
                    (hit.position.first == base.first && hit.position.second >= base.second))
            }.map { it.position }.toSet()
        }

        fun size(messages: List<Message>): Int = messages.sumOf { m ->
            m.parts.sumOf { p ->
                when (p) {
                    is Part.Text -> p.text.length
                    is Part.ToolResult -> p.text.length + (p.image?.let { 6_000 } ?: 0)
                    is Part.Image -> 6_000
                    is Part.ToolCall -> p.args.toString().length + p.name.length
                }
            }
        }

        fun collapseScreen(text: String): String {
            val m = SCREEN.find(text) ?: return text
            val head = text.substring(0, m.range.first).trimEnd()
            val headerLine = text.substring(m.range.first).lineSequence().first()
            return (if (head.isNotEmpty()) "$head\n" else "") + "[$headerLine — older screen listing omitted; indices are no longer valid]"
        }

        fun firstLine(text: String, max: Int): String {
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            return if (line.length > max) line.take(max) + "…" else line + if (text.length > line.length + 1) " […]" else ""
        }
    }
}
