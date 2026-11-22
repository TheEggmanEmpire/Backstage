package com.eggman.backstage.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.eggman.backstage.ui.ToggleRow

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AccessibilitySection(store: BackstageSettingsStore) {
    SettingsSection("Accessibility", "Enable desktop accessibility through Dextop setup. Backstage Agent handles optional AI screen access.") {
        com.eggman.backstage.ui.onboarding.CompanionSetupStep()
    }
    SettingsSection("Device permissions", "Choose which Android permissions to grant to Backstage.") {
        com.eggman.backstage.ui.onboarding.PlayPermissionChecklist()
    }
}
