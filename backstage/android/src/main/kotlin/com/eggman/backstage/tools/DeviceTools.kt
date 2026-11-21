package com.eggman.backstage.tools

import com.eggman.backstage.exec.TargetKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Date/time parsing for calendar tools: ISO-8601 with or without zone, "YYYY-MM-DD HH:MM", or date only (all-day). */
object DateParsing {
    data class Parsed(val epochMs: Long, val dateOnly: Boolean)

    fun parse(value: String, zone: ZoneId): Parsed {
        val v = value.trim().replace(' ', 'T')
        try { return Parsed(OffsetDateTime.parse(v).toInstant().toEpochMilli(), false) } catch (_: DateTimeParseException) {}
        try { return Parsed(ZonedDateTime.parse(v).toInstant().toEpochMilli(), false) } catch (_: DateTimeParseException) {}
        try { return Parsed(LocalDateTime.parse(v).atZone(zone).toInstant().toEpochMilli(), false) } catch (_: DateTimeParseException) {}
        try { return Parsed(LocalDateTime.parse(v + ":00").atZone(zone).toInstant().toEpochMilli(), false) } catch (_: DateTimeParseException) {}
        try { return Parsed(LocalDate.parse(v).atStartOfDay(zone).toInstant().toEpochMilli(), true) } catch (_: DateTimeParseException) {}
        throw ToolArgumentException("Cannot read date/time '$value' — use ISO format like 2026-03-14T15:30 or 2026-03-14")
    }

    private val fmt = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm")
    private val dayFmt = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd")

    fun format(ms: Long, zone: ZoneId, allDay: Boolean = false): String =
        if (allDay) Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).format(dayFmt) else Instant.ofEpochMilli(ms).atZone(zone).format(fmt)
}

class ClipboardGetTool : Tool {
    override val name = "clipboard_get"
    override val description = "Read the current clipboard text (Android may block this while Backstage is in the background)."
    override val schema = Schema.obj()
    override val targets = emptySet<TargetKind>()
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val t = ctx.services.clipboardGet() ?: return ToolResult.error("Clipboard is empty or not readable from the background.")
        return ToolResult("Clipboard: $t")
    }
}

class ClipboardSetTool : Tool {
    override val name = "clipboard_set"
    override val description = "Put text on the clipboard (e.g. to paste it into an app with a long-press → Paste)."
    override val schema = Schema.obj("text" to Schema.str("Text to copy"), required = listOf("text"))
    override val targets = emptySet<TargetKind>()
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult =
        if (ctx.services.clipboardSet(args.string("text"))) ToolResult("Copied to clipboard.") else ToolResult.error("Could not set the clipboard.")
}

class NotificationsTool : Tool {
    override val name = "get_notifications"
    override val description = "Recent notifications seen by Backstage (app, text, time), newest first. Use for questions like \"what did Anna just write?\"."
    override val schema = Schema.obj("app" to Schema.str("Optional app name/package filter"), "limit" to Schema.int("Default 15"))
    override val targets = emptySet<TargetKind>()
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val f = args.optString("app")?.lowercase()
        val list = ctx.services.recentNotifications()
            .filter { f == null || it.packageName.lowercase().contains(f) || it.appLabel.lowercase().contains(f) }
            .sortedByDescending { it.timeMs }
            .take((args.optInt("limit") ?: 15).coerceIn(1, 50))
        if (list.isEmpty()) return ToolResult("No recent notifications recorded" + (f?.let { " for \"$it\"" } ?: "") + ".")
        val zone = ZoneId.of(ctx.services.timeZoneId())
        return ToolResult(list.joinToString("\n") { "- ${DateParsing.format(it.timeMs, zone).drop(4)} ${it.appLabel.ifBlank { it.packageName }}: ${it.text.take(300)}" })
    }
}
