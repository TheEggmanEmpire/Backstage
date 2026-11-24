package com.eggman.backstage.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eggman.backstage.R
import com.eggman.backstage.a11y.CompanionConnection
import com.eggman.backstage.a11y.CompanionState

@Composable
fun PlaySetupScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    if (!CompanionConnection.isRequired(LocalContext.current)) return
    val companion by observeCompanionConnection()
    OnboardingWizard(
        onDone = onDone,
        modifier = modifier,
        companionReady = { companion == CompanionState.CONNECTED &&
            CompanionConnection.state.value == CompanionState.CONNECTED },
        companionStep = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.play_onboarding_step_four), style = MaterialTheme.typography.titleLarge)
                CompanionSetupCard(companion, showHeading = false)
            }
        },
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun SetupScreen(onOpenSettings: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    PlaySetupScreen(onDone = onDone, modifier = modifier)
}
