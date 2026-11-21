package com.eggman.backstage.overlay

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.notify.SessionNotification
import com.eggman.backstage.shell.ShellLifecycle
import com.eggman.backstage.ui.StatusText
import com.eggman.backstage.ui.minichat.MiniChatActivity
import com.eggman.backstage.ui.settings.SettingsStores
import com.eggman.backstage.ui.theme.BackstageTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * State + actions behind the bubble. the bubble is a launcher of the chat sheet
 * ([MiniChatActivity.bubbleIntent]): the same chat as the desktop popup's chat button, labelled "Bubble". The bubble
 * itself shows the agent's status (ring + spinner), opens the chat on a tap, starts a spoken task on a long-press and
 * can be dragged onto the remove target ([BubbleService]).
 */
class BubbleController(private val service: BubbleService) {
    val context: android.content.Context get() = service
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The finger holds the bubble over the remove target. */
    val overTarget = MutableStateFlow(false)
    /** Task whose end should remove a transient bubble (plugin "show progress"). */
    @Volatile
    var hideAfterTaskId: String? = null

    private var started = false

    /** Called once the window is attached. */
    fun start() {
        if (started) return
        started = true
        // Result display: open the bubble's chat with the outcome (it closes again after 5 s when chosen).
        scope.launch {
            var lastTerminal: String? = AgentAccess.state().value.current?.id
            AgentAccess.state().collect { st ->
                val s = st.current ?: return@collect
                if (!s.status.isTerminal() || s.id == lastTerminal) return@collect
                lastTerminal = s.id
                if (s.id == hideAfterTaskId) {
                    hideAfterTaskId = null
                    service.stopSelf()
                    return@collect
                }
                val settings = SettingsStores.get(service)
                val opens = BubbleModel.resultOpensChat(
                    settings.resultDisplay,
                    appVisible = ShellLifecycle.visible.value,
                    chatShowing = MiniChatActivity.showing.value,
                    interactive = service.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false,
                    locked = SessionNotification.deviceLocked(service),
                )
                if (opens) openChat(peek = true, autoClose = settings.resultAutoDismiss)
            }
        }
    }

    /** The bubble's tap: the chat sheet. [voice]: [MiniChatActivity.VOICE_DRAFT] / [MiniChatActivity.VOICE_SEND]. */
    fun openChat(voice: String? = null, peek: Boolean = false, autoClose: Boolean = false) {
        runCatching {
            service.startActivity(MiniChatActivity.bubbleIntent(service, voice, peek, autoClose))
        }.onFailure { toast("Open Backstage to chat with the agent") }
    }

    /** Long-press: speak a task; it is sent when heard (the user's own gesture on the bubble). */
    fun quickVoice() = openChat(voice = MiniChatActivity.VOICE_SEND)

    /** Run a task from an external trigger; optionally remove the bubble when it finishes. */
    fun startTask(text: String, source: Source, target: com.eggman.backstage.agent.api.Target, autoHide: Boolean) {
        // Only quick actions the user saved start tasks here, so their wording is the user's own.
        val handle = AgentAccess.submit(TaskRequest(text, source, target = target, waiverText = text))
        if (handle == null) {
            toast("Agent not ready — open Backstage")
            return
        }
        if (autoHide) scope.launch {
            handle.await()
            service.stopSelf()
        }
    }

    private fun toast(text: String) {
        runCatching { android.widget.Toast.makeText(service, text, android.widget.Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private fun TaskStatus.isTerminal() =
            this == TaskStatus.SUCCEEDED || this == TaskStatus.FAILED || this == TaskStatus.CANCELLED
    }

    fun dispose() {
        scope.cancel()
    }
}

/** The bubble: a circle with the agent's status. Touches go to [BubbleService.onTouch] in screen coordinates. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BubbleContent(c: BubbleController, onTouch: (android.view.MotionEvent) -> Boolean) {
    BackstageTheme {
        val state by AgentAccess.state().collectAsState()
        val over by c.overTarget.collectAsState()
        val s = state.current
        val active = StatusText.isActive(s)
        val waiting = s?.status == TaskStatus.WAITING_FOR_USER
        val ring = when {
            over -> MaterialTheme.colorScheme.error
            waiting -> MaterialTheme.colorScheme.tertiary
            active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        }
        Box(
            Modifier
                .size(BubbleService.BUBBLE_DP.dp)
                .padding(4.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .border(3.dp, ring, CircleShape)
                .pointerInteropFilter { onTouch(it) }
                .semantics {
                    // "Backstage" (the app's name and icon), the agent's status as the state.
                    contentDescription = BubbleModel.LABEL
                    stateDescription = StatusText.line(state)
                    onClick(label = BubbleModel.OPEN_LABEL) { c.openChat(); true }
                    onLongClick(label = BubbleModel.VOICE_LABEL) { c.quickVoice(); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            // The Backstage app icon (round rendition of the launcher icon) inside the status ring; the spinner around it.
            Image(
                painterResource(BubbleModel.ICON),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(3.dp).clip(CircleShape),
            )
            if (active && !waiting) CircularProgressIndicator(Modifier.size(44.dp), strokeWidth = 2.dp)
        }
    }
}

/** The remove target shown at the bottom centre while the bubble is dragged; it grows when the bubble is over it. */
@Composable
fun DismissTarget(armed: MutableStateFlow<Boolean>) {
    BackstageTheme {
        val on by armed.collectAsState()
        val size by animateDpAsState(if (on) (BubbleService.TARGET_RADIUS_DP * 2 + 16).dp else (BubbleService.TARGET_RADIUS_DP * 2).dp, label = "target")
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(size)
                    .background(if (on) MaterialTheme.colorScheme.error else Color(0xCC202124), CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                    .semantics { contentDescription = BubbleModel.REMOVE_LABEL },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}
