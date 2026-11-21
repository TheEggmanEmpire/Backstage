package com.eggman.backstage.ui.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.host.extras

/**
 * Mini window shape toggle: rounded rectangle (default; grip bar, taps reach the desktop) or circle (a bubble
 * viewport showing the centre of the desktop; drag, pinch to resize, tap for controls). The host persists the
 * choice and applies it at once; nothing is shown without the Dextop glue.
 */
@Composable
fun MiniShapeChips() {
    val extras = DesktopHosts.extras() ?: return
    var shape by remember { mutableStateOf(runCatching { extras.miniShape() }.getOrDefault(DesktopHostExtras.SHAPE_ROUNDED)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Mini window shape", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DesktopHostExtras.SHAPE_ROUNDED to "Rounded", DesktopHostExtras.SHAPE_CIRCLE to "Circle").forEach { (value, label) ->
                FilterChip(selected = shape == value, onClick = {
                    if (runCatching { extras.setMiniShape(value) }.getOrDefault(false)) shape = value
                }, label = { Text(label) })
            }
        }
        Text(
            if (shape == DesktopHostExtras.SHAPE_CIRCLE) "Circle: shows the centre of the desktop. Drag to move, pinch to resize, tap for controls."
            else "Rounded: the whole desktop with a grip bar; taps on it reach the desktop.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
