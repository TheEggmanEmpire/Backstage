package com.eggman.backstage.exec

import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target

/** Inputs for choosing where a task runs; gathered from DesktopHost/settings by the controller. */
data class TargetContext(
    val requested: Target,
    val defaultTarget: Target,
    val source: Source,
    val prompt: String,
    val hostPresent: Boolean,
    val desktopActive: Boolean,
    val privilegedAvailable: Boolean,
    val autoStartDesktop: Boolean,
    /** Target of the previous turn in the same conversation (follow-ups stay put). */
    val conversationTarget: TargetKind? = null,
    /** Lets remote callers (MCP clients, broadcast API) put tasks on the phone screen. Off by default. */
    val externalPhoneAllowed: Boolean = false,
)

data class TargetDecision(val kind: TargetKind?, val needsDesktopStart: Boolean, val reason: String)

/**
 * Target policy. The agent is gated to the Dextop (DeX) desktop by default:
 *  - default target DESKTOP (the shipped default): every task runs on the desktop; it is started when
 *    needed; if it cannot run, the task fails with an explanation — it NEVER silently falls back to the
 *    phone screen;
 *  - the phone screen is used only when the user explicitly switches (chat/bubble chip, Settings default,
 *    or a schedule/Tasker action they configured); remote callers (MCP, broadcast API) additionally need
 *    [TargetContext.externalPhoneAllowed];
 *  - default AUTO (advanced) keeps the heuristic policy: desktop when available, phone hints and
 *    "this screen" from on-phone surfaces mean the phone, otherwise the phone as a fallback.
 */
object TargetResolver {
    private val phoneHints = listOf(
        "on my phone", "on the phone", "phone screen", "on this screen", "on my screen", "this screen",
        "current screen", "what's on screen", "what is on screen", "auf dem handy", "auf meinem handy", "diesen bildschirm",
    )
    private val deictic = Regex("\\b(this|here|current|these|that)\\b", RegexOption.IGNORE_CASE)
    /**
     * Whole words/phrases only ("dex" must not match "index"), and no bare "background" or "desktop":
     * "change the background colour" or "open the desktop site" are phone requests.
   */
    private val desktopHints = Regex(
        "\\b(on (the |my )?desktop|to the desktop|desktop (mode|session)|in the background|mini window|dex|auf dem desktop|im hintergrund)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Sources that are not a person on this phone: they may not take over the phone screen unless allowed. */
    val REMOTE_SOURCES = setOf(Source.MCP, Source.BROADCAST)
    fun isRemote(source: Source) = source in REMOTE_SOURCES
    const val SWITCH_HINT = "Start the desktop on the Desktop tab, set up Shizuku so the agent can start it, or switch the target to Phone."
    const val PHONE_LOCKED = "Remote callers may not control the phone screen. Allow it in Settings (Agent > Remote callers may use the phone screen) or target the desktop."

    fun mentionsDesktop(prompt: String): Boolean = desktopHints.containsMatchIn(prompt)

    const val PHONE_GATED = "The phone screen is off-limits for this task: the agent works on the DeX desktop only (the user's " +
        "phone stays theirs). Act on the desktop instead; only the user can switch the target to Phone."

    /**
     * Whether a task may act on the phone's own screen at all. Gated to the desktop unless the user
     * chose the phone: an explicit PHONE request, or a PHONE/AUTO default; remote callers additionally need
     * [TargetContext.externalPhoneAllowed]. An explicit DESKTOP request keeps the task on the desktop.
   */
    fun phoneAllowed(c: TargetContext): Boolean {
        val remoteLocked = c.source in REMOTE_SOURCES && !c.externalPhoneAllowed
        // Without a desktop host the phone is the only screen (phone-only build): only the remote gate applies.
        if (!c.hostPresent) return !remoteLocked
        return when (c.requested) {
            Target.PHONE -> !remoteLocked
            Target.DESKTOP -> false
            Target.AUTO -> c.defaultTarget != Target.DESKTOP && !remoteLocked
        }
    }

    fun canUseDesktop(c: TargetContext) = c.hostPresent && (c.desktopActive || (c.autoStartDesktop && c.privilegedAvailable))

    fun resolve(c: TargetContext): TargetDecision {
        val p = c.prompt.lowercase()
        fun phone(reason: String): TargetDecision =
            if (c.source in REMOTE_SOURCES && !c.externalPhoneAllowed) TargetDecision(null, false, PHONE_LOCKED)
            else TargetDecision(TargetKind.PHONE, false, reason)
        fun desktop(reason: String): TargetDecision =
            if (!c.hostPresent) TargetDecision(null, false, "Desktop requested but no Dextop desktop is available in this build.")
            else if (c.desktopActive) TargetDecision(TargetKind.DESKTOP, false, reason)
            else if (c.privilegedAvailable) TargetDecision(TargetKind.DESKTOP, true, "$reason (desktop will be started)")
            else TargetDecision(null, false, "The desktop is not running and cannot be started without wireless debugging/Shizuku.")

        when (c.requested) {
            Target.DESKTOP -> return desktop("requested desktop")
            Target.PHONE -> return phone("requested phone")
            Target.AUTO -> Unit
        }
        when (c.defaultTarget) {
            Target.DESKTOP -> return desktop("the agent works on the DeX desktop (switch the target to Phone to use the phone screen)")
                .let { if (it.kind != null) it else it.copy(reason = it.reason + " " + SWITCH_HINT) }
            Target.PHONE -> {
                // The gate keeps the agent OFF the phone, never off the desktop: explicit desktop wording wins.
                if (mentionsDesktop(p) && c.hostPresent) desktop("request mentions the desktop/background").let { if (it.kind != null) return it }
                return phone("default target is the phone")
            }
            Target.AUTO -> Unit
        }
        // ---- AUTO (advanced) below: heuristic routing with phone fallback ----
        if (phoneHints.any { it in p }) return phone("request refers to the phone screen")
        val onPhoneSurface = c.source == Source.ASSIST || c.source == Source.OVERLAY || c.source == Source.SHARE
        if (onPhoneSurface && deictic.containsMatchIn(p)) return phone("request refers to what is on screen")
        if (mentionsDesktop(p) && c.hostPresent) {
            val d = desktop("request mentions the desktop/background")
            if (d.kind != null) return d
        }
        c.conversationTarget?.let { prev ->
            if (prev == TargetKind.PHONE || canUseDesktop(c)) {
                return if (prev == TargetKind.DESKTOP) desktop("continuing on the desktop") else phone("continuing on the phone")
            }
        }
        if (canUseDesktop(c)) return desktop("desktop available: working in the background")
        return phone(if (c.hostPresent) "desktop not available: using the phone screen" else "phone-only build")
    }
}
