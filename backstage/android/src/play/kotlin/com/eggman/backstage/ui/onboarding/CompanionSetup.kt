package com.eggman.backstage.ui.onboarding

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggman.backstage.R
import com.eggman.backstage.a11y.CompanionConnection
import com.eggman.backstage.a11y.CompanionState
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.a11y.companion.CompanionSignatures

/** Play-only connection status and optional companion install. The companion owns its own disclosure and consent. */
@Composable
fun CompanionSetupStep(showHeading: Boolean = true) {
    val context = LocalContext.current
    if (!CompanionConnection.isRequired(context)) return
    LaunchedEffect(Unit) { CompanionConnection.connect(context); CompanionConnection.refresh() }
    val state by CompanionConnection.state.collectAsStateWithLifecycle()
    val updateAvailable by CompanionConnection.updateAvailable.collectAsStateWithLifecycle()
    var installCheck by remember { mutableStateOf<String?>(null) }
    val installed = remember(state, installCheck) {
        runCatching { context.packageManager.getPackageInfo(CompanionProtocol.PACKAGE, 0) }.isSuccess
    }
    val signerMatches = remember(state, installCheck) {
        installed && runCatching { CompanionSignatures.companionMatches(context) }.getOrDefault(false)
    }
    fun openVerifiedCompanion() {
        if (CompanionSignatures.companionMatches(context)) {
            runCatching {
                context.startActivity(Intent().setComponent(ComponentName(CompanionProtocol.PACKAGE, CompanionProtocol.ACTIVITY)))
            }
        }
        CompanionConnection.refresh()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showHeading) {
                Text(stringResource(R.string.play_onboarding_companion_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.play_onboarding_companion_disclosure), style = MaterialTheme.typography.bodySmall)
            }
            val status = when (state) {
                CompanionState.MISSING -> R.string.play_companion_not_installed
                CompanionState.CONSENT_NEEDED, CompanionState.SERVICE_OFF -> R.string.play_companion_not_enabled
                CompanionState.CONNECTED -> R.string.play_companion_connected
                CompanionState.INCOMPATIBLE -> R.string.play_companion_update_needed
                CompanionState.CONNECTING -> R.string.play_companion_connecting
                CompanionState.DISCONNECTED -> R.string.play_companion_disconnected
                CompanionState.SIGNATURE_MISMATCH -> R.string.play_onboarding_companion_bad_signer
                CompanionState.PERMISSION_MISSING -> null
            }
            Text(status?.let { stringResource(it) } ?: state.message, style = MaterialTheme.typography.bodySmall)
            if (updateAvailable) Text(stringResource(R.string.play_companion_update_available))
            installCheck?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CompanionProtocol.RELEASES))) }
            }) { Text(stringResource(R.string.play_onboarding_companion_get)) }
            OutlinedButton(onClick = {
                val exists = runCatching { context.packageManager.getPackageInfo(CompanionProtocol.PACKAGE, 0) }.isSuccess
                val matches = exists && runCatching { CompanionSignatures.companionMatches(context) }.getOrDefault(false)
                installCheck = when {
                    !exists -> context.getString(R.string.play_onboarding_companion_missing)
                    !matches -> context.getString(R.string.play_onboarding_companion_bad_signer)
                    else -> context.getString(R.string.play_onboarding_companion_verified)
                }
                if (matches) openVerifiedCompanion() else CompanionConnection.refresh()
            }) { Text(stringResource(R.string.play_onboarding_companion_installed)) }
            if (signerMatches) OutlinedButton(onClick = ::openVerifiedCompanion) {
                Text(stringResource(R.string.play_onboarding_companion_open))
            }
        }
    }
}
