package com.eggman.backstage.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eggman.backstage.R
import com.eggman.backstage.a11y.CompanionConnection
import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.privileged.ShizukuSetup
import com.eggman.backstage.privileged.ShizukuState
import com.eggman.backstage.ui.desktop.DesktopSettings
import com.eggman.backstage.ui.settings.ProviderOptions
import com.eggman.backstage.ui.settings.SettingsStores

/** Four short, skippable first-run steps for the Play build. */
@Composable
fun PlaySetupScreen(
    onOpenSettings: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    providerSettingsContent: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    if (!CompanionConnection.isRequired(context)) return

    val lifecycle = LocalLifecycleOwner.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showProviderSettings by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
                CompanionConnection.refresh()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        val unregister = Privileged.addPermissionListener { refreshKey++ }
        onDispose { unregister() }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { CompanionConnection.connect(context); CompanionConnection.refresh() }

    val probe = remember(refreshKey) { AndroidPermissionProbe.snapshot(context) }
    val privilegeState = remember(refreshKey) { runCatching { ShizukuSetup.current(context) }.getOrDefault(ShizukuState.NOT_INSTALLED) }
    val store = remember { SettingsStores.get(context) }
    val provider = ProviderOptions.byId(store.providerId)
    val providerReady = provider != null && ProviderOptions.validate(provider, store.apiKey(), store.model(), store.baseUrl()).isEmpty()

    fun openPrivilegeManager() {
        when (privilegeState) {
            ShizukuState.NOT_INSTALLED -> runCatching { context.startActivity(ShizukuSetup.download()) }
            ShizukuState.NOT_RUNNING -> {
                val intent = ShizukuSetup.openShizuku(context)
                if (intent == null) runCatching { context.startActivity(ShizukuSetup.download()) }
                else runCatching { context.startActivity(intent) }
            }
            ShizukuState.PERMISSION_NEEDED -> ShizukuSetup.requestPermission()
            ShizukuState.READY -> Unit
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.play_onboarding_title), style = MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(progress = { (page + 1) / 4f }, modifier = Modifier.fillMaxWidth())

        when (page) {
            0 -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.play_onboarding_step_one), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.play_onboarding_intro_body))
                }
            }
            1 -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.play_onboarding_step_two), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.play_onboarding_privilege_body))
                    Text(
                        if (probe.privileged) stringResource(R.string.play_onboarding_privilege_ready)
                        else stringResource(R.string.play_onboarding_privilege_missing),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = ::openPrivilegeManager,
                        enabled = !probe.privileged && privilegeState != ShizukuState.READY,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (probe.privileged || privilegeState == ShizukuState.READY) {
                            stringResource(R.string.play_onboarding_provider_connected)
                        } else when (privilegeState) {
                            ShizukuState.NOT_INSTALLED -> stringResource(R.string.play_onboarding_get_stellar)
                            ShizukuState.NOT_RUNNING -> stringResource(R.string.play_onboarding_open_stellar)
                            ShizukuState.PERMISSION_NEEDED -> stringResource(R.string.play_onboarding_allow_stellar)
                            ShizukuState.READY -> stringResource(R.string.play_onboarding_provider_connected)
                        })
                    }
                    if (DesktopSettings.available(context)) OutlinedButton(
                        onClick = { DesktopSettings.open(context); refreshKey++ }, modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.play_onboarding_dextop_wireless)) }
                    Text(stringResource(R.string.play_onboarding_privilege_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            2 -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.play_onboarding_step_three), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.play_onboarding_provider_body))
                    Text(
                        if (providerReady) context.getString(R.string.play_onboarding_provider_ready, provider!!.label)
                        else stringResource(R.string.play_onboarding_provider_missing),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = {
                        if (providerSettingsContent != null) showProviderSettings = !showProviderSettings
                        else onOpenSettings()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showProviderSettings) stringResource(R.string.play_onboarding_provider_hide)
                            else stringResource(R.string.play_onboarding_provider_button))
                    }
                    if (showProviderSettings) providerSettingsContent?.invoke()
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.play_onboarding_step_four), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.play_onboarding_companion_disclosure), style = MaterialTheme.typography.bodyMedium)
                CompanionSetupStep(showHeading = false)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page > 0) OutlinedButton(onClick = { page-- }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.play_onboarding_back))
            }
            if (page < 3) Button(onClick = { page++ }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.play_onboarding_next))
            } else Button(onClick = {
                store.onboardingDone = true
                onDone()
            }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.play_onboarding_finish)) }
        }
        TextButton(onClick = {
            store.onboardingDone = true
            onDone()
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.play_onboarding_skip)) }
    }
}


@Composable
fun SetupScreen(onOpenSettings: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    PlaySetupScreen(
        onOpenSettings = onOpenSettings,
        onDone = onDone,
        modifier = modifier,
        providerSettingsContent = {
            com.eggman.backstage.ui.settings.ProviderSection(
                com.eggman.backstage.ui.settings.SettingsStores.get(context),
            )
        },
    )
}
