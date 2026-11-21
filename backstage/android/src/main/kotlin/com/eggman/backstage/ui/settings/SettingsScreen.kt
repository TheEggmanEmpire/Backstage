package com.eggman.backstage.ui.settings

import androidx.compose.foundation.clickable
import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.data.SettingsBackup
import com.eggman.backstage.overlay.Overlay
import com.eggman.backstage.ui.CodeBlock
import com.eggman.backstage.ui.NumberSetting
import com.eggman.backstage.ui.Section
import com.eggman.backstage.ui.SettingToggle
import com.eggman.backstage.ui.ToggleRow
import com.eggman.backstage.ui.copyToClipboard
import com.eggman.backstage.voice.VoskModelCatalog
import com.eggman.backstage.voice.WakePhrase
import com.eggman.backstage.voice.WakeWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Hooks the orchestrator wires to the core (MCP server restart etc.). */
object SettingsHooks {
    /** Called after MCP enable/port/LAN/token changes so the core can restart its server. */
    @Volatile
    var mcpConfigChanged: (() -> Unit)? = null
}

@Composable
fun SettingsScreen(
    store: BackstageSettingsStore,
    onOpenSetup: () -> Unit,
    onOpenLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSkills: () -> Unit = {},
    onOpenAppSettings: (() -> Unit)? = null,
    openUpdatesRequest: Int = 0,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf<SettingsGroup?>(null) }
    var revealMcpTools by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val searching = query.isNotBlank() && destination == null
    val updatesAvailable = com.eggman.backstage.update.AppUpdates.enabled(LocalContext.current)
    androidx.compose.runtime.LaunchedEffect(openUpdatesRequest) {
        if (openUpdatesRequest > 0 && updatesAvailable) { query = ""; destination = SettingsGroup.UPDATES }
    }
    val desktopAvailable = com.eggman.backstage.host.DesktopHosts.current != null
    val results = remember(query, desktopAvailable, updatesAvailable, onOpenAppSettings != null) {
        val tools = if (query.isNotBlank()) SettingsSearch.mcpTools(McpToolChoices.all()) else emptyList()
        SettingsSearch.search(query, tools).filter {
            (it.group != SettingsGroup.DESKTOP || desktopAvailable) &&
                (it.group != SettingsGroup.UPDATES || updatesAvailable) &&
                (it.group != SettingsGroup.APP || onOpenAppSettings != null)
        }
    }
    androidx.compose.runtime.LaunchedEffect(query, destination) {
        if (destination == null) revealMcpTools = false // only an MCP-tool search result opens the tool list
        scroll.scrollTo(0)
    }
    Column(modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Agent settings", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(query, { query = it; destination = null }, Modifier.fillMaxWidth(),
            label = { Text("Search settings") }, placeholder = { Text("Try token, screenshots, approvals…") }, singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) TextButton(onClick = { query = ""; destination = null }) { Text("Clear") }
            })
        if (searching) {
            if (results.isEmpty()) Text("No settings match", style = MaterialTheme.typography.bodyMedium)
            else {
                Text("${results.size} settings found", style = MaterialTheme.typography.labelLarge)
                results.forEach { entry ->
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text("${entry.group.title} · ${entry.description}") },
                        modifier = Modifier.clickable {
                            when (entry.title) {
                                "App settings" -> onOpenAppSettings?.invoke()
                                "Skills" -> onOpenSkills()
                                else -> {
                                    revealMcpTools = entry.id.startsWith("mcp-tool:")
                                    destination = entry.group
                                }
                            }
                        },
                    )
                }
            }
        } else {
            if (destination != null) TextButton(onClick = { destination = null }) { Text("Back to search results") }
            if (destination == null) onOpenAppSettings?.let { open ->
                Section(SettingsGroup.APP.title, SettingsGroup.APP.subtitle) {
                    OutlinedButton(onClick = open) { Text("Open app settings") }
                }
            }
            SettingsCard(SettingsGroup.MODEL, destination) { ProviderSection(store) }
            SettingsCard(SettingsGroup.INSTRUCTIONS, destination) {
                StandingInstructions(store)
                GlobalInstructionsSection(store)
                RulesSection(store)
                MemorySection(store)
                SkillsSection(onOpenSkills)
            }
            SettingsCard(SettingsGroup.AGENT, destination) { AgentWorkflowSettings(store) }
            SettingsCard(SettingsGroup.SAFETY, destination) {
                SafetySettings(store)
                SkillScriptsSettingsSection(store)
            }
            SettingsCard(SettingsGroup.SCREEN, destination) { ScreenSettings(store) }
            if (desktopAvailable) SettingsCard(SettingsGroup.DESKTOP, destination) { DesktopPopupSettings(store) }
            SettingsCard(SettingsGroup.ACCESSIBILITY, destination) {
                AccessibilitySection(store)
                ModulesSection(store)
            }
            SettingsCard(SettingsGroup.VOICE, destination) { VoiceSection(store) }
            SettingsCard(SettingsGroup.BUBBLE, destination) { BubbleSection(store) }
            SettingsCard(SettingsGroup.CONNECTIONS, destination) {
                McpSection(store, revealMcpTools)
                RemoteMcpSection(store)
            }
            SettingsCard(SettingsGroup.AUTOMATION, destination) { AutomationSection(store) }
            SettingsCard(SettingsGroup.OMP, destination) { OmpSettingsSection(store) }
            if (updatesAvailable) SettingsCard(SettingsGroup.UPDATES, destination) { com.eggman.backstage.update.UpdatesSection() }
            SettingsCard(SettingsGroup.ABOUT, destination) { BackupAboutSettings(onOpenSetup, onOpenLicenses) }
        }
    }
}

@Composable
private fun DesktopPopupSettings(store: BackstageSettingsStore) {
    SettingsSection(
        "Desktop popup",
        "How the desktop shows while you use other apps: a floating popup window (like a video), or nothing at all " +
            "while it keeps running in the background. Inside Backstage it plays in the Home screen.",
    ) {
        SettingToggle("Pop out when leaving the app", "The running desktop comes back as the popup whenever you leave " +
            "Backstage, also after you sent it to the background", { store.popOutOnLeave }) { store.popOutOnLeave = it }
        SettingToggle("Start desktop when Backstage opens", "Opening Backstage starts the desktop in the Home player " +
            "when it is not running", { store.startDesktopOnOpen }) { store.startDesktopOnOpen = it }
        com.eggman.backstage.ui.desktop.MiniShapeChips()
        (com.eggman.backstage.host.DesktopHosts.current as? com.eggman.backstage.host.DesktopHostExtras)?.let { extras ->
            var lockedKey by remember { mutableStateOf(0) }
            val lockedScope = rememberCoroutineScope()
            SettingToggle(
                "Keep the desktop running while locked",
                "Lets tasks, schedules and automations use the desktop while the phone is locked. Desktop apps then " +
                    "stay awake and are not covered by your lock screen, so anything that can reach the desktop (the " +
                    "agent, MCP clients) can use them without your unlock. Applies when the desktop starts; a task " +
                    "started while locked restarts a desktop that was started before.",
                { runCatching { extras.runWhileLocked() }.getOrDefault(false) },
            ) { on ->
                runCatching { extras.setRunWhileLocked(on) }
                // Waking the desktop's own display group needs TURN_SCREEN_ON (granted through the shell).
                if (on) lockedScope.launch(kotlinx.coroutines.Dispatchers.Default) { runCatching { extras.ensureDesktopWakePermission() } }
                lockedKey++
            }
            LockedModeRow(extras, lockedKey)
            DesktopFocusSetting(extras)
        }
    }

}

@Composable
private fun BackupAboutSettings(onOpenSetup: () -> Unit, onOpenLicenses: () -> Unit) {
    SettingsSection("About") {
        val context = LocalContext.current
        val version = remember { com.eggman.backstage.ui.licenses.appVersionName(context) }
        val backupScope = rememberCoroutineScope()
        var backupBusy by remember { mutableStateOf(false) }
        var backupMessage by remember { mutableStateOf<String?>(null) }
        var confirmExport by remember { mutableStateOf(false) }
        var confirmImport by remember { mutableStateOf(false) }

        fun transferBackup(uri: Uri, exporting: Boolean) {
            backupBusy = true
            backupScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        if (exporting) {
                            context.contentResolver.openOutputStream(uri, "wt")?.use { SettingsBackup.export(context, it) }
                                ?: error("Could not open the destination file")
                        } else {
                            context.contentResolver.openInputStream(uri)?.use { SettingsBackup.import(context, it) }
                                ?: error("Could not open the backup file")
                        }
                    }
                    backupMessage = (if (exporting) "Exported " else "Imported ") + result.summary() +
                        if (exporting) "" else " Restart Backstage (fully close it, then reopen it) to reload settings and schedules."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    backupMessage = "Backup failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    backupBusy = false
                }
            }
        }

        val backupExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) transferBackup(uri, exporting = true)
        }
        val backupImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) transferBackup(uri, exporting = false)
        }
        Text("Backstage ${version.orEmpty()} — ${com.eggman.backstage.ui.licenses.Licenses.APP_LICENSE}", style = MaterialTheme.typography.bodyMedium)
        Text(com.eggman.backstage.ui.licenses.Licenses.OWN_COPYRIGHT, style = MaterialTheme.typography.bodySmall)
        Text(com.eggman.backstage.ui.licenses.Licenses.sourceLine(version, com.eggman.backstage.BuildConfig.SOURCE_REVISION, com.eggman.backstage.BuildConfig.SOURCE_URL), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenSetup) { Text("Setup checklist") }
            OutlinedButton(onClick = onOpenLicenses) { Text("Open-source licenses") }
        }
        Text("Backup", style = MaterialTheme.typography.labelLarge)
        Text("Save or restore all Backstage, desktop and Linux settings, API keys and tokens, and your own skills as a .zip file. Skills synced from GitHub are downloaded again after import; skills too large for the backup are left out with a warning.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirmExport = true }, enabled = !backupBusy) { Text("Export settings") }
            OutlinedButton(onClick = { confirmImport = true }, enabled = !backupBusy) { Text("Import settings") }
        }
        if (backupBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (confirmExport) AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Export settings?") },
            text = { Text("This file contains your AI API keys and tokens in readable plaintext. Anyone with the file can use them. Keep it private and store it securely.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExport = false
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT))
                    backupExporter.launch("backstage-settings-$stamp.zip")
                }) { Text("Export") }
            },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("Cancel") } },
        )
        if (confirmImport) AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Import settings?") },
            text = { Text("Importing replaces all current settings and user skills with the backup. Only choose a file you trust. Restart Backstage after importing.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    backupImporter.launch(arrayOf("application/zip", "application/octet-stream"))
                }) { Text("Choose backup") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }
}

/** One sign-in flow per process: it must outlive the screen while the user is in the browser. */
private var chatGptSignIn: ChatGptSignIn? = null

private fun chatGptSignIn(store: BackstageSettingsStore): ChatGptSignIn =
    chatGptSignIn ?: ChatGptSignIn(save = { store.chatGptTokens = it }).also { chatGptSignIn = it }

@Composable
private fun ChatGptAccount(store: BackstageSettingsStore, onChanged: () -> Unit) {
    val context = LocalContext.current
    // The flow outlives this screen: hand it the application context, never the Activity.
    val app = context.applicationContext
    val flow = remember { chatGptSignIn(store) }
    val state by flow.state.collectAsState()
    var tokens by remember { mutableStateOf(store.chatGptTokens) }
    androidx.compose.runtime.LaunchedEffect(state) {
        if (state is ChatGptSignIn.State.Done) { tokens = store.chatGptTokens; onChanged() }
    }
    val openUrl: (String) -> Unit = { url ->
        runCatching {
            app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    val t = tokens
    if (t != null) {
        val who = listOf(t.email, t.plan.replaceFirstChar { it.uppercase() }).filter { it.isNotBlank() }.joinToString(" · ")
        Text("Signed in" + if (who.isNotEmpty()) " as $who" else "", style = MaterialTheme.typography.bodyMedium)
        Text("Model calls use your ChatGPT plan's limits, not API credit.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { flow.signOut(); tokens = null; onChanged() }) { Text("Sign out") }
        return
    }
    when (val s = state) {
        is ChatGptSignIn.State.Browser -> {
            Text("Finish signing in in the browser, then come back here.", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            var pasted by remember { mutableStateOf("") }
            Text("If the browser ends on a page that cannot be opened, copy its address and paste it here:", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(pasted, { pasted = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Address from the browser") }, placeholder = { Text("http://localhost:1455/auth/callback?code=…") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { flow.submitRedirect(pasted) }, enabled = pasted.isNotBlank()) { Text("Use address") }
                TextButton(onClick = { openUrl(s.url) }) { Text("Open browser again") }
                TextButton(onClick = { flow.cancel() }) { Text("Cancel") }
            }
        }
        is ChatGptSignIn.State.Code -> {
            Text("On any device, open ${s.device.verificationUrl}, sign in and enter this code:", style = MaterialTheme.typography.bodyMedium)
            Text(s.device.userCode, style = MaterialTheme.typography.headlineMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openUrl(s.device.verificationUrl) }) { Text("Open page") }
                TextButton(onClick = { copyToClipboard(context, "ChatGPT code", s.device.userCode) }) { Text("Copy code") }
                TextButton(onClick = { flow.cancel() }) { Text("Cancel") }
            }
            Text("The code is valid for 15 minutes.", style = MaterialTheme.typography.bodySmall)
        }
        ChatGptSignIn.State.Finishing -> {
            Text("Connecting your ChatGPT account…", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        else -> {
            (s as? ChatGptSignIn.State.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("Use a ChatGPT Plus, Pro, Business or Enterprise subscription instead of an API key.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = { flow.startBrowser(openUrl) }) { Text("Sign in with ChatGPT") }
                OutlinedButton(onClick = { flow.startCode() }) { Text("Use a code instead") }
            }
            Text("\"Use a code\" works when the browser on this phone cannot sign in: approve the code on a computer or another phone. " +
                "It may need device code sign-in allowed in ChatGPT → Settings → Security.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProviderSection(store: BackstageSettingsStore) {
    SettingsSection("AI provider", "Bring your own key. Requests go straight from this phone to the provider you pick.") {
        var engine by remember { mutableStateOf(store.agentEngine) }
        DisposableEffect(store) {
            val stop = store.observe { if (it == "agent_engine") engine = store.agentEngine }
            onDispose { stop() }
        }
        Text("Agent engine", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.eggman.backstage.data.AgentEngine.entries.forEach { e ->
                FilterChip(engine == e, { engine = e; store.agentEngine = e }, { Text(e.label) })
            }
        }
        Text(if (engine == com.eggman.backstage.data.AgentEngine.OMP)
            "Uses the installed OMP harness and privileged access. Falls back to Backstage when unavailable. omp's own shell tools run with shell-user access."
            else "The native Backstage agent loop.", style = MaterialTheme.typography.bodySmall)
        var providerId by remember { mutableStateOf(store.providerId) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProviderOptions.all.forEach { p ->
                FilterChip(providerId == p.id, { providerId = p.id; store.providerId = p.id }, { Text(p.label) })
            }
        }
        val option = ProviderOptions.byId(providerId) ?: return@SettingsSection
        key(providerId) {
            var apiKey by remember { mutableStateOf(store.apiKey(providerId)) }
            var model by remember { mutableStateOf(store.model(providerId)) }
            var modelDraft by remember { mutableStateOf(model) }
            var url by remember { mutableStateOf(store.baseUrl(providerId)) }
            var showKey by remember { mutableStateOf(false) }
            if (option.note.isNotEmpty()) Text(option.note, style = MaterialTheme.typography.bodySmall)
            if (providerId == com.eggman.backstage.data.Provider.CHATGPT.name) {
                ChatGptAccount(store) { apiKey = store.apiKey(providerId) }
            } else OutlinedTextField(apiKey, { apiKey = it; store.setApiKey(providerId, it) }, Modifier.fillMaxWidth(),
                label = { Text(if (option.needsKey) "API key" else "API key (optional)") }, placeholder = { Text(option.keyHint) },
                singleLine = true, visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } })
            ProviderModelsSection(store, providerId, apiKey, url, model) { model = it; modelDraft = it }
            OutlinedTextField(modelDraft, { modelDraft = it }, Modifier.fillMaxWidth(),
                label = { Text("Main model / add model ID") }, supportingText = { Text("Chat uses the main model. Enter an ID and tap Use to add/select it; needs tool calling, vision recommended.") }, singleLine = true,
                trailingIcon = { TextButton(onClick = {
                    model = modelDraft.trim(); modelDraft = model; store.setModel(providerId, model)
                }, enabled = modelDraft.isNotBlank()) { Text("Use") } })
            OutlinedTextField(url, { url = it; store.setBaseUrl(providerId, it) }, Modifier.fillMaxWidth(),
                label = { Text("Base URL") }, singleLine = true)
            var ctxText by remember { mutableStateOf(store.contextOverride(providerId)?.toString().orEmpty()) }
            var effective by remember { mutableStateOf(store.contextWindow(providerId)) }
            var caps by remember { mutableStateOf(store.visionSupported(providerId) to store.toolsSupported(providerId)) }
            androidx.compose.runtime.LaunchedEffect(model, apiKey, url) {
                kotlinx.coroutines.delay(1_000)
                store.detectContext(providerId)
                effective = store.contextWindow(providerId)
                caps = store.visionSupported(providerId) to store.toolsSupported(providerId)
            }
            OutlinedTextField(ctxText, { v ->
                ctxText = v.filter(Char::isDigit).take(9)
                store.setContextOverride(providerId, ctxText.toLongOrNull())
                effective = store.contextWindow(providerId)
            }, Modifier.fillMaxWidth(), label = { Text("Context window (tokens)") },
                placeholder = { Text("Automatic") }, singleLine = true,
                supportingText = { Text("In use: ${"%,d".format(effective)} tokens" + if (ctxText.isEmpty()) " (from the provider's model list or defaults; type a number to override)" else " (your value)") })
            val visionLabel = if (caps.first) "yes" else "no"
            val toolsLabel = when (caps.second) { true -> "yes"; false -> "no"; null -> "unknown" }
            Text("From the model list — vision: $visionLabel · tools: $toolsLabel", style = MaterialTheme.typography.bodySmall)
            var visionChoice by remember { mutableStateOf(store.visionOverride(providerId)) }
            Text("Vision", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(visionChoice == null, { visionChoice = null; store.setVisionOverride(providerId, null); effective = store.contextWindow(providerId); caps = store.visionSupported(providerId) to store.toolsSupported(providerId) }, { Text("Auto") })
                FilterChip(visionChoice == true, { visionChoice = true; store.setVisionOverride(providerId, true); caps = store.visionSupported(providerId) to store.toolsSupported(providerId) }, { Text("On") })
                FilterChip(visionChoice == false, { visionChoice = false; store.setVisionOverride(providerId, false); caps = store.visionSupported(providerId) to store.toolsSupported(providerId) }, { Text("Off") })
            }
            Text(when (visionChoice) { null -> "Auto: from the model list, else the model's default."; true -> "Screenshots are sent to this model."; false -> "No screenshots sent to this model." }, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { model = option.defaultModel; modelDraft = model; url = option.defaultBaseUrl
                    store.setModel(providerId, model); store.setBaseUrl(providerId, url) }) { Text("Reset to defaults") }
            }
            ProviderOptions.validate(option, apiKey, model, url).forEach {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun GlobalInstructionsSection(store: BackstageSettingsStore) {
    var instructions by remember(store) { mutableStateOf(store.effectiveGlobalInstructions()) }
    SettingsSection("Global instructions", "Shared by Backstage and all omp sessions. The Android environment guide always stays active in omp.") {
        OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(), minLines = 5,
            maxLines = 14, label = { Text("Global instructions") })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { store.globalInstructions = instructions; instructions = store.effectiveGlobalInstructions() }) { Text("Save") }
            TextButton(onClick = { store.globalInstructions = ""; instructions = store.effectiveGlobalInstructions() }) { Text("Reset to default") }
        }
        Text("Saved changes apply to the next Backstage task. Termux checks about every 8 seconds: provider/model/MCP changes apply live, skills load on the next turn/session, and global instructions apply when an omp session restarts.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ProviderModelsSection(store: BackstageSettingsStore, providerId: String, apiKey: String, url: String, main: String, onMain: (String) -> Unit) {
    val provider = com.eggman.backstage.data.Provider.parse(providerId) ?: return
    var enabled by remember(store, providerId, main) { mutableStateOf(store.enabledModels(providerId)) }
    var result by remember(store, providerId, apiKey, url) { mutableStateOf<com.eggman.backstage.llm.ModelListResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    DisposableEffect(store, providerId, main) {
        val stop = store.observe { changed ->
            if (changed == "models_enabled_$providerId" || changed == "model_$providerId") enabled = store.enabledModels(providerId)
        }
        onDispose { stop() }
    }
    androidx.compose.runtime.LaunchedEffect(store, providerId, apiKey, url, refresh) {
        loading = true
        try {
            kotlinx.coroutines.delay(1_000)
            result = store.fetchModels(providerId, force = refresh > 0)
        } finally { loading = false }
    }
    val loaded = result as? com.eggman.backstage.llm.ModelListResult.Loaded
    val models = (store.modelOptions(providerId) + provider.suggestedModels + loaded?.models.orEmpty().map { it.id } + loaded?.hidden.orEmpty().map { it.id } + enabled.sorted() + main)
        .filter(String::isNotBlank).distinct()
    Text("Models", style = MaterialTheme.typography.labelLarge)
    Text("Enable models for omp switching. Select one main model for Backstage chat; the main model stays on even after All off.", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { store.setEnabledModels(providerId, models.toSet()); enabled = store.enabledModels(providerId) }) { Text("All on") }
        TextButton(onClick = { store.setEnabledModels(providerId, emptySet()); enabled = store.enabledModels(providerId) }) { Text("All off") }
        TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Reload models") }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    when (val r = result) {
        is com.eggman.backstage.llm.ModelListResult.Unavailable -> Text(r.message, style = MaterialTheme.typography.bodySmall)
        is com.eggman.backstage.llm.ModelListResult.KeyRejected -> Text(r.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else -> Unit
    }
    var modelQuery by remember { mutableStateOf("") }
    var modelsOnlyOn by remember { mutableStateOf(false) }
    OutlinedTextField(modelQuery, { modelQuery = it }, Modifier.fillMaxWidth(), label = { Text("Search models") },
        singleLine = true, trailingIcon = { if (modelQuery.isNotEmpty()) TextButton(onClick = { modelQuery = "" }) { Text("Clear") } })
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(modelsOnlyOn, { modelsOnlyOn = it }); Text("Only enabled", style = MaterialTheme.typography.bodySmall)
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        models.filter { (!modelsOnlyOn || it in enabled) && (modelQuery.isBlank() || it.contains(modelQuery.trim(), ignoreCase = true)) }.forEach { id ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(modifier = Modifier.semantics { contentDescription = "Use $id as main model" }, selected = main == id, onClick = {
                    store.setModel(providerId, id); enabled = store.enabledModels(providerId); onMain(id)
                }, enabled = id in enabled)
                Text(id + if (main == id) " (main)" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.Switch(modifier = Modifier.semantics { contentDescription = "Enable $id" }, checked = id in enabled, enabled = main != id, onCheckedChange = { on ->
                    store.setEnabledModels(providerId, if (on) enabled + id else enabled - id)
                    enabled = store.enabledModels(providerId)
                })
            }
        }
    }
}

@Composable
private fun StandingInstructions(store: BackstageSettingsStore) {
    var instructions by remember { mutableStateOf(store.customInstructions) }
    OutlinedTextField(instructions, { instructions = it.take(CoreSettingsStore.MAX_INSTRUCTIONS); store.customInstructions = instructions },
        Modifier.fillMaxWidth(), minLines = 3, label = { Text("Your standing instructions") },
        supportingText = { Text("Applied to every task, e.g. \"Reply in English. Never buy anything. Use Firefox for the web.\"") })
}

@Composable
private fun AgentWorkflowSettings(store: BackstageSettingsStore) {
    SettingToggle("Plan Mode", "Writes a checklist first and works through it step by step", { store.planMode }) { store.planMode = it }
    NumberSetting("Safety limit: actions per task (20–200)", store.maxSteps, Modifier.fillMaxWidth()) { store.maxSteps = it }
    TimeLimitSetting(store)
    NumberSetting("Chat history: chats kept (5–500)", store.chatThreadsMax, Modifier.fillMaxWidth()) { store.chatThreadsMax = it }
    Text("Where the agent works", style = MaterialTheme.typography.labelLarge)
    var target by remember { mutableStateOf(store.defaultTarget) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TARGET_ORDER.forEach { t ->
            FilterChip(target == t, { target = t; store.defaultTarget = t; com.eggman.backstage.shell.ShellLifecycle.onTargetChosen(t) }, { Text(targetLabel(t)) })
        }
    }
    Text(
        when (target) {
            Target.DESKTOP -> "Gated to the DeX desktop: the agent only operates apps there (mini window or background) and never touches your phone screen. The chat's DeX desktop / Phone / Auto chips (in the app and in the popup chat) change this same choice; a message that says \"on my phone\" or \"on DeX\" goes there."
            Target.PHONE -> "The agent operates apps on your phone screen."
            Target.AUTO -> "Advanced: desktop when available, the phone screen otherwise or when a request refers to it."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    SettingToggle("Fast mode", "Shorter waits for the screen to settle after each action and no cursor lead (the cursor still glides). " +
        "Turn off if an app reacts slowly and the agent acts on screens that are still changing.",
        { store.fastMode }) { store.fastMode = it }
}

@Composable
private fun SafetySettings(store: BackstageSettingsStore) {
    SettingToggle("Confirm before irreversible actions", "Sending, posting, buying, deleting or calling waits for your approval",
        { store.confirmActions }) { store.confirmActions = it }
    Text("When nobody can confirm", style = MaterialTheme.typography.labelLarge)
    var unattended by remember { mutableStateOf(store.nonInteractiveConfirm) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        UNATTENDED_ORDER.forEach { c ->
            FilterChip(unattended == c, { unattended = c; store.nonInteractiveConfirm = c }, { Text(unattendedLabel(c)) })
        }
    }
    Text(unattendedDetail(unattended), style = MaterialTheme.typography.bodySmall)
    Text("ADB commands", style = MaterialTheme.typography.labelLarge)
    val adbAutoApprove by rememberAdbAutoApprove(store)
    val adbAvailable by rememberAdbAvailable()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(!adbAutoApprove, { store.adbAutoApprove = false }, { Text("Ask each time") })
        FilterChip(adbAutoApprove, { store.adbAutoApprove = true }, { Text("Auto") })
    }
    Text(
        if (adbAutoApprove) "Commands run without asking, as the shell user. Anything the agent decides to run — uninstalling apps, changing system settings — happens immediately."
        else "The agent can run ADB shell commands (install, uninstall, pm, am, settings …) through Stellar, Shizuku or Dextop's wireless debugging. You approve each command.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (!adbAvailable) Text("Needs Stellar, Shizuku or Dextop wireless debugging.", style = MaterialTheme.typography.bodySmall)
    SettingToggle("Remote callers may use the phone screen", "MCP clients and the broadcast API stay on the DeX desktop unless this is on",
        { store.externalPhoneControl }) { store.externalPhoneControl = it }
}

@Composable
private fun ScreenSettings(store: BackstageSettingsStore) {
    SettingToggle("Vision", "Send screenshots to the model when the element list is not enough", { store.vision }) { store.vision = it }
    Text("Screenshots", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberSetting("JPEG quality (30-100)", store.screenshotQuality, Modifier.weight(1f)) { store.screenshotQuality = it }
        NumberSetting("Scale in % (10-100)", store.screenshotScale, Modifier.weight(1f)) { store.screenshotScale = it }
    }
    Text("Lower values send fewer tokens to the provider; higher ones help with small text.", style = MaterialTheme.typography.bodySmall)
    SettingToggle("Show agent cursor", "An animated pointer shows where the agent taps, types and scrolls (never in its own screenshots, never catches touches)",
        { store.showAgentCursor }) { store.showAgentCursor = it }
    SettingToggle("Fresh desktop for automations", "Scheduled, Tasker, broadcast and MCP runs restart a running desktop first, " +
        "so they start on a clean screen with no windows left from earlier work (unsaved work in desktop apps is lost)",
        { store.freshDesktopForAutomation }) { store.freshDesktopForAutomation = it }
    var fast by remember(store) { mutableStateOf(store.fastMode) }
    DisposableEffect(store) {
        val stop = store.observe { if (it == "fast_mode") fast = store.fastMode }
        onDispose { stop() }
    }
    var lead by remember { mutableStateOf(store.agentCursorLeadMs) }
    Text(if (fast) "Cursor leads each action by (not in fast mode)" else "Cursor leads each action by", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0, 100, 200, 400).forEach { ms ->
            FilterChip(lead == ms, { lead = ms; store.agentCursorLeadMs = ms }, { Text(if (ms == 0) "No delay" else "$ms ms") })
        }
    }
}

/** Accessibility consent (prominent disclosure), revocable at any time. */
@Composable
internal fun AccessibilitySection(store: BackstageSettingsStore) {
    var consent by remember { mutableStateOf(store.accessibilityConsent) }
    var auto by remember { mutableStateOf(store.autoEnableAccessibility) }
    var disclose by remember { mutableStateOf(false) }
    SettingsSection("Accessibility", "One accessibility service, \"Backstage\", runs the desktop and lets the agent read and operate apps. Screen content goes only to your AI provider.") {
        ToggleRow("Consent to accessibility use", if (consent) "Given. Turn off to withdraw: Backstage then never switches the service on by itself." +
            " Switch the service off in Android's accessibility settings to stop it now." else "Not given: Backstage does not switch its accessibility service on.", consent) { on ->
            if (on) disclose = true
            else { consent = false; store.accessibilityConsent = false }
        }
        ToggleRow("Turn the service back on automatically", "With Stellar, Shizuku or wireless debugging: re-enables the service when the desktop resets the accessibility list",
            auto && consent, enabled = consent) { auto = it; store.autoEnableAccessibility = it }
        var pause by remember { mutableStateOf(store.detectorPause) }
        var delayS by remember { mutableStateOf((store.detectorPauseDelayMs / 1000).toString()) }
        var pkgs by remember { mutableStateOf(store.detectorPackages) }
        ToggleRow("Pause while a detector app is in front",
            "Switches the service off while an app that warns about enabled accessibility services is in front, and back on after it left",
            pause && consent, enabled = consent) { pause = it; store.detectorPause = it }
        if (pause && consent) {
            OutlinedTextField(delayS, { v ->
                val d = v.filter { it.isDigit() }.take(2)
                delayS = d
                d.toIntOrNull()?.let { store.detectorPauseDelayMs = it.coerceIn(1, 60) * 1000 }
            }, label = { Text("Re-check seconds") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pkgs, { pkgs = it; store.detectorPackages = it },
                label = { Text("Detector packages") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Package names, colon- or comma-separated.") })
        }
    }
    if (disclose) com.eggman.backstage.ui.onboarding.AccessibilityDisclosureDialog(
        onAgree = { consent = true; store.accessibilityConsent = true; disclose = false },
        onDecline = { disclose = false },
    )
}

@Composable
private fun VoiceSection(store: BackstageSettingsStore) {
    val context = LocalContext.current
    val download by WakeWord.download.collectAsState()
    val status by WakeWord.status.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    var confirmModel by remember { mutableStateOf<com.eggman.backstage.voice.VoskModel?>(null) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++; WakeWord.refresh(context) }

    SettingsSection("Voice", "Push-to-talk uses Android's speech recogniser. The wake word runs fully on the phone with an open-source Vosk model.") {
        SettingToggle("Read replies aloud", "Speak results of voice commands", { store.speakReplies }) { store.speakReplies = it }
        SettingToggle("Prefer on-device recognition", "Keeps your voice on the phone when the recogniser supports it",
            { store.preferOnDeviceStt }) { store.preferOnDeviceStt = it }
        HorizontalDivider()
        key(refresh) {
            val model = WakeWord.selectedModel(context)
            val installed = WakeWord.installer(context).isInstalled(model)
            ToggleRow("Wake word", "Say the phrase, then your command. Works with only a small notification — no bubble needed.",
                store.wakeWordEnabled) { on ->
                store.wakeWordEnabled = on
                if (on && !WakeWord.micGranted(context)) mic.launch(Manifest.permission.RECORD_AUDIO)
                if (on && !installed) confirmModel = model
                WakeWord.refresh(context)
                refresh++
            }
            Text("Status: $status", style = MaterialTheme.typography.bodySmall)
            var phrase by remember { mutableStateOf(store.wakePhrase) }
            OutlinedTextField(phrase, { phrase = it }, Modifier.fillMaxWidth(), label = { Text("Wake phrase") }, singleLine = true,
                supportingText = { Text("Use common words in the model's language, e.g. \"hey computer\"") })
            val problems = WakePhrase.validate(phrase)
            problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (phrase != store.wakePhrase && problems.isEmpty()) TextButton(onClick = {
                store.wakePhrase = phrase
                // Restart so the notification and the listener use the new phrase.
                WakeWord.restart(context)
            }) { Text("Save phrase") }
            // The recogniser only knows words of its model's language: "backstage" is an English word.
            if (!model.id.contains("-en-") && WakePhrase.normalize(phrase) == WakePhrase.normalize(CoreSettingsStore.DEFAULT_WAKE_PHRASE)) {
                Text("The ${model.language} model may not know \"${phrase}\". Choose a phrase of everyday ${model.language} words.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text("Speech model", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VoskModelCatalog.models.forEach { m ->
                    val have = WakeWord.installer(context).isInstalled(m)
                    FilterChip(model.id == m.id, {
                        store.wakeModelId = m.id
                        if (!have) confirmModel = m
                        // A running listener keeps its loaded model until restarted.
                        else if (model.id != m.id) WakeWord.restart(context)
                        refresh++
                    }, { Text(m.language + if (have) " ✓" else "") })
                }
            }
            when (val d = download) {
                is WakeWord.DownloadState.Running -> {
                    LinearProgressIndicator(progress = { if (d.total > 0) d.done.toFloat() / d.total else 0f }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = WakeWord::cancelDownload) { Text("Cancel download") }
                }
                is WakeWord.DownloadState.Failed -> Text("Download failed: ${d.message}", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                else -> Unit
            }
            if (installed) TextButton(onClick = { WakeWord.installer(context).delete(model); refresh++; WakeWord.refresh(context) }) {
                Text("Delete ${model.language} model (${model.sizeLabel})")
            }
            SettingToggle("Pause while the screen is off", "Saves battery; say the phrase after waking the phone",
                { store.wakePauseWhenScreenOff }) { store.wakePauseWhenScreenOff = it; WakeWord.refresh(context) }
            SettingToggle("Pause while the phone is locked",
                "Recommended. While locked, the wake word only accepts \"stop\": tasks, answers and spoken replies need an unlocked phone",
                { store.wakePauseWhenLocked }) { store.wakePauseWhenLocked = it; WakeWord.refresh(context) }
            SettingToggle("Only while charging", "Listen only when the phone is plugged in", { store.wakeOnlyWhileCharging }) {
                store.wakeOnlyWhileCharging = it; WakeWord.refresh(context)
            }
        }
    }
    confirmModel?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmModel = null },
            title = { Text("Download speech model?") },
            text = {
                Text("${m.language} model, ${m.sizeLabel}, from alphacephei.com (Vosk project, ${m.license}). " +
                    "It is checked against a known size and SHA-256 and stored privately in the app. Wi-Fi recommended.")
            },
            confirmButton = { TextButton(onClick = { WakeWord.startDownload(context, m); confirmModel = null }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirmModel = null }) { Text("Not now") } },
        )
    }
}

@Composable
private fun BubbleSection(store: BackstageSettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(store.bubbleEnabled) }
    var note by remember { mutableStateOf<String?>(null) }
    SettingsSection("Floating bubble", "A small button over other apps that shows the agent's progress. Tap it for the chat (the same as the desktop popup's chat), long-press to speak a task, drag it onto the X at the bottom to hide it.") {
        var dismissed by remember { mutableStateOf(Overlay.dismissed(context)) }
        ToggleRow("Show the bubble", null, enabled) { on ->
            enabled = on
            store.bubbleEnabled = on
            if (on) {
                Overlay.setDismissed(context, false)
                dismissed = false
            }
            if (on && !Overlay.canDraw(context)) {
                scope.launch {
                    if (!Overlay.grantPrivileged(context)) {
                        note = "Allow \"Display over other apps\" for Backstage, then come back."
                        context.startActivity(Overlay.permissionIntent(context))
                    }
                    Overlay.refresh(context)
                }
            } else Overlay.refresh(context)
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (enabled && dismissed) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hidden: you dragged it away.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { Overlay.show(context); dismissed = false }) { Text("Show it again") }
        }
        Text("When a task ends", style = MaterialTheme.typography.labelLarge)
        var display by remember { mutableStateOf(store.resultDisplay) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(display == ResultDisplay.EXPANDED, { display = ResultDisplay.EXPANDED; store.resultDisplay = display }, { Text("Open the chat with the result") })
            FilterChip(display == ResultDisplay.COMPACT, { display = ResultDisplay.COMPACT; store.resultDisplay = display }, { Text("Notification only") })
        }
        SettingToggle("Close the result after 5 seconds", null, { store.resultAutoDismiss }) { store.resultAutoDismiss = it }
        SettingToggle("Result notifications", "Notify when tasks started outside the app finish", { store.notifyResults }) { store.notifyResults = it }
        Text("The bubble hides itself while the agent takes screenshots of your phone screen.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun lanAddress(context: Context): String? = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
        ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
}.getOrNull()

@Composable
private fun McpSection(store: BackstageSettingsStore, revealTools: Boolean = false) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(store.mcpEnabled) }
    var lan by remember { mutableStateOf(store.mcpLan) }
    var port by remember { mutableStateOf(store.mcpPort) }
    var token by remember { mutableStateOf(store.mcpToken) }
    var reveal by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    val changed = { SettingsHooks.mcpConfigChanged?.invoke(); Unit }
    // The server runs as a foreground service: without the notification permission it would run invisibly, with no
    // address and no Stop button in sight, so a refusal switches it off again.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) { enabled = false; store.mcpEnabled = false; changed() }
    }
    var tools by remember { mutableStateOf(store.mcpEnabledTools) }
    var extended by remember { mutableStateOf(store.mcpExtendedTools) }
    var showExperimental by remember(revealTools) { mutableStateOf(extended || revealTools) }

    SettingsSection("MCP server", "Lets Claude Code, Claude Desktop, Cursor or any MCP client hand tasks to Backstage " +
        "(execute_task, get_screen_state, get_device_status and more) over Streamable HTTP.") {
        ToggleRow("Enable MCP server", "Runs with a persistent notification", enabled) {
            enabled = it; store.mcpEnabled = it; changed()
            if (it && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val server by com.eggman.backstage.mcp.McpServerStatus.state.collectAsState()
        server.error?.takeIf { enabled }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text("Tools offered to clients", style = MaterialTheme.typography.labelLarge)
        // every tool at once. Clients read the set on each request (tools/list, tools/call): nothing restarts.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { tools = McpToolChoices.all(); store.mcpEnabledTools = tools; showExperimental = true }) { Text("All on") }
            TextButton(onClick = { tools = emptySet(); store.mcpEnabledTools = tools; extended = false; store.mcpExtendedTools = false }) { Text("All off") }
        }
        McpToolChoices.recommended.forEach { (name, label) ->
            ToggleRow(label, name, name in tools) { on ->
                tools = if (on) tools + name else tools - name
                store.mcpEnabledTools = tools
            }
        }
        TextButton(onClick = { showExperimental = !showExperimental }) {
            Text(if (showExperimental) "Hide experimental tools" else "Experimental: low-level tools")
        }
        if (showExperimental) {
            ToggleRow(McpToolChoices.WAKE.second, McpToolChoices.WAKE.first, McpToolChoices.WAKE.first in tools) { on ->
                tools = if (on) tools + McpToolChoices.WAKE.first else tools - McpToolChoices.WAKE.first
                store.mcpEnabledTools = tools
            }
            Text("The client drives the screen step by step with these and must read the screen again after each action. " +
                "Each tool's description costs the client context tokens.", style = MaterialTheme.typography.bodySmall)
            ToggleRow("Every low-level tool", "All of the tools below, including ones added in later versions", extended) {
                extended = it; store.mcpExtendedTools = it
            }
            // or tick single tools (grouped names such as tap_screen are listed too).
            if (!extended) McpToolChoices.lowLevel.forEach { name ->
                ToggleRow(name, null, name in tools) { on ->
                    tools = if (on) tools + name else tools - name
                    store.mcpEnabledTools = tools
                }
            }
        }
        ToggleRow("Allow access from the local network", "Off: only this phone (use adb forward from a computer)", lan) {
            lan = it; store.mcpLan = it; changed()
        }
        // Applied with a button: restarting the server on every keystroke ("8", "84", "848"...) would churn ports.
        var portDraft by remember { mutableStateOf(port) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberSetting("Port (1024–65535)", portDraft, Modifier.weight(1f)) { portDraft = it }
            OutlinedButton(enabled = portDraft != port && portDraft in 1024..65535, onClick = {
                port = portDraft; store.mcpPort = portDraft; changed()
            }) { Text("Apply") }
        }
        Text("Access token", style = MaterialTheme.typography.labelLarge)
        CodeBlock(if (reveal) token else token.take(6) + "•".repeat(18))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
            OutlinedButton(onClick = { copyToClipboard(context, "Backstage token", token, sensitive = true) }) { Text("Copy") }
            OutlinedButton(onClick = { confirmRotate = true }) { Text("New token") }
        }
        Text("The token also authorises the broadcast API. Anyone with it can control your phone — keep it private.",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        val host = if (lan) lanAddress(context) ?: "<phone-ip>" else "127.0.0.1"
        Text("Endpoint: ${McpClientSnippets.endpoint(host, port)}", style = MaterialTheme.typography.bodyMedium)
        if (!lan) {
            Text("From a computer over USB:", style = MaterialTheme.typography.bodySmall)
            CodeBlock(McpClientSnippets.adbForward(port))
        }
        McpClientSnippets.all(host, port, token).forEach { s ->
            Text(s.title, style = MaterialTheme.typography.labelLarge)
            Text(s.where, style = MaterialTheme.typography.bodySmall)
            CodeBlock(if (reveal) s.body else s.body.replace(token, "<token>"))
            TextButton(onClick = { copyToClipboard(context, s.title, s.body, sensitive = true) }) { Text("Copy ${s.title} config") }
        }
    }
    if (confirmRotate) AlertDialog(
        onDismissRequest = { confirmRotate = false },
        title = { Text("Create a new token?") },
        text = { Text("Connected MCP clients and automations using the old token stop working until you update them.") },
        confirmButton = { TextButton(onClick = { token = store.rotateMcpToken(); confirmRotate = false; changed() }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("Cancel") } },
    )
}

@Composable
private fun AutomationSection(store: BackstageSettingsStore) {
    val context = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    SettingsSection("Automation", "Tasker, MacroDroid, Locale-compatible apps and plain Android broadcasts.") {
        Text("Plugin", style = MaterialTheme.typography.labelLarge)
        Text(AutomationDocs.PLUGIN_HELP, style = MaterialTheme.typography.bodySmall)
        Text(AutomationDocs.PLUGIN_SECURITY, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { confirmReset = true }) { Text("Reset plugin signing key") }
        HorizontalDivider()
        Text("Broadcast API", style = MaterialTheme.typography.labelLarge)
        CodeBlock(AutomationDocs.broadcastReference(context.packageName))
        TextButton(onClick = { copyToClipboard(context, "adb example", AutomationDocs.adbExample(context.packageName, store.mcpToken), sensitive = true) }) {
            Text("Copy adb example")
        }
        SettingToggle("Include result text in open broadcasts",
            "When no reply_package is given, results are broadcast to every app; turn off to send only the status",
            { store.broadcastResultText }) { store.broadcastResultText = it }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset signing key?") },
        text = { Text("Every saved Tasker/MacroDroid action will stop working until you open it and save it again.") },
        confirmButton = { TextButton(onClick = { store.rotatePluginSigningSecret(); confirmReset = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

/** The MCP tool allow-list shown in Settings (names are the MCP tool names the core serves). */
internal object McpToolChoices {
    val recommended = listOf(
        "execute_task" to "Run a task",
        "get_screen_state" to "Read the screen",
        "get_device_status" to "Device status",
    )
    val WAKE = "wake_device" to "Wake the screen"

    /**
     * Low-level tools a client may be given one by one: the registry's MCP tools (never agent-only or irreversible
     * ones) and the interoperability names (tap_screen, click_node, …). What a client finally sees also depends on
     * the device (desktop present, permissions granted).
   */
    /** Every tool the list shows ("All on"). */
    fun all(): Set<String> = (recommended.map { it.first } + WAKE.first + lowLevel).toSet()

    val lowLevel: List<String> by lazy {
        com.eggman.backstage.tools.ToolRegistry.default().tools.filter { !it.agentOnly && !it.irreversible }.map { it.name } +
            com.eggman.backstage.mcp.McpAliases.all.map { it.name }
    }
}

/** Own or shared desktop focus (shown only when the host can change it), plus the running desktop's actual mode. */
@Composable
private fun DesktopFocusSetting(extras: com.eggman.backstage.host.DesktopHostExtras) {
    var mode by remember { mutableStateOf(runCatching { extras.focusModeSetting() }.getOrNull()) }
    val running = remember { runCatching { extras.desktopFocusMode() }.getOrNull() }
    val current = mode ?: return
    Text("Desktop focus", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(com.eggman.backstage.host.DesktopFocusPolicy.OWN, com.eggman.backstage.host.DesktopFocusPolicy.SHARED).forEach { m ->
            FilterChip(current == m, {
                if (runCatching { extras.setFocusModeSetting(m) }.getOrDefault(false)) mode = runCatching { extras.focusModeSetting() }.getOrNull()
            }, { Text(DesktopFocusText.label(m)) })
        }
    }
    Text(DesktopFocusText.detail(current, running), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TimeLimitSetting(store: BackstageSettingsStore) {
    var minutes by remember { mutableStateOf(store.taskTimeLimitMin) }
    Text("Time limit per task", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val choices = (com.eggman.backstage.ui.TimeLimitChoices.options + minutes).distinct().sorted()
        choices.forEach { m ->
            FilterChip(minutes == m, { minutes = m; store.taskTimeLimitMin = m; minutes = store.taskTimeLimitMin },
                { Text(com.eggman.backstage.ui.TimeLimitChoices.label(m)) })
        }
    }
    Text(com.eggman.backstage.ui.TimeLimitChoices.summary(minutes), style = MaterialTheme.typography.bodySmall)
}

/** Wording of the desktop focus setting (pure, unit-tested). */
internal object DesktopFocusText {
    fun label(mode: String): String = when (mode) {
        com.eggman.backstage.host.DesktopFocusPolicy.SHARED -> "Shared"
        else -> "Own (recommended)"
    }

    fun detail(setting: String, running: String?): String = buildString {
        append(
            if (setting == com.eggman.backstage.host.DesktopFocusPolicy.SHARED) {
                "Shared: the desktop has no focus of its own. Typing on the desktop briefly takes the focus (and the keyboard) " +
                    "from the phone app you are using. Use it only if desktop apps do not react to keys with \"Own\"."
            } else {
                "Own: the desktop keeps a focus of its own, so the agent can type there while you keep using the phone."
            }
        )
        append(" Applies the next time the desktop starts.")
        when (running) {
            com.eggman.backstage.host.DesktopFocusPolicy.QUIET -> append(" Running desktop: own focus that never takes the phone's.")
            com.eggman.backstage.host.DesktopFocusPolicy.OWN -> append(" Running desktop: own focus.")
            com.eggman.backstage.host.DesktopFocusPolicy.SHARED -> append(" Running desktop: shared focus.")
        }
    }
}

/** DeX desktop first: it is the default, gated target. */
internal val TARGET_ORDER = listOf(Target.DESKTOP, Target.PHONE, Target.AUTO)

internal val UNATTENDED_ORDER = listOf(NonInteractiveConfirm.DENY, NonInteractiveConfirm.ALLOW_IF_REQUESTED, NonInteractiveConfirm.ALLOW)

internal fun unattendedLabel(c: NonInteractiveConfirm): String = when (c) {
    NonInteractiveConfirm.DENY -> "Deny"
    NonInteractiveConfirm.ALLOW_IF_REQUESTED -> "Ask in a notification"
    NonInteractiveConfirm.ALLOW -> "Allow"
}

internal fun unattendedDetail(c: NonInteractiveConfirm): String = when (c) {
    NonInteractiveConfirm.DENY -> "Tasks from schedules, automations and MCP clients are refused any step that needs your approval."
    NonInteractiveConfirm.ALLOW_IF_REQUESTED -> "The question waits in a notification (answering needs an unlocked phone); no answer in time counts as no."
    NonInteractiveConfirm.ALLOW -> "Ordinary confirmations are approved automatically. Texts, calls and other irreversible actions still ask in a notification."
}

internal fun targetLabel(t: Target): String = when (t) {
    Target.DESKTOP -> "DeX desktop"
    Target.PHONE -> "Phone"
    Target.AUTO -> "Auto"
}
