package com.eggman.backstage.ui.chat

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.agent.api.Answers
import com.eggman.backstage.ui.AgentViewModel
import com.eggman.backstage.ui.StatusText

private val examples = listOf(
    "Open YouTube on the desktop and play some lo-fi music",
    "Check my unread messages and summarise them",
    "Turn on Do Not Disturb until 8 am",
    "Find a 2-person table for Friday 7 pm near me",
)

@Composable
fun ChatScreen(vm: AgentViewModel, onOpenSetup: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.agent.collectAsState()
    val transcript by vm.transcript.collectAsState()
    val coreInstalled by vm.coreInstalled.collectAsState()
    val list = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val current = state.current
    val active = StatusText.isActive(current)
    val question = current?.question?.takeIf { current.status == TaskStatus.WAITING_FOR_USER }

    // The chips are one shared target: pick up a change made in the popup chat or Settings.
    LaunchedEffect(Unit) { vm.refreshTarget() }
    LaunchedEffect(transcript.entries.size) {
        if (transcript.entries.isNotEmpty()) list.animateScrollToItem(transcript.entries.size - 1)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Backstage", style = MaterialTheme.typography.titleLarge)
                Text(StatusText.line(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ThreadButtons(vm)
        }
        // the live desktop at the top (DeX desktop / Auto chip only); the popup chat does not get it.
        DesktopPane(vm)
        if (!coreInstalled) Banner("The agent core is not available in this build.", null, null)
        else if (!active) com.eggman.backstage.agent.ReadinessCheck.setupProblems(state.ready).firstOrNull()?.let { Banner(it, "Fix", onOpenSetup) }

        if (active && current != null) {
            if (current.maxSteps > 0) LinearProgressIndicator(
                progress = { (current.step.toFloat() / current.maxSteps).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
            ) else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (current != null && current.plan.isNotEmpty() && (active || current.status == TaskStatus.SUCCEEDED)) PlanCard(current.plan)

        val showThreads by vm.showThreads.collectAsState()
        if (showThreads) ThreadList(vm, Modifier.weight(1f)) else LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (transcript.entries.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tell Backstage what to do. It can work on the desktop in the background, so your phone stays yours.",
                        style = MaterialTheme.typography.bodyLarge)
                    val quick = remember { com.eggman.backstage.automation.QuickActionStore.get(context).list() }
                    if (quick.isNotEmpty()) Text("Quick actions", style = MaterialTheme.typography.labelLarge)
                    quick.forEach { qa ->
                        AssistChip(onClick = { vm.updateDraft(qa.prompt); vm.target.value = qa.target }, label = { Text(qa.name, fontSize = 13.sp) })
                    }
                    if (quick.isNotEmpty()) Text("Ideas", style = MaterialTheme.typography.labelLarge)
                    examples.forEach { ex -> AssistChip(onClick = { vm.updateDraft(ex) }, label = { Text(ex, fontSize = 13.sp) }) }
                }
            }
            items(transcript.entries, key = { it.id }) { EntryRow(it) }
        }

        if (question != null) QuestionBar(question.text, question.confirm, question.choices,
            onAnswer = { vm.answer(it, current.id, question.text) })

        ChatTargetRow(vm)
        ChatInputRow(vm, state, onMic = { vm.startVoice(autoSubmit = question == null) })
    }
}

/** What each chip does, read by TalkBack (and documented in the user guide). */
internal fun targetDescription(t: com.eggman.backstage.agent.api.Target): String = when (t) {
    com.eggman.backstage.agent.api.Target.DESKTOP -> "DeX desktop: the agent works on the DeX desktop, never on your phone screen"
    com.eggman.backstage.agent.api.Target.PHONE -> "Phone: the agent works on your phone's own screen"
    com.eggman.backstage.agent.api.Target.AUTO -> "Auto: the desktop when it runs, otherwise the phone"
}

internal const val CLEAR_TEXT = "Clear text"

internal const val PLAN_DESCRIPTION = "Plan: the agent writes a checklist first and works through it"

/**
 * The target chips (one shared current target: [com.eggman.backstage.entry.CurrentTarget]) and the Plan toggle. Used
 * by the in-app chat and the popup's chat panel alike.
 */
@Composable
internal fun ChatTargetRow(vm: AgentViewModel, modifier: Modifier = Modifier) {
    val target by vm.target.collectAsState()
    val planOverride by vm.planOverride.collectAsState()
    val adbAutoApprove by com.eggman.backstage.ui.settings.rememberAdbAutoApprove(vm.settings)
    val adbAvailable by com.eggman.backstage.ui.settings.rememberAdbAvailable()
    FlowRow(modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.eggman.backstage.ui.settings.TARGET_ORDER.forEach { t ->
            FilterChip(selected = target == t, onClick = { vm.setTarget(t) },
                modifier = Modifier.semantics { contentDescription = targetDescription(t) },
                label = { Text(com.eggman.backstage.ui.settings.targetLabel(t), fontSize = 12.sp) })
        }
        if (adbAvailable) {
            Spacer(Modifier.size(4.dp))
            FilterChip(selected = adbAutoApprove, onClick = { vm.settings.adbAutoApprove = !adbAutoApprove },
                modifier = Modifier.semantics { contentDescription = "ADB auto: run shell commands without asking" },
                label = { Text("ADB auto", fontSize = 12.sp) })
        }
        FilterChip(
            selected = planOverride ?: vm.settings.planMode,
            onClick = { vm.setPlanOverride(!(planOverride ?: vm.settings.planMode)) },
            modifier = Modifier.semantics { contentDescription = PLAN_DESCRIPTION },
            label = { Text("Plan", fontSize = 12.sp) },
        )
    }
}

/**
 * Text field, microphone, send, and pause/stop. [onStopAgent] (the popup chat): a "Stop the agent" button that stops the
 * running task and the queue whenever the agent is busy, and send stays available (text goes to the running task).
 */
@Composable
internal fun ChatInputRow(
    vm: AgentViewModel,
    state: com.eggman.backstage.agent.api.AgentState,
    onMic: () -> Unit,
    onStopAgent: (() -> Unit)? = null,
    fieldModifier: Modifier = Modifier,
) {
    val draft by vm.draft.collectAsState()
    val listening by vm.listening.collectAsState()
    val partial by vm.partial.collectAsState()
    val current = state.current
    val active = StatusText.isActive(current)
    val question = current?.question?.takeIf { current.status == TaskStatus.WAITING_FOR_USER }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusTicks by vm.composerFocus.collectAsState()
    // A reopened thread: the field is ready to continue it (the keyboard may open).
    LaunchedEffect(focusTicks) { if (focusTicks > 0) runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = if (listening && partial != null) partial!! else draft,
            onValueChange = vm::updateDraft,
            modifier = fieldModifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Message to the agent" },
            trailingIcon = if (draft.isNotEmpty() && !listening) {
                { IconButton(onClick = vm::clearDraft) { Icon(Icons.Default.Clear, CLEAR_TEXT) } }
            } else null,
            placeholder = { Text(com.eggman.backstage.ui.Composer.placeholder(com.eggman.backstage.ui.Composer.mode(current), listening)) },
            maxLines = if (onStopAgent != null) 3 else 5,
        )
        IconButton(onClick = onMic) {
            Icon(Icons.Default.Mic, if (listening) "Stop listening" else "Voice input",
                tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onStopAgent == null && active && question == null && draft.isBlank()) {
            val paused = current?.paused == true
            if (current?.status == TaskStatus.RUNNING) IconButton(onClick = vm::togglePause) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Resume task" else "Pause task")
            }
            IconButton(onClick = vm::stop) { Icon(Icons.Default.Stop, "Stop task", tint = MaterialTheme.colorScheme.error) }
        } else {
            val resume = com.eggman.backstage.ui.Composer.mode(current) == com.eggman.backstage.ui.Composer.Mode.RESUME
            IconButton(onClick = { vm.send(fromVoice = false, shownTask = current) }, enabled = draft.isNotBlank()) {
                Icon(if (resume) Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.Send, if (resume) "Resume with this instruction" else "Send")
            }
        }
        if (onStopAgent != null && com.eggman.backstage.agent.SessionStop.agentActive(state)) {
            IconButton(onClick = onStopAgent) {
                Icon(Icons.Default.Stop, com.eggman.backstage.ui.minichat.MiniChatModel.STOP_LABEL, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Banner(text: String, action: String?, onAction: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, Modifier.size(18.dp))
            Text(text, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
internal fun QuestionBar(text: String, confirm: Boolean, choices: List<String>, onAnswer: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (confirm) "Approve this action?" else "The agent asks", style = MaterialTheme.typography.labelLarge)
            key(text) {
                Text(text, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (confirm) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAnswer(Answers.APPROVE) }, Modifier.weight(1f)) { Text("Approve") }
                OutlinedButton(onClick = { onAnswer(Answers.DENY) }, Modifier.weight(1f)) { Text("Deny") }
            } else if (choices.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.take(4).forEach { c -> AssistChip(onClick = { onAnswer(c) }, label = { Text(c) }) }
            }
        }
    }
}

@Composable
fun PlanCard(plan: List<PlanStep>) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Plan  ${plan.count { it.status == "done" }}/${plan.size}", style = MaterialTheme.typography.labelLarge)
            plan.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when (step.status) {
                        "done" -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                        "in_progress" -> Icons.Default.Autorenew to MaterialTheme.colorScheme.tertiary
                        "skipped" -> Icons.Default.RemoveCircleOutline to MaterialTheme.colorScheme.outline
                        else -> Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
                    }
                    Icon(icon, step.status, Modifier.size(16.dp), tint = tint)
                    Spacer(Modifier.size(6.dp))
                    Text(step.title, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Small copy icon under a message. */
@Composable
private fun CopyButton(text: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    IconButton(onClick = { ChatExport.copy(context, text) }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Default.ContentCopy, contentDescription = "Copy message", Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EntryRow(e: Transcript.Entry) {
    when (e.kind) {
        Transcript.Kind.USER, Transcript.Kind.ANSWER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(horizontalAlignment = Alignment.End) {
                if (e.source != null && e.source != Source.CHAT) Text(sourceLabel(e.source), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(e.text, Modifier.widthIn(max = 320.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)).padding(12.dp))
                CopyButton(e.text)
            }
        }
        Transcript.Kind.RESULT -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            e.thinking?.let { ThinkingBlock(e.id, it, e.thinkingActive) }
            if (e.text.isNotEmpty()) com.eggman.backstage.ui.report.ReportableAiResponse(e.text, e.taskId) { report ->
                Text(e.text, report.widthIn(max = 340.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(12.dp))
            }
            if (e.text.isNotEmpty()) CopyButton(e.text)
        }
        Transcript.Kind.QUESTION -> Column {
            com.eggman.backstage.ui.report.ReportableAiResponse(e.text, e.taskId) { report ->
                Text(e.text, report.widthIn(max = 340.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(16.dp)).padding(12.dp))
            }
            CopyButton(e.text)
        }
        Transcript.Kind.ACTION -> {
            var expanded by remember(e.id) { mutableStateOf(false) }
            Column {
                Row(Modifier.then(if (e.detail.isNotEmpty()) Modifier.clickable { expanded = !expanded } else Modifier),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TouchApp, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(6.dp))
                    Text((if (e.step > 0) "${e.step}. " else "") + e.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (e.singleLine || e.detail.isNotEmpty()) 1 else 3,
                        overflow = TextOverflow.Ellipsis)
                }
                if (expanded && e.detail.isNotEmpty()) Text(e.detail, Modifier.padding(start = 20.dp, top = 4.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
        Transcript.Kind.ERROR -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(6.dp))
            Text(e.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Transcript.Kind.INFO -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.size(6.dp))
            Text(e.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shared by the in-app chat and popup/bubble sheets. Only provider-visible reasoning is displayed. */
@Composable
internal fun ThinkingBlock(id: Long, text: String, active: Boolean) {
    var expanded by remember(id, active) { mutableStateOf(active) }
    val hidden = text == com.eggman.backstage.omp.engine.OmpEventMapper.HIDDEN_THINKING
    Column(Modifier.widthIn(max = 340.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (hidden) text else "Thinking")
            Text(if (expanded) " ▾" else " ▸")
        }
        if (expanded && !hidden && text.isNotEmpty()) Text(text,
            Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun sourceLabel(s: Source): String = when (s) {
    Source.CHAT -> "Chat"
    Source.VOICE -> "Voice"
    Source.WAKE_WORD -> "Wake word"
    Source.OVERLAY -> "Bubble"
    Source.TILE -> "Quick tile"
    Source.ASSIST -> "Assistant"
    Source.SHARE -> "Shared"
    Source.TASKER -> "Tasker / plugin"
    Source.BROADCAST -> "Broadcast"
    Source.SCHEDULE -> "Schedule"
    Source.MCP -> "MCP client"
    Source.NOTIFICATION -> "Notification"
    Source.WIDGET -> "Widget"
    Source.POPUP_CHAT -> "Popup chat"
}
