package com.eggman.backstage.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.text.DateFormat
import java.util.Date

@Composable
internal fun UpdatesSection() {
    val context = LocalContext.current
    val repo = remember { AppUpdates.repository(context) }
    val state by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    var permissionDialog by remember { mutableStateOf(false) }
    var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
    var taskDialog by remember { mutableStateOf<String?>(null) }
    var approvedTask by rememberSaveable { mutableStateOf<String?>(null) }
    var autoInstall by rememberSaveable { mutableStateOf(false) }

    fun run(action: String, approvedKey: String? = approvedTask) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            pendingAction = action
            permissionDialog = true
        } else if (runningTaskKey()?.let { it != approvedKey } == true) taskDialog = action
        else {
            approvedTask = approvedKey
            autoInstall = action == "download"
            UpdateDownloadService.start(context, install = action == "install", approvedTask = approvedKey)
        }
    }
    fun returnFromSettings() {
        val action = pendingAction ?: return
        pendingAction = null
        if (context.packageManager.canRequestPackageInstalls()) run(action)
        else repo.change { it.copy(message = "Install unknown apps is still disabled. Allow it for Backstage to continue.") }
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { returnFromSettings() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) returnFromSettings() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Activity state survives permission Settings/recreation; no download starts from a boot/alarm receiver.
    LaunchedEffect(Unit) {
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        if (pendingAction != null && !permissionDialog) returnFromSettings()
    }
    LaunchedEffect(state.phase) {
        if (state.phase == UpdatePhase.READY && autoInstall) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            autoInstall = false
            run("install") // re-check the task now; a task may have started during the large download
        }
    }

    Text("Installed version: ${state.installed}")
    Text("Latest version: ${state.latest?.version ?: "Not checked"}")
    Text("Last check: ${if (state.checkedAt == 0L) "Never" else DateFormat.getDateTimeInstance().format(Date(state.checkedAt))}")
    state.checkError?.let { Text("Check failed: $it") }
    if (state.nextCheck > System.currentTimeMillis()) Text("Next automatic check: ${DateFormat.getDateTimeInstance().format(Date(state.nextCheck))}")
    state.message?.let { Text(it) }
    if (state.busy) {
        if (state.phase == UpdatePhase.DOWNLOADING) {
            LinearProgressIndicator(progress = { if (state.total > 0) (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) else 0f })
            Text("${state.bytes / (1024 * 1024)} / ${state.total / (1024 * 1024)} MB")
        } else { LinearProgressIndicator(); Text(if (state.phase == UpdatePhase.VERIFYING) "Verifying update…" else "Preparing installation…") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !state.checking && !state.busy, onClick = { scope.launch(Dispatchers.IO) { repo.check(manual = true) } }) {
            Text(if (state.checking) "Checking…" else "Check now")
        }
        if (state.newer && !state.busy && state.phase != UpdatePhase.PENDING) OutlinedButton(enabled = !state.checking, onClick = { run(if (state.phase == UpdatePhase.READY) "install" else "download") }) {
            Text(if (state.phase == UpdatePhase.READY) "Install update" else "Update")
        }
        if (state.phase == UpdatePhase.DOWNLOADING || state.phase == UpdatePhase.VERIFYING) TextButton(onClick = {
            autoInstall = false
            approvedTask = null
            context.stopService(Intent(context, UpdateDownloadService::class.java))
        }) { Text("Cancel") }
        if (state.phase == UpdatePhase.PENDING) TextButton(onClick = { UpdateInstaller.cancel(context, repo) }) { Text("Cancel installation") }
        UpdateConfirmationActivity.pending?.let { confirmation ->
            OutlinedButton(onClick = { runCatching { confirmation.send() } }) { Text("Continue installation") }
        }
    }
    Text("Checks run at startup and about once a day. Failed checks back off; Check now waits at least a minute between requests and respects error cooldowns.")
    if (!UpdateNotifications.allowed(context)) Text("Update notifications are off. Updates and progress remain available here.")
    if (permissionDialog) AlertDialog(onDismissRequest = { permissionDialog = false; pendingAction = null },
        title = { Text("Allow app updates") },
        text = { Text("Android requires Install unknown apps approval for Backstage. Allow from this source on the next screen, then return here. Android will ask you to confirm each installation.") },
        confirmButton = { TextButton(onClick = {
            permissionDialog = false
            runCatching { settings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                .onFailure { pendingAction = null; repo.change { state -> state.copy(message = "Cannot open Install unknown apps settings: ${it.message}") } }
        }) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = { permissionDialog = false; pendingAction = null }) { Text("Cancel") } })
    taskDialog?.let { action -> AlertDialog(onDismissRequest = { taskDialog = null },
        title = { Text("Restart Backstage?") },
        text = { Text("The agent is running a task. Updating restarts the app and interrupts that task.") },
        confirmButton = { TextButton(onClick = { taskDialog = null; run(action, runningTaskKey()) }) { Text("Update anyway") } },
        dismissButton = { TextButton(onClick = { taskDialog = null; autoInstall = false; approvedTask = null }) { Text("Wait") } }) }
}
