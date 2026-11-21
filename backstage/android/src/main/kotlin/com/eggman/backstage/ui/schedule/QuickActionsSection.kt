package com.eggman.backstage.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.automation.QuickAction
import com.eggman.backstage.automation.QuickActionStore
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.Widgets

/** Saved one-tap tasks: create, edit, reorder, delete and run. */
@Composable
fun QuickActionsSection(onRun: (String) -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { QuickActionStore.get(context) }
    var version by remember { mutableIntStateOf(0) }
    val actions = remember(version) { store.list() }
    var editing by remember { mutableStateOf<QuickAction?>(null) }
    val changed = { version++; Widgets.updateQuick(context) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Quick actions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = { editing = QuickAction("", "", "") }) { Icon(Icons.Default.Add, null); Text("New") }
    }
    if (actions.isEmpty()) Text("Save tasks you repeat often and run them with one tap — here, from the bubble or from a home-screen widget.",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    actions.forEachIndexed { i, a ->
        Card(onClick = { editing = a }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.titleSmall)
                    Text(a.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {
                    AgentAccess.submit(TaskRequest(a.prompt, Source.CHAT, target = a.target, waiverText = a.prompt))
                    onRun(a.name)
                }) { Icon(Icons.Default.PlayArrow, "Run ${a.name}") }
                if (i > 0) IconButton(onClick = { store.move(a.id, -1); changed() }) { Icon(Icons.Default.ArrowUpward, "Move up") }
                if (i < actions.size - 1) IconButton(onClick = { store.move(a.id, 1); changed() }) { Icon(Icons.Default.ArrowDownward, "Move down") }
            }
        }
    }
    editing?.let { a ->
        var name by remember(a) { mutableStateOf(a.name) }
        var prompt by remember(a) { mutableStateOf(a.prompt) }
        var target by remember(a) { mutableStateOf(a.target) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (a.id.isEmpty()) "New quick action" else "Edit quick action") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("Task") }, minLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Target.entries.forEach { t -> FilterChip(target == t, { target = t }, { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                    }
                    if (a.id.isNotEmpty()) TextButton(onClick = { store.delete(a.id); editing = null; changed() }) {
                        Icon(Icons.Default.Delete, null); Text("Delete")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = prompt.isNotBlank(), onClick = {
                    store.upsert(a.copy(name = name, prompt = prompt, target = target))
                    editing = null
                    changed()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
