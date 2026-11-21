package com.eggman.backstage.exec

/**
 * The agent's waits between acting and looking ("Fast mode", on by default). Fast mode shortens the waits that
 * only pad time; the upper bounds (how long a busy app may keep changing) stay the same, so slow apps are still
 * waited for. Off restores the timings exactly. The agent environment points [fast] at the setting.
 */
object AgentPace {
    @Volatile var fast: () -> Boolean = { true }

    private fun on(): Boolean = runCatching { fast() }.getOrDefault(true)

    fun isFast(): Boolean = on()

    /** Accessibility-event quiet window before a screen counts as settled (the hard cap is unchanged). */
    fun settleQuietMs(): Long = if (on()) 200 else 350

    /** How long the screen text must stay the same after an action ([com.eggman.backstage.tools.ToolContext.stabilityMs]). */
    fun stabilityMs(): Long = if (on()) 300 else 600

    /** Poll interval while waiting for that stability. */
    fun stablePollMs(): Long = if (on()) 150 else 300

    /** Poll interval while checking where a launched app's window appeared. */
    fun launchPollMs(): Long = if (on()) 100 else 250

    /** The agent cursor's lead before each action: none in fast mode (the glide still animates alongside). */
    fun cursorLeadMs(settingMs: Int): Int = if (on()) 0 else settingMs
}
