package com.eggman.backstage.agent

import com.eggman.backstage.exec.ScreenState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stale-decision protection: right before an acting tool runs, the screen is read again and compared
 * with the one the model decided on. If elements appeared or disappeared (a dialog, toast or ad),
 * the action is withheld once and the model re-decides on the new screen. Positions, focus, digits
 * and dot/ellipsis runs are ignored so clocks and counters do not cause refusals. Never blocks twice
 * in a row and at most [maxBlocks] times per task.
 */
class FreshScreenGuard(private val maxBlocks: Int = 5) {
    private var blocks = 0
    private var justBlocked = false

    fun normalize(s: ScreenState): Set<String> = s.elements.asSequence()
        .map { "${it.className} ${it.label}".replace(DIGITS, "").replace(DOTS, "…").replace(SPACES, " ").trim() }
        .filter { it.isNotBlank() }
        .toSet()

    /** A refusal message, or null when the action may run. */
    fun check(seen: ScreenState, fresh: ScreenState): String? {
        if (justBlocked || blocks >= maxBlocks) { justBlocked = false; return null }
        val a = normalize(seen)
        val b = normalize(fresh)
        val appeared = b - a
        val gone = a - b
        if (appeared.isEmpty() && gone.isEmpty()) return null
        blocks++
        justBlocked = true
        val sample = appeared.take(3).joinToString(" | ") { it.take(30) }
        return "Action withheld because the display changed after your last look " +
            "(${appeared.size} element(s) appeared, ${gone.size} disappeared" +
            (if (sample.isNotEmpty()) ", e.g. $sample" else "") + "). It did not run; choose again from the up-to-date listing:\n\n" + fresh.text
    }

    /**
     * the re-read replaces the listing indices resolve against. When the same elements came back in another
     * order (the set comparison above cannot see that, and digits are ignored there), [index] may name a different
     * element: refuse, with the fresh listing, instead of acting on it.
   */
    fun indexMoved(seen: ScreenState, fresh: ScreenState, index: Int): String? {
        val was = seen.element(index) ?: return null
        val now = fresh.element(index)
        if (now != null && now.className == was.className && now.label == was.label) return null
        return "Action withheld: element [$index] is no longer “${was.label.take(40)}” (the screen changed after your last look" +
            (now?.let { "; [$index] is now “${it.label.take(40)}”" } ?: "") + "). It did not run; choose again from the up-to-date listing:\n\n" + fresh.text
    }

    /** A non-guarded tool ran: the next guarded call is checked again. */
    fun clear() { justBlocked = false }

    companion object {
        private val DIGITS = Regex("\\d")
        private val DOTS = Regex("[.·•…]{2,}")
        private val SPACES = Regex("\\s+")
        val GUARDED = setOf("tap", "long_press", "swipe", "type_text", "desktop_right_click")
    }
}

/**
 * Detects the agent going round in circles: the same action on the same screen producing the
 * same screen again. Returns a escalating verdict so the loop can first warn the model, then stop.
 */
class StuckDetector(private val warnAfter: Int = 3, private val abortAfter: Int = 6) {
    enum class Verdict { OK, WARN, ABORT }

    private var lastKey: String? = null
    private var repeats = 0
    /** Counts consecutive screens identical to the one before, regardless of action. */
    private var unchangedRun = 0
    private var lastScreen: String? = null

    val repeatCount: Int get() = repeats

    fun record(tool: String, args: JSONObject, screenBefore: String?, screenAfter: String?): Verdict {
        val key = tool + "|" + canonical(args) + "|" + (screenBefore ?: "")
        // The key embeds the screen the action is taken on: an identical key means the previous
        // identical action left the screen unchanged.
        repeats = if (key == lastKey) repeats + 1 else 1
        lastKey = key
        unchangedRun = if (screenAfter != null && screenAfter == lastScreen) unchangedRun + 1 else 0
        lastScreen = screenAfter
        return when {
            repeats >= abortAfter || unchangedRun >= abortAfter * 2 -> Verdict.ABORT
            repeats >= warnAfter || unchangedRun >= abortAfter -> Verdict.WARN
            else -> Verdict.OK
        }
    }

    fun reset() { lastKey = null; repeats = 0; unchangedRun = 0; lastScreen = null }

    companion object {
        const val WARNING = "Warning: this exact action has now been repeated without any effect on the screen. Stop repeating it and switch " +
            "approach (a different element, scrolling, back, waiting, a screenshot, a deep link), or call finish with success=false if the goal cannot be reached."

        /** Order-independent JSON rendering for comparing arguments. */
        fun canonical(v: Any?): String = when (v) {
            is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { "$it:" + canonical(v.opt(it)) }
            is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
            is Number -> v.toDouble().let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
            null -> "null"
            else -> v.toString()
        }
    }
}

/**
 * Extracts explicit quantities from the request so the loop can remind the model that they are
 * literal ("delete 2 photos" → exactly two). Number words are supported for English and German.
 */
object QuantityHints {
    private val words = mapOf(
        "one" to 1, "a single" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "twelve" to 12, "twenty" to 20, "a couple of" to 2, "both" to 2,
        "ein" to 1, "eine" to 1, "einen" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fünf" to 5, "sechs" to 6, "sieben" to 7,
        "acht" to 8, "neun" to 9, "zehn" to 10, "beide" to 2,
    )
    private val numberThenNoun = Regex("\\b(\\d{1,4})\\s+([\\p{L}][\\p{L}'-]{2,}(?:\\s+[\\p{L}][\\p{L}'-]{2,})?)", RegexOption.IGNORE_CASE)
    private val wordThenNoun = Regex(
        "\\b(" + words.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\s+([\\p{L}][\\p{L}'-]{2,})",
        RegexOption.IGNORE_CASE,
    )
    private val allWords = Regex("\\b(all|every|each|alle|jede[nrs]?|sämtliche)\\b", RegexOption.IGNORE_CASE)
    private val unitNouns = setOf(
        "minutes", "minute", "hours", "hour", "seconds", "second", "days", "day", "weeks", "week", "months", "month", "years", "year",
        "uhr", "minuten", "stunden", "tage", "tagen", "am", "pm", "percent", "euro", "dollars", "km", "kg",
    )

    data class Quantity(val count: Int?, val phrase: String)

    fun extract(prompt: String): List<Quantity> {
        val out = ArrayList<Quantity>()
        numberThenNoun.findAll(prompt).forEach { m ->
            val noun = m.groupValues[2].lowercase().substringBefore(' ')
            if (noun !in unitNouns) out += Quantity(m.groupValues[1].toInt(), m.value.trim())
        }
        wordThenNoun.findAll(prompt).forEach { m ->
            val noun = m.groupValues[2].lowercase()
            if (noun !in unitNouns && noun !in setOf("of", "more", "time", "moment", "bit", "second", "mal")) {
                out += Quantity(words[m.groupValues[1].lowercase()], m.value.trim())
            }
        }
        allWords.findAll(prompt).forEach { out += Quantity(null, it.value) }
        return out.distinctBy { it.phrase.lowercase() }
    }

    /** Reminder appended to a task request, or null when the request has no explicit quantity. */
    fun reminder(prompt: String): String? {
        val q = extract(prompt).filter { it.count != null }
        if (q.isEmpty()) return null
        return "Note: quantities in this request are literal — " + q.joinToString("; ") { "\"${it.phrase}\" means exactly ${it.count}" } + "."
    }
}
