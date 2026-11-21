package com.eggman.backstage.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import org.json.JSONArray
import org.json.JSONObject

/** One line of a task's visible log (assistant text, tool action, status). */
data class TranscriptEntry(
    val kind: String, // user | assistant | action | status | error
    val text: String,
    val detail: String = "",
    val timeMs: Long = 0,
)

/** Everything kept about a finished task. */
data class TaskRecord(
    val snapshot: TaskSnapshot,
    val target: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double? = null,
    val model: String = "",
    val error: String? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
)

interface TaskHistoryStore {
    suspend fun save(record: TaskRecord)
    suspend fun list(limit: Int): List<TaskRecord>
    suspend fun get(id: String): TaskRecord?
    suspend fun delete(id: String)
    suspend fun clear()
}

/** JSON (de)serialization of task records — pure, unit-tested; also used for export. */
object TaskRecordCodec {
    fun encode(r: TaskRecord): JSONObject = JSONObject()
        .put("v", 1)
        .put("snapshot", encodeSnapshot(r.snapshot))
        .putOpt("target", r.target)
        .put("inputTokens", r.inputTokens)
        .put("outputTokens", r.outputTokens)
        .putOpt("costUsd", r.costUsd)
        .put("model", r.model)
        .putOpt("error", r.error)
        .put("transcript", JSONArray().apply {
            r.transcript.forEach { put(JSONObject().put("k", it.kind).put("t", it.text).put("d", it.detail).put("ms", it.timeMs)) }
        })

    fun decode(o: JSONObject): TaskRecord = TaskRecord(
        snapshot = decodeSnapshot(o.getJSONObject("snapshot")),
        target = o.optString("target").takeIf { o.has("target") && it.isNotEmpty() },
        inputTokens = o.optLong("inputTokens"),
        outputTokens = o.optLong("outputTokens"),
        costUsd = if (o.has("costUsd") && !o.isNull("costUsd")) o.optDouble("costUsd") else null,
        model = o.optString("model"),
        error = if (o.has("error") && !o.isNull("error")) o.optString("error") else null,
        transcript = o.optJSONArray("transcript")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                TranscriptEntry(it.optString("k"), it.optString("t"), it.optString("d"), it.optLong("ms"))
            }
        } ?: emptyList(),
    )

    fun encodeRequest(r: TaskRequest): JSONObject = JSONObject()
        .put("prompt", r.prompt)
        .put("source", r.source.name)
        .put("target", r.target.name)
        .putOpt("planMode", r.planMode)
        .put("attachments", JSONObject(r.attachments as Map<*, *>))
        .put("interactive", r.interactive)
        .putOpt("timeoutMs", r.timeoutMs)

    fun decodeRequest(o: JSONObject): TaskRequest = TaskRequest(
        prompt = o.optString("prompt"),
        source = runCatching { Source.valueOf(o.optString("source")) }.getOrDefault(Source.CHAT),
        target = runCatching { Target.valueOf(o.optString("target")) }.getOrDefault(Target.AUTO),
        planMode = if (o.has("planMode") && !o.isNull("planMode")) o.optBoolean("planMode") else null,
        attachments = o.optJSONObject("attachments")?.let { a -> a.keys().asSequence().associateWith { a.optString(it) } } ?: emptyMap(),
        interactive = o.optBoolean("interactive", true),
        timeoutMs = if (o.has("timeoutMs") && !o.isNull("timeoutMs")) o.optLong("timeoutMs") else null,
    )

    fun encodeSnapshot(s: TaskSnapshot): JSONObject = JSONObject()
        .put("id", s.id)
        .put("request", encodeRequest(s.request))
        .put("status", s.status.name)
        .put("step", s.step)
        .put("maxSteps", s.maxSteps)
        .put("plan", JSONArray().apply { s.plan.forEach { put(JSONObject().put("title", it.title).put("status", it.status)) } })
        .putOpt("lastAction", s.lastAction)
        .putOpt("question", s.question?.let { q -> JSONObject().put("text", q.text).put("confirm", q.confirm).put("choices", JSONArray(q.choices)) })
        .putOpt("result", s.result)
        .put("startedAtMs", s.startedAtMs)
        .putOpt("finishedAtMs", s.finishedAtMs)
        .putOpt("timing", s.timing?.toJson())

    fun decodeSnapshot(o: JSONObject): TaskSnapshot = TaskSnapshot(
        id = o.getString("id"),
        request = decodeRequest(o.getJSONObject("request")),
        status = runCatching { TaskStatus.valueOf(o.optString("status")) }.getOrDefault(TaskStatus.FAILED),
        step = o.optInt("step"),
        maxSteps = o.optInt("maxSteps"),
        plan = o.optJSONArray("plan")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { PlanStep(it.optString("title"), it.optString("status")) }
        } ?: emptyList(),
        lastAction = if (o.has("lastAction") && !o.isNull("lastAction")) o.optString("lastAction") else null,
        question = o.optJSONObject("question")?.let { q ->
            PendingQuestion(q.optString("text"), q.optBoolean("confirm"), q.optJSONArray("choices")?.let { c -> (0 until c.length()).map { c.optString(it) } } ?: emptyList())
        },
        result = if (o.has("result") && !o.isNull("result")) o.optString("result") else null,
        startedAtMs = o.optLong("startedAtMs"),
        finishedAtMs = if (o.has("finishedAtMs") && !o.isNull("finishedAtMs")) o.optLong("finishedAtMs") else null,
        timing = com.eggman.backstage.agent.api.TaskTiming.fromJson(o.optJSONObject("timing")),
    )
}

class MemoryTaskHistory(private val maxRecords: Int = 500) : TaskHistoryStore {
    private val records = LinkedHashMap<String, TaskRecord>()

    override suspend fun save(record: TaskRecord) = synchronized(records) {
        records.remove(record.snapshot.id)
        records[record.snapshot.id] = record
        while (records.size > maxRecords) records.remove(records.keys.first())
    }

    override suspend fun list(limit: Int): List<TaskRecord> = synchronized(records) {
        records.values.sortedByDescending { it.snapshot.startedAtMs }.take(limit)
    }

    override suspend fun get(id: String): TaskRecord? = synchronized(records) { records[id] }
    override suspend fun delete(id: String) = synchronized(records) { records.remove(id); Unit }
    override suspend fun clear() = synchronized(records) { records.clear() }
}

/** SQLite-backed history (android.database.sqlite; no Room dependency). Rows hold the JSON record. */
class SqliteTaskHistory(context: Context, private val maxRecords: () -> Int) : TaskHistoryStore {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "backstage_tasks.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE tasks (id TEXT PRIMARY KEY, started INTEGER NOT NULL, finished INTEGER, status TEXT NOT NULL, " +
                    "source TEXT NOT NULL, prompt TEXT NOT NULL, json TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX tasks_started ON tasks(started DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override suspend fun save(record: TaskRecord) = io {
        val s = record.snapshot
        val db = helper.writableDatabase
        db.insertWithOnConflict("tasks", null, ContentValues().apply {
            put("id", s.id)
            put("started", s.startedAtMs)
            s.finishedAtMs?.let { put("finished", it) }
            put("status", s.status.name)
            put("source", s.request.source.name)
            put("prompt", s.request.prompt.take(2_000))
            put("json", TaskRecordCodec.encode(record).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("DELETE FROM tasks WHERE id NOT IN (SELECT id FROM tasks ORDER BY started DESC LIMIT ?)", arrayOf(maxRecords()))
    }

    override suspend fun list(limit: Int): List<TaskRecord> = io {
        helper.readableDatabase.rawQuery("SELECT json FROM tasks ORDER BY started DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) runCatching { add(TaskRecordCodec.decode(JSONObject(c.getString(0)))) } }
        }
    }

    override suspend fun get(id: String): TaskRecord? = io {
        helper.readableDatabase.rawQuery("SELECT json FROM tasks WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) runCatching { TaskRecordCodec.decode(JSONObject(c.getString(0))) }.getOrNull() else null
        }
    }

    override suspend fun delete(id: String) = io { helper.writableDatabase.delete("tasks", "id = ?", arrayOf(id)); Unit }
    override suspend fun clear() = io { helper.writableDatabase.delete("tasks", null, null); Unit }

    private suspend fun <T> io(block: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
}
