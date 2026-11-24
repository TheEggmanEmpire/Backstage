package com.eggman.backstage.ui.onboarding

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eggman.backstage.BuildConfig
import com.eggman.backstage.R
import com.eggman.backstage.a11y.AccessibilityAutoEnable
import com.eggman.backstage.data.Provider
import com.eggman.backstage.llm.ModelListResult
import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.privileged.PrivilegeProviders
import com.eggman.backstage.privileged.ShizukuSetup
import com.eggman.backstage.privileged.ShizukuState
import com.eggman.backstage.ui.components.PrivilegeManagerButtons
import com.eggman.backstage.ui.settings.BackstageSettingsStore
import com.eggman.backstage.ui.settings.ChatGptAccount
import com.eggman.backstage.ui.settings.ProviderSection
import com.eggman.backstage.ui.settings.SettingsStores
import kotlinx.coroutines.launch

private enum class OnboardingPage { WELCOME, DESKTOP_ACCESS, DESKTOP_ACCESSIBILITY, AI_PROVIDER, MODEL, COMPANION }

@Composable
internal fun OnboardingWizard(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    companionReady: () -> Boolean = { true },
    companionStep: (@Composable () -> Unit)? = null,
    automaticAccessibility: (suspend () -> String)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val store = remember(context) { SettingsStores.get(context) }
    val pages = if (companionStep == null) OnboardingPage.entries.dropLast(1) else OnboardingPage.entries
    var refreshKey by remember { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf(OnboardingPage.WELCOME) }
    var choice by rememberSaveable { mutableStateOf<ProviderSetupChoice?>(null) }
    var chosenProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var chosenModel by rememberSaveable { mutableStateOf<String?>(null) }

    fun previousPage() {
        if (page != OnboardingPage.WELCOME) page = pages[page.ordinal - 1]
    }
    BackHandler(enabled = page != OnboardingPage.WELCOME || !store.onboardingDone) {
        previousPage()
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
                if (!Privileged.binderAlive() && ShizukuSetup.preferredPackage(context) == PrivilegeProviders.STELLAR_PACKAGE) {
                    context.sendBroadcast(Intent(PrivilegeProviders.STELLAR_REQUEST_BINDER).setPackage(PrivilegeProviders.STELLAR_PACKAGE))
                }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(store) {
        val unregisterPrivilege = Privileged.addPermissionListener { refreshKey++ }
        val unregisterSettings = store.observe { key ->
            if (key == "provider" || key == "a11y_consent" ||
                key.startsWith("key_") || key.startsWith("model_") || key.startsWith("url_")) refreshKey++
        }
        onDispose {
            unregisterPrivilege()
            unregisterSettings()
        }
    }
    DisposableEffect(context.contentResolver) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refreshKey++ }
        }
        resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
        resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    fun currentPrivilege() = runCatching { ShizukuSetup.current(context) }.getOrDefault(ShizukuState.NOT_INSTALLED)
    fun desktopAccessibilityEnabled() = AccessibilityAutoEnable.enabledInSettings(context) &&
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1

    val privilegeState = remember(refreshKey) { currentPrivilege() }
    val accessibilityEnabled = remember(refreshKey) { desktopAccessibilityEnabled() }
    val desktopAccessibilityMissing = when {
        !store.accessibilityConsent -> R.string.onboarding_desktop_accessibility_consent
        !accessibilityEnabled -> R.string.onboarding_desktop_accessibility_missing
        else -> null
    }
    val provider = Provider.parse(store.providerId)
    fun credentialsReady() = when (choice) {
        ProviderSetupChoice.CHATGPT -> store.providerId == Provider.CHATGPT.name && store.providerCredentialsConfigured()
        ProviderSetupChoice.API_KEY -> store.providerId != Provider.CHATGPT.name && store.providerCredentialsConfigured()
        null -> false
    }
    fun modelReady() = chosenProvider == store.providerId && chosenModel == store.model() && store.providerConfigured()
    val privilegeReady = privilegeState == ShizukuState.READY
    val missingPrivilege = when (privilegeState) {
        ShizukuState.NOT_INSTALLED -> R.string.onboarding_missing_privilege_manager
        ShizukuState.NOT_RUNNING -> R.string.onboarding_missing_privilege_connection
        ShizukuState.PERMISSION_NEEDED -> R.string.onboarding_missing_privilege_permission
        ShizukuState.READY -> null
    }
    val missingToFinish = when {
        missingPrivilege != null -> missingPrivilege
        desktopAccessibilityMissing != null -> desktopAccessibilityMissing
        !credentialsReady() -> R.string.onboarding_provider_missing
        !modelReady() -> R.string.onboarding_model_missing
        !companionReady() -> R.string.onboarding_missing_companion
        else -> null
    }
    val finalPage = page == pages.last()
    val missing = if (finalPage) missingToFinish else when (page) {
        OnboardingPage.DESKTOP_ACCESS -> missingPrivilege
        OnboardingPage.DESKTOP_ACCESSIBILITY -> desktopAccessibilityMissing
        OnboardingPage.AI_PROVIDER -> if (credentialsReady()) null else R.string.onboarding_provider_missing
        OnboardingPage.MODEL -> if (modelReady()) null else R.string.onboarding_model_missing
        OnboardingPage.WELCOME, OnboardingPage.COMPANION -> null
    }

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
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(progress = { (page.ordinal + 1f) / pages.size }, modifier = Modifier.fillMaxWidth())

        when (page) {
            OnboardingPage.WELCOME -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.onboarding_step_one), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.onboarding_intro_body))
                }
            }
            OnboardingPage.DESKTOP_ACCESS -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.onboarding_step_two), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.onboarding_privilege_body))
                    if (privilegeReady) {
                        Text(stringResource(R.string.onboarding_provider_connected))
                    } else if (privilegeState != ShizukuState.NOT_INSTALLED) OutlinedButton(
                        onClick = ::openPrivilegeManager,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(when (privilegeState) {
                            ShizukuState.NOT_RUNNING -> stringResource(R.string.onboarding_open_stellar)
                            ShizukuState.PERMISSION_NEEDED -> stringResource(R.string.onboarding_allow_stellar)
                            else -> stringResource(R.string.onboarding_provider_connected)
                        })
                    }
                    PrivilegeManagerButtons()
                    Text(stringResource(R.string.onboarding_privilege_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            OnboardingPage.DESKTOP_ACCESSIBILITY -> DesktopAccessibilityStep(
                store, accessibilityEnabled,
                automaticAccessibility?.takeIf { privilegeReady || Privileged.available() },
            ) { refreshKey++ }
            OnboardingPage.AI_PROVIDER -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.onboarding_step_three), style = MaterialTheme.typography.titleLarge)
                    if (credentialsReady() && provider != null) {
                        Text(stringResource(R.string.onboarding_provider_ready, provider.label), style = MaterialTheme.typography.bodySmall)
                    }
                    ProviderSetup(store, choice) { choice = it }
                }
            }
            OnboardingPage.MODEL -> if (provider != null) {
                ModelSetup(store, provider, chosenModel.takeIf { chosenProvider == provider.name }) { model ->
                    store.setModel(provider.name, model)
                    chosenProvider = provider.name
                    chosenModel = model
                }
            }
            OnboardingPage.COMPANION -> companionStep?.invoke()
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page != OnboardingPage.WELCOME) OutlinedButton(onClick = ::previousPage, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_back))
            }
            if (!finalPage) Button(onClick = {
                val ready = when (page) {
                    OnboardingPage.DESKTOP_ACCESS -> currentPrivilege() == ShizukuState.READY
                    OnboardingPage.DESKTOP_ACCESSIBILITY -> store.accessibilityConsent && desktopAccessibilityEnabled()
                    OnboardingPage.AI_PROVIDER -> credentialsReady()
                    OnboardingPage.MODEL -> modelReady()
                    else -> true
                }
                if (ready) page = pages[page.ordinal + 1] else refreshKey++
            }, enabled = missing == null, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_next))
            } else Button(onClick = {
                if (currentPrivilege() == ShizukuState.READY && store.accessibilityConsent &&
                    desktopAccessibilityEnabled() && credentialsReady() && modelReady() &&
                    companionReady()) {
                    store.onboardingDone = true
                    context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                        .edit().putBoolean("flutter.setup_completed", true).apply()
                    onDone()
                } else refreshKey++
            }, enabled = missing == null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.onboarding_finish)) }
        }
        if (missing != null) Text(stringResource(missing), style = MaterialTheme.typography.bodySmall)
    }
}

private enum class AccessibilityAction { OPEN_SETTINGS, AUTOMATIC }

@Composable
private fun DesktopAccessibilityStep(
    store: BackstageSettingsStore,
    enabled: Boolean,
    automatic: (suspend () -> String)?,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingAction by rememberSaveable { mutableStateOf<AccessibilityAction?>(null) }
    var settingUp by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val serviceName = stringResource(if (BuildConfig.PLAY_DISTRIBUTION)
        R.string.onboarding_accessibility_play_service else R.string.onboarding_accessibility_github_service)

    fun perform(action: AccessibilityAction) {
        when (action) {
            AccessibilityAction.OPEN_SETTINGS -> context.startActivity(AndroidPermissionProbe.accessibilitySettings())
            AccessibilityAction.AUTOMATIC -> {
                val setUp = automatic ?: return
                settingUp = true
                scope.launch {
                    try {
                        message = setUp()
                    } finally {
                        settingUp = false
                        onChanged()
                    }
                }
            }
        }
    }

    fun request(action: AccessibilityAction) {
        if (store.accessibilityConsent) perform(action) else pendingAction = action
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.onboarding_desktop_accessibility), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (enabled) Text("✓")
                Text(stringResource(if (enabled) R.string.onboarding_desktop_accessibility_enabled else R.string.onboarding_desktop_accessibility_disabled))
            }
            Text(stringResource(R.string.onboarding_accessibility_step_one, serviceName))
            OutlinedButton(onClick = { request(AccessibilityAction.OPEN_SETTINGS) }) {
                Text(stringResource(R.string.onboarding_desktop_accessibility_open))
            }
            Text(stringResource(R.string.onboarding_accessibility_step_two))
            OutlinedButton(onClick = { context.startActivity(AndroidPermissionProbe.appDetails(context)) }) {
                Text(stringResource(R.string.onboarding_open_app_details))
            }
            Text(stringResource(R.string.onboarding_accessibility_step_three, serviceName))
            OutlinedButton(onClick = { request(AccessibilityAction.OPEN_SETTINGS) }) {
                Text(stringResource(R.string.onboarding_desktop_accessibility_open))
            }
            if (automatic != null) OutlinedButton(
                enabled = !settingUp,
                onClick = { request(AccessibilityAction.AUTOMATIC) },
            ) {
                Text(stringResource(if (settingUp) R.string.onboarding_accessibility_setting_up
                    else R.string.onboarding_accessibility_automatic))
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    pendingAction?.let { action ->
        AccessibilityDisclosureDialog(
            onAgree = {
                store.accessibilityConsent = true
                pendingAction = null
                perform(action)
            },
            onDecline = { pendingAction = null },
        )
    }
}

private enum class ProviderSetupChoice { CHATGPT, API_KEY }

@Composable
private fun ProviderSetup(store: BackstageSettingsStore, choice: ProviderSetupChoice?, choose: (ProviderSetupChoice) -> Unit) {
    fun selectChatGpt() {
        if (store.chatGptTokens != null) {
            store.setBaseUrl(Provider.CHATGPT.name, Provider.CHATGPT.defaultBaseUrl)
            store.providerId = Provider.CHATGPT.name
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = choice == ProviderSetupChoice.CHATGPT,
            onClick = { choose(ProviderSetupChoice.CHATGPT); selectChatGpt() },
            label = { Text(stringResource(R.string.onboarding_chatgpt)) },
        )
        FilterChip(
            selected = choice == ProviderSetupChoice.API_KEY,
            onClick = { choose(ProviderSetupChoice.API_KEY) },
            label = { Text(stringResource(R.string.onboarding_use_key)) },
        )
    }
    when (choice) {
        ProviderSetupChoice.CHATGPT -> ChatGptAccount(store, onChanged = ::selectChatGpt)
        ProviderSetupChoice.API_KEY -> ProviderSection(store, apiKeyOnly = true, credentialsOnly = BuildConfig.PLAY_DISTRIBUTION)
        null -> Unit
    }
}

@Composable
private fun ModelSetup(store: BackstageSettingsStore, provider: Provider, selected: String?, onSelect: (String) -> Unit) {
    val id = provider.name
    var result by remember(id) { mutableStateOf<ModelListResult?>(null) }
    var retry by remember(id) { mutableIntStateOf(0) }
    var query by rememberSaveable(id) { mutableStateOf("") }
    var custom by rememberSaveable(id) { mutableStateOf("") }
    if (provider.canListModels || provider == Provider.CHATGPT) LaunchedEffect(store, id, store.apiKey(id), store.baseUrl(id), retry) {
        result = null
        result = store.fetchModels(id, force = retry > 0)
    }
    val listed = (result as? ModelListResult.Loaded)?.let { it.models + it.hidden }
    val loading = (provider.canListModels || provider == Provider.CHATGPT) && result == null
    val models = (listed?.map { it.id } ?: if (loading) emptyList() else provider.suggestedModels)
        .plus(listOfNotNull(selected)).filter(String::isNotBlank).distinct()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.onboarding_choose_model), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.onboarding_model_hint))
            Text(provider.label, style = MaterialTheme.typography.labelLarge)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val error = when (val loaded = result) {
                is ModelListResult.KeyRejected -> loaded.message
                is ModelListResult.Unavailable -> loaded.message
                else -> null
            }
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.onboarding_reload_models)) }
            }
            if (models.size > 1) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.onboarding_search_models)) }, singleLine = true)
            Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                models.filter { it.contains(query.trim(), ignoreCase = true) }.forEach { model ->
                    Row(Modifier.fillMaxWidth().selectable(selected == model, role = Role.RadioButton, onClick = { onSelect(model) }).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected == model, onClick = null)
                        Text(model)
                    }
                }
            }
            if (provider != Provider.CHATGPT) OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.onboarding_custom_model)) }, singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { onSelect(custom.trim()); query = ""; custom = "" }, enabled = custom.isNotBlank()) {
                        Text(stringResource(R.string.onboarding_use_model))
                    }
                })
        }
    }
}
