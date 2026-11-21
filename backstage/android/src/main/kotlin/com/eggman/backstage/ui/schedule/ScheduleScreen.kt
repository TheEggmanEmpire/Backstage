package com.eggman.backstage.ui.schedule

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.schedule.Repeat
import com.eggman.backstage.schedule.ScheduleMath
import com.eggman.backstage.schedule.ScheduledTask
import com.eggman.backstage.schedule.Schedules
import com.eggman.backstage.ui.NumberSetting
import com.eggman.backstage.ui.onboarding.AndroidPermissionProbe
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date

@Composable
fun ScheduleScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = remember { Schedules.engine(context) }
    var version by remember { mutableIntStateOf(0) }
    val tasks = remember(version) { engine.list() }
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    val exact = remember(version) { Schedules.canScheduleExact(context) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickActionsSection()
        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scheduled tasks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = {
                editing = ScheduledTask(id = engine.newId(), prompt = "", minuteOfDay = 8 * 60,
                    onceAtMs = System.currentTimeMillis() + 60 * 60 * 1000L)
            }) { Icon(Icons.Default.Add, null); Text("New") }
        }
        if (!exact) Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Exact alarms are off: Android may start scheduled tasks a few minutes late.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { context.startActivity(AndroidPermissionProbe.exactAlarmSettings(context)) }) { Text("Allow") }
            }
        }
        if (tasks.isEmpty()) Text("Run the agent on a timetable, e.g. \"Every weekday at 8:00 summarise my unread email\".",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        tasks.forEach { t -> ScheduleRow(context, t, onEdit = { editing = t }, onChanged = { version++ }) }
    }
    editing?.let { t ->
        ScheduleEditor(t, onDismiss = { editing = null }, onSave = { saved ->
            // Returns an error to show in the editor, or null when stored.
            try {
                // Saved by the user in the editor, so the instruction is theirs (also when the agent first wrote it).
                engine.upsert(saved.copy(source = com.eggman.backstage.schedule.ScheduledTask.SOURCE_UI), checkConflicts = true)
                editing = null
                version++
                null
            } catch (e: com.eggman.backstage.schedule.ScheduleEngine.ConflictException) {
                e.message
            }
        })
    }
}

@Composable
private fun ScheduleRow(context: Context, t: ScheduledTask, onEdit: () -> Unit, onChanged: () -> Unit) {
    val engine = Schedules.engine(context)
    val zone = ZoneId.systemDefault()
    var confirmDelete by remember { mutableStateOf(false) }
    var rowError by remember { mutableStateOf<String?>(null) }
    // Live countdown: re-read the clock every 30 s while the card is on screen.
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(ScheduleMath.describe(t, zone, LocalConfiguration.current.locales[0]) + " · " + t.target.name.lowercase(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(t.enabled, {
                    rowError = try {
                        engine.setEnabled(t.id, it)
                        null
                    } catch (e: com.eggman.backstage.schedule.ScheduleEngine.ConflictException) {
                        e.message
                    }
                    onChanged()
                })
            }
            val next = t.nextAtMs?.let {
                "Next: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) +
                    " (" + ScheduleMath.countdown(it, now) + ")"
            }
            val last = t.lastStatus?.let { "Last: ${it.lowercase()}" + (t.lastResult?.let { r -> " — ${r.take(120)}" } ?: "") }
            listOfNotNull(next, last).forEach { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            rowError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row {
                IconButton(onClick = {
                    val msg = Schedules.runNow(context, t.id, manual = true)
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    onChanged()
                }) { Icon(Icons.Default.PlayArrow, "Run now") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete scheduled task?") },
        text = { Text("\"${t.label}\" will no longer run.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; engine.delete(t.id); onChanged() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

private enum class RepeatKind(val label: String) { ONCE("Once"), DAILY("Daily"), WEEKLY("Weekly"), INTERVAL("Interval"), CRON("Cron") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditor(initial: ScheduledTask, onDismiss: () -> Unit, onSave: (ScheduledTask) -> String?) {
    val zone = ZoneId.systemDefault()
    val context = LocalContext.current
    var cronText by remember {
        mutableStateOf(com.eggman.backstage.schedule.ScheduleCron.of(initial, zone)?.takeIf { it != "ONCE" } ?: "0 8 * * 1-5")
    }
    var prompt by remember { mutableStateOf(initial.prompt) }
    var target by remember { mutableStateOf(initial.target) }
    var kind by remember {
        mutableStateOf(when (initial.repeat) {
            Repeat.Once -> RepeatKind.ONCE; Repeat.Daily -> RepeatKind.DAILY
            is Repeat.Weekly -> RepeatKind.WEEKLY; is Repeat.Interval -> RepeatKind.INTERVAL
        })
    }
    var days by remember { mutableStateOf((initial.repeat as? Repeat.Weekly)?.days ?: setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) }
    var intervalMin by remember { mutableIntStateOf((initial.repeat as? Repeat.Interval)?.minutes ?: 60) }
    val onceLocal = Instant.ofEpochMilli(initial.onceAtMs).atZone(zone)
    var dayOffset by remember { mutableIntStateOf(maxOf(0, (onceLocal.toLocalDate().toEpochDay() - LocalDate.now(zone).toEpochDay()).toInt())) }
    val startMinute = if (kind == RepeatKind.ONCE) onceLocal.hour * 60 + onceLocal.minute else initial.minuteOfDay
    // 12/24 h follows the system setting.
    val time = rememberTimePickerState(initialHour = startMinute / 60, initialMinute = startMinute % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context))
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }

    /** The task as edited, or an error text (invalid cron). */
    fun buildCron(): Result<ScheduledTask> = runCatching {
        val p = com.eggman.backstage.schedule.ScheduleSpec.parse(cronText, System.currentTimeMillis(), zone)
        initial.copy(prompt = prompt.trim(), target = target, repeat = p.repeat, minuteOfDay = p.minuteOfDay,
            onceAtMs = p.onceAtMs, anchorMs = p.anchorMs)
    }

    fun build(): ScheduledTask {
        if (kind == RepeatKind.CRON) buildCron().getOrNull()?.let { return it }
        val minute = time.hour * 60 + time.minute
        val repeat = when (kind) {
            RepeatKind.ONCE -> Repeat.Once
            RepeatKind.DAILY -> Repeat.Daily
            RepeatKind.WEEKLY -> Repeat.Weekly(days)
            RepeatKind.INTERVAL, RepeatKind.CRON -> Repeat.Interval(intervalMin)
        }
        val onceAt = LocalDate.now(zone).plusDays(dayOffset.toLong()).atTime(LocalTime.of(time.hour, time.minute)).atZone(zone).toInstant().toEpochMilli()
        val anchor = if (kind == RepeatKind.INTERVAL && initial.repeat !is Repeat.Interval) 0L else initial.anchorMs
        return initial.copy(prompt = prompt.trim(), target = target, repeat = repeat, minuteOfDay = minute, onceAtMs = onceAt, anchorMs = anchor)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.prompt.isEmpty()) "New scheduled task" else "Edit scheduled task") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("What should the agent do?") }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Target.entries.forEach { t -> FilterChip(target == t, { target = t }, { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RepeatKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k.label) }) }
                }
                when (kind) {
                    RepeatKind.ONCE -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Today", 1 to "Tomorrow").forEach { (o, l) -> FilterChip(dayOffset == o, { dayOffset = o }, { Text(l) }) }
                        if (dayOffset > 1) FilterChip(true, {}, { Text(LocalDate.now(zone).plusDays(dayOffset.toLong()).toString()) })
                        TextButton(onClick = { dayOffset++ }) { Text("+1 day") }
                    }
                    RepeatKind.WEEKLY -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        DayOfWeek.entries.forEach { d ->
                            FilterChip(d in days, { days = if (d in days) days - d else days + d },
                                { Text(d.getDisplayName(TextStyle.NARROW, LocalConfiguration.current.locales[0])) })
                        }
                    }
                    RepeatKind.INTERVAL -> NumberSetting("Every N minutes (≥ ${ScheduleMath.MIN_INTERVAL_MINUTES})", intervalMin, Modifier.fillMaxWidth()) {
                        intervalMin = it
                    }
                    RepeatKind.DAILY -> Unit
                    RepeatKind.CRON -> {
                        val parsed = buildCron()
                        OutlinedTextField(cronText, { cronText = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Cron (minute hour day month weekday)") }, isError = parsed.isFailure,
                            supportingText = {
                                Text(parsed.exceptionOrNull()?.message ?: "e.g. 30 7 * * 1-5 (weekdays 07:30), */30 * * * *, 0 */2 * * *")
                            })
                    }
                }
                if (kind != RepeatKind.INTERVAL && kind != RepeatKind.CRON) TimePicker(state = time)
                // Next-run preview.
                val preview = runCatching {
                    val t = build()
                    val anchored = if (t.repeat is Repeat.Interval && t.anchorMs == 0L) t.copy(anchorMs = System.currentTimeMillis()) else t
                    ScheduleMath.nextFire(anchored.copy(enabled = true), System.currentTimeMillis(), zone)
                }.getOrNull()
                if (preview != null) Text("Next run: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(preview)),
                    style = MaterialTheme.typography.bodySmall)
                errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Scheduled runs cannot ask you questions; they stop and report instead.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cronError = if (kind == RepeatKind.CRON) buildCron().exceptionOrNull()?.message else null
                val t = build()
                errors = listOfNotNull(cronError) + ScheduleMath.validate(t, System.currentTimeMillis())
                if (errors.isEmpty()) onSave(t)?.let { errors = listOf(it) }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
