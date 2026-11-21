package com.eggman.backstage.ui.onboarding

import com.eggman.backstage.privileged.ShizukuState
import com.eggman.backstage.tools.DeviceAccess

/** Facts gathered from the device (Android adapter: [AndroidPermissionProbe]). */
data class ProbeSnapshot(
    val providerConfigured: Boolean,
    val hostAvailable: Boolean,
    val privileged: Boolean,
    val accessibility: Boolean,
    val overlay: Boolean,
    val notifications: Boolean,
    val microphone: Boolean,
    val calendar: Boolean,
    val exactAlarms: Boolean,
    val batteryUnrestricted: Boolean,
    /** Installed outside an app store: Android blocks accessibility until "restricted settings" are allowed. */
    val sideloaded: Boolean,
    val wakeWordWanted: Boolean = false,
    val bubbleWanted: Boolean = false,
    /** Shared Stellar/Shizuku connection ([privileged] also covers Dextop's wireless-ADB fallback). */
    val shizuku: ShizukuState = ShizukuState.NOT_INSTALLED,
    /** Backstage is the default digital assistant (long-press power / assist gesture opens it). */
    val defaultAssistant: Boolean = false,
    /** Device-action access already granted / usable on this device (contacts, SMS, calls, location, settings, DND). */
    val deviceAccess: Set<DeviceAccess.Kind> = emptySet(),
    val deviceAccessSupported: Set<DeviceAccess.Kind> = DeviceAccess.Kind.entries.toSet(),
)

/** The last six mirror [DeviceAccess.Kind] by name (one optional item each). */
enum class ItemId {
    PROVIDER, PRIVILEGED, ACCESSIBILITY, NOTIFICATIONS, OVERLAY, MICROPHONE, CALENDAR, EXACT_ALARMS, BATTERY, ASSISTANT,
    CONTACTS, SMS, PHONE, LOCATION, SYSTEM_SETTINGS, DND;

    val deviceAccess: DeviceAccess.Kind? get() = DeviceAccess.Kind.entries.firstOrNull { it.name == name }
}

enum class Importance { REQUIRED, RECOMMENDED, OPTIONAL }

data class ChecklistItem(
    val id: ItemId,
    val title: String,
    val why: String,
    val done: Boolean,
    val importance: Importance,
    val actionLabel: String?,
    val hint: String? = null,
    /** Can be granted in one go through the privileged shell. */
    val privilegedGrantable: Boolean = false,
)

object PermissionChecklist {

    fun build(p: ProbeSnapshot): List<ChecklistItem> = listOf(
        ChecklistItem(
            ItemId.PROVIDER, "AI provider",
            "Bring an API key of yours (Claude, OpenAI, Gemini, OpenRouter, DeepSeek) or point it at an Ollama server on your network.",
            p.providerConfigured, Importance.REQUIRED, "Open settings",
        ),
        shizukuItem(p),
        ChecklistItem(
            ItemId.ACCESSIBILITY, "Accessibility service",
            "\"Backstage\" in Android's accessibility settings: runs the desktop, and lets the agent read the screen as a list of elements and tap, type and swipe. The agent only acts while a task you started runs.",
            p.accessibility, Importance.REQUIRED, "Enable",
            hint = if (p.sideloaded && !p.accessibility) "Greyed out? Open App info → ⋮ → \"Allow restricted settings\", then try again." else null,
            privilegedGrantable = true,
        ),
        ChecklistItem(
            ItemId.NOTIFICATIONS, "Notifications",
            "Progress, questions you can answer from the shade, and results of background tasks.",
            p.notifications, Importance.RECOMMENDED, "Allow", privilegedGrantable = true,
        ),
        ChecklistItem(
            ItemId.OVERLAY, "Display over other apps",
            "Needed for the floating agent bubble.",
            p.overlay, if (p.bubbleWanted) Importance.REQUIRED else Importance.OPTIONAL, "Allow", privilegedGrantable = true,
        ),
        ChecklistItem(
            ItemId.MICROPHONE, "Microphone",
            "Voice commands and the optional on-device wake word.",
            p.microphone, if (p.wakeWordWanted) Importance.REQUIRED else Importance.RECOMMENDED, "Allow", privilegedGrantable = true,
        ),
        ChecklistItem(
            ItemId.CALENDAR, "Calendar",
            "Lets the agent read and add events directly instead of tapping through the calendar app.",
            p.calendar, Importance.OPTIONAL, "Allow", privilegedGrantable = true,
        ),
        ChecklistItem(
            ItemId.EXACT_ALARMS, "Exact alarms",
            "Scheduled tasks start on the minute; without it Android may delay them.",
            p.exactAlarms, Importance.OPTIONAL, "Allow", privilegedGrantable = true,
        ),
        ChecklistItem(
            ItemId.BATTERY, "Unrestricted battery",
            "Keeps long tasks, the wake word and the MCP server alive while the screen is off.",
            p.batteryUnrestricted, Importance.RECOMMENDED, "Allow", privilegedGrantable = true,
        ),
        ChecklistItem(
            ItemId.ASSISTANT, "Default assistant",
            "Long-press the power button (or use the assist gesture) to talk to Backstage from any app.",
            p.defaultAssistant, Importance.OPTIONAL, "Choose",
            hint = if (!p.defaultAssistant) "Pick Backstage under \"Default digital assistant app\"." else null,
        ),
    ) + DeviceAccess.Kind.entries.filter { it in p.deviceAccessSupported }.map { k ->
        // the device tools behind these appear for the agent as soon as they are granted.
        ChecklistItem(ItemId.valueOf(k.name), k.title, k.why, k in p.deviceAccess, Importance.OPTIONAL, "Allow",
            privilegedGrantable = k.shellGrantable)
    }

    /**
     * Stellar first, then Shizuku: the provider drives the background desktop, switches on the accessibility service and grants the
     * rest of this list in one go. Dextop's built-in wireless debugging is only the fallback.
   */
    private fun shizukuItem(p: ProbeSnapshot): ChecklistItem {
        val ready = p.shizuku == ShizukuState.READY
        val action = when (p.shizuku) {
            ShizukuState.NOT_INSTALLED -> "Get Stellar"
            ShizukuState.NOT_RUNNING -> "Open manager"
            ShizukuState.PERMISSION_NEEDED -> "Allow"
            ShizukuState.READY -> null
        }
        val hint = when {
            ready -> null
            p.privileged -> "Using Dextop's wireless debugging (fallback). Stellar is recommended; enable its Shizuku compatibility layer."
            p.shizuku == ShizukuState.NOT_RUNNING -> "Start Stellar (wireless debugging or root), enable its Shizuku compatibility layer, then come back. Shizuku is an alternative."
            p.shizuku == ShizukuState.PERMISSION_NEEDED -> "Stellar / Shizuku is running: allow Backstage in the dialog."
            p.hostAvailable -> "No Stellar or Shizuku? Dextop's built-in wireless debugging works as a fallback (Desktop tab → Desktop settings → privilege setup)."
            else -> "Without it the agent works on the phone screen only."
        }
        return ChecklistItem(
            ItemId.PRIVILEGED, "Stellar / Shizuku",
            "Lets Backstage run the desktop in the background, switch on its accessibility service and grant the rest of this list for you.",
            // Done only when the external connection is ready: the wireless-debugging fallback is no reason to hide "Allow".
            ready, Importance.RECOMMENDED, action, hint,
        )
    }

    /** Required items not done yet: blocks the agent. */
    fun blockers(items: List<ChecklistItem>) = items.filter { it.importance == Importance.REQUIRED && !it.done }

    fun progress(items: List<ChecklistItem>): Pair<Int, Int> {
        val relevant = items.filter { it.importance != Importance.OPTIONAL || it.done }
        return relevant.count { it.done } to relevant.size
    }

    /**
     * Shell commands (run as uid 2000 through the privileged runtime) that grant the missing
     * items. Accessibility itself is enabled by the agent core; here we only lift the
     * restricted-settings block so the user can switch it on.
   */
    fun privilegedCommands(pkg: String, items: List<ChecklistItem>): List<List<String>> {
        val missing = items.filter { !it.done && it.privilegedGrantable }.map { it.id }.toSet()
        return buildList {
            if (ItemId.ACCESSIBILITY in missing) add(listOf("appops", "set", pkg, "ACCESS_RESTRICTED_SETTINGS", "allow"))
            if (ItemId.NOTIFICATIONS in missing) add(listOf("pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS"))
            if (ItemId.OVERLAY in missing) add(listOf("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"))
            if (ItemId.MICROPHONE in missing) add(listOf("pm", "grant", pkg, "android.permission.RECORD_AUDIO"))
            if (ItemId.CALENDAR in missing) {
                add(listOf("pm", "grant", pkg, "android.permission.READ_CALENDAR"))
                add(listOf("pm", "grant", pkg, "android.permission.WRITE_CALENDAR"))
            }
            if (ItemId.EXACT_ALARMS in missing) add(listOf("appops", "set", pkg, "SCHEDULE_EXACT_ALARM", "allow"))
            if (ItemId.BATTERY in missing) add(listOf("dumpsys", "deviceidle", "whitelist", "+$pkg"))
            missing.mapNotNull { it.deviceAccess }.forEach { addAll(DeviceAccess.shellGrant(pkg, it)) }
        }
    }
}
