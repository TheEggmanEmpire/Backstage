package com.eggman.backstage.ui.report

import android.content.ActivityNotFoundException
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared by the main and popup chat's assistant rows. It changes no layout, colors, taps or dependencies on
 * the accessibility/companion implementation. Long-press and the screen-reader action expose the same menu.
 * User/answer/action/error rows are deliberately not wrapped. Reports never start an agent task or upload data.
 */
@Composable
fun ReportableAiResponse(response: String, taskId: String?, content: @Composable (Modifier) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { AiReportStore(context.applicationContext.noBackupFilesDir.resolve("ai-reports")) }
    val scope = rememberCoroutineScope()
    var menu by remember(response, taskId) { mutableStateOf(false) }
    var drafting by remember(response, taskId) { mutableStateOf(false) }
    var history by remember(response, taskId) { mutableStateOf(false) }
    var saved by remember(response, taskId) { mutableStateOf<AiContentReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box {
        content(Modifier
            .pointerInput(response, taskId) { detectTapGestures(onLongPress = { menu = true }) }
            .semantics {
                onLongClick(label = "AI response options") { menu = true; true }
                customActions = listOf(CustomAccessibilityAction("Report response") { drafting = true; true })
            })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; com.eggman.backstage.ui.chat.ChatExport.copy(context, response) })
            DropdownMenuItem(text = { Text("Report response") }, onClick = { menu = false; error = null; drafting = true })
            DropdownMenuItem(text = { Text("Saved reports") }, onClick = { menu = false; history = true })
        }
    }
    if (drafting) ReportDraftDialog(response, busy, error,
        onDismiss = { if (!busy) drafting = false },
        onSave = { category, note ->
            scope.launch {
                busy = true
                error = null
                try {
                    val report = AiContentReport.create(response, taskId, category, note)
                    withContext(Dispatchers.IO) { store.save(report) }
                    drafting = false
                    saved = report
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { error = "Could not save report: ${e.message ?: "storage unavailable"}"
                } finally { busy = false }
            }
        },
    )
    if (history) ReportHistoryDialog(store, onDismiss = { history = false }, onSelect = { history = false; saved = it })
    saved?.let { report -> ReportDetailsDialog(report, store, onDismiss = { saved = null }) }
}

@Composable
private fun ReportDraftDialog(
    response: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (ReportCategory, String) -> Unit,
) {
    var category by remember { mutableStateOf<ReportCategory?>(null) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report AI response") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save a flag on this device. Nothing is sent to the developer or provider. After saving, you can preview and share it yourself.")
                Text(response.take(320), style = MaterialTheme.typography.bodySmall)
                if (response.length > AiContentReport.MAX_RESPONSE_CHARS) Text(
                    "This response is long. Only the first ${AiContentReport.MAX_RESPONSE_CHARS} characters will be saved and shared.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ReportCategory.entries.forEach { item ->
                    Row(Modifier.fillMaxWidth().selectable(selected = category == item, enabled = !busy,
                        role = Role.RadioButton, onClick = { category = item }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = category == item, onClick = null, enabled = !busy)
                        Text(item.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(value = note, onValueChange = { if (it.length <= AiContentReport.MAX_NOTE_CHARS) note = it },
                    label = { Text("Optional note") }, supportingText = { Text("${note.length}/${AiContentReport.MAX_NOTE_CHARS}") },
                    enabled = !busy, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Text("Review for private information before sharing. The report includes only this response, category, note, time and task reference.",
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = category != null && !busy, onClick = { category?.let { onSave(it, note) } }) {
            Text(if (busy) "Saving…" else "Save report")
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReportHistoryDialog(store: AiReportStore, onDismiss: () -> Unit, onSelect: (AiContentReport) -> Unit) {
    var reports by remember { mutableStateOf<List<AiContentReport>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) {
        try { reports = withContext(Dispatchers.IO) { store.list() }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { error = "Could not read saved reports." }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Saved reports") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("Local flags only. Opening an email/share app does not confirm delivery.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (reports == null && error == null) Text("Loading…")
            if (reports?.isEmpty() == true) Text("No saved reports.")
            reports.orEmpty().forEach { report ->
                TextButton(onClick = { onSelect(report) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${report.category.label}\n${java.time.Instant.ofEpochMilli(report.createdAtMs)}\n${report.response.take(80)}")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun ReportDetailsDialog(report: AiContentReport, store: AiReportStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var destinations by remember { mutableStateOf(ReportDestinations()) }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) {
        try { destinations = withContext(Dispatchers.IO) { store.destinations() }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { error = "Could not load report destinations. Configure them again to use email."
        } finally { loaded = true }
    }
    fun share(address: String = "") {
        error = null
        try { AiReportIntents.open(context, report, address)
        } catch (_: ActivityNotFoundException) { error = "No app could open this report. Try Share report or copy the preview."
        } catch (_: SecurityException) { error = "Android did not allow opening a recipient app. The report is still saved locally." }
    }
    if (editing) {
        ReportDestinationsDialog(destinations, store, onDismiss = { editing = false }, onSaved = { destinations = it; editing = false })
        return
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Saved on this device") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This flag has not been submitted. The exact text below is shared only when you choose a destination and send it there.")
            SelectionContainer { Text(report.shareText(), style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { share() }, enabled = !busy) { Text("Share report") }
            if (destinations.maintainer.isNotEmpty()) TextButton(onClick = { share(destinations.maintainer) }, enabled = !busy) {
                Text("Email maintainer (${destinations.maintainer})")
            } else Text("No maintainer address configured. Share report opens Android's share sheet.", style = MaterialTheme.typography.bodySmall)
            if (destinations.provider.isNotEmpty()) TextButton(onClick = { share(destinations.provider) }, enabled = !busy) {
                Text("Email provider (${destinations.provider})")
            }
            TextButton(onClick = { editing = true }, enabled = loaded && !busy) { Text("Report destinations") }
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try { withContext(Dispatchers.IO) { store.delete(report.id) }; onDismiss()
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { error = "Could not delete the local report."
                    } finally { busy = false }
                }
            }) { Text("Delete local report") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } })
}

@Composable
private fun ReportDestinationsDialog(
    initial: ReportDestinations, store: AiReportStore, onDismiss: () -> Unit, onSaved: (ReportDestinations) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var maintainer by remember { mutableStateOf(initial.maintainer) }
    var provider by remember { mutableStateOf(initial.provider) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = ReportDestinations.validAddress(maintainer.trim()) && ReportDestinations.validAddress(provider.trim())
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Report destinations") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Optional email addresses, saved only on this device. Use an address published by the maintainer or your provider. No email is sent automatically; no provider API key is used.")
            OutlinedTextField(maintainer, { maintainer = it }, enabled = !busy, label = { Text("Maintainer email (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(provider, { provider = it }, enabled = !busy, label = { Text("Provider reporting email (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!valid) Text("Enter one email address per field, or leave it blank.", color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = valid && !busy, onClick = {
        scope.launch {
            busy = true
            val destinations = ReportDestinations(maintainer.trim(), provider.trim())
            try { withContext(Dispatchers.IO) { store.saveDestinations(destinations) }; onSaved(destinations)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { error = "Could not save report destinations."
            } finally { busy = false }
        }
    }) { Text("Save destinations") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}
