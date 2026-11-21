package com.eggman.backstage.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eggman.backstage.ui.Section

private val LocalSettingsCard = staticCompositionLocalOf<SettingsGroup?> { null }

/**
 * One card per group, collapsed at first so the whole list fits at a glance; tapping the title opens it. A search
 * result opens its group's card directly. Embedded sections get consistent subheadings.
 */
@Composable
internal fun SettingsCard(group: SettingsGroup, destination: SettingsGroup?, content: @Composable () -> Unit) {
    if (destination != null && destination != group) return
    var open by rememberSaveable(group.name) { mutableStateOf(false) }
    val expanded = open || destination == group
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = destination != group, onClickLabel = if (expanded) "Collapse" else "Expand") { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium)
                    Text(group.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (destination != group) Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
            }
            if (expanded) CompositionLocalProvider(LocalSettingsCard provides group, content = content)
        }
    }
}

/** Sections retain their original cards when displayed independently (including their existing UI tests). */
@Composable
internal fun SettingsSection(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    val group = LocalSettingsCard.current
    if (group == null) {
        Section(title, subtitle, content)
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != group.title) Text(title, style = MaterialTheme.typography.labelLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}
