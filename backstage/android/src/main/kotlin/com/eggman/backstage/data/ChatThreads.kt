package com.eggman.backstage.data

import android.content.Context
import com.eggman.backstage.llm.Message
import com.eggman.backstage.llm.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Chat threads: every chat conversation is a thread the user can reopen and continue. The in-app chat and the
 * popup chat show the same current thread; the agent continues a reopened thread with its earlier messages and results
 * ([seedMessages]) and its screen.
 */
data class ThreadEntry(
    /** user | answer | action | question | result | error | info */
    val kind: String,
    val text: String,
    val timeMs: Long = 0,
    val taskId: String = "",
    val source: String? = null,
    val step: Int = 0,
    /** Optional omp display data; missing fields in older stores mean no thinking/output detail. */
    val thinking: String? = null,
    val detail: String? = null,
)

data class ChatThread(
    val id: String,
    val title: String = "",
    val createdMs: Long = 0,
    val lastActiveMs: Long = 0,
    /** The screen the conversation worked on last ("desktop" / "phone"), or null. */
    val target: String? = null,
    val entries: List<ThreadEntry> = emptyList(),
    val taskIds: List<String> = emptyList(),
    val steps: Int = 0,
    /** A user-chosen title stays independent of the first message. */
    val renamed: Boolean = false,
) {
    val messageCount: Int get() = entries.count { it.kind == "user" || it.kind == "answer" }
    val isEmpty: Boolean get() = entries.isEmpty()

    /** The title shown in the list: the user's name for it, else the first message. */
    val displayTitle: String
        get() = title.ifBlank { entries.firstOrNull { it.kind == "user" }?.text?.let(::titleOf) ?: "New chat" }

    companion object {
        const val TITLE_CHARS = 60
        fun titleOf(text: String): String {
            val oneLine = text.replace(Regex("\\s+"), " ").trim()
            return if (oneLine.length <= TITLE_CHARS) oneLine else oneLine.take(TITLE_CHARS - 1).trimEnd() + "…"
        }
    }
}

interface ChatThreadStore {
    /** The thread the chats show and continue (shared by the in-app chat and the popup chat). */
    val currentId: StateFlow<String>
    /** Threads with at least one message, newest activity first. */
    val threads: StateFlow<List<ChatThread>>
    fun get(id: String): ChatThread?
    /** Makes [id] the current thread (false when it does not exist). */
    fun select(id: String): Boolean
    /** Starts a new, empty current thread (the empty current one is reused) and returns its id. */
    fun newThread(): String
    /**
     * A new thread for an automation run (schedule, Tasker, broadcast, MCP), listed at once with [intro] and NOT made
     * current, so the user's open chat stays where it is. null when unsupported.
   */
    fun newAutomationThread(title: String, intro: ThreadEntry): String? = null
    /** A finished task of [threadId]: its lines, steps and screen. */
    fun appendTask(threadId: String, taskId: String, entries: List<ThreadEntry>, steps: Int, target: String?, timeMs: Long)
    fun rename(id: String, title: String)
    fun delete(id: String)
}

/**
 * The thread store: kept in memory, persisted as one JSON file ([file] null = memory only, for tests). At most
 * [maxThreads] threads (the oldest activity goes first) and [MAX_ENTRIES] lines per thread are kept.
 */
class FileChatThreadStore(
    private val file: File?,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxThreads: () -> Int = { DEFAULT_MAX_THREADS },
) : ChatThreadStore {
    private val lock = Any()
    private val all = LinkedHashMap<String, ChatThread>()
    private val _current: MutableStateFlow<String>
    private val _threads = MutableStateFlow<List<ChatThread>>(emptyList())
    override val currentId: StateFlow<String> get() = _current.asStateFlow()
    override val threads: StateFlow<List<ChatThread>> get() = _threads.asStateFlow()

    init {
        var current: String? = null
        runCatching {
            if (file != null && file.exists()) {
                val (threads, cur) = ChatThreadCodec.decode(JSONObject(file.readText()))
                threads.forEach { all[it.id] = it }
                current = cur
            }
        }
        val id = current?.takeIf { it in all } ?: newEmpty().id
        _current = MutableStateFlow(id)
        publish()
    }

    private fun newEmpty(): ChatThread {
        val t = ChatThread(UUID.randomUUID().toString(), createdMs = now(), lastActiveMs = now())
        all[t.id] = t
        return t
    }

    override fun get(id: String): ChatThread? = synchronized(lock) { all[id] }

    override fun select(id: String): Boolean = synchronized(lock) {
        if (id !in all) return false
        val previous = _current.value
        _current.value = id
        dropIfEmpty(previous)
        save()
        true
    }

    override fun newThread(): String = synchronized(lock) {
        val cur = all[_current.value]
        if (cur != null && cur.isEmpty) return cur.id
        val t = newEmpty()
        _current.value = t.id
        save()
        t.id
    }

    override fun newAutomationThread(title: String, intro: ThreadEntry): String = synchronized(lock) {
        val t = ChatThread(UUID.randomUUID().toString(), title = ChatThread.titleOf(title), createdMs = now(), lastActiveMs = now(),
            entries = listOf(intro))
        all[t.id] = t
        prune()
        save()
        t.id
    }

    override fun appendTask(threadId: String, taskId: String, entries: List<ThreadEntry>, steps: Int, target: String?, timeMs: Long) {
        synchronized(lock) {
            val t = all[threadId] ?: ChatThread(threadId, createdMs = timeMs)
            if (taskId in t.taskIds) return
            all[threadId] = t.copy(
                lastActiveMs = timeMs,
                target = target ?: t.target,
                entries = (t.entries + entries.map { it.copy(taskId = taskId) }).takeLast(MAX_ENTRIES),
                taskIds = (t.taskIds + taskId).takeLast(MAX_ENTRIES),
                steps = t.steps + steps,
            )
            prune()
            save()
        }
    }

    override fun rename(id: String, title: String) {
        synchronized(lock) {
            val t = all[id] ?: return
            val clean = title.replace(Regex("\\s+"), " ").trim().take(ChatThread.TITLE_CHARS * 2)
            all[id] = t.copy(title = clean, renamed = clean.isNotEmpty())
            save()
        }
    }

    override fun delete(id: String) {
        synchronized(lock) {
            all.remove(id) ?: return
            if (_current.value == id) _current.value = newEmpty().id
            save()
        }
    }

    private fun dropIfEmpty(id: String) {
        if (id != _current.value && all[id]?.isEmpty == true) all.remove(id)
    }

    /** Keeps the [maxThreads] most recently active threads (never the current one). */
    private fun prune() {
        val keep = maxThreads().coerceIn(MIN_THREADS, MAX_THREADS)
        val listed = all.values.filter { !it.isEmpty }.sortedByDescending { it.lastActiveMs }
        listed.drop(keep).filter { it.id != _current.value }.forEach { all.remove(it.id) }
    }

    private fun publish() {
        _threads.value = all.values.filter { !it.isEmpty }.sortedByDescending { it.lastActiveMs }
    }

    private fun save() {
        publish()
        val f = file ?: return
        runCatching {
            val json = ChatThreadCodec.encode(all.values.filter { !it.isEmpty || it.id == _current.value }, _current.value).toString()
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) { f.writeText(json); tmp.delete() }
        }
    }

    companion object {
        const val DEFAULT_MAX_THREADS = 100
        const val MIN_THREADS = 5
        const val MAX_THREADS = 500
        const val MAX_ENTRIES = 400
    }
}

/** JSON of the thread store (pure, unit-tested). */
object ChatThreadCodec {
    fun encode(threads: Collection<ChatThread>, current: String): JSONObject = JSONObject()
        .put("v", 1)
        .put("current", current)
        .put("threads", JSONArray().apply {
            threads.forEach { t ->
                put(JSONObject()
                    .put("id", t.id).put("title", t.title).put("created", t.createdMs).put("last", t.lastActiveMs)
                    .putOpt("target", t.target).put("steps", t.steps).put("renamed", t.renamed)
                    .put("tasks", JSONArray(t.taskIds))
                    .put("entries", JSONArray().apply {
                        t.entries.forEach { e ->
                            put(JSONObject().put("k", e.kind).put("t", e.text).put("ms", e.timeMs).put("task", e.taskId)
                                .putOpt("src", e.source).put("step", e.step).putOpt("thinking", e.thinking)
                                .putOpt("detail", e.detail))
                        }
                    }))
            }
        })

    fun decode(o: JSONObject): Pair<List<ChatThread>, String?> {
        val list = o.optJSONArray("threads") ?: JSONArray()
        val threads = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { t ->
            val id = t.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val tasks = t.optJSONArray("tasks")
            val entries = t.optJSONArray("entries")
            ChatThread(
                id = id, title = t.optString("title"), createdMs = t.optLong("created"), lastActiveMs = t.optLong("last"),
                target = if (t.has("target") && !t.isNull("target")) t.optString("target") else null,
                steps = t.optInt("steps"), renamed = t.optBoolean("renamed"),
                taskIds = tasks?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                entries = entries?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { e ->
                        ThreadEntry(e.optString("k"), e.optString("t"), e.optLong("ms"), e.optString("task"),
                            if (e.has("src") && !e.isNull("src")) e.optString("src") else null, e.optInt("step"),
                            if (e.has("thinking") && !e.isNull("thinking")) e.optString("thinking") else null,
                            if (e.has("detail") && !e.isNull("detail")) e.optString("detail") else null)
                    }
                } ?: emptyList(),
            )
        }
        return threads to o.optString("current").takeIf { it.isNotBlank() }
    }
}

/** What the agent reads when it continues a thread that is not its live conversation. */
object ThreadContext {
    const val MAX_MESSAGES = 40
    const val MAX_CHARS_PER_MESSAGE = 4_000

    /**
     * The thread's earlier turns as model messages: the user's messages and answers, and each task's outcome. Steps are
     * left out (a finished conversation keeps no screen dumps either). Consecutive lines of one side are merged, and the
     * history starts with the user.
   */
    fun seedMessages(thread: ChatThread?): List<Message> {
        if (thread == null) return emptyList()
        val turns = ArrayList<Pair<Role, String>>()
        for (e in thread.entries) {
            val (role, text) = when (e.kind) {
                "user" -> Role.USER to e.text
                "answer" -> Role.USER to "(my answer) ${e.text}"
                "result", "assistant" -> Role.ASSISTANT to e.text
                "error" -> Role.ASSISTANT to "(the task failed) ${e.text}"
                "info" -> Role.ASSISTANT to "(the task was stopped) ${e.text}"
                "question" -> Role.ASSISTANT to "(I asked) ${e.text}"
                else -> continue
            }
            val clipped = text.take(MAX_CHARS_PER_MESSAGE)
            val last = turns.lastOrNull()
            if (last != null && last.first == role) turns[turns.size - 1] = role to (last.second + "\n" + clipped)
            else turns += role to clipped
        }
        while (turns.isNotEmpty() && turns.first().first != Role.USER) turns.removeAt(0)
        var kept = turns.takeLast(MAX_MESSAGES)
        while (kept.isNotEmpty() && kept.first().first != Role.USER) kept = kept.drop(1)
        return kept.map { (r, t) -> if (r == Role.USER) Message.user(t) else Message.assistant(t) }
    }
}

/** The process's thread store (tests install their own with [set]). */
object ChatThreads {
    @Volatile private var instance: ChatThreadStore? = null

    fun get(context: Context): ChatThreadStore = instance ?: synchronized(this) {
        instance ?: FileChatThreadStore(
            File(context.applicationContext.filesDir, FILE),
            maxThreads = { runCatching {
                Settings(SharedPrefsStore(context.applicationContext.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE))).chatThreadsMax
            }.getOrDefault(FileChatThreadStore.DEFAULT_MAX_THREADS) },
        ).also { instance = it }
    }

    /** The store when one exists (no file access); the agent core uses it through its environment. */
    fun peek(): ChatThreadStore? = instance

    fun set(store: ChatThreadStore?) { instance = store }

    const val FILE = "chat_threads.json"
}
