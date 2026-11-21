package com.eggman.backstage.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eggman.backstage.skills.ArchiveScan
import com.eggman.backstage.skills.ImportRow
import com.eggman.backstage.skills.InstalledSkill
import com.eggman.backstage.skills.SkillArchive
import com.eggman.backstage.skills.SkillFileInfo
import com.eggman.backstage.skills.SkillImportLogic
import com.eggman.backstage.skills.SkillSources
import com.eggman.backstage.skills.SkillStore
import com.eggman.backstage.skills.SkillSync
import com.eggman.backstage.skills.SkillSyncConfig
import com.eggman.backstage.skills.SkillSyncInputs
import com.eggman.backstage.skills.SkillSyncPreferences
import com.eggman.backstage.skills.SkillSyncSource
import com.eggman.backstage.ui.Section
import com.eggman.backstage.ui.ToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Wording of the skills settings (pure, unit-tested). */
internal object SkillsText {
    const val INTRO = "Skills are instructions for kinds of tasks, in the open SKILL.md format (a folder with SKILL.md and " +
        "optional reference files). The agent sees only the name and description of each enabled skill and loads the " +
        "instructions when a task needs them. Skills never override Backstage's safety rules or approvals."
    const val SCRIPTS = "Scripts inside skills run real code on the phone. Native execution is off until you enable Run skill scripts below; omp can run scripts directly."
    const val SOURCES = "Install skills only from sources you trust: a skill tells the agent how to work. Imported skills " +
        "start switched off. GitHub sources below can keep their mirrored skills up to date."
    const val OMP = "Enabled skills are shared with omp in Termux and the omp chat engine; disabled skills are not."

    fun summary(skills: List<InstalledSkill>): String {
        if (skills.isEmpty()) return "No skills installed."
        val on = skills.count { it.enabled }
        return "${skills.size} installed, $on enabled" + if (on > 0) ": " + skills.filter { it.enabled }.joinToString(", ") { it.name } else ""
    }

    fun size(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }

    fun fileLine(f: SkillFileInfo): String = f.path + " · " + size(f.size) + when {
        f.script -> " · script"
        !f.text -> " · binary (not readable by the agent)"
        else -> ""
    }

    fun rowLine(r: ImportRow): String = buildString {
        append(r.label)
        when {
            !r.ok -> append(" — cannot be installed: ").append(r.errors.firstOrNull() ?: "invalid")
            r.duplicate -> append(" — already installed")
            else -> append(" — ${r.files} file(s), ${size(r.bytes)}")
        }
    }
}

/** The "Skills" entry in Settings: a summary and the way into [SkillsScreen]. */
@Composable
internal fun SkillsSection(onOpenSkills: () -> Unit) {
    val context = LocalContext.current
    var summary by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        summary = withContext(Dispatchers.IO) { runCatching { SkillsText.summary(SettingsServices.skills(context).list()) }.getOrDefault("") }
    }
    SettingsSection("Skills", "Reusable instructions for kinds of tasks (SKILL.md). Import them from a file, a link or GitHub, keep GitHub repositories in sync, or write your own.") {
        if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOpenSkills) { Text("Manage skills") }
    }
}

private data class EditorState(val name: String, val description: String, val instructions: String, val editing: String?)

private class Preview(val scan: ArchiveScan, val rows: List<ImportRow>, val source: String, val temp: File?)

/** Settings → Skills: list with switches, details, delete, export/share, import (file, link, GitHub) and an editor. */
@Composable
fun SkillsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsServices.skills(context) }
    var refresh by remember { mutableIntStateOf(0) }
    var skills by remember { mutableStateOf<List<InstalledSkill>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var urlDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var exportName by remember { mutableStateOf<String?>(null) }
    DisposableEffect(store) {
        val stop = store.observe { scope.launch { refresh++ } }
        onDispose { stop() }
    }
    LaunchedEffect(refresh) { skills = withContext(Dispatchers.IO) { runCatching { store.list() }.getOrDefault(emptyList()) } }

    fun work(label: String, block: suspend () -> String?) {
        scope.launch {
            busy = label
            message = try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { "Failed: ${e.message ?: e.javaClass.simpleName}" }
            busy = null
            refresh++
        }
    }

    fun scanFile(temp: File, subPath: String?, source: String) {
        scope.launch {
            busy = "Reading the archive…"
            val scan = withContext(Dispatchers.IO) { runCatching { SkillArchive.scan(temp, subPath, store.limits) }.getOrElse { ArchiveScan(emptyList(), listOf(it.message ?: "unreadable")) } }
            busy = null
            preview = Preview(scan, SkillImportLogic.rows(scan, store), source, temp)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Copying the file…"
            val result = withContext(Dispatchers.IO) {
                val temp = File(context.cacheDir, "skill-import/pick-${System.currentTimeMillis()}")
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { SkillImportLogic.copyBounded(it, temp, store.limits.maxArchiveBytes) }
                        ?: error("could not open the file")
                    temp to SkillImportLogic.fileSource(displayName(context, uri))
                }.onFailure { temp.delete() } // a failed or oversized copy must not stay in the cache
            }
            busy = null
            result.fold({ (temp, source) -> scanFile(temp, null, source) }, { message = "Failed: ${it.message}" })
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val name = exportName
        exportName = null
        if (uri != null && name != null) work("Exporting…") {
            context.contentResolver.openOutputStream(uri)?.use { store.exportZip(name, it) } ?: error("could not write the file")
            "Exported $name."
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                when {
                    editor != null -> editor = null
                    selected != null -> selected = null
                    else -> onBack()
                }
            }) { Text("‹ Back") }
            Text("Skills", style = MaterialTheme.typography.titleLarge)
        }
        busy?.let { Text(it, style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val ed = editor
        val sel = selected?.let { n -> skills.firstOrNull { it.name == n } }
        when {
            ed != null -> SkillEditor(ed, onCancel = { editor = null }) { name, description, instructions ->
                val errors = store.saveSimple(name.trim(), description, instructions, ed.editing)
                if (errors.isEmpty()) {
                    editor = null; selected = name.trim(); refresh++
                    message = if (ed.editing == null) "Created $name (switched off until you enable it)." else "Saved $name."
                }
                errors
            }
            sel != null -> SkillDetails(
                sel,
                onToggle = { on -> work("Saving…") { store.setEnabled(sel.name, on); null } },
                onEdit = {
                    val managed = store.managedMessage(sel.name)
                    if (managed != null) message = managed else editor = EditorState(sel.name, sel.description, store.body(sel.name).orEmpty(), sel.name)
                },
                onExport = { exportName = sel.name; exporter.launch("${sel.name}.zip") },
                onShare = { work("Preparing…") { share(context, store, sel.name); null } },
                onDelete = { val managed = store.managedMessage(sel.name); if (managed != null) message = managed else confirmDelete = sel.name },
            )
            else -> {
                Text(SkillsText.INTRO, style = MaterialTheme.typography.bodySmall)
                Text(SkillsText.OMP, style = MaterialTheme.typography.bodySmall)
                Text(SkillsText.SCRIPTS, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "text/markdown", "text/plain", "*/*")) }) { Text("Import file") }
                    OutlinedButton(onClick = { urlDialog = !urlDialog }) { Text("Import from link") }
                    OutlinedButton(onClick = { editor = EditorState("", "", "", null) }) { Text("New skill") }
                }
                if (urlDialog) LinkImport(
                    onCancel = { urlDialog = false },
                ) { d ->
                    urlDialog = false
                    scope.launch {
                        busy = "Downloading…"
                        val temp = File(context.cacheDir, "skill-import/dl-${System.currentTimeMillis()}")
                        temp.parentFile?.mkdirs()
                        val r = runCatching { SettingsServices.downloadSkill(d, temp, store.limits.maxArchiveBytes) }
                        busy = null
                        r.fold({ scanFile(temp, d.subPath, d.source) }, { temp.delete(); message = "Download failed: ${it.message}" })
                    }
                }
                Text(SkillsText.SOURCES, style = MaterialTheme.typography.bodySmall)
                Text(SkillsText.summary(skills), style = MaterialTheme.typography.labelLarge)
                if (skills.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { work("Enabling all…") { skills.forEach { store.setEnabled(it.name, true) }; null } },
                        enabled = skills.any { !it.enabled }) { Text("All on") }
                    TextButton(onClick = { work("Disabling all…") { skills.forEach { store.setEnabled(it.name, false) }; null } },
                        enabled = skills.any { it.enabled }) { Text("All off") }
                }
                SkillScriptsSettingsSection(SettingsStores.get(context))
                GitHubSyncSection()
                var skillQuery by remember { mutableStateOf("") }
                var skillsOnlyOn by remember { mutableStateOf(false) }
                if (skills.size > 5) OutlinedTextField(skillQuery, { skillQuery = it }, Modifier.fillMaxWidth(),
                    label = { Text("Search skills") }, singleLine = true,
                    trailingIcon = { if (skillQuery.isNotEmpty()) TextButton(onClick = { skillQuery = "" }) { Text("Clear") } })
                if (skills.size > 5) Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(skillsOnlyOn, { skillsOnlyOn = it }); Text("Only enabled", style = MaterialTheme.typography.bodySmall)
                }
                skills.filter { (!skillsOnlyOn || it.enabled) && (skillQuery.isBlank() || it.name.contains(skillQuery.trim(), true) || it.description.contains(skillQuery.trim(), true)) }.forEach { s ->
                    HorizontalDivider()
                    ToggleRow(s.name, s.description.take(160) + if (s.description.length > 160) "…" else "", s.enabled) { on ->
                        work("Saving…") { store.setEnabled(s.name, on); null }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(listOfNotNull(s.syncSource?.let { "GitHub source $it" } ?: s.source.ifBlank { null }, if (s.hasScripts) "has scripts" else null,
                            if (s.warnings.isNotEmpty()) "${s.warnings.size} warning(s)" else null).joinToString(" · "),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { selected = s.name }) { Text("Details") }
                    }
                }
                TextButton(onClick = { work("Restoring…") { "Restored ${store.seedExamples(force = true)} example skill(s)." } }) { Text("Restore example skills") }
            }
        }
    }

    preview?.let { p ->
        var replace by remember(p) { mutableStateOf(true) }
        val close = { p.temp?.delete(); preview = null }
        AlertDialog(
            onDismissRequest = { close() },
            title = { Text("Import skills") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Source: ${p.source}", style = MaterialTheme.typography.bodySmall)
                    if (p.rows.isEmpty()) Text("No skill found.", style = MaterialTheme.typography.bodyMedium)
                    p.rows.forEach { r ->
                        Text(SkillsText.rowLine(r), style = MaterialTheme.typography.bodyMedium)
                        if (r.description.isNotBlank()) Text(r.description.take(200), style = MaterialTheme.typography.bodySmall)
                        r.warnings.take(4).forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    p.scan.problems.take(6).forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }
                    if (p.rows.any { it.ok && it.duplicate }) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(replace, { replace = it })
                        Text("Replace installed skills with the same name", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(SkillsText.SCRIPTS, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = p.rows.any { it.ok && (!it.duplicate || replace) }, onClick = {
                    val scan = p.scan; val source = p.source; val rep = replace
                    close()
                    work("Installing…") { SkillImportLogic.summary(SkillImportLogic.install(scan, store, source, rep)) }
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { close() }) { Text("Cancel") } },
        )
    }

    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete $name?") },
            text = { Text("The skill and its files are removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null; selected = null
                    work("Deleting…") { store.managedMessage(name) ?: if (store.delete(name)) "Deleted $name." else "Already gone." }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** The link field (inline rather than a dialog: one less window over the list). */
@Composable
private fun LinkImport(onCancel: () -> Unit, onDownload: (com.eggman.backstage.skills.SkillDownload) -> Unit) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("A link to a .zip or .skill file, or a GitHub repository or folder, e.g. github.com/owner/repo/tree/main/skills/name. " +
            "All skills in it are offered.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(url, { url = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Link") }, singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                try { onDownload(SkillSources.resolve(url)) } catch (e: SkillSources.BadSource) { error = e.message }
            }) { Text("Download") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun SkillDetails(
    s: InstalledSkill,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.name, style = MaterialTheme.typography.titleMedium)
        ToggleRow("Enabled", "The agent may use this skill", s.enabled, onChange = onToggle)
        Text(s.description, style = MaterialTheme.typography.bodyMedium)
        val facts = listOfNotNull(
            s.syncSource?.let { "Managed by GitHub source $it; remove the source to delete it" },
            s.source.ifBlank { null }?.let { "Source: $it" },
            s.manifest.license.ifBlank { null }?.let { "License: $it" },
            s.manifest.compatibility.ifBlank { null }?.let { "Compatibility: $it" },
            s.manifest.allowedTools.takeIf { it.isNotEmpty() }?.let { "Allowed tools (informational): ${it.joinToString(" ")}" },
        ) + s.manifest.metadata.map { (k, v) -> "$k: $v" }
        facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (s.hasScripts) Text(SkillsText.SCRIPTS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        s.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }
        Text("Files", style = MaterialTheme.typography.labelLarge)
        s.files.forEach { Text(SkillsText.fileLine(it), style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text("Edit") }
            OutlinedButton(onClick = onExport) { Text("Export") }
            OutlinedButton(onClick = onShare) { Text("Share") }
            OutlinedButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/** GitHub sync config is separate from general settings so its tokens never enter ordinary config JSON. */
@Composable
private fun GitHubSyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { SkillSyncPreferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    var config by remember { mutableStateOf(SkillSyncConfig()) }
    var statuses by remember { mutableStateOf(emptyMap<String, com.eggman.backstage.skills.SkillSyncStatus>()) }
    var nextSync by remember { mutableStateOf(0L) }
    var interval by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf("hours") }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var repository by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<SkillSyncSource?>(null) }
    var tokenSource by remember { mutableStateOf<String?>(null) }
    var replacementToken by remember { mutableStateOf("") }
    val progress by SkillSync.progress.collectAsState()

    DisposableEffect(context) {
        val shared = context.getSharedPreferences(SkillSyncPreferences.FILE, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> scope.launch { revision++ } }
        shared.registerOnSharedPreferenceChangeListener(listener)
        onDispose { shared.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(revision) {
        runCatching { withContext(Dispatchers.IO) { Triple(prefs.config(), prefs.statuses(), prefs.nextSync) } }
            .onSuccess { (c, s, next) -> config = c; statuses = s; nextSync = next }
            .onFailure { message = "GitHub settings could not be loaded: ${it.message}" }
    }
    LaunchedEffect(config.intervalMinutes) {
        SkillSyncInputs.splitInterval(config.intervalMinutes).let { interval = it.first; unit = it.second }
    }
    fun action(onSuccess: () -> Unit = {}, block: suspend () -> String) {
        scope.launch {
            working = true
            try { message = withContext(Dispatchers.IO) { block() }; onSuccess() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = SkillSyncInputs.redact(e.message ?: "GitHub sync failed.", token.ifEmpty { replacementToken }) }
            finally { working = false; revision++ }
        }
    }
    fun time(ms: Long): String = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(ms))

    Section("GitHub sync", "Mirror skills from repositories on a fixed interval. New mirrored skills start switched off. Android may delay background jobs to save battery.") {
        ToggleRow("Automatic sync", "Sync configured repositories in the background", config.enabled) { enabled ->
            if (!working) action { SkillSync.updateConfig(context) { it.copy(enabled = enabled) }; "GitHub sync settings saved." }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(interval, { interval = it }, Modifier.weight(1f), label = { Text("Interval") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            SkillSyncUnitMenu(unit) { unit = it }
        }
        Text("15 minutes to 7 days", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !working, onClick = { action {
            val minutes = SkillSyncInputs.joinInterval(interval, unit)
            SkillSync.updateConfig(context) { it.copy(intervalMinutes = minutes) }
            "Sync interval saved."
        } }) { Text("Save interval") }
        Text(when {
            !config.enabled || config.sources.isEmpty() -> "Next sync: automatic sync is off."
            nextSync > 0 -> "Next sync: around ${time(nextSync)} (when Android allows)."
            else -> "Next sync: waiting for Android to schedule."
        }, style = MaterialTheme.typography.bodySmall)
        progress?.let { Text(it.description(), style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        config.sources.forEach { source ->
            HorizontalDivider()
            Text(source.id, style = MaterialTheme.typography.titleSmall)
            Text("${source.owner}/${source.repo} · ${source.ref.ifEmpty { "default branch" }} · ${source.paths.joinToString()}", style = MaterialTheme.typography.bodySmall)
            val status = statuses[source.id]
            Text(if (status == null) "Never synced" else {
                "${time(status.lastRun)} · ${status.result} · " + (status.error ?: status.counts.let { "+${it.added} added, ${it.updated} updated, ${it.deleted} deleted, ${it.skipped} skipped" })
            }, style = MaterialTheme.typography.bodySmall)
            status?.takeIf { it.error == null }?.counts?.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
                Text("Skipped: " + notes.take(5).joinToString("; ") + if (notes.size > 5) "; and ${notes.size - 5} more" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !working && progress == null, onClick = { action { SkillSync.syncNow(context, source.id); "Sync finished; see the source status." } }) { Text("Sync now") }
                TextButton(enabled = !working, onClick = { tokenSource = source.id; replacementToken = "" }) { Text("Token") }
                TextButton(enabled = !working && progress == null, onClick = { remove = source }) { Text("Remove") }
            }
            if (tokenSource == source.id) {
                OutlinedTextField(replacementToken, { replacementToken = it }, Modifier.fillMaxWidth(), label = { Text("GitHub token") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), supportingText = { Text("Paste a replacement token, or leave empty to remove it.") })
                OutlinedButton(enabled = !working, onClick = {
                    val secret = replacementToken
                    action { SkillSync.updateConfig(context, mapOf(source.id to secret)) { current ->
                        require(current.sources.any { it.id == source.id }) { "This source was removed." }; current }; "Source token saved." }
                    replacementToken = ""; tokenSource = null
                }) { Text("Save token") }
            }
        }
        if (!adding) OutlinedButton(enabled = !working, onClick = { adding = true }) { Text("Add source") }
        else {
            OutlinedTextField(repository, { repository = it }, Modifier.fillMaxWidth(), label = { Text("Repository") }, singleLine = true,
                supportingText = { Text("owner/repo or github.com/owner/repo/tree/ref/folder") })
            OutlinedTextField(ref, { ref = it }, Modifier.fillMaxWidth(), label = { Text("Ref (optional)") }, singleLine = true,
                supportingText = { Text("Leave empty to use the URL's ref or the repository's default branch.") })
            OutlinedTextField(folders, { folders = it }, Modifier.fillMaxWidth(), label = { Text("Folders (optional, one per line)") },
                supportingText = { Text("Leave empty to use the URL's folder or the whole repository.") })
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("GitHub token (optional)") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), supportingText = { Text("For private repositories or rate limits. Stored encrypted on this device.") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !working, onClick = {
                    // Capture the secret before clearing the UI; validation/encryption happens in the IO action.
                    val input = repository; val secret = token; val inputRef = ref; val inputFolders = folders
                    action(onSuccess = { adding = false; repository = ""; ref = ""; folders = "" }) {
                        val parsed = SkillSyncInputs.parseRepository(input, config.sources.map { it.id })
                        val source = parsed.copy(ref = inputRef.trim().ifEmpty { parsed.ref }, paths = inputFolders.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            .takeIf { it.isNotEmpty() }?.map { if (it == ".") it else com.eggman.backstage.skills.SkillPaths.normalize(it) ?: error("Invalid repository folder.") } ?: parsed.paths)
                        SkillSync.updateConfig(context, mapOf(source.id to secret)) { current ->
                            require(current.sources.none { it.id == source.id }) { "A source with this id already exists." }
                            current.copy(enabled = if (current.sources.isEmpty()) true else current.enabled, sources = current.sources + source)
                        }
                        SkillSync.syncNow(context, source.id)
                        "Source added; see its sync status."
                    }
                    token = ""
                }) { Text("Add repository") }
                TextButton(enabled = !working, onClick = { adding = false; token = "" }) { Text("Cancel") }
            }
        }
    }
    remove?.let { source ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${source.id}?") },
            text = { Text("Delete this source, its stored token, and all skills mirrored from it? Manually imported skills are kept.") },
            confirmButton = { TextButton(onClick = { remove = null; action { SkillSync.removeSource(context, source.id); "Source and its mirrored skills removed." } }) { Text("Remove source") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SkillSyncUnitMenu(unit: String, onUnit: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }) { Text(unit) }
        androidx.compose.material3.DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            listOf("minutes", "hours", "days").forEach { value ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(value) }, onClick = { expanded = false; onUnit(value) })
            }
        }
    }
}

@Composable
private fun SkillEditor(state: EditorState, onCancel: () -> Unit, onSave: (String, String, String) -> List<String>) {
    var name by remember(state) { mutableStateOf(state.name) }
    var description by remember(state) { mutableStateOf(state.description) }
    var instructions by remember(state) { mutableStateOf(state.instructions) }
    var errors by remember(state) { mutableStateOf<List<String>>(emptyList()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (state.editing == null) "New skill" else "Edit ${state.editing}", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
            supportingText = { Text("Lowercase letters, digits and hyphens, e.g. weekly-report") })
        if (name.isNotBlank() && !com.eggman.backstage.skills.SkillRules.validName(name.trim())) {
            val suggestion = com.eggman.backstage.skills.SkillRules.suggestName(name)
            if (suggestion.isNotEmpty()) TextButton(onClick = { name = suggestion }) { Text("Use \"$suggestion\"") }
        }
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description") },
            supportingText = { Text("What it does and when to use it (the agent decides from this). ${description.length}/1024") }, minLines = 2)
        OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(), label = { Text("Instructions (Markdown)") }, minLines = 8)
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { errors = onSave(name, description, instructions) }) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

/** Writes the skill as a zip into the cache's share folder and opens the share sheet. */
private suspend fun share(context: Context, store: SkillStore, name: String) {
    val dir = File(context.cacheDir, "skill-share").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "$name.zip")
    file.outputStream().use { store.exportZip(name, it) }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.backstage-skills", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    withContext(Dispatchers.Main) {
        context.startActivity(Intent.createChooser(send, "Share $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
