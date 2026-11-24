package com.eggman.backstage.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import com.eggman.backstage.privileged.ShizukuState

/**
 * Disclosure for Backstage's accessibility service. On Play it covers desktop input and windows. On GitHub it
 * also covers agent screen access. Consent is stored in [com.eggman.backstage.data.Settings.accessibilityConsent].
 */
object AccessibilityDisclosure {
    const val TITLE = "Before Backstage uses Accessibility"

    val POINTS = listOf(
        "One service, two jobs: the \"Backstage\" accessibility service runs the desktop (its window, the popup, " +
            "keyboard and mouse input on the desktop) and lets the agent read and operate apps.",
        "What is read: the agent uses this service to read what is shown on the screen it works on " +
            "(the DeX desktop by default; your phone screen only when you switch to it): text, buttons and other " +
            "controls, plus screenshots when vision is on.",
        "What is done: it uses the same service to act for you (tap, type, scroll, open apps, go back) only while a " +
            "task you started is running: from the chat, voice, the bubble, a schedule, an automation or an MCP client you set up.",
        "Where it goes: screen content is sent only to the AI provider you choose in Settings, to decide the next step. " +
            "Backstage has no server of its own and does not sell or share this data. Task history stays on this phone.",
        "With Shizuku, Backstage switches the service on for you and turns it back on when the desktop resets it. " +
            "You can withdraw this consent at any time in Settings; switching the service off in Android settings is " +
            "respected as well (the desktop and the agent then stop working).",
    )

    private val desktopPoints = listOf(
        "The Backstage accessibility service manages the desktop window and sends keyboard and mouse input to the desktop.",
        "Backstage-play-agent asks for its own permission to read and operate apps. The desktop service does not send screen content to your AI provider.",
        "To turn off desktop accessibility, disable Backstage in Android Accessibility settings. The desktop needs this service to run.",
    )

    val points: List<String> get() = if (PLAY_DISTRIBUTION) desktopPoints else POINTS

    const val CHECKBOX = "I understand and agree"
    const val AGREE = "Agree and continue"
    const val DECLINE = "Not now"

    /** The one-shot Shizuku setup (which switches the service on) may run by itself only with consent. */
    fun autoSetupAllowed(shizuku: ShizukuState, setupDone: Boolean, consent: Boolean): Boolean =
        consent && shizuku == ShizukuState.READY && !setupDone

    /** Show the disclosure by itself when Shizuku just became ready and the user has not decided yet in this screen. */
    fun promptAutomatically(shizuku: ShizukuState, setupDone: Boolean, consent: Boolean, declinedHere: Boolean): Boolean =
        !consent && !declinedHere && shizuku == ShizukuState.READY && !setupDone
}

/** The disclosure dialog: "Agree and continue" stays disabled until the checkbox is ticked. */
@Composable
fun AccessibilityDisclosureDialog(onAgree: () -> Unit, onDecline: () -> Unit) {
    var ticked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(AccessibilityDisclosure.TITLE) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccessibilityDisclosure.points.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(
                    Modifier.toggleable(value = ticked, role = Role.Checkbox, onValueChange = { ticked = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = ticked, onCheckedChange = null)
                    Text(AccessibilityDisclosure.CHECKBOX, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onAgree, enabled = ticked) { Text(AccessibilityDisclosure.AGREE) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(AccessibilityDisclosure.DECLINE) } },
    )
}
