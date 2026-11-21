package com.eggman.backstage.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.automation.QuickActionStore
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.ui.AgentViewModel
import com.eggman.backstage.ui.StatusText
import com.eggman.backstage.ui.desktop.DesktopStatus
import com.eggman.backstage.ui.desktop.desktopStatusFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The bubble's "Open Backstage" in the chat sheet's header (only when the sheet is opened from the bubble). */
@Composable
fun BubbleChatOpenApp(onOpen: () -> Unit) {
    IconButton(onClick = onOpen) { Icon(Icons.Default.OpenInNew, contentDescription = OPEN_APP_LABEL) }
}

/**
 * What the bubble's own panel had besides the chat, kept in the chat sheet opened from it: up to four quick actions
 * while the agent is idle, and the desktop popup's mini window / background / full screen switches while a desktop runs.
 */
@Composable
fun BubbleChatExtras(vm: AgentViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val agent by vm.agent.collectAsState()
    val desktop by desktopStatusFlow().collectAsState(initial = DesktopStatus.of(AgentAccess.host()))
    val current = agent.current
    val idle = !StatusText.isActive(current)
    val quick = remember { runCatching { QuickActionStore.get(context).list().take(4) }.getOrDefault(emptyList()) }
    val switches = if (!desktop.active) emptyList() else buildList {
        if (Presentation.MINI in desktop.presentationChoices && desktop.presentation != Presentation.MINI) add(Presentation.MINI)
        if (Presentation.HIDDEN in desktop.presentationChoices && desktop.presentation != Presentation.HIDDEN) add(Presentation.HIDDEN)
        if (desktop.presentation != Presentation.FULL) add(Presentation.FULL)
    }
    val showQuick = idle && quick.isNotEmpty()
    if (!showQuick && switches.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showQuick) quick.forEach { qa ->
            AssistChip(
                onClick = {
                    val refused = vm.sendGuard()
                    if (refused != null) vm.lastSent.value = refused
                    else {
                        // Quick actions are the user's own saved words.
                        val handle = AgentAccess.submit(TaskRequest(qa.prompt, BubbleModel.SOURCE, target = qa.target, waiverText = qa.prompt))
                        vm.lastSent.value = if (handle == null) NOT_READY else "Task started."
                    }
                },
                label = { Text(qa.name, maxLines = 1) },
            )
        }
        switches.forEach { p ->
            IconButton(onClick = { scope.launch(Dispatchers.Default) { runCatching { AgentAccess.host()?.setPresentation(p) } } }) {
                when (p) {
                    Presentation.MINI -> Icon(Icons.Default.PictureInPicture, "Show desktop mini window")
                    Presentation.HIDDEN -> Icon(Icons.Default.VisibilityOff, "Hide desktop to background")
                    else -> Icon(Icons.Default.Fullscreen, "Show desktop full screen")
                }
            }
        }
    }
}

const val OPEN_APP_LABEL = "Open Backstage"
private const val NOT_READY = "Agent not ready — open Backstage"
