package com.eggman.backstage.exec

/** Differences between two listings whose element indices share the same generation. Pure Kotlin/JVM. */
object ScreenDiff {
    const val MARKER = "Changes since the last listing (unchanged elements keep their index):"

    fun lines(previous: ScreenState, current: ScreenState): List<String> = buildList {
        val old = previous.elements.associateBy { it.index }
        val now = current.elements.associateBy { it.index }
        previous.elements.filter { it.index !in now }.forEach { add("- ${identity(it)}") }
        for (element in current.elements) {
            val before = old[element.index]
            if (before == null) {
                add("+ ${identity(element)} (${element.centerX},${element.centerY})${traits(element.traits)}")
                continue
            }
            val changes = ArrayList<String>()
            val labelChanged = before.label != element.label
            if (labelChanged) changes += "${quoted(before.label)} → ${quoted(element.label)}"
            if (before.text != element.text && (!labelChanged || before.text != before.label || element.text != element.label)) {
                changes += "text ${quoted(before.text)} → ${quoted(element.text)}"
            }
            if (before.description != element.description) changes += "description ${quoted(before.description)} → ${quoted(element.description)}"
            if (before.hint != element.hint) changes += "hint ${quoted(before.hint)} → ${quoted(element.hint)}"
            if (before.traits.toSet() != element.traits.toSet()) {
                val removed = before.traits.filter { it !in element.traits }.joinToString(",").ifEmpty { "none" }
                val added = element.traits.filter { it !in before.traits }.joinToString(",").ifEmpty { "none" }
                changes += "$removed → $added"
            }
            val dx = element.centerX.toLong() - before.centerX; val dy = element.centerY.toLong() - before.centerY
            if (dx * dx + dy * dy >= 256) changes += "(${before.centerX},${before.centerY}) → (${element.centerX},${element.centerY})"
            if (before.bounds.width != element.bounds.width || before.bounds.height != element.bounds.height) {
                changes += "size ${before.bounds.width}x${before.bounds.height} → ${element.bounds.width}x${element.bounds.height}"
            }
            if (changes.isNotEmpty()) {
                val name = if (labelChanged) "[${element.index}] ${element.className}" else identity(element)
                add("~ $name ${changes.joinToString("; ")}")
            }
        }
    }

    fun render(previous: ScreenState, current: ScreenState): String = lines(previous, current).joinToString("\n")

    private fun identity(e: UiElement): String = "[${e.index}] ${e.className}" +
        if (e.label.isNotEmpty()) " ${quoted(e.label)}" else if (e.hint.isNotEmpty()) " hint=${quoted(e.hint)}" else ""

    private fun quoted(text: String) = "\"${text.replace(Regex("\\s+"), " ").replace('"', '\'')}\""
    private fun traits(traits: List<String>) = if (traits.isEmpty()) "" else " " + traits.joinToString(",")
}
