package com.eggman.backstage.ui.desktop

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.ui.Section
import com.eggman.backstage.ui.ToggleRow
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Desktop panel: start/stop the Dextop desktop, FULL/MINI/HIDDEN, live preview with tap-through. */
@Composable
fun DesktopScreen(modifier: Modifier = Modifier) {
    // Remembered (a new flow per recomposition would re-subscribe every time) and collected only while the
    // activity is started: the process outlives it, and the poll must not run behind the user's back.
    val statusFlow = remember { desktopStatusFlow(1500) }
    val status by statusFlow.collectAsStateWithLifecycle(initialValue = DesktopStatus.of(AgentAccess.host()))
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf(true) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(status.active, live, lifecycle) {
        if (!status.active || !live) return@LaunchedEffect
        runPreviewLoop(lifecycle, FRAME_INTERVAL_MS, capture = {
            AgentAccess.host()?.let { host -> withContext(Dispatchers.IO) { runCatching { host.captureFrame() }.getOrNull() } }
        }) { frame = it }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Desktop", style = MaterialTheme.typography.titleLarge)
        if (!status.hostAvailable) {
            Section("Desktop not available", "This build runs without Dextop. The agent is gated to the DeX desktop by default; switch the target to Phone to use it on the phone screen.") {}
            return@Column
        }
        Section("Session", if (status.active) "Running · ${DesktopStatus.label(status.presentation)}" +
            (status.geometry?.let { " · ${it.width}×${it.height} @${it.densityDpi}dpi" } ?: "") else "Not running") {
            if (!status.privileged) Text("No privileged access yet: start Stellar (with Shizuku compatibility), Shizuku, or set up the wireless-debugging fallback " +
                "in ${DesktopSettings.LABEL}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (!status.active) {
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true
                    scope.launch {
                        val g = withContext(Dispatchers.Default) { AgentAccess.host()?.startDesktop(Presentation.FULL) }
                        message = if (g == null) "The desktop did not start (timeout). Check its setup in ${DesktopSettings.LABEL}." else null
                        busy = false
                    }
                }) { Text("Open full screen (work in it yourself)") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!status.active) {
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val g = withContext(Dispatchers.Default) { AgentAccess.host()?.startDesktop(Presentation.MINI) }
                            message = if (g == null) "The desktop did not start (timeout). Check its setup in ${DesktopSettings.LABEL}." else null
                            busy = false
                        }
                    }) { Text("Start in mini window") }
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val g = withContext(Dispatchers.Default) { AgentAccess.host()?.startDesktop(Presentation.HIDDEN) }
                            message = if (g == null) "The desktop did not start (timeout)." else null
                            busy = false
                        }
                    }) { Text("Start hidden") }
                } else {
                    OutlinedButton(onClick = { scope.launch(Dispatchers.Default) { AgentAccess.host()?.stopDesktop() } }) { Text("Stop desktop") }
                }
            }
            if (status.active) {
                Text("Show the desktop as", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    status.presentationChoices.forEach { p ->
                        FilterChip(selected = status.presentation == p, onClick = {
                            scope.launch(Dispatchers.Default) {
                                val ok = AgentAccess.host()?.setPresentation(p) == true
                                if (!ok) message = "Could not switch to ${DesktopStatus.label(p)}"
                            }
                        }, label = { Text(DesktopStatus.label(p)) })
                    }
                }
                if (!status.direct) Text("Mini window and background mode need Dextop's direct display mode.",
                    style = MaterialTheme.typography.bodySmall)
                MiniShapeChips()
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (status.active) Section("Live view", "Tap the preview to tap the desktop.") {
            ToggleRow("Live preview", "Refreshes about twice a second while this screen is open", live) { live = it }
            val bmp = frame
            val g = status.geometry
            Box(
                Modifier.fillMaxWidth().aspectRatio(g?.let { it.width.toFloat() / it.height } ?: (16f / 9f))
                    .background(Color.Black)
                    .onSizeChanged { viewSize = it }
                    .pointerInput(bmp, g) {
                        detectTapGestures(
                            onTap = { off ->
                                val b = bmp ?: return@detectTapGestures
                                val geo = g ?: return@detectTapGestures
                                PreviewMapper.toDisplay(off.x, off.y, viewSize.width.toFloat(), viewSize.height.toFloat(),
                                    b.width, b.height, geo.width, geo.height)?.let { (x, y) ->
                                    scope.launch(Dispatchers.Default) { AgentAccess.host()?.tap(x, y) }
                                }
                            },
                            onLongPress = { off ->
                                val b = bmp ?: return@detectTapGestures
                                val geo = g ?: return@detectTapGestures
                                PreviewMapper.toDisplay(off.x, off.y, viewSize.width.toFloat(), viewSize.height.toFloat(),
                                    b.width, b.height, geo.width, geo.height)?.let { (x, y) ->
                                    scope.launch(Dispatchers.Default) { AgentAccess.host()?.rightClick(x, y) }
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) Image(bmp.asImageBitmap(), "Desktop preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text("No frame yet", color = Color.White)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("back" to "Back", "home" to "Home", "recents" to "Windows").forEach { (a, label) ->
                    OutlinedButton(onClick = { scope.launch(Dispatchers.Default) { AgentAccess.host()?.globalAction(a) } }) { Text(label) }
                }
            }
        }
        // Dextop's own UI lives inside this app (one launcher icon): its settings open from here and from Settings.
        DesktopSettingsSection()
    }
}

private const val FRAME_INTERVAL_MS = 500L
