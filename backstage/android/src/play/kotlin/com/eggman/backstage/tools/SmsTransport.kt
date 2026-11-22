package com.eggman.backstage.tools

import android.content.Context
import android.content.Intent
import android.net.Uri

internal object SmsTransport {
    fun canOpen(context: Context) = SmsDraftPolicy.canOpen(context)

    fun available(context: Context): Boolean {
        val pm = context.packageManager
        return smsDraft("").resolveActivity(pm) != null
    }

    private fun smsDraft(number: String) = Intent(
        Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun send(context: Context, number: String, message: String, canOpenDraft: (Context) -> Boolean): SmsSendResult = runCatching {
        val draft = smsDraft(number).putExtra("sms_body", message)
        if (draft.resolveActivity(context.packageManager) == null) return SmsSendResult.FAILED
        if (canOpenDraft(context)) {
            context.startActivity(draft)
            SmsSendResult.DRAFT_OPENED
        } else if (SmsDraftPolicy.notify(context, draft)) {
            SmsSendResult.DRAFT_READY
        } else SmsSendResult.FAILED
    }.getOrDefault(SmsSendResult.FAILED)
}
