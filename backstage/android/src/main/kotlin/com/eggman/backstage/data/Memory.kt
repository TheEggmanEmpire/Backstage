package com.eggman.backstage.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.eggman.backstage.tools.MemoryItem
import com.eggman.backstage.tools.MemoryService
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Keyword relevance for memory search (no embedding model on device): token overlap with prefix
 * matching for inflections, normalized by query length, with a small recency bonus.
 */
object MemoryScoring {
    private val split = Regex("[^\\p{L}\\p{N}]+")
    private val stop = setOf("the", "a", "an", "of", "to", "and", "or", "is", "my", "me", "i", "in", "on", "for", "what", "who", "der", "die", "das", "und", "ist", "mein", "meine")

    fun tokens(s: String): List<String> = s.lowercase().split(split).filter { it.length > 1 && it !in stop }

    fun score(query: String, content: String, ageMs: Long = 0): Double {
        val q = tokens(query).distinct()
        if (q.isEmpty()) return 0.0
        val c = tokens(content)
        var hit = 0.0
        for (t in q) {
            hit += when {
                c.contains(t) -> 1.0
                c.any { it.startsWith(t) || (t.length >= 4 && t.startsWith(it) && it.length >= 4) } -> 0.7
                else -> 0.0
            }
        }
        if (hit == 0.0) return 0.0
        val recency = 0.05 * (1.0 / (1.0 + ageMs / (30.0 * 86_400_000)))
        return (hit / q.size).coerceAtMost(1.0) * 0.95 + recency
    }

    fun rank(query: String, items: List<MemoryItem>, max: Int, now: Long): List<MemoryItem> =
        items.map { it.copy(score = score(query, it.content, now - it.createdMs)) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(max)

    /** Portable export format (also importable). */
    fun export(items: List<MemoryItem>, appVersion: String, now: Long): JSONObject = JSONObject()
        .put("schema", "backstage.memory.export").put("version", 1).put("appVersion", appVersion).put("exportedAt", now)
        .put("memoryCount", items.size)
        .put("memories", JSONArray().apply { items.forEach { put(JSONObject().put("id", it.id).put("content", it.content).put("source", it.source).put("createdAt", it.createdMs)) } })

    fun import(json: JSONObject): List<MemoryItem> {
        require(json.optString("schema") == "backstage.memory.export") { "Not a Backstage memory export" }
        val arr = json.optJSONArray("memories") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("content").isNotBlank() }
            .map { MemoryItem(it.optString("id").ifBlank { UUID.randomUUID().toString() }, it.optString("content"), it.optString("source", "import"), it.optLong("createdAt")) }
    }
}

class InMemoryMemory(private val clock: () -> Long = System::currentTimeMillis) : MemoryService {
    private val items = LinkedHashMap<String, MemoryItem>()
    override fun save(content: String, source: String): MemoryItem = synchronized(items) {
        items.values.firstOrNull { it.content.equals(content.trim(), true) }?.let { return it }
        MemoryItem(UUID.randomUUID().toString().take(8), content.trim(), source, clock()).also { items[it.id] = it }
    }
    override fun search(query: String, max: Int) = synchronized(items) { MemoryScoring.rank(query, items.values.toList(), max, clock()) }
    override fun all() = synchronized(items) { items.values.sortedByDescending { it.createdMs } }
    override fun delete(id: String) = synchronized(items) { items.remove(id) != null }
}

/** SQLite-backed memory (local only; never sent anywhere except as search results to the user's model). */
class SqliteMemory(context: Context) : MemoryService {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "backstage_memory.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE memories (id TEXT PRIMARY KEY, content TEXT NOT NULL, source TEXT NOT NULL, created INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override fun save(content: String, source: String): MemoryItem {
        val c = content.trim()
        all().firstOrNull { it.content.equals(c, true) }?.let { return it }
        val item = MemoryItem(UUID.randomUUID().toString().take(8), c, source, System.currentTimeMillis())
        helper.writableDatabase.insert("memories", null, ContentValues().apply {
            put("id", item.id); put("content", item.content); put("source", item.source); put("created", item.createdMs)
        })
        return item
    }

    override fun search(query: String, max: Int): List<MemoryItem> = MemoryScoring.rank(query, all(), max, System.currentTimeMillis())

    override fun all(): List<MemoryItem> = helper.readableDatabase.rawQuery("SELECT id, content, source, created FROM memories ORDER BY created DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(MemoryItem(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) }
    }

    override fun delete(id: String): Boolean = helper.writableDatabase.delete("memories", "id = ?", arrayOf(id)) > 0
}
