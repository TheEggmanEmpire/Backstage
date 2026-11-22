package com.eggman.backstage.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.eggman.backstage.ui.ToggleRow

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
