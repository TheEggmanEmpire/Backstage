package com.eggman.backstage.ui.desktop

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eggman.backstage.ui.Section

/**
 * Dextop's own app UI, opened from inside Backstage: Backstage is ONE app with ONE launcher icon (the agent), so the
 * desktop's settings (display and DeX options, workspaces, Linux, its first-time and privilege setup) are reached
 * from the Desktop tab and from Settings. The library never links against Dextop: the activity is named, not
 * referenced, and the entry is hidden when it is not in the app (standalone library builds, tests).
 */
object DesktopSettings {
    /**
     * Dextop's launcher trampoline. backstage/overlay removes its LAUNCHER filter (one icon) and keeps the activity:
     * started from Backstage on the phone it opens Dextop's phone UI in Dextop's own task; started on the desktop
     * display it opens Dextop's desktop UI there.
   */
    const val ACTIVITY = "moe.n4tsu.dextop.DextopLauncherActivity"
    const val LABEL = "Desktop settings"
    const val DESCRIPTION = "Dextop's own settings: display and DeX options, workspaces, Linux apps, and its first-time " +
        "and privilege setup."

    fun intent(context: Context): Intent = Intent().setClassName(context.packageName, ACTIVITY).apply {
        // From an activity the trampoline opens on that activity's display; other contexts need a task of their own.
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The Dextop UI is part of this app (always in Backstage builds). */
    fun available(context: Context): Boolean =
        runCatching { context.packageManager.resolveActivity(intent(context), 0) != null }.getOrDefault(false)

    /** Opens Dextop's UI; false when it is not part of this app or the start is refused. */
    fun open(context: Context): Boolean {
        if (!available(context)) return false
        return runCatching { context.startActivity(intent(context)) }.isSuccess
    }
}

/** "Desktop settings" button; nothing when Dextop's UI is not part of the app. */
@Composable
fun DesktopSettingsButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val available = remember(context) { DesktopSettings.available(context) }
    if (!available) return
    OutlinedButton(onClick = { DesktopSettings.open(context) }, modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(DesktopSettings.LABEL)
        }
    }
}

/** Section with the "Desktop settings" button (Desktop tab, Settings); nothing when Dextop's UI is not part of the app. */
@Composable
fun DesktopSettingsSection() {
    val context = LocalContext.current
    if (!remember(context) { DesktopSettings.available(context) }) return
    Section(DesktopSettings.LABEL, DesktopSettings.DESCRIPTION) { DesktopSettingsButton() }
}
