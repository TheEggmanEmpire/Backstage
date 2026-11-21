package com.eggman.backstage.ui.licenses

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.eggman.backstage.R
import com.eggman.backstage.ui.Section

/** The bundled GPL text (offline), or null when the resource cannot be read. */
fun licenseText(context: Context): String? = runCatching {
    context.resources.openRawResource(R.raw.backstage_license_gpl3).bufferedReader().use { it.readText() }
}.getOrNull()

/** The native-library notices generated into the APK, or null when the asset is missing (e.g. unit tests). */
fun thirdPartyNotices(context: Context): String? = runCatching {
    context.assets.open(Licenses.NOTICES_ASSET).bufferedReader().use { it.readText() }
}.getOrNull()

/** This app's version name (the release whose tag is the corresponding source). */
fun appVersionName(context: Context): String? = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull()

@Composable
fun LicensesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun open(url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    var showLicense by remember { mutableStateOf(false) }
    var showNotices by remember { mutableStateOf(false) }
    val version = remember { appVersionName(context) }
    val revision = com.eggman.backstage.BuildConfig.SOURCE_REVISION
    val sourceUrl = com.eggman.backstage.BuildConfig.SOURCE_URL
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Open-source licenses", style = MaterialTheme.typography.titleLarge)
        Section("Backstage", Licenses.APP_LICENSE) {
            Text(Licenses.COPYRIGHT, style = MaterialTheme.typography.bodyMedium)
            Text(Licenses.APP_NOTICE, style = MaterialTheme.typography.bodySmall)
            Text(Licenses.sourceLine(version, revision, sourceUrl), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open(Licenses.sourceTreeUrl(revision, sourceUrl)) }) { Text("Open the source code of this build") }
            TextButton(onClick = { showLicense = !showLicense }) { Text(if (showLicense) "Hide the GPL-3.0" else "Read the GPL-3.0") }
            if (showLicense) {
                val text = remember { licenseText(context) }
                if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                else TextButton(onClick = { open("https://www.gnu.org/licenses/gpl-3.0.html") }) { Text("gnu.org/licenses/gpl-3.0") }
            }
        }
        Section(Licenses.desktop.name, Licenses.desktop.license) {
            Text(Licenses.desktop.note, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open(Licenses.desktop.url) }) { Text(Licenses.desktop.url) }
        }
        Section("Third-party components") {
            Licenses.thirdParty.forEach { c ->
                Text(c.name, style = MaterialTheme.typography.titleSmall)
                Text(c.license + if (c.note.isNotEmpty()) " — ${c.note}" else "", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { open(c.url) }) { Text(c.url) }
            }
        }
        Section("Closed-source components", "Not free software; distributed under their owner's terms") {
            Licenses.closedSource.forEach { c ->
                Text(c.name, style = MaterialTheme.typography.titleSmall)
                Text(c.license + if (c.note.isNotEmpty()) " — ${c.note}" else "", style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("Native libraries and license texts", "Every native library in this app, its license and the full license texts") {
            TextButton(onClick = { showNotices = !showNotices }) { Text(if (showNotices) "Hide the notices" else "Show the notices") }
            if (showNotices) {
                val sections = remember { thirdPartyNotices(context)?.let(Licenses::noticeSections) }
                if (sections == null) Text("The notices file is missing from this build; see NOTICE.md in the source.", style = MaterialTheme.typography.bodySmall)
                else sections.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
        Text(Licenses.SERVICES_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
