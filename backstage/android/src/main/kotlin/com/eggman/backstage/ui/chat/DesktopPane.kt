package com.eggman.backstage.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.SurfaceScope
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.shell.ShellLifecycle
import com.eggman.backstage.ui.AgentViewModel
import kotlinx.coroutines.launch

/**
 * The live DeX desktop at the top of the agent chat: the user watches the agent (and its cursor) work. Pure
 * rules here, unit-tested; the pane below binds [ChatPlayerView] as the IN_APP player through the host
 * ([DesktopHostExtras.setChatPlayerSurface], one player at a time with Home's).
 *
 *: no "Show desktop here" placeholder. While the Agent tab is on screen with the DeX desktop or
 * Auto chip, a running desktop shows here by itself ([autoShow]), also right after the popup's "Open Backstage" (the
 * keep-in-background rule is Home's; the chat player wins while the Agent tab is visible). Phone mode hides the pane.
 */
object DesktopPaneRules {
    enum class Content { PLAYER, STATUS, START }

    const val TITLE = "DeX desktop"
    const val START = "Start the desktop"
    const val COLLAPSE = "Hide the desktop view"
    const val EXPAND = "Show the desktop view"
    const val SHOW_HERE = "Show it in the chat"

    /**
     * Only for a desktop task (the DeX desktop or Auto chip) on a build with the desktop; never with Phone, nor while the
     * running task works on the phone ([com.eggman.backstage.shell.ShellLifecycle.phoneMode]).
   */
    fun visible(target: Target, hostPresent: Boolean, phoneMode: Boolean = false): Boolean =
        hostPresent && target != Target.PHONE && !phoneMode

    /** IN_APP: the picture. No session: a Start button. Otherwise a status line while it moves here (or stays away). */
    fun content(presentation: Presentation): Content = when (presentation) {
        Presentation.NONE -> Content.START
        Presentation.IN_APP -> Content.PLAYER
        Presentation.MINI, Presentation.HIDDEN, Presentation.FULL -> Content.STATUS
    }

    /**
     * The chat player takes a running desktop by itself while the Agent tab is on screen ([onScreen]): the popup and the
     * background come here. Full screen is the user's explicit choice and is left alone, and so is a popup or background
     * the user picked with this pane's own buttons ([userMovedIt]) until they leave the tab or show it here again.
   */
    fun autoShow(presentation: Presentation, onScreen: Boolean, userMovedIt: Boolean): Presentation? =
        if (onScreen && !userMovedIt && (presentation == Presentation.MINI || presentation == Presentation.HIDDEN)) Presentation.IN_APP
        else null

    fun status(presentation: Presentation): String = when (presentation) {
        Presentation.IN_APP -> "live"
        Presentation.MINI -> "in the popup"
        Presentation.HIDDEN -> "in the background"
        Presentation.FULL -> "full screen"
        Presentation.NONE -> "not running"
    }
}

@Composable
internal fun DesktopPane(vm: AgentViewModel, modifier: Modifier = Modifier) {
    val host = remember { AgentAccess.host() }
    val target by vm.target.collectAsState()
    val phoneMode by ShellLifecycle.phoneMode.collectAsState()
    if (!DesktopPaneRules.visible(target, host != null, phoneMode) || host == null) return
    fun now(): Presentation = runCatching { if (host.sessionActive()) host.presentation() else Presentation.NONE }.getOrDefault(Presentation.NONE)
    var presentation by remember { mutableStateOf(now()) }
    var geometry by remember { mutableStateOf(runCatching { host.geometry() }.getOrNull()) }
    DisposableEffect(host) {
        val listener = object : DesktopHost.Listener {
            override fun onDesktopChanged(g: DesktopHost.Geometry?, p: Presentation) {
                geometry = g
                presentation = if (g == null) Presentation.NONE else p
            }
        }
        host.addListener(listener)
        // A change between the first read and the listener's registration.
        presentation = now()
        geometry = runCatching { host.geometry() }.getOrNull()
        onDispose { host.removeListener(listener) }
    }
    var collapsed by remember { mutableStateOf(vm.settings.agentDesktopCollapsed) }
    // The Agent tab is on screen (started): only then may the chat take the desktop.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val onScreen = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    var userMovedIt by remember { mutableStateOf(false) }
    LaunchedEffect(onScreen) { if (!onScreen) userMovedIt = false }
    fun present(p: Presentation) {
        if (p == Presentation.IN_APP) ShellLifecycle.clearKeepBackground()
        SurfaceScope.launch { runCatching { host.setPresentation(p) } }
    }
    fun moveAway(p: Presentation) { userMovedIt = true; present(p) }
    LaunchedEffect(presentation, onScreen, userMovedIt) {
        DesktopPaneRules.autoShow(presentation, onScreen, userMovedIt)?.let(::present)
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp / 3).dp
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${DesktopPaneRules.TITLE} · ${DesktopPaneRules.status(presentation)}", Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge)
            if (presentation != Presentation.NONE) {
                if (presentation != Presentation.IN_APP) {
                    IconButton(onClick = { userMovedIt = false; present(Presentation.IN_APP) }) { Icon(Icons.Default.PlayArrow, DesktopPaneRules.SHOW_HERE) }
                }
                IconButton(onClick = { present(Presentation.FULL) }) { Icon(Icons.Default.Fullscreen, "Full screen") }
                IconButton(onClick = { moveAway(Presentation.MINI) }) { Icon(Icons.Default.PictureInPictureAlt, "Pop out") }
                IconButton(onClick = { moveAway(Presentation.HIDDEN) }) { Icon(Icons.Default.Headphones, "Play in background") }
            }
            IconButton(onClick = { collapsed = !collapsed; vm.settings.agentDesktopCollapsed = collapsed }) {
                Icon(if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    if (collapsed) DesktopPaneRules.EXPAND else DesktopPaneRules.COLLAPSE)
            }
        }
        if (collapsed) return@Column
        val g = geometry
        val aspect = if (g != null && g.width > 0 && g.height > 0) g.width.toFloat() / g.height else 16f / 9f
        Box(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).aspectRatio(aspect, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when (DesktopPaneRules.content(presentation)) {
                DesktopPaneRules.Content.PLAYER -> ChatPlayer(host as? DesktopHostExtras, g?.width ?: 0, g?.height ?: 0)
                DesktopPaneRules.Content.STATUS -> Text(DesktopPaneRules.status(presentation).replaceFirstChar { it.uppercase() },
                    color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                DesktopPaneRules.Content.START -> TextButton(onClick = {
                    SurfaceScope.launch { runCatching { host.startDesktop(Presentation.IN_APP) } }
                }) { Text(DesktopPaneRules.START) }
            }
        }
    }
}

/**
 * The player: [ChatPlayerView] fills the box, fits the whole desktop frame into it (letterboxed) and forwards touches
 * through the same fitted rectangle. Its Surface is handed to the host while it exists.
 */
@Composable
private fun ChatPlayer(extras: DesktopHostExtras?, desktopWidth: Int, desktopHeight: Int) {
    if (extras == null) return
    AndroidView(
        factory = { context -> ChatPlayerView(context, extras).apply { setDesktopSize(desktopWidth, desktopHeight) } },
        modifier = Modifier.fillMaxSize(),
        update = { view -> view.setDesktopSize(desktopWidth, desktopHeight) },
    )
}
