package com.eggman.backstage.automation

import android.content.Context
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.schedule.ScheduleStorage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A saved, named task the user can run with one tap (chat, bubble, home-screen widget). */
data class QuickAction(val id: String, val name: String, val prompt: String, val target: Target = Target.AUTO)

/** Ordered quick-action list persisted as JSON. Pure apart from the storage seam. */
class QuickActionStore(private val storage: ScheduleStorage) {
    private val lock = Any()

    fun list(): List<QuickAction> = synchronized(lock) { decode(storage.load()) }

    fun get(id: String): QuickAction? = list().firstOrNull { it.id == id }

    fun upsert(action: QuickAction): QuickAction = synchronized(lock) {
        val clean = action.copy(
            id = action.id.ifBlank { UUID.randomUUID().toString() },
            name = action.name.trim().ifEmpty { action.prompt.trim().take(30) },
            prompt = action.prompt.trim(),
        )
        require(clean.prompt.isNotEmpty()) { "empty prompt" }
        val all = decode(storage.load())
        val idx = all.indexOfFirst { it.id == clean.id }
        val next = if (idx >= 0) all.toMutableList().also { it[idx] = clean } else all + clean
        storage.save(encode(next))
        clean
    }

    fun delete(id: String) = synchronized(lock) { storage.save(encode(decode(storage.load()).filterNot { it.id == id })) }

    /** Move an item up (-1) or down (+1); out-of-range moves are ignored. */
    fun move(id: String, delta: Int) = synchronized(lock) {
        val all = decode(storage.load()).toMutableList()
        val i = all.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in all.indices) return
        all.add(j, all.removeAt(i))
        storage.save(encode(all))
    }

    companion object {
        fun encode(list: List<QuickAction>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt).put("target", it.target.name)) }
        }.toString()

        fun decode(json: String?): List<QuickAction> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val prompt = o.optString("prompt")
                if (prompt.isBlank()) null else QuickAction(
                    o.optString("id"), o.optString("name"), prompt,
                    runCatching { Target.valueOf(o.optString("target")) }.getOrDefault(Target.AUTO),
                )
            }
        }

        @Volatile
        private var instance: QuickActionStore? = null

        fun get(context: Context): QuickActionStore = instance ?: synchronized(this) {
            instance ?: QuickActionStore(object : ScheduleStorage {
                private val prefs = context.applicationContext.getSharedPreferences("backstage_quick_actions", Context.MODE_PRIVATE)
                override fun load(): String? = prefs.getString("actions", null)
                override fun save(json: String) {
                    prefs.edit().putString("actions", json).apply()
                }
            }).also { instance = it }
        }
    }
}
