package com.eggman.backstage.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eggman.backstage.overlay.Overlay
import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.privileged.PrivilegedSetup
import com.eggman.backstage.privileged.ShizukuSetup
import com.eggman.backstage.privileged.ShizukuState
import androidx.compose.runtime.LaunchedEffect
import com.eggman.backstage.ui.settings.SettingsStores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SetupScreen(onOpenSettings: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { SettingsStores.get(context) }
    if (store.onboardingDone) {
        SetupChecklist(onOpenSettings, onDone, modifier)
    } else {
        OnboardingWizard(onDone, modifier, automaticAccessibility = {
            val report = try {
                withContext(Dispatchers.IO) { PrivilegedSetup.applyAll(context) }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                null
            }
            if (report?.accessibilityDone == true) {
                runCatching { com.eggman.backstage.init.Backstage.environment.settings.privilegedSetupDone = true }
            }
            report?.summary() ?: "Start Stellar or Shizuku Plus and allow Backstage, then try again."
        })
    }
}

/** Permission checklist, re-evaluated every time the screen resumes. */
@Composable
private fun SetupChecklist(onOpenSettings: () -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val probe = remember(tick) { AndroidPermissionProbe.snapshot(context) }
    val items = remember(probe) { PermissionChecklist.build(probe) }
    val (done, total) = PermissionChecklist.progress(items)

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    var settingUp by remember { mutableStateOf(false) }

    // Stellar/Shizuku permission / binder changes re-evaluate the checklist.
    DisposableEffect(Unit) {
        val unregister = Privileged.addPermissionListener { tick++ }
        onDispose { unregister() }
    }

    val store = remember { SettingsStores.get(context) }
    var consent by remember { mutableStateOf(store.accessibilityConsent) }
    // The action waiting for the disclosure (set = dialog shown); declinedHere stops the automatic prompt re-appearing.
    var afterConsent by remember { mutableStateOf<(() -> Unit)?>(null) }
    var declinedHere by remember { mutableStateOf(false) }

    /** Runs [action] when the user already consented, else after they accept the disclosure. */
    fun withConsent(action: () -> Unit) {
        if (consent) action() else afterConsent = action
    }

    fun setUpEverything() {
        // Switches the accessibility service on: never without the user's consent.
        if (settingUp || !consent) return
        settingUp = true
        scope.launch {
            val report = withContext(Dispatchers.IO) { PrivilegedSetup.applyAll(context) }
            message = report?.summary() ?: "No privileged access yet: start Stellar (with Shizuku compatibility) or Shizuku Plus first."
            if (report?.accessibilityDone == true) runCatching { com.eggman.backstage.init.Backstage.environment.settings.privilegedSetupDone = true }
            settingUp = false
            tick++
        }
    }

    // Stellar/Shizuku just became ready: run the one-shot setup (provider, the accessibility service, grants) once the
    // user has accepted the accessibility disclosure; without consent, show the disclosure first.
    val autoSetupDone = remember { runCatching { com.eggman.backstage.init.Backstage.environment.settings.privilegedSetupDone }.getOrDefault(false) }
    LaunchedEffect(probe.shizuku, consent) {
        when {
            AccessibilityDisclosure.autoSetupAllowed(probe.shizuku, autoSetupDone, consent) -> setUpEverything()
            AccessibilityDisclosure.promptAutomatically(probe.shizuku, autoSetupDone, consent, declinedHere) ->
                if (afterConsent == null) afterConsent = { setUpEverything() }
        }
    }

    fun act(id: ItemId) {
        when (id) {
            ItemId.PROVIDER -> onOpenSettings()
            ItemId.PRIVILEGED -> when (probe.shizuku) {
                ShizukuState.NOT_INSTALLED -> runCatching { context.startActivity(ShizukuSetup.download()) }
                ShizukuState.NOT_RUNNING -> ShizukuSetup.openShizuku(context)?.let { runCatching { context.startActivity(it) } }
                ShizukuState.PERMISSION_NEEDED -> ShizukuSetup.requestPermission()
                ShizukuState.READY -> Unit
            }
            ItemId.ACCESSIBILITY -> withConsent {
                if (probe.privileged) setUpEverything()
                else context.startActivity(AndroidPermissionProbe.accessibilitySettings())
            }
            ItemId.NOTIFICATIONS -> permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            ItemId.OVERLAY -> context.startActivity(Overlay.permissionIntent(context))
            ItemId.MICROPHONE -> permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            ItemId.CALENDAR -> permissions.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            ItemId.EXACT_ALARMS -> context.startActivity(AndroidPermissionProbe.exactAlarmSettings(context))
            ItemId.BATTERY -> runCatching { context.startActivity(AndroidPermissionProbe.batterySettings(context)) }
            ItemId.ASSISTANT -> runCatching { context.startActivity(AndroidPermissionProbe.assistantSettings()) }
                .recoverCatching { context.startActivity(AndroidPermissionProbe.defaultAppsSettings()) }
            ItemId.CONTACTS, ItemId.SMS, ItemId.PHONE, ItemId.LOCATION, ItemId.SYSTEM_SETTINGS, ItemId.DND -> id.deviceAccess?.let { k ->
                if (k.permissions.isNotEmpty()) permissions.launch(k.permissions.toTypedArray())
                else com.eggman.backstage.tools.DeviceAccess.settingsIntent(context, k)?.let { runCatching { context.startActivity(it) } }
            }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Set up Backstage", style = MaterialTheme.typography.titleLarge)
        Text("Backstage works on the Dextop desktop in the background (or on your phone screen). Grant what you need; everything optional can be added later.",
            style = MaterialTheme.typography.bodyMedium)
        Text("Privilege provider: install Stellar or Shizuku Plus. " +
            "For Stellar, enable its Shizuku compatibility layer and allow Backstage.", style = MaterialTheme.typography.bodySmall)
        com.eggman.backstage.ui.components.PrivilegeManagerButtons()
        LinearProgressIndicator(progress = { if (total == 0) 1f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        if (probe.privileged) {
            FilledTonalButton(onClick = { withConsent { setUpEverything() } }, enabled = !settingUp, modifier = Modifier.fillMaxWidth()) {
                Text(if (settingUp) "Setting up…" else "Set up everything with ${if (probe.shizuku == ShizukuState.READY) "Stellar / Shizuku" else "the privileged runtime"}")
            }
        }
        if (probe.privileged && items.any { !it.done && it.privilegedGrantable }) {
            OutlinedButton(onClick = {
                scope.launch {
                    val (ok, n) = withContext(Dispatchers.Default) { AndroidPermissionProbe.grantAllPrivileged(context, items) }
                    message = "Granted $ok of $n through the privileged runtime." +
                        if (items.any { it.id == ItemId.ACCESSIBILITY && !it.done }) " Now switch on the Backstage accessibility service." else ""
                    tick++
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Also grant microphone, calendar and the rest of this list") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        items.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (item.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                        Modifier.size(22.dp), tint = if (item.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(item.title + when (item.importance) {
                            Importance.REQUIRED -> ""
                            Importance.RECOMMENDED -> " · recommended"
                            Importance.OPTIONAL -> " · optional"
                        }, style = MaterialTheme.typography.titleSmall)
                        Text(item.why, style = MaterialTheme.typography.bodySmall)
                        item.hint?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            if (item.id == ItemId.ACCESSIBILITY) TextButton(onClick = {
                                context.startActivity(AndroidPermissionProbe.appDetails(context))
                            }) { Text("Open App info") }
                        }
                    }
                    if (!item.done && item.actionLabel != null) OutlinedButton(onClick = { act(item.id) }) { Text(item.actionLabel) }
                }
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
    afterConsent?.let { action ->
        AccessibilityDisclosureDialog(
            onAgree = {
                consent = true
                store.accessibilityConsent = true
                afterConsent = null
                action()
            },
            onDecline = {
                declinedHere = true
                afterConsent = null
                message = "Backstage did not switch on any accessibility service. Tap Enable when you are ready."
            },
        )
    }
}
