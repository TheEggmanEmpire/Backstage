package com.eggman.backstage.ui.report

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeveloperReportButton(
    report: AiContentReport,
    busy: Boolean,
    setBusy: (Boolean) -> Unit,
    setError: (String?) -> Unit,
) {
    var sent by remember(report.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    when {
        sent -> Text("Report submitted to the developer.")
        !PlayReportDelivery.configured -> Text("Developer reporting is unavailable in this build. Your report is saved here.")
        else -> TextButton(onClick = {
            scope.launch {
                setBusy(true)
                setError(null)
                try {
                    withContext(Dispatchers.IO) { PlayReportDelivery.send(report) }
                    sent = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    setError("Could not confirm delivery. Your report is saved for retry.")
                } finally {
                    setBusy(false)
                }
            }
        }, enabled = !busy) { Text(if (busy) "Sending…" else "Send to developer") }
    }
}
