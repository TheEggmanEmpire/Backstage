package com.eggman.backstage.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eggman.backstage.data.ChatThread
import com.eggman.backstage.ui.AgentViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure texts of the chat history (unit-tested). */
object ChatThreadTexts {
    const val HISTORY = "Chat history"
    const val NEW_CHAT = "New chat"
    const val BACK = "Back to the chat"
    const val EMPTY = "No earlier chats yet. Every chat is kept here; \"New chat\" starts another one."

    /** "3 Nov 14:02 · DeX desktop · 2 messages · 7 steps" */
    fun subtitle(t: ChatThread, zone: ZoneId = ZoneId.systemDefault()): String = buildList {
        add(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault()).withZone(zone).format(Instant.ofEpochMilli(t.lastActiveMs)))
        when (t.target?.lowercase()) {
            "desktop" -> add("DeX desktop")
            "phone" -> add("Phone")
        }
        add(if (t.messageCount == 1) "1 message" else "${t.messageCount} messages")
        if (t.steps > 0) add(if (t.steps == 1) "1 step" else "${t.steps} steps")
    }.joinToString(" · ")

    fun matches(t: ChatThread, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return t.displayTitle.contains(q, true) || t.entries.any { it.text.contains(q, true) }
    }
}

/** The chat header's thread buttons: chat history and "New chat" (the in-app chat and the popup chat alike). */
@Composable
internal fun ThreadButtons(vm: AgentViewModel) {
    val showing by vm.showThreads.collectAsState()
    IconButton(onClick = { vm.showThreads.value = !showing }) {
        Icon(if (showing) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.History,
            contentDescription = if (showing) ChatThreadTexts.BACK else ChatThreadTexts.HISTORY)
    }
    IconButton(onClick = vm::newChat) { Icon(Icons.Default.AddComment, contentDescription = ChatThreadTexts.NEW_CHAT) }
    val context = androidx.compose.ui.platform.LocalContext.current
    IconButton(onClick = {
        val t = vm.threads.get(vm.threadId.value)
        if (t == null || t.isEmpty) android.widget.Toast.makeText(context, "Nothing to export yet", android.widget.Toast.LENGTH_SHORT).show()
        else ChatExport.share(context, ChatExport.fileName(t.displayTitle), ChatExport.markdown(t))
    }) { Icon(Icons.Default.Share, contentDescription = "Export this chat") }
}

/** The chat history: earlier threads, newest first; tap one to continue it, the menu renames or deletes it. */
@Composable
internal fun ThreadList(vm: AgentViewModel, modifier: Modifier = Modifier) {
    val threads by vm.threads.threads.collectAsState()
    val current by vm.threadId.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ChatThread?>(null) }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val context = androidx.compose.ui.platform.LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ChatThreadTexts.HISTORY, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (threads.isNotEmpty()) TextButton(onClick = {
                ChatExport.share(context, "backstage-chats.md", ChatExport.markdownAll(threads))
            }) { Text("Export all") }
        }
        if (threads.size > 3) OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search chats") },
        )
        val shown = threads.filter { ChatThreadTexts.matches(it, query) }
        if (shown.isEmpty()) Text(ChatThreadTexts.EMPTY, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown, key = { it.id }) { t ->
                ThreadRow(t, current = t.id == current, onOpen = { vm.openThread(t.id) }, onRename = { renaming = t },
                    onDelete = { vm.deleteThread(t.id) },
                    onExport = { ChatExport.share(context, ChatExport.fileName(t.displayTitle), ChatExport.markdown(t)) })
                HorizontalDivider()
            }
        }
    }
    renaming?.let { t ->
        var title by remember(t.id) { mutableStateOf(t.displayTitle) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(title, { title = it.take(ChatThread.TITLE_CHARS * 2) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameThread(t.id, title); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ThreadRow(t: ChatThread, current: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp)
            .semantics { contentDescription = "Open chat: ${t.displayTitle}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.displayTitle, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((if (current) "Current chat · " else "") + ChatThreadTexts.subtitle(t), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Chat options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Export (Markdown)") }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
