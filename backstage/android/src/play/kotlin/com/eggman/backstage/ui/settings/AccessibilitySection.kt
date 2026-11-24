package com.eggman.backstage.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.eggman.backstage.a11y.CompanionConnection
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.ui.ToggleRow

@Composable
internal fun AccessibilitySection(store: BackstageSettingsStore) {
    val capabilities by CompanionConnection.capabilities.collectAsState()
    SettingsSection("Accessibility", "Enable desktop accessibility through Dextop setup. Backstage-play-agent provides AI screen access.") {
        com.eggman.backstage.ui.onboarding.CompanionSetupStep()
        if (capabilities and CompanionProtocol.CAP_ACCESSIBILITY_SETTINGS != 0) {
            var auto by remember { mutableStateOf(store.autoEnableAccessibility) }
            var pause by remember { mutableStateOf(store.detectorPause) }
            var seconds by remember { mutableStateOf((store.detectorPauseDelayMs / 1000).toString()) }
            var packages by remember { mutableStateOf(store.detectorPackages) }
            ToggleRow("Turn the service back on automatically",
                "Restores the Backstage-play-agent service while its accessibility consent is active.", auto) {
                auto = it
                store.autoEnableAccessibility = it
            }
            ToggleRow("Pause while a detector app is in front",
                "Pauses the companion's accessibility service while a listed app is in front.", pause) {
                pause = it
                store.detectorPause = it
            }
            OutlinedTextField(seconds, { value ->
                seconds = value.filter { it in '0'..'9' }.take(2)
                seconds.toIntOrNull()?.let { store.detectorPauseDelayMs = it.coerceIn(1, 60) * 1000 }
            }, label = { Text("Re-check seconds") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(packages, { value ->
                packages = value.take(4096)
                store.detectorPackages = packages
            }, label = { Text("Detector packages") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Package names, colon- or comma-separated.") })
        }
    }
    SettingsSection("Device permissions", "Choose which Android permissions to grant to Backstage.") {
        com.eggman.backstage.ui.onboarding.PlayPermissionChecklist()
    }
}
