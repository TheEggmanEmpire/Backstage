package com.eggman.backstage.ui.report

import com.eggman.backstage.BuildConfig
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Explicit in-app submission. Retrying the same saved report preserves its idempotency key. */
internal object PlayReportDelivery {
    val configured: Boolean get() = BuildConfig.PLAY_REPORT_ENDPOINT.isNotEmpty()

    fun send(report: AiContentReport) {
        check(configured) { "Report delivery is unavailable in this build." }
        val endpoint = URL(BuildConfig.PLAY_REPORT_ENDPOINT)
        require(endpoint.protocol == "https" && endpoint.host.isNotEmpty() &&
            endpoint.userInfo == null && endpoint.ref == null) { "Invalid report endpoint" }
        val payload = AiContentReportCodec.encode(report).toString().toByteArray(Charsets.UTF_8)
        val connection = endpoint.openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Idempotency-Key", report.id)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            check(status in 200..299) { "Report endpoint returned HTTP $status." }
        } finally {
            connection.disconnect()
        }
    }
}
