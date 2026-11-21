package com.eggman.backstage.ui.report

import android.content.Context
import android.content.Intent
import android.net.Uri

/** No network transport: only an explicit UI action opens another app, which still must send the message. */
object AiReportIntents {
    fun create(report: AiContentReport, address: String = ""): Intent {
        require(ReportDestinations.validAddress(address)) { "Invalid report email address" }
        val intent = if (address.isBlank()) Intent(Intent.ACTION_SEND).setType("text/plain")
        else Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
        return intent.putExtra(Intent.EXTRA_SUBJECT, "Backstage AI response report")
            .putExtra(Intent.EXTRA_TEXT, report.shareText())
    }

    /** A chooser opening is not proof of submission/delivery. Do not update a report to a fictional 'sent' state. */
    fun open(context: Context, report: AiContentReport, address: String = "") {
        context.startActivity(Intent.createChooser(create(report, address), "Share AI response report")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
