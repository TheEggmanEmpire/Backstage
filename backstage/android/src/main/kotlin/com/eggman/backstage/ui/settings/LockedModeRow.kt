package com.eggman.backstage.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.eggman.backstage.agent.LockedModeLog
import com.eggman.backstage.agent.LockedModeReport
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Locked mode" diagnostics next to "Keep the desktop running while locked": the setting, the display flags the
 * running desktop asked for / got / really carries, whether it runs while locked, and the last fallback reason
 * (host or agent). Refreshed every 2 s while shown; [refreshKey] changes refresh it at once (the toggle).
 */
@Composable
internal fun LockedModeRow(extras: DesktopHostExtras, refreshKey: Int) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<LockedModeReport.Report?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(refreshKey, tick) {
        while (true) {
            report = withContext(Dispatchers.Default) { snapshot(extras) }
            delay(2_000L)
        }
    }
    val r = report ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Locked mode", style = MaterialTheme.typography.labelLarge)
        Text(r.summary, style = MaterialTheme.typography.bodySmall)
        r.lines.forEach { line ->
            Text("${line.label}: ${line.value}", style = MaterialTheme.typography.bodySmall)
        }
        val label = when (r.action) {
            LockedModeReport.Action.RESTART_DESKTOP -> "Restart the desktop now"
            LockedModeReport.Action.GRANT_WAKE -> "Allow waking the desktop"
            LockedModeReport.Action.NONE -> null
        }
        if (label != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    withContext(Dispatchers.Default) {
                        runCatching {
                            when (r.action) {
                                LockedModeReport.Action.RESTART_DESKTOP -> {
                                    val host = extras as? DesktopHost
                                    val now = runCatching { host?.presentation() }.getOrNull()
                                    val keep = now?.takeIf { it != DesktopHost.Presentation.NONE } ?: DesktopHost.Presentation.HIDDEN
                                    extras.restartDesktop(keep)
                                }
                                LockedModeReport.Action.GRANT_WAKE -> extras.ensureDesktopWakePermission()
                                LockedModeReport.Action.NONE -> Unit
                            }
                        }
                    }
                    busy = false
                    tick++
                }
            }) { Text(if (busy) "Working…" else label) }
        }
    }
}

private fun snapshot(extras: DesktopHostExtras): LockedModeReport.Report = LockedModeReport.build(
    settingOn = runCatching { extras.runWhileLocked() }.getOrDefault(false),
    status = runCatching { extras.lockedModeStatus() }.getOrNull(),
    libraryFallback = LockedModeLog.last,
    nowMs = System.currentTimeMillis(),
)
