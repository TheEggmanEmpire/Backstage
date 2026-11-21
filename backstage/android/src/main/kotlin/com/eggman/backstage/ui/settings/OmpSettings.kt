package com.eggman.backstage.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eggman.backstage.omp.OmpHarness
import com.eggman.backstage.omp.OmpPayload
import com.eggman.backstage.ui.NumberSetting
import com.eggman.backstage.ui.SettingToggle
import com.eggman.backstage.ui.copyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun OmpSettingsSection(store: BackstageSettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val payload = remember { OmpPayload(context.assets) }
    val runtime = OmpHarness.runtime
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    DisposableEffect(store) {
        val unregister = store.observe { if (it.startsWith("omp_") || it.startsWith("termux_shell_") || it == "agent_engine") revision++ }
        onDispose { unregister() }
    }
    LaunchedEffect(runtime) {
        withContext(Dispatchers.IO) { runtime?.status() }
        revision++
    }
    fun runOperation(update: Boolean) {
        busy = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    checkNotNull(runtime) { "Open Backstage again to initialize the harness" }
                    if (update) runtime.update() else runtime.install { progress -> message = progress }
                }
                message = if (r.ok) (if (update) "OMP update finished" else "OMP installed") else "Operation failed (${r.code}): ${r.err.takeLast(500)}"
            } catch (e: Exception) { message = "Operation failed: ${e.message ?: e.javaClass.simpleName}" }
            finally { busy = false; revision++ }
        }
    }
    // Reading revision makes external settings changes refresh the choices and setup line.
    @Suppress("UNUSED_VARIABLE") val observed = revision
    SettingsSection("OMP harness", "Run omp with shell-user access and Backstage's providers: as the chat engine, for subagents and in Termux.") {
        if (!payload.available) {
            Text("OMP runtime payload is missing", style = MaterialTheme.typography.bodySmall)
        } else {
            payload.manifest?.let { Text("Bundled omp ${it.ompVersion} · bun ${it.bunVersion}", style = MaterialTheme.typography.bodySmall) }
            Text(if (busy && message.isNotBlank()) message else "Runtime: ${runtime?.message ?: "Not checked"}${runtime?.version?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            Text(OmpHarness.brokerMessage, style = MaterialTheme.typography.bodySmall)
            SettingToggle("Enable OMP harness", "Lets the agent start shell-user subagents and starts the local key broker", { store.ompEnabled }) { store.ompEnabled = it }
            Text("Chat engine", style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                com.eggman.backstage.data.AgentEngine.entries.forEach { e ->
                    FilterChip(store.agentEngine == e, { store.agentEngine = e }, { Text(e.label) })
                }
            }
            Text(if (store.agentEngine == com.eggman.backstage.data.AgentEngine.OMP) {
                if (store.ompEnabled && runtime?.installed == true) "The chats run on omp." else "The chats will run on omp once the harness is enabled and installed; until then each message says it used Backstage."
            } else "The chats run on Backstage; omp is used only for subagents.", style = MaterialTheme.typography.bodySmall)
            NumberSetting("Broker port", store.ompBrokerPort) { store.ompBrokerPort = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runOperation(false) }, enabled = !busy) { Text(if (busy) "Working…" else if (runtime?.installed == true) "Repair" else "Install") }
                OutlinedButton(onClick = { runOperation(true) }, enabled = !busy && runtime?.installed == true && store.ompEnabled) { Text("Update omp") }
            }
            Text("Repair restores this APK's pins and keeps agent state and job history. Update downloads the latest omp using omp-termux.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!store.ompAutoApprove, { store.ompAutoApprove = false }, { Text("Ask each time") })
                FilterChip(store.ompAutoApprove, { store.ompAutoApprove = true }, { Text("Auto") })
            }
            Text(if (store.ompAutoApprove) "OMP Auto: subagents run without asking, as the shell user. Anything they decide to run — uninstalling apps, changing system settings — happens immediately."
                else "You approve each OMP task. The subagent and its own subagents can execute arbitrary commands as the shell user.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                runCatching { store.rotateOmpHarnessToken() }.onFailure { message = "Device keystore unavailable" }
                revision++
            }, enabled = !busy) { Text("Rotate harness token") }
            Text("Termux", style = MaterialTheme.typography.labelLarge)
            Text("Enabled remote MCP servers are shared with omp as whole servers; per-tool switches apply to Backstage's agent, and servers with all known tools off are not shared.", style = MaterialTheme.typography.bodySmall)
            SettingToggle("Enable Termux pairing", "Allows the Termux app to fetch the runtime and current provider keys while Backstage is running. Treat the setup line like a password.", { store.ompTermuxEnabled }) { store.ompTermuxEnabled = it }
            if (store.ompTermuxEnabled) {
                val token = runCatching { store.ompTermuxToken }.getOrDefault("")
                val line = OmpHarness.setupLine(store.ompBrokerPort, token)
                if (token.isBlank()) Text("Device keystore unavailable") else {
                    Text(line, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { copyToClipboard(context, "Termux pairing (secret)", line, sensitive = true) }) { Text("Copy setup line") }
                        TextButton(onClick = {
                            runCatching { store.rotateOmpTermuxToken() }.onFailure { message = "Device keystore unavailable" }
                            revision++
                        }) { Text("Rotate pairing") }
                    }
                }
                Text("Paste into Termux once, then type omp. Rotating pairing revokes the old token; already running omp processes may keep cached credentials until they exit.", style = MaterialTheme.typography.bodySmall)
            }
            SettingToggle("Shell access (uid 2000)", "Off by default. Anything holding the Termux pairing token can request shell-user commands through Backstage. Requires Termux pairing and privileged access; no OMP installation is needed.", { store.termuxShellEnabled }) { store.termuxShellEnabled = it }
            if (store.termuxShellEnabled) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!store.termuxShellAutoApprove, { store.termuxShellAutoApprove = false }, { Text("Ask each time") })
                    FilterChip(store.termuxShellAutoApprove, { store.termuxShellAutoApprove = true }, { Text("Auto") })
                }
                Text(if (store.termuxShellAutoApprove) "Termux Shell Auto: commands run without asking, as the shell user. Anything holding the Termux pairing token can uninstall apps or change system settings immediately."
                    else "You approve each Termux shell command. Backstage shows the command with Approve / Deny; commands can do anything the shell user can.", style = MaterialTheme.typography.bodySmall)
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
