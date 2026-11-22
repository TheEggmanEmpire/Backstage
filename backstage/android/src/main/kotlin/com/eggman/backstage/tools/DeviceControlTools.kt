package com.eggman.backstage.tools

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import com.eggman.backstage.exec.TargetKind
import org.json.JSONArray
import org.json.JSONObject

data class Contact(val name: String, val numbers: List<String>, val emails: List<String>)
enum class SmsSendResult { SENT, DRAFT_OPENED, DRAFT_READY, FAILED }

/**
 * Device settings and personal-data actions that need no screen (permission-gated device tools).
 * Each capability is only offered when the permission or privilege behind it is present.
 */
interface DeviceControl {
    /** Capabilities: volume, ringer, flashlight, wifi, bluetooth, brightness, screen_timeout, auto_rotate, dnd, contacts, sms, call, location. */
    fun has(capability: String): Boolean
    fun volumes(): Map<String, Int>
    fun setVolume(stream: String, percent: Int): Boolean
    fun setRingerMode(mode: String): Boolean
    fun setFlashlight(on: Boolean): Boolean
    fun wifiStatus(): JSONObject
    fun bluetoothStatus(): JSONObject
    fun brightness(): JSONObject
    suspend fun setBrightness(percent: Int?, auto: Boolean?): Boolean
    suspend fun setScreenTimeout(seconds: Int): Boolean
    fun autoRotate(): Boolean
    suspend fun setAutoRotate(on: Boolean): Boolean
    fun dnd(): String
    suspend fun setDnd(mode: String): Boolean
    fun searchContacts(query: String, max: Int): List<Contact>
    fun sendSms(number: String, message: String): Boolean
    /** Reports whether the adapter sent the message successfully. */
    fun sendSmsResult(number: String, message: String): SmsSendResult =
        if (!sendSms(number, message)) SmsSendResult.FAILED
        else SmsSendResult.SENT
    fun call(number: String): Boolean
    suspend fun location(): JSONObject?
}

private val NONE_T = emptySet<TargetKind>()

private fun dc(ctx: ToolContext, cap: String): DeviceControl {
    val d = (ctx.services as? DeviceControlProvider)?.deviceControl ?: throw ToolFailure("Device controls are not available.")
    if (!d.has(cap)) throw ToolFailure(DeviceControlTools.missing(cap))
    return d
}

/** Implemented by DeviceServices that offer device controls. */
interface DeviceControlProvider {
    val deviceControl: DeviceControl?
}

private suspend fun guarded(block: suspend () -> ToolResult): ToolResult = try { block() } catch (e: ToolFailure) { ToolResult.error(e.message ?: "failed") }

private fun json(o: JSONObject) = ToolResult(o.toString(), data = o)

object DeviceControlTools {
    /** Tool name → capability it needs. */
    val capabilityOf = mapOf(
        "volume_get" to "volume", "volume_set" to "volume", "ringer_mode_set" to "ringer", "flashlight_set" to "flashlight",
        "wifi_status" to "wifi", "bluetooth_status" to "bluetooth", "brightness_get" to "brightness", "brightness_set" to "brightness",
        "screen_timeout_set" to "screen_timeout", "auto_rotate_get" to "auto_rotate", "auto_rotate_set" to "auto_rotate",
        "dnd_get" to "dnd", "dnd_set" to "dnd", "contacts_search" to "contacts", "sms_send" to "sms", "phone_call" to "call",
        "location_get" to "location",
    )

    fun missing(cap: String) = when (cap) {
        "contacts" -> "Contacts permission not granted (Settings → Apps → Backstage → Permissions)."
        "sms" -> "SMS permission not granted, or this device cannot send SMS."
        "call" -> "Phone permission not granted, or this device cannot make calls."
        "location" -> "Location permission not granted."
        "brightness", "screen_timeout", "auto_rotate" -> "Changing system settings needs the \"Modify system settings\" permission or privileged access."
        "dnd" -> "Do Not Disturb access is not granted (and no privileged access)."
        "flashlight" -> "This device has no flashlight."
        else -> "This phone does not support it."
    }

    fun all(): List<Tool> = listOf(
        simple("volume_get", "Current volume levels (percent) of media, ring, notification and alarm streams.", Schema.obj()) { ctx, _ ->
            json(JSONObject(dc(ctx, "volume").volumes() as Map<*, *>).put("success", true))
        },
        simple("volume_set", "Set a volume stream to a percentage.", Schema.obj(
            "stream" to Schema.enum("Stream", "media", "ring", "notification", "alarm"), "percentage" to Schema.int("0-100"),
            required = listOf("stream", "percentage"),
        ), mutating = true) { ctx, a ->
            val s = a.string("stream").lowercase()
            if (s !in setOf("media", "ring", "notification", "alarm")) throw ToolArgumentException("stream must be media, ring, notification or alarm")
            val p = a.int("percentage").coerceIn(0, 100)
            if (dc(ctx, "volume").setVolume(s, p)) ToolResult("$s volume set to $p%.") else ToolResult.error("The system refused the volume change.")
        },
        simple("ringer_mode_set", "Set the ringer to normal, vibrate or silent.", Schema.obj("mode" to Schema.enum("Mode", "normal", "vibrate", "silent"), required = listOf("mode")), mutating = true) { ctx, a ->
            val m = a.string("mode").lowercase()
            if (m !in setOf("normal", "vibrate", "silent")) throw ToolArgumentException("mode must be normal, vibrate or silent")
            if (dc(ctx, "ringer").setRingerMode(m)) ToolResult("Ringer set to $m.") else ToolResult.error("The system refused (silent mode may need Do Not Disturb access).")
        },
        simple("flashlight_set", "Turn the flashlight on or off.", Schema.obj("enabled" to Schema.bool("On or off"), required = listOf("enabled")), mutating = true) { ctx, a ->
            val on = a.optBool("enabled") ?: throw ToolArgumentException("enabled must be true or false")
            if (dc(ctx, "flashlight").setFlashlight(on)) ToolResult("Flashlight ${if (on) "on" else "off"}.") else ToolResult.error("Could not switch the flashlight.")
        },
        simple("wifi_status", "Wi-Fi state (enabled, connected).", Schema.obj()) { ctx, _ -> json(dc(ctx, "wifi").wifiStatus().put("success", true)) },
        simple("bluetooth_status", "Bluetooth state (enabled).", Schema.obj()) { ctx, _ -> json(dc(ctx, "bluetooth").bluetoothStatus().put("success", true)) },
        simple("brightness_get", "Screen brightness (percent) and whether automatic brightness is on.", Schema.obj()) { ctx, _ ->
            json(dc(ctx, "brightness").brightness().put("success", true))
        },
        simple("brightness_set", "Set screen brightness (1-100 %) or automatic brightness.", Schema.obj(
            "percentage" to Schema.int("1-100 (ignored when auto is true)"), "auto" to Schema.bool("Automatic brightness"),
        ), mutating = true) { ctx, a ->
            val auto = a.optBool("auto")
            val pct = a.optInt("percentage")?.coerceIn(1, 100)
            if (auto == null && pct == null) throw ToolArgumentException("give percentage or auto")
            if (dc(ctx, "brightness").setBrightness(if (auto == true) null else pct, auto)) ToolResult("Brightness updated.") else ToolResult.error("Could not change brightness.")
        },
        simple("screen_timeout_set", "Set the screen-off timeout in seconds.", Schema.obj("seconds" to Schema.int("15-1800"), required = listOf("seconds")), mutating = true) { ctx, a ->
            val s = a.int("seconds").coerceIn(15, 1800)
            if (dc(ctx, "screen_timeout").setScreenTimeout(s)) ToolResult("Screen timeout set to $s s.") else ToolResult.error("Could not change the screen timeout.")
        },
        simple("auto_rotate_get", "Whether auto-rotate is on.", Schema.obj()) { ctx, _ ->
            json(JSONObject().put("success", true).put("enabled", dc(ctx, "auto_rotate").autoRotate()))
        },
        simple("auto_rotate_set", "Turn auto-rotate on or off.", Schema.obj("enabled" to Schema.bool("On or off"), required = listOf("enabled")), mutating = true) { ctx, a ->
            val on = a.optBool("enabled") ?: throw ToolArgumentException("enabled must be true or false")
            if (dc(ctx, "auto_rotate").setAutoRotate(on)) ToolResult("Auto-rotate ${if (on) "on" else "off"}.") else ToolResult.error("Could not change auto-rotate.")
        },
        simple("dnd_get", "Current Do Not Disturb mode.", Schema.obj()) { ctx, _ -> json(JSONObject().put("success", true).put("mode", dc(ctx, "dnd").dnd())) },
        simple("dnd_set", "Set Do Not Disturb: off, priority_only, alarms_only or total_silence.", Schema.obj(
            "mode" to Schema.enum("Mode", "off", "priority_only", "alarms_only", "total_silence"), required = listOf("mode"),
        ), mutating = true) { ctx, a ->
            val m = a.string("mode").lowercase()
            if (m !in setOf("off", "priority_only", "alarms_only", "total_silence")) throw ToolArgumentException("invalid mode")
            if (dc(ctx, "dnd").setDnd(m)) ToolResult("Do Not Disturb: $m.") else ToolResult.error("Could not change Do Not Disturb.")
        },
        simple("contacts_search", "Find contacts by name, number or email.", Schema.obj("query" to Schema.str("Name, number or email"), required = listOf("query"))) { ctx, a ->
            val list = dc(ctx, "contacts").searchContacts(a.string("query"), 10)
            json(JSONObject().put("success", true).put("count", list.size).put("contacts", JSONArray().apply {
                list.forEach { put(JSONObject().put("name", it.name).put("phones", JSONArray(it.numbers)).put("emails", JSONArray(it.emails))) }
            }))
        },
        simple("sms_send", if (PLAY_DISTRIBUTION) "Prepare a draft in the phone's SMS app for the user to review and send."
        else "Send an SMS. The phone asks its user to approve the exact number and text before it is sent, " +
            "so do not call confirm_action first.", Schema.obj(
            "phone_number" to Schema.str("Recipient number"), "message" to Schema.str("Text"), required = listOf("phone_number", "message"),
        ), mutating = true, approval = { ctx, a ->
            dc(ctx, "sms")
            "${if (PLAY_DISTRIBUTION) "Prepare an SMS draft" else "Send an SMS"} to ${smsNumber(a)}: “${a.string("message").take(500)}”"
        }) { ctx, a ->
            val n = smsNumber(a)
            val control = dc(ctx, "sms")
            when (control.sendSmsResult(n, a.string("message"))) {
                SmsSendResult.DRAFT_OPENED -> ToolResult("SMS draft opened for $n. The user must send it.")
                SmsSendResult.DRAFT_READY -> ToolResult("SMS draft ready for $n. The user must open the notification and send it.")
                SmsSendResult.FAILED -> ToolResult.error(if (PLAY_DISTRIBUTION) "The SMS draft could not be prepared." else "The SMS could not be sent.")
                SmsSendResult.SENT -> ToolResult("SMS sent to $n.")
            }
        },
        simple("phone_call", "Start a phone call. The phone asks its user to approve the number before dialling, " +
            "so do not call confirm_action first.", Schema.obj("phone_number" to Schema.str("Number to call"), required = listOf("phone_number")),
            mutating = true, approval = { ctx, a -> dc(ctx, "call"); "Call ${callNumber(a)}" }) { ctx, a ->
            val n = callNumber(a)
            if (dc(ctx, "call").call(n)) ToolResult("Calling $n.") else ToolResult.error("The call could not be started.")
        },
        simple("location_get", "Current device location (latitude, longitude, accuracy).", Schema.obj()) { ctx, _ ->
            val loc = dc(ctx, "location").location() ?: return@simple ToolResult.error("Location unavailable right now.")
            json(loc.put("success", true))
        },
    )

    private fun smsNumber(a: Args): String = a.string("phone_number").trim().also {
        if (!it.matches(Regex("^[+0-9 ()\\-]{3,20}$"))) throw ToolArgumentException("phone_number looks invalid")
    }

    private fun callNumber(a: Args): String = a.string("phone_number").trim().also {
        if (!it.matches(Regex("^[+0-9 ()\\-*#]{3,20}$"))) throw ToolArgumentException("phone_number looks invalid")
    }

    /** [approval] marks the tool [Tool.irreversible] and words its approval question. */
    private fun simple(
        name: String, description: String, schema: JSONObject, mutating: Boolean = false,
        approval: (suspend (ToolContext, Args) -> String)? = null,
        body: suspend (ToolContext, Args) -> ToolResult,
    ): Tool = object : Tool {
        override val name = name
        override val description = description
        override val schema = schema
        override val targets = NONE_T
        override val mutating = mutating
        override val irreversible = approval != null
        override suspend fun approvalQuestion(ctx: ToolContext, args: Args): String = approval?.invoke(ctx, args) ?: super.approvalQuestion(ctx, args)
        override suspend fun run(ctx: ToolContext, args: Args): ToolResult = guarded { body(ctx, args) }
    }
}
