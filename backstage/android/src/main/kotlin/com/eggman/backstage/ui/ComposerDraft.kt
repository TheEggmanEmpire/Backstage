package com.eggman.backstage.ui

import android.content.Context
import com.eggman.backstage.data.KeyValueStore
import com.eggman.backstage.data.SharedPrefsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The chat composer's unsent message. One
 * draft shared by the in-app chat and the popup chat, so the user continues where they typed on either. It survives
 * tab switches, leaving the app, activity recreation and process death, and is cleared only when the message is sent
 * or the user clears the field.
 *
 * [prefilled] and [attachments] travel with the text: text another app or a link put in the field must never count as
 * the user's own words ([com.eggman.backstage.agent.api.TaskRequest.waiverText]), also after a restore.
 */
data class ComposerDraft(
    val text: String = "",
    /** The Plan chip: null = the Plan Mode setting. */
    val planOverride: Boolean? = null,
    val prefilled: String? = null,
    val attachments: Map<String, String> = emptyMap(),
    /** When it last changed (wall clock): the newer of the stored draft and an activity's saved state wins. */
    val updatedMs: Long = 0,
) {
    fun encode(): String = JSONObject()
        .put("text", text)
        .putOpt("plan", planOverride)
        .putOpt("prefilled", prefilled)
        .put("attachments", JSONObject(attachments as Map<*, *>))
        .put("at", updatedMs)
        .toString()

    companion object {
        fun decode(json: String?): ComposerDraft? = runCatching {
            if (json.isNullOrBlank()) return null
            val o = JSONObject(json)
            val att = o.optJSONObject("attachments")
            ComposerDraft(
                text = o.optString("text"),
                planOverride = if (o.has("plan") && !o.isNull("plan")) o.optBoolean("plan") else null,
                prefilled = if (o.has("prefilled") && !o.isNull("prefilled")) o.optString("prefilled") else null,
                attachments = att?.keys()?.asSequence()?.associateWith { att.optString(it) } ?: emptyMap(),
                updatedMs = o.optLong("at"),
            )
        }.getOrNull()

        /** The newer of two drafts (a missing one loses). */
        fun newer(a: ComposerDraft?, b: ComposerDraft?): ComposerDraft? = when {
            a == null -> b
            b == null -> a
            b.updatedMs > a.updatedMs -> b
            else -> a
        }
    }
}

/** The persisted draft, one per process; every chat surface reads and writes the same [state]. */
class ComposerDraftStore(private val kv: KeyValueStore, private val now: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow(ComposerDraft.decode(kv.getString(KEY)) ?: ComposerDraft())
    val state: StateFlow<ComposerDraft> = _state.asStateFlow()

    fun update(transform: (ComposerDraft) -> ComposerDraft): ComposerDraft = synchronized(this) {
        val old = _state.value
        val next = transform(old).let { if (it == old) return old else it.copy(updatedMs = maxOf(now(), old.updatedMs + 1)) }
        _state.value = next
        kv.putString(KEY, next.encode())
        next
    }

    /** Restores [draft] (an activity's saved state that is newer than what is stored). */
    fun restore(draft: ComposerDraft) = synchronized(this) {
        if (draft.updatedMs <= _state.value.updatedMs) return
        _state.value = draft
        kv.putString(KEY, draft.encode())
    }

    companion object {
        const val KEY = "composer_draft"
        const val PREFS = "backstage_composer"
    }
}

object ComposerDrafts {
    @Volatile private var instance: ComposerDraftStore? = null

    fun get(context: Context): ComposerDraftStore = instance ?: synchronized(this) {
        instance ?: ComposerDraftStore(
            SharedPrefsStore(context.applicationContext.getSharedPreferences(ComposerDraftStore.PREFS, Context.MODE_PRIVATE)),
        ).also { instance = it }
    }

    /** Tests: install a store, or null to read the persisted one again (as after process death). */
    fun set(store: ComposerDraftStore?) { instance = store }
}
