package com.eggman.backstage.tools

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import com.eggman.backstage.a11y.AgentAccessibility
import com.eggman.backstage.privileged.Privileged
import org.json.JSONObject

/** Android implementation of [DeviceControl]; settings writes fall back to the privileged shell. */
class AndroidDeviceControl(
    private val context: Context,
) : DeviceControl {
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun canWriteSettings() = Settings.System.canWrite(context) || Privileged.available()
    private val pm get() = context.packageManager

    override fun has(capability: String): Boolean = runCatching {
        when (capability) {
            "volume", "ringer", "wifi", "bluetooth" -> true
            "dnd" -> context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted || Privileged.available()
            "brightness", "auto_rotate", "screen_timeout" -> canWriteSettings()
            "flashlight" -> pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
            "contacts" -> granted(Manifest.permission.READ_CONTACTS)
            "sms" -> pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) &&
                granted(Manifest.permission.SEND_SMS)
            "call" -> granted(Manifest.permission.CALL_PHONE) && pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
            "location" -> granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> false
        }
    }.getOrDefault(false)

    private val streams = mapOf("media" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING,
        "notification" to AudioManager.STREAM_NOTIFICATION, "alarm" to AudioManager.STREAM_ALARM)

    override fun volumes(): Map<String, Int> = streams.mapValues { (_, s) ->
        val max = audio.getStreamMaxVolume(s).coerceAtLeast(1)
        audio.getStreamVolume(s) * 100 / max
    }

    override fun setVolume(stream: String, percent: Int): Boolean = runCatching {
        val s = streams.getValue(stream)
        audio.setStreamVolume(s, (audio.getStreamMaxVolume(s) * percent + 50) / 100, 0)
        true
    }.getOrDefault(false)

    override fun setRingerMode(mode: String): Boolean = runCatching {
        audio.ringerMode = when (mode) { "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; "silent" -> AudioManager.RINGER_MODE_SILENT; else -> AudioManager.RINGER_MODE_NORMAL }
        true
    }.getOrDefault(false)

    override fun setFlashlight(on: Boolean): Boolean = runCatching {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        cm.setTorchMode(id, on)
        true
    }.getOrDefault(false)

    override fun wifiStatus(): JSONObject {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        return JSONObject().put("enabled", runCatching { wm.isWifiEnabled }.getOrDefault(false))
            .put("connected", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            .put("internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }

    override fun bluetoothStatus(): JSONObject = JSONObject().put("enabled",
        runCatching { context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false))

    override fun brightness(): JSONObject {
        val cr = context.contentResolver
        val v = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(-1)
        val auto = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }.getOrDefault(false)
        return JSONObject().put("percentage", if (v < 0) JSONObject.NULL else (v * 100 + 127) / 255).put("auto", auto)
    }

    private suspend fun putSystem(key: String, value: Int): Boolean {
        if (Settings.System.canWrite(context)) return runCatching { Settings.System.putInt(context.contentResolver, key, value) }.getOrDefault(false)
        if (!Privileged.available()) return false
        return Privileged.exec("settings", "put", "system", key, value.toString()).ok
    }

    override suspend fun setBrightness(percent: Int?, auto: Boolean?): Boolean {
        var ok = true
        auto?.let { ok = putSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, if (it) 1 else 0) && ok }
        percent?.let {
            if (auto == null) ok = putSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, 0) && ok
            ok = putSystem(Settings.System.SCREEN_BRIGHTNESS, (it * 255 + 50) / 100) && ok
        }
        return ok
    }

    override suspend fun setScreenTimeout(seconds: Int) = putSystem(Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
    override fun autoRotate() = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1 }.getOrDefault(false)
    override suspend fun setAutoRotate(on: Boolean) = putSystem(Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)

    override fun dnd(): String = when (context.getSystemService(NotificationManager::class.java).currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority_only"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms_only"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
        else -> "off"
    }

    override suspend fun setDnd(mode: String): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) {
            return runCatching {
                nm.setInterruptionFilter(when (mode) {
                    "priority_only" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    "alarms_only" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                    "total_silence" -> NotificationManager.INTERRUPTION_FILTER_NONE
                    else -> NotificationManager.INTERRUPTION_FILTER_ALL
                })
                true
            }.getOrDefault(false)
        }
        if (!Privileged.available()) return false
        val arg = when (mode) { "priority_only" -> "priority"; "alarms_only" -> "alarms"; "total_silence" -> "on"; else -> "off" }
        return Privileged.exec("cmd", "notification", "set_dnd", arg).ok
    }

    override fun searchContacts(query: String, max: Int): List<Contact> {
        val cr = context.contentResolver
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        val ids = ArrayList<Pair<Long, String>>()
        cr.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
            while (c.moveToNext() && ids.size < max) ids += c.getLong(0) to c.getString(1).orEmpty()
        }
        return ids.map { (id, name) ->
            val phones = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString()), null)?.use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }.orEmpty()
            val emails = cr.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?", arrayOf(id.toString()), null)?.use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }.orEmpty()
            Contact(name, phones.distinct(), emails.distinct())
        }
    }

    override fun sendSms(number: String, message: String): Boolean = sendSmsResult(number, message) != SmsSendResult.FAILED

    override fun sendSmsResult(number: String, message: String): SmsSendResult = runCatching {
        if (!granted(Manifest.permission.SEND_SMS)) return SmsSendResult.FAILED
        // If the grant is revoked, report failure. Never replay an uncertain direct send as another action.
        val sms = context.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(message)
        if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null) else sms.sendTextMessage(number, null, message, null, null)
        SmsSendResult.SENT
    }.getOrDefault(SmsSendResult.FAILED)

    override fun call(number: String): Boolean = runCatching {
        val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (AgentAccessibility.instance?.service ?: context).startActivity(i)
        true
    }.getOrDefault(false)

    @Suppress("MissingPermission")
    override suspend fun location(): JSONObject? = runCatching {
        val lm = context.getSystemService(LocationManager::class.java)
        val best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        JSONObject().put("latitude", best.latitude).put("longitude", best.longitude).put("accuracy_m", best.accuracy.toDouble())
            .put("age_s", (System.currentTimeMillis() - best.time) / 1000).put("provider", best.provider)
    }.getOrNull()
}
