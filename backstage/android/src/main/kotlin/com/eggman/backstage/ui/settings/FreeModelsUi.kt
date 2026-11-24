package com.eggman.backstage.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eggman.backstage.llm.FreeModels

/** "1M", "256K", "131K": a context window as the model menu shows it. */
internal fun ctxLabel(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${(tokens / 100_000) / 10.0}".removeSuffix(".0") + "M"
    tokens % 1024 == 0L && tokens % 1000 != 0L -> "${tokens / 1024}K"
    else -> "${Math.round(tokens / 1000.0)}K"
}

/** Context window and an eye (vision) or crossed-out eye (text only), as far as the model list reported them. */
@Composable
internal fun ModelCapsHint(caps: FreeModels.Caps) {
    if (caps.context == null && caps.vision == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        caps.context?.takeIf { it > 0 }?.let { Text("${ctxLabel(it)} ctx", style = MaterialTheme.typography.labelSmall) }
        caps.vision?.let { v ->
            Icon(if (v) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (v) "vision" else "text only",
                Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The Free models box: on/off switch, privacy note and Free Auto's live order. */
@Composable
internal fun FreeModelsBox(store: BackstageSettingsStore) {
    var on by remember(store) { mutableStateOf(store.freeModelsEnabled) }
    var order by remember(store) { mutableStateOf(store.freeAutoRank()) }
    DisposableEffect(store) {
        val stop = store.observe { key ->
            if (key == "free_enabled") on = store.freeModelsEnabled
            if (key == "models_enabled_FREE" || key == "model_FREE" || key.startsWith("ctxdet_FREE_") || key.startsWith("cap_vision_FREE_")) order = store.freeAutoRank()
        }
        onDispose { stop() }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Free models", style = MaterialTheme.typography.labelLarge)
            Text(if (on) "On: no account or key needed." else "Off: free models are not offered.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(on, { on = it; store.freeModelsEnabled = it })
    }
    Text("These providers may log prompts and use them for training. Don't send sensitive data.",
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (!on) return
    Text("Free Auto order", style = MaterialTheme.typography.labelLarge)
    Text("Score = intelligence + context (2 per doubling above 32K, max 10) + 5 for vision. Intelligence scores ship with each " +
        "app update (Artificial Analysis Intelligence Index, ${FreeModels.INTEL_AS_OF}); a model without one is placed at the median, " +
        "so context and vision decide. A failed request moves on to the next model, up to ${FreeModels.RETRIES} times. Requests with " +
        "screenshots skip text-only models. A rate-limited model sits out for a minute (or as long as the server asks).",
        style = MaterialTheme.typography.bodySmall)
    if (order.isEmpty()) Text("Enable at least one free model below.", style = MaterialTheme.typography.bodySmall)
    order.forEachIndexed { i, c ->
        val intel = if (c.estimated) "~${fmt(c.intelligence)} (no score yet: median)" else fmt(c.intelligence)
        val parts = listOfNotNull("intelligence $intel", c.context?.let { "${ctxLabel(it)} ctx" }, "vision".takeIf { c.vision == true })
        Text("${i + 1}. ${c.model} — ${fmt(c.score)} (${parts.joinToString(" · ")})", style = MaterialTheme.typography.bodySmall)
    }
}

private fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, d)
