package com.eggman.backstage.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eggman.backstage.overlay.Overlay

/** Onboarding / permission checklist, re-evaluated every time the screen resumes. */
@Composable
fun PlayPermissionChecklist(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val probe = remember(tick) { AndroidPermissionProbe.snapshot(context) }
    val items = remember(probe) {
        PermissionChecklist.build(probe).filterNot {
            it.id in setOf(ItemId.ACCESSIBILITY, ItemId.SMS, ItemId.PROVIDER, ItemId.PRIVILEGED)
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    fun act(id: ItemId) {
        when (id) {
            ItemId.PROVIDER, ItemId.PRIVILEGED, ItemId.ACCESSIBILITY, ItemId.SMS -> Unit
            ItemId.NOTIFICATIONS -> permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            ItemId.OVERLAY -> context.startActivity(Overlay.permissionIntent(context))
            ItemId.MICROPHONE -> permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            ItemId.CALENDAR -> permissions.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            ItemId.EXACT_ALARMS -> context.startActivity(AndroidPermissionProbe.exactAlarmSettings(context))
            ItemId.BATTERY -> runCatching { context.startActivity(AndroidPermissionProbe.batterySettings(context)) }
            ItemId.ASSISTANT -> runCatching { context.startActivity(AndroidPermissionProbe.assistantSettings()) }
                .recoverCatching { context.startActivity(AndroidPermissionProbe.defaultAppsSettings()) }
            ItemId.CONTACTS, ItemId.PHONE, ItemId.LOCATION, ItemId.SYSTEM_SETTINGS, ItemId.DND -> id.deviceAccess?.let { k ->
                if (k.permissions.isNotEmpty()) permissions.launch(k.permissions.toTypedArray())
                else com.eggman.backstage.tools.DeviceAccess.settingsIntent(context, k)?.let { runCatching { context.startActivity(it) } }
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        }
                    }
                    if (!item.done && item.actionLabel != null) OutlinedButton(onClick = { act(item.id) }) { Text(item.actionLabel) }
                }
            }
        }
    }
}
