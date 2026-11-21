package com.eggman.backstage.exec

/**
 * Screen text that changes on its own (clocks, battery percentages, counters, "typing…" and
 * "online / last seen" presence indicators). Ignored when deciding whether the screen changed, so
 * a ticking clock neither fakes progress nor hides a stuck agent.
 */
object VolatileText {
    private val patterns = listOf(
        Regex("^[.·•…]{1,5}$"),
        Regex("^\\d{1,2}:\\d{2}(:\\d{2})?\\s*([ap]\\.?m\\.?)?$", RegexOption.IGNORE_CASE),
        Regex("^\\d{1,3}\\s?%$"),
        Regex("^\\d+\\s?/\\s?\\d+$"),
        Regex("^[^\\p{L}\\p{N}]$"),
    )
    private val presence = Regex(
        "^(.{0,40}\\s)?((is |are )?typing|recording( audio| video)?|online|active now|last seen.*|" +
            "schreibt|tippt|zuletzt online.*|escribiendo|en línea|digitando|online agora|écrit|en ligne|pisze|aktywny teraz)(…|\\.{3})?$",
        RegexOption.IGNORE_CASE,
    )

    fun isVolatile(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return t.isNotEmpty()
        if (patterns.any { it.matches(t) }) return true
        return t.length <= 60 && presence.matches(t.lowercase())
    }
}
