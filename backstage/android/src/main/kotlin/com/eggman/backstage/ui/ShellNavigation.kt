package com.eggman.backstage.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.eggman.backstage.R
import com.eggman.backstage.entry.EntryRouter.Screen
import com.eggman.backstage.shell.ShellPage

private data class AiTab(val screen: Screen, val label: String)
private val aiTabs = listOf(AiTab(Screen.CHAT, "Chat"), AiTab(Screen.HISTORY, "History"),
    AiTab(Screen.SCHEDULES, "Automate"), AiTab(Screen.SETTINGS, "Agent settings"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiTabs(screen: Screen, onSelect: (Screen) -> Unit) {
    SecondaryTabRow(selectedTabIndex = aiTabs.indexOfFirst { it.screen == screen }.coerceAtLeast(0)) {
        aiTabs.forEach { tab ->
            Tab(selected = screen == tab.screen, onClick = { onSelect(tab.screen) }, text = { Text(tab.label, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) })
        }
    }
}

/** Same labels, order and 24-unit Material / PenguinIcon glyphs as Flutter's shell. Agent stays selected. */
@Composable
internal fun ShellNavigation(wide: Boolean, onSelect: (ShellPage) -> Unit) {
    val labels = listOf(stringResource(R.string.bs_nav_home), "Agent", stringResource(R.string.bs_nav_linux),
        stringResource(R.string.bs_nav_settings))
    val pages = listOf(ShellPage.HOME, null, ShellPage.LINUX, ShellPage.SETTINGS)
    val select: (Int) -> Unit = { index -> pages[index]?.let(onSelect) }
    if (wide) NavigationRail(Modifier.fillMaxHeight()) {
        labels.forEachIndexed { index, label ->
            NavigationRailItem(alwaysShowLabel = true, selected = index == 1, onClick = { select(index) },
                icon = { ShellIcon(index, index == 1) }, label = { Text(label) })
        }
    } else NavigationBar {
        labels.forEachIndexed { index, label ->
            NavigationBarItem(selected = index == 1, onClick = { select(index) },
                icon = { ShellIcon(index, index == 1) }, label = { Text(label) })
        }
    }
}

@Composable
private fun ShellIcon(index: Int, selected: Boolean) {
    when (index) {
        0 -> Icon(if (selected) Icons.Rounded.Home else Icons.Outlined.Home, null)
        1 -> Icon(if (selected) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome, null)
        2 -> Icon(painterResource(if (selected) R.drawable.bs_nav_penguin_filled else R.drawable.bs_nav_penguin), null)
        else -> Icon(if (selected) Icons.Rounded.Tune else Icons.Outlined.Tune, null)
    }
}
