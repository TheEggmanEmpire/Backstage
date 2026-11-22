package com.eggman.backstage.tools

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.telephony.SmsManager

internal object SmsTransport {
    fun canOpen(context: Context) = false

    fun available(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) &&
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }

    fun send(context: Context, number: String, message: String, canOpenDraft: (Context) -> Boolean): SmsSendResult = runCatching {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return SmsSendResult.FAILED
        // If the grant is revoked, report failure. Never replay an uncertain direct send as another action.
        val sms = context.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(message)
        if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null) else sms.sendTextMessage(number, null, message, null, null)
        SmsSendResult.SENT
    }.getOrDefault(SmsSendResult.FAILED)
}
