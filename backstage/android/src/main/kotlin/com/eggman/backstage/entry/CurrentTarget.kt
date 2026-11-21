package com.eggman.backstage.entry

import android.content.Context
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.exec.TargetResolver
import com.eggman.backstage.ui.settings.SettingsStores

/**
 * The ONE current target. The chat's "DeX desktop / Phone / Auto" chips, the popup chat's chips and Settings' default target are
 * the same persisted value; every local entry point (in-app chat, popup chat, notification reply, voice, bubble) sends
 * with it, unless the message itself names the other screen. Remote callers (MCP, broadcast) keep their own gating.
 */
object CurrentTarget {
    private val phoneWords = Regex(
        "\\b(on|in|auf|am) (my|the|this|meinem|dem) (phone|handy|smartphone)\\b|\\bphone screen\\b|\\bauf dem handy\\b",
        RegexOption.IGNORE_CASE,
    )

    fun get(context: Context): Target = runCatching { SettingsStores.get(context).defaultTarget }.getOrDefault(Target.DESKTOP)

    fun set(context: Context, target: Target) {
        runCatching { SettingsStores.get(context).defaultTarget = target }
        com.eggman.backstage.shell.ShellLifecycle.onTargetChosen(target)
    }

    /** The target one message runs with: [selected], unless its text explicitly names the other screen. Pure. */
    fun forMessage(selected: Target, text: String): Target = when {
        phoneWords.containsMatchIn(text) -> Target.PHONE
        TargetResolver.mentionsDesktop(text) -> Target.DESKTOP
        else -> selected
    }

    /** For one message from a local entry point: the current target, adjusted by the message's own words. */
    fun forMessage(context: Context, text: String): Target = forMessage(get(context), text)
}
