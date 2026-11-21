package com.eggman.backstage.ui.report

import org.json.JSONObject
import java.util.UUID

enum class ReportCategory(val label: String) {
    OFFENSIVE("Offensive or hateful content"),
    HARMFUL("Harmful or dangerous advice"),
    SEXUAL("Sexual content or child safety"),
    VIOLENCE("Violence or self-harm"),
    DECEPTIVE("Deceptive or misleading content"),
    PRIVACY("Privacy or personal information"),
    OTHER("Other concern"),
}

/**
 * A local flag, not a submission receipt. Deliberately contains only the selected assistant text and the
 * user's explanation: no automatic conversation, screenshot, endpoint URL, API key or device identifiers.
 * The selected text can itself contain private information, so export always requires a separate user action.
 */
data class AiContentReport(
    val id: String,
    val createdAtMs: Long,
    val taskId: String?,
    val category: ReportCategory,
    val response: String,
    val responseTruncated: Boolean,
    val note: String,
) {
    init {
        require(validId(id)) { "Invalid report ID" }
        require(createdAtMs >= 0) { "Invalid report time" }
        require(taskId == null || taskId.length <= 512) { "Task reference is too long" }
        require(response.length <= MAX_RESPONSE_CHARS) { "Response is too long" }
        require(note.length <= MAX_NOTE_CHARS) { "Note is too long" }
    }

    /** This exact text is previewed before being passed to an email app or the Android share sheet. */
    fun shareText(): String = buildString {
        append("Backstage AI response report\nSaved locally; delivery to a recipient is not confirmed.\n\n")
        append("Report: $id\nCreated (UTC): ${java.time.Instant.ofEpochMilli(createdAtMs)}\n")
        taskId?.let { append("Task reference: $it\n") }
        append("Category: ${category.label}\n\nSelected AI response")
        if (responseTruncated) append(" (truncated at $MAX_RESPONSE_CHARS characters)")
        append(":\n$response\n\nUser note:\n$note")
    }

    companion object {
        // Bound storage and Binder intent payloads without silently dropping text: truncation is recorded and
        // displayed in both the local preview and export. No context beyond this single response is collected.
        const val MAX_RESPONSE_CHARS = 32_000
        const val MAX_NOTE_CHARS = 2_000
        fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

        fun create(response: String, taskId: String?, category: ReportCategory, note: String, nowMs: Long = System.currentTimeMillis()): AiContentReport {
            val text = response.take(MAX_RESPONSE_CHARS).let {
                if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
            }
            return AiContentReport(UUID.randomUUID().toString(), nowMs, taskId, category, text, text.length < response.length, note)
        }
    }
}

/** Versioned, strict codec: bad/newer files are skipped by the store, never silently reinterpreted. */
object AiContentReportCodec {
    fun encode(report: AiContentReport): JSONObject = JSONObject()
        .put("version", 1).put("id", report.id).put("createdAtMs", report.createdAtMs)
        .putOpt("taskId", report.taskId).put("category", report.category.name)
        .put("response", report.response).put("responseTruncated", report.responseTruncated).put("note", report.note)

    fun decode(json: JSONObject): AiContentReport {
        fun string(key: String): String = (json.opt(key) as? String) ?: throw IllegalArgumentException("Invalid $key")
        fun integer(key: String): Long {
            val value = json.opt(key)
            require(value is Int || value is Long) { "Invalid $key" }
            return (value as Number).toLong()
        }
        require(integer("version") == 1L) { "Unsupported report version" }
        return AiContentReport(
            string("id"), integer("createdAtMs"), if (json.has("taskId")) string("taskId") else null,
            ReportCategory.valueOf(string("category")), string("response"),
            (json.opt("responseTruncated") as? Boolean) ?: throw IllegalArgumentException("Invalid truncation flag"), string("note"),
        )
    }
}
