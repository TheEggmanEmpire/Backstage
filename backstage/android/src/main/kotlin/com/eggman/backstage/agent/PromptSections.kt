package com.eggman.backstage.agent

import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.tools.MemoryItem

/**
 * Built-in per-app notes, written for Backstage. Shown to the model the first time the app is in front during
 * a task, before the user's own rules for that app (which win where they differ).
 */
object AppRuleRegistry {
    private val MESSAGING = """
        - Open the exact chat by searching the contact's name; check the chat header shows that person (or group) before typing.
        - Type the message into the message field at the bottom and send it with the send button; one message unless asked for more.
        - After sending, confirm it appears at the end of the chat. Do not react, forward, call, or open media unless asked.
    """.trimIndent()

    private val rules: Map<String, String> = mapOf(
        "com.whatsapp" to MESSAGING + "\n- Voice and video call buttons sit in the chat header: never touch them while looking for the chat.",
        "com.whatsapp.w4b" to MESSAGING + "\n- WhatsApp Business: quick replies and labels are business tools; leave them alone unless asked.",
        "org.telegram.messenger" to MESSAGING + "\n- Channels and bots look like chats: check the header before writing.",
        "org.telegram.messenger.web" to MESSAGING + "\n- Channels and bots look like chats: check the header before writing.",
        "org.thoughtcrime.securesms" to MESSAGING + "\n- Signal may ask for its PIN or show safety-number changes: report those to the user instead of confirming them.",
        "com.facebook.orca" to MESSAGING + "\n- Messenger offers stickers and suggested replies next to the field: type the user's own words instead.",
    )

    fun rulesFor(packageName: String): String? = rules[packageName]

    val packages: Set<String> get() = rules.keys
}

/** Saved memories as a short prompt section: newest first, bounded, data rather than instructions. */
object MemoryDigest {
    const val MAX_ITEMS = 15
    const val MAX_CHARS = 1_500

    fun build(items: List<MemoryItem>): String {
        if (items.isEmpty()) return ""
        val out = StringBuilder()
        for (m in items.sortedByDescending { it.createdMs }.take(MAX_ITEMS)) {
            val line = "- " + m.content.replace(Regex("\\s+"), " ").trim().take(200)
            if (out.length + line.length + 1 > MAX_CHARS) break
            out.append(line).append('\n')
        }
        return out.toString().trimEnd()
    }
}

/**
 * The current plan as the model sees it on every step: status markers, 1-based numbers, the current step and
 * a k-of-n line. Re-rendered from the live plan, so it survives history compression.
 */
object PlanSection {
    private fun marker(status: String) = when (status) {
        "done" -> "[x]"
        "in_progress" -> "[>]"
        "skipped" -> "[-]"
        "blocked" -> "[!]"
        else -> "[ ]"
    }

    fun render(plan: List<PlanStep>): String = buildString {
        appendLine()
        appendLine("## Current plan")
        if (plan.isEmpty()) {
            append("No plan yet. For a task of three or more steps, call update_plan first.")
            return@buildString
        }
        val done = plan.count { it.status == "done" || it.status == "skipped" }
        plan.forEachIndexed { i, s -> appendLine("${marker(s.status)} ${i + 1}. ${s.title}") }
        val current = plan.indexOfFirst { it.status == "in_progress" }.takeIf { it >= 0 }
            ?: plan.indexOfFirst { it.status == "pending" }.takeIf { it >= 0 }
        append("Progress: $done of ${plan.size} done")
        if (current != null) append("; now: step ${current + 1}")
        append('.')
    }
}
