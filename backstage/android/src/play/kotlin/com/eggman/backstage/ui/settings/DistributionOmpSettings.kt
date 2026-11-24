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
import com.eggman.backstage.omp.CompanionOmpClient
import com.eggman.backstage.ui.NumberSetting
import com.eggman.backstage.ui.SettingToggle
import com.eggman.backstage.ui.copyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DistributionOmpSettingsSection(store: BackstageSettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Connecting to companion OMP…") }
    var installed by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf("") }
    var broker by remember { mutableStateOf("Stopped") }
    DisposableEffect(store) {
        val unregister = store.observe { if (it.startsWith("omp_") || it.startsWith("termux_shell_") || it == "agent_engine") revision++ }
        onDispose { unregister() }
    }
    LaunchedEffect(revision) {
        runCatching {
            withContext(Dispatchers.IO) {
                CompanionOmpClient.request(CompanionOmpClient.configRequest(store))
                CompanionOmpClient.request(CompanionOmpClient.action("status"))
            }
        }.onSuccess { reply ->
            message = reply.getString("message") ?: "OMP companion connected"
            installed = reply.getBoolean("installed", false)
            version = reply.getString("version").orEmpty()
            broker = reply.getString("brokerMessage") ?: broker
        }.onFailure { message = it.message ?: "OMP companion unavailable" }
    }
    fun runOperation(action: String) {
        busy = true
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    CompanionOmpClient.requestLong(store, action) { progress -> message = progress }
                }
                check(reply.getBoolean("ok", false)) { reply.getString("error") ?: "OMP operation failed" }
                message = reply.getString("message") ?: "OMP operation finished"
                installed = reply.getBoolean("installed", installed)
                version = reply.getString("version") ?: version
                broker = reply.getString("brokerMessage") ?: broker
            } catch (e: Exception) { message = e.message ?: "OMP operation failed" }
            finally { busy = false; revision++ }
        }
    }
    @Suppress("UNUSED_VARIABLE") val observed = revision
    SettingsSection("OMP harness", "Use the separate companion's shell-user runtime for OMP, subagents and Termux.") {
        Text(if (version.isBlank()) "Runtime: $message" else "Runtime: $message · $version", style = MaterialTheme.typography.bodySmall)
        Text(broker, style = MaterialTheme.typography.bodySmall)
        SettingToggle("Enable OMP harness", "Lets OMP use companion shell-user access and starts its local key broker", { store.ompEnabled }) { store.ompEnabled = it }
        Text("Primary chats use Backstage. The companion provides OMP subagents and Termux.", style = MaterialTheme.typography.bodySmall)
        NumberSetting("Broker port", store.ompBrokerPort) { store.ompBrokerPort = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { runOperation(if (installed) "repair" else "install") }, enabled = !busy) {
                Text(if (busy) "Working…" else if (installed) "Repair" else "Install")
            }
            OutlinedButton(onClick = { runOperation("update") }, enabled = !busy && installed && store.ompEnabled) { Text("Update OMP") }
        }
        Text("Repair restores the bundled OMP runtime and keeps its job history. Update downloads the latest OMP through the Termux package endpoint.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!store.ompAutoApprove, { store.ompAutoApprove = false }, { Text("Ask each time") })
            FilterChip(store.ompAutoApprove, { store.ompAutoApprove = true }, { Text("Auto") })
        }
        Text(if (store.ompAutoApprove) "OMP Auto runs subagents without asking, as the companion shell user." else "You approve each OMP task. Subagents can run commands as the companion shell user.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { runCatching { store.rotateOmpHarnessToken() }.onFailure { message = "Device keystore unavailable" }; revision++ }, enabled = !busy) { Text("Rotate harness token") }
        Text("Termux", style = MaterialTheme.typography.labelLarge)
        Text("Enabled remote MCP servers are shared with OMP as whole servers; per-tool switches apply to Backstage's agent.", style = MaterialTheme.typography.bodySmall)
        SettingToggle("Enable Termux pairing", "Allows Termux to fetch the runtime and current provider settings while Backstage is running. Treat its setup line like a password.", { store.ompTermuxEnabled }) { store.ompTermuxEnabled = it }
        if (store.ompTermuxEnabled) {
            val token = runCatching { store.ompTermuxToken }.getOrDefault("")
            val line = CompanionOmpClient.setupLine(store.ompBrokerPort, token)
            if (token.isBlank()) Text("Device keystore unavailable") else {
                Text(line, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { copyToClipboard(context, "Termux pairing (secret)", line, sensitive = true) }) { Text("Copy setup line") }
                    TextButton(onClick = { runCatching { store.rotateOmpTermuxToken() }.onFailure { message = "Device keystore unavailable" }; revision++ }) { Text("Rotate pairing") }
                }
            }
            Text("Paste into Termux once, then type omp. Rotating pairing revokes the old token.", style = MaterialTheme.typography.bodySmall)
        }
        SettingToggle("Shell access (uid 2000)", "Off by default. Requires Termux pairing and privileged access.", { store.termuxShellEnabled }) { store.termuxShellEnabled = it }
        if (store.termuxShellEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!store.termuxShellAutoApprove, { store.termuxShellAutoApprove = false }, { Text("Ask each time") })
                FilterChip(store.termuxShellAutoApprove, { store.termuxShellAutoApprove = true }, { Text("Auto") })
            }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

private fun CompanionOmpClient.setupLine(port: Int, token: String) =
    "curl -fsSL -H 'Authorization: Bearer $token' http://127.0.0.1:$port/omp/v1/termux/setup | bash -s -- '$token'"
