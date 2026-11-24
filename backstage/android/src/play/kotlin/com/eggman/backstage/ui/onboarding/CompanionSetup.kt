package com.eggman.backstage.ui.onboarding

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggman.backstage.R
import com.eggman.backstage.a11y.CompanionConnection
import com.eggman.backstage.a11y.CompanionState
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.a11y.companion.CompanionSignatures
import com.eggman.backstage.update.CompanionReleaseLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun observeCompanionConnection(): State<CompanionState> {
    val context = LocalContext.current
    LifecycleResumeEffect(context) {
        CompanionConnection.connect(context)
        CompanionConnection.refresh()
        onPauseOrDispose {}
    }
    return CompanionConnection.state.collectAsStateWithLifecycle()
}

@Composable
fun CompanionSetupStep() {
    if (!CompanionConnection.isRequired(LocalContext.current)) return
    val state by observeCompanionConnection()
    CompanionSetupCard(state, showHeading = true)
}

@Composable
internal fun CompanionSetupCard(state: CompanionState, showHeading: Boolean) {
    val context = LocalContext.current
    val updateAvailable by CompanionConnection.updateAvailable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var resolving by remember { mutableStateOf(false) }
    fun openVerifiedCompanion() {
        if (CompanionSignatures.companionMatches(context)) {
            runCatching {
                context.startActivity(Intent().setComponent(ComponentName(CompanionProtocol.PACKAGE, CompanionProtocol.ACTIVITY)))
            }
        } else CompanionConnection.refresh()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showHeading) {
                Text(stringResource(R.string.play_onboarding_companion_title), style = MaterialTheme.typography.titleMedium)
            }
            val status = when (state) {
                CompanionState.MISSING -> R.string.play_companion_not_installed
                CompanionState.CONSENT_NEEDED -> R.string.play_companion_confirmation_needed
                CompanionState.SERVICE_OFF -> R.string.play_companion_not_enabled
                CompanionState.CONNECTED -> R.string.play_companion_connected
                CompanionState.INCOMPATIBLE -> R.string.play_companion_update_needed
                CompanionState.CONNECTING -> R.string.play_companion_connecting
                CompanionState.DISCONNECTED -> R.string.play_companion_disconnected
                CompanionState.SIGNATURE_MISMATCH -> R.string.play_onboarding_companion_bad_signer
                CompanionState.PERMISSION_MISSING -> R.string.play_companion_permission_missing
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state == CompanionState.CONNECTED) Icon(Icons.Default.Check, contentDescription = null)
                Text(stringResource(status), style = MaterialTheme.typography.bodySmall)
            }
            if (showHeading && updateAvailable) Text(stringResource(R.string.play_companion_update_available))
            when (state) {
                CompanionState.MISSING, CompanionState.SIGNATURE_MISMATCH, CompanionState.PERMISSION_MISSING -> {
                    OutlinedButton(enabled = !resolving, onClick = {
                        resolving = true
                        scope.launch {
                            try {
                                val url = withContext(Dispatchers.IO) { CompanionReleaseLink().resolve() }
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            } finally { resolving = false }
                        }
                    }) {
                        Text(stringResource(if (resolving) R.string.play_companion_resolving_download else R.string.play_onboarding_companion_get))
                    }
                    if (resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.play_onboarding_companion_protect_hint), style = MaterialTheme.typography.bodySmall)
                }
                CompanionState.CONSENT_NEEDED, CompanionState.SERVICE_OFF, CompanionState.INCOMPATIBLE, CompanionState.DISCONNECTED -> {
                    OutlinedButton(onClick = ::openVerifiedCompanion) {
                        Text(stringResource(R.string.play_onboarding_companion_open))
                    }
                }
                CompanionState.CONNECTED -> if (showHeading && updateAvailable) {
                    OutlinedButton(onClick = ::openVerifiedCompanion) {
                        Text(stringResource(R.string.play_onboarding_companion_open))
                    }
                }
                CompanionState.CONNECTING -> Unit
            }
        }
    }
}
