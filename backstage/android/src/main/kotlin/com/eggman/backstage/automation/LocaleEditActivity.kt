package com.eggman.backstage.automation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.ui.ToggleRow
import com.eggman.backstage.ui.settings.AutomationDocs
import com.eggman.backstage.ui.settings.SettingsStores
import com.eggman.backstage.ui.theme.BackstageTheme

/** Locale/Tasker/MacroDroid "edit setting" screen: configures one Backstage task action. */
class LocaleEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val secret = SettingsStores.get(this).pluginSigningSecret
        // Only a bundle this phone signed is shown again; anything else starts empty (a foreign app must not be able to
        // hand the user a prepared prompt to sign, such as a bare "%x" that would later run any text).
        val existing = (PluginBundleCodec.decode(
            SafeExtras.read(SafeExtras.bundleExtra(intent, PluginProtocol.EXTRA_BUNDLE), PluginBundleCodec.KEYS), secret,
        ) as? PluginBundleCodec.Decoded.Ok)?.config
        val caller = callingActivity?.packageName ?: callingPackage
        val host = caller?.let { PluginBundleCodec.knownHosts[it] }

        setContent {
            BackstageTheme {
                Surface(Modifier.fillMaxSize()) {
                    var prompt by remember { mutableStateOf(existing?.prompt.orEmpty()) }
                    var target by remember { mutableStateOf(existing?.target ?: Target.AUTO) }
                    var plan by remember { mutableStateOf(existing?.planMode) }
                    var wait by remember { mutableStateOf(existing?.waitForResult ?: true) }
                    var timeout by remember { mutableStateOf(existing?.timeoutTemplate ?: (existing?.timeoutMin ?: PluginConfig.DEFAULT_TIMEOUT_MIN).toString()) }
                    var bubble by remember { mutableStateOf(existing?.showBubble ?: false) }
                    var confirmed by remember { mutableStateOf(false) }
                    val wildcard = PluginBundleCodec.isWildcard(prompt.trim())
                    val timeoutIsVariable = timeout.trim().startsWith("%")
                    val timeoutOk = timeoutIsVariable || PluginConfig.parseMinutes(timeout) != null
                    val needsConfirm = host == null || wildcard

                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Backstage task", style = MaterialTheme.typography.titleLarge)
                        if (host == null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text("Opened by ${caller ?: "an unknown app"}, which is not a known automation app. Only save if you set this up yourself.",
                                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        } else Text("Configuring an action for $host.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), minLines = 3,
                            label = { Text("What should the agent do?") },
                            supportingText = { Text("Variables such as %SMSRB or %clip are filled in by the automation app") })
                        Text("Run on", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Target.entries.forEach { t -> FilterChip(target == t, { target = t }, { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                        }
                        Text("Plan Mode", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(null to "App setting", true to "On", false to "Off").forEach { (v, l) -> FilterChip(plan == v, { plan = v }, { Text(l) }) }
                        }
                        ToggleRow("Wait for the result", "Tasker pauses until the task ends and receives %success, %result and %error", wait) { wait = it }
                        ToggleRow("Show the bubble while it runs",
                            "Removed again when the task ends. Needs \"display over other apps\"; on Android 15 and later only " +
                                "while the Backstage bubble is already on screen", bubble) { bubble = it }
                        OutlinedTextField(timeout, { timeout = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Timeout (minutes)") }, isError = !timeoutOk,
                            supportingText = { Text("1 to ${PluginConfig.MAX_TIMEOUT_MIN}, or a variable such as %wait") })
                        if (wildcard) Text("The task is only a variable: whatever text it holds will be run as the task.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (needsConfirm) ToggleRow("I set this action up myself", null, confirmed) { confirmed = it }
                        Text(AutomationDocs.PLUGIN_HELP, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { setResult(Activity.RESULT_CANCELED); finish() }) { Text("Cancel") }
                            Button(enabled = prompt.isNotBlank() && timeoutOk && (confirmed || !needsConfirm), onClick = {
                                val t = timeout.trim()
                                save(PluginConfig(prompt.trim().take(BroadcastApi.MAX_PROMPT), target, plan, wait,
                                    timeoutMin = PluginConfig.parseMinutes(t) ?: PluginConfig.DEFAULT_TIMEOUT_MIN, showBubble = bubble,
                                    timeoutTemplate = t.takeIf { timeoutIsVariable }))
                            }) { Text("Save") }
                        }
                    }
                }
            }
        }
    }

    private fun save(config: PluginConfig) {
        val secret = SettingsStores.get(this).pluginSigningSecret
        val values = PluginBundleCodec.encode(config, secret)
        val result = Intent()
            .putExtra(PluginProtocol.EXTRA_BUNDLE, values.toBundle())
            .putExtra(PluginProtocol.EXTRA_BLURB, config.blurb)
        if (config.waitForResult) {
            result.putExtra(PluginProtocol.EXTRA_REQUESTED_TIMEOUT, config.requestedTimeoutMs)
            result.putExtra(PluginProtocol.EXTRA_RELEVANT_VARIABLES, PluginProtocol.relevantVariables)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
