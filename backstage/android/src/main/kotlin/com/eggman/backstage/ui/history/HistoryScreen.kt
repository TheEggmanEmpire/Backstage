package com.eggman.backstage.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.ui.AgentViewModel
import com.eggman.backstage.ui.StatusText
import com.eggman.backstage.ui.chat.PlanCard
import com.eggman.backstage.ui.chat.sourceLabel
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(vm: AgentViewModel, modifier: Modifier = Modifier) {
    var items by remember { mutableStateOf<List<TaskSnapshot>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) { items = vm.history() }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Task history", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        val list = items
        when {
            list == null -> Text("Loading…", Modifier.padding(16.dp))
            list.isEmpty() -> Text("No tasks yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { s ->
                    Card(Modifier.fillMaxWidth().clickable { expanded = if (expanded == s.id) null else s.id }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(statusLabel(s.status), style = MaterialTheme.typography.labelMedium,
                                    color = if (s.status == TaskStatus.SUCCEEDED) MaterialTheme.colorScheme.primary
                                    else if (s.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("  ·  ${sourceLabel(s.request.source)} · ${s.request.target.name.lowercase()} · " +
                                    StatusText.elapsed(s, System.currentTimeMillis()),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f))
                                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.startedAtMs)),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Text(s.request.prompt, style = MaterialTheme.typography.bodyMedium,
                                maxLines = if (expanded == s.id) 20 else 2, overflow = TextOverflow.Ellipsis)
                            s.result?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (expanded == s.id) 40 else 3, overflow = TextOverflow.Ellipsis)
                            }
                            if (expanded == s.id) {
                                if (s.plan.isNotEmpty()) PlanCard(s.plan)
                                Text("${s.step} steps", style = MaterialTheme.typography.labelSmall)
                                // where the time went (measured on this phone, never sent anywhere).
                                s.timing?.summary()?.takeIf { it.isNotEmpty() }?.let { Text("Time: $it", style = MaterialTheme.typography.labelSmall) }
                                TextButton(onClick = { vm.rerun(s) }) { Text("Run again…") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(s: TaskStatus) = when (s) {
    TaskStatus.QUEUED -> "Queued"
    TaskStatus.RUNNING -> "Running"
    TaskStatus.WAITING_FOR_USER -> "Waiting"
    TaskStatus.SUCCEEDED -> "Done"
    TaskStatus.FAILED -> "Failed"
    TaskStatus.CANCELLED -> "Stopped"
}
