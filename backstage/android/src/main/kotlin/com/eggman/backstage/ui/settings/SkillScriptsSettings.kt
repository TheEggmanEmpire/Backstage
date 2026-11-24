package com.eggman.backstage.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

@Composable
internal fun SkillScriptsSettingsSection(store: BackstageSettingsStore) {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val unregister = store.observe { if (it.startsWith("skill_scripts_")) revision++ }
        onDispose { unregister() }
    }
    @Suppress("UNUSED_VARIABLE") val observed = revision
    SettingsSection("Run skill scripts", "Skill scripts run real code on the phone and can read or change anything their runtime user can access. Install only code you trust.") {
        val enabled = store.skillScriptsEnabled
        val auto = store.skillScriptsAutoApprove
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!enabled, { store.skillScriptsEnabled = false; revision++ }, { Text("Off") })
            FilterChip(enabled && !auto, {
                store.skillScriptsAutoApprove = false; store.skillScriptsEnabled = true; revision++
            }, { Text("Ask") })
            FilterChip(enabled && auto, {
                store.skillScriptsAutoApprove = true; store.skillScriptsEnabled = true; revision++
            }, { Text("Auto") })
        }
        Text(if (!enabled) "Native skill script execution is disabled."
            else if (auto) "Auto runs scripts without asking. Shell access keeps its separate enablement and approval."
            else "Approve each script before it runs. Shell access may also ask for approval.", style = MaterialTheme.typography.bodySmall)
        Text(if (com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION) {
            "Backstage-play-agent asks its owner before running a script as the shell user. JavaScript, TypeScript and Python need the companion’s OMP runtime and the corresponding interpreter."
        } else "Requires the installed OMP harness and privileged access. This runtime supports shell_user=true (uid 2000); app-user execution is unavailable. These controls govern the native run_skill_script tool; omp can run scripts directly.", style = MaterialTheme.typography.bodySmall)
    }
}
