package com.eggman.backstage.exec

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns a raw [ScreenCapture] into the indexed element list the model reads.
 *
 * Rules (original design, tuned for small prompts and unambiguous targeting):
 *  - windows are walked top-most first; our own overlays (accessibility overlays and windows of
 *    [ownPackages]) are skipped so the agent never "sees" or taps its own bubble;
 *  - a node is kept when it is visible, on-screen and either actionable or carries text;
 *  - an actionable node without its own label is labelled with the text of its non-actionable
 *    descendants, which are then not repeated as separate elements;
 *  - elements whose centre lies under a higher window (dialog, keyboard, another freeform window)
 *    are counted as covered and omitted — a tap there would hit the other window;
 *  - output is capped by element count and characters; the cap is reported, never silent.
 */
/** Precompiled: clean runs for every node of every capture. */
private val WHITESPACE = Regex("\\s+")

class ScreenSerializer(
    private val ownPackages: Set<String> = emptySet(),
    private val maxElements: Int = 220,
    private val maxChars: Int = 14_000,
    private val maxLabel: Int = 120,
) {

    fun serialize(capture: ScreenCapture, target: String, previous: ScreenState? = null): ScreenState {
        val screen = Box(0, 0, capture.width, capture.height)
        val screenArea = screen.area.coerceAtLeast(1)
        val candidates = capture.windows
            .filter { it.kind != WindowKind.ACCESSIBILITY_OVERLAY && it.packageName !in ownPackages && it.root?.packageName !in ownPackages }
            .sortedByDescending { it.layer }
        // Status/navigation bars (small SystemUI windows) are noise for the model; the shade, volume
        // panel and system dialogs (large SystemUI windows) stay. If nothing else is on screen, keep them all.
        val withoutBars = candidates.filter { w ->
            val pkg = w.packageName.ifBlank { w.root?.packageName.orEmpty() }
            !(pkg == ScreenMarkup.SYSTEM_UI && w.bounds.area * 5 < screenArea && !isSystemAlert(w, screen))
        }
        val windows = if (withoutBars.any { it.root != null && it.kind != WindowKind.INPUT_METHOD }) withoutBars else candidates
        val summaries = windows.map { WindowSummary(it.id, it.kind, it.title, it.packageName, it.bounds, it.focused || it.active) }
        val keyboard = windows.any { it.kind == WindowKind.INPUT_METHOD && !it.bounds.isEmpty }

        val elements = ArrayList<UiElement>()
        var covered = 0
        var omitted = 0
        val higher = ArrayList<Box>()
        for (window in windows) {
            val root = window.root
            if (root != null && window.kind != WindowKind.INPUT_METHOD && window.kind != WindowKind.SPLIT_DIVIDER) {
                val collected = ArrayList<Pending>()
                collect(root, screen, window, collected, 0)
                for (p in collected) {
                    val cx = p.bounds.centerX; val cy = p.bounds.centerY
                    if (higher.any { it.contains(cx, cy) }) { covered++; continue }
                    if (elements.size >= maxElements) { omitted++; continue }
                    elements += p.toElement(elements.size, window)
                }
            }
            // Everything below this window is hidden where it overlaps (system bars are thin; include them too).
            if (!window.bounds.isEmpty && window.kind != WindowKind.MAGNIFICATION) higher += window.bounds
        }

        val alerts = windows.filter { isSystemAlert(it, screen) }
        val app = windows.firstOrNull { it.kind == WindowKind.APPLICATION && it !in alerts }
        val pkg = app?.let(::windowPackage).orEmpty()
        val title = app?.title.orEmpty()
        val (indexed, generation, highWaterMark, allocation) = assignIndices(elements, previous, capture.displayId, pkg)

        val text = render(capture, target, pkg, title, summaries, indexed, keyboard, omitted, covered, alerts)
        val (finalText, cut) = capChars(text)
        return ScreenState(
            displayId = capture.displayId,
            target = target,
            width = capture.width,
            height = capture.height,
            foregroundPackage = pkg,
            windowTitle = title,
            windows = summaries,
            elements = indexed,
            keyboardVisible = keyboard,
            omittedElements = omitted + cut,
            coveredElements = covered,
            fingerprint = fingerprint(pkg, indexed),
            text = finalText,
            indexGeneration = generation,
            indexHighWaterMark = highWaterMark,
            indexAllocation = allocation,
        )
    }

    /** Reserve all exact matches before fallbacks, so nearby new controls cannot steal an existing id. */
    private data class Indexed(val elements: List<UiElement>, val generation: Long, val highWaterMark: Int, val allocation: AtomicLong)

    private fun assignIndices(elements: List<UiElement>, previous: ScreenState?, displayId: Int, pkg: String): Indexed {
        fun fresh() = Indexed(elements.mapIndexed { i, e -> e.copy(index = i) }, generations.incrementAndGet(),
            elements.lastIndex, AtomicLong(elements.lastIndex.toLong()))
        if (previous == null || previous.displayId != displayId || previous.foregroundPackage != pkg) return fresh()
        val available = previous.elements.toMutableList()
        val matches = arrayOfNulls<UiElement>(elements.size)
        fun match(exact: Boolean) {
            for ((i, current) in elements.withIndex()) {
                if (matches[i] != null) continue
                val found = available.asSequence().filter { old ->
                    if (old.className != current.className) false
                    else {
                        val sameNid = current.nid.isNotEmpty() && current.nid == old.nid
                        val distance = centerDistanceSquared(current, old)
                        val near = distance < 64
                        // A recycled nid with a new label must not sneak through the fallback either.
                        if (sameNid && current.label != old.label && distance > 64) false
                        else if (exact) sameNid else current.label == old.label || near
                    }
                }.minWithOrNull(compareBy<UiElement> { centerDistanceSquared(current, it) }.thenBy { it.index })
                if (found != null) {
                    matches[i] = found
                    available.remove(found)
                }
            }
        }
        match(exact = true)
        match(exact = false)
        val matched = matches.count { it != null }
        if (elements.isNotEmpty() && matched * 10 < elements.size * 3) return fresh()
        val highWater = maxOf(previous.indexHighWaterMark, previous.elements.maxOfOrNull { it.index } ?: -1)
        val allocation = previous.indexAllocation ?: AtomicLong(highWater.toLong())
        allocation.accumulateAndGet(highWater.toLong()) { allocated, captured -> maxOf(allocated, captured) }
        var next = allocation.getAndAdd((elements.size - matched).toLong()) + 1
        val indexed = elements.mapIndexed { i, e -> e.copy(index = matches[i]?.index ?: (next++).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        if ((indexed.maxOfOrNull { it.index } ?: -1).toLong() > elements.size.toLong() * 3 || next > Int.MAX_VALUE) return fresh()
        return Indexed(indexed, previous.indexGeneration.takeIf { it > 0 } ?: generations.incrementAndGet(),
            allocation.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), allocation)
    }

    private fun centerDistanceSquared(a: UiElement, b: UiElement): Long {
        val dx = a.centerX.toLong() - b.centerX; val dy = a.centerY.toLong() - b.centerY
        return dx * dx + dy * dy
    }

    private fun windowPackage(w: UiWindow) = w.packageName.ifBlank { w.root?.packageName.orEmpty() }

    private fun nodes(root: UiNode?, depth: Int = 0): Sequence<UiNode> = sequence {
        if (root == null || !root.visible || depth > 80) return@sequence
        yield(root)
        root.children.forEach { yieldAll(nodes(it, depth + 1)) }
    }

    private fun isSystemAlert(window: UiWindow, screen: Box): Boolean {
        val pkg = windowPackage(window)
        if (pkg !in alertPackages && pkg != ScreenMarkup.SYSTEM_UI) return false
        if (pkg in alertPackages && (window.bounds.intersect(screen)?.area ?: 0) * 10 < screen.area * 9) return true
        return nodes(window.root).any { n -> alertMarkers.any { n.resourceId.endsWith(it) } }
    }

    private fun alertTitle(window: UiWindow): String {
        val nodes = nodes(window.root).toList()
        val text = alertTitleIds.firstNotNullOfOrNull { id ->
            nodes.firstOrNull { it.resourceId.substringAfterLast('/') == id && it.shownText.isNotBlank() }?.shownText
        } ?: nodes.firstOrNull { it.shownText.isNotBlank() }?.shownText.orEmpty()
        return clean(text).take(maxLabel).replace('"', '\'')
    }

    private class Pending(val node: UiNode, val bounds: Box, val label: String, val traits: List<String>) {
        fun toElement(index: Int, window: UiWindow) = UiElement(
            nid = ScreenMarkup.nid(node, window.id),
            index = index,
            className = node.shortClass,
            label = label,
            text = node.shownText,
            description = node.contentDescription,
            hint = node.hint,
            resourceId = node.resourceId.substringAfter(":id/"),
            bounds = bounds,
            traits = traits,
            windowId = window.id,
            packageName = node.packageName.ifBlank { window.packageName },
            handle = node.handle,
            range = node.range,
        )
    }

    private fun collect(node: UiNode, screen: Box, window: UiWindow, out: MutableList<Pending>, depth: Int) {
        if (!node.visible || depth > 80) return
        val clipped = node.bounds.intersect(screen)
        if (clipped == null) {
            // Off-screen containers can still hold on-screen children (e.g. scrolled lists): descend.
            node.children.forEach { collect(it, screen, window, out, depth + 1) }
            return
        }
        val own = ownLabel(node)
        var consumedChildren = false
        val label = when {
            own.isNotEmpty() -> own
            node.actionable -> {
                val inner = descendantText(node).take(maxLabel)
                if (inner.isNotEmpty()) consumedChildren = true
                inner
            }
            else -> ""
        }
        if (node.actionable || label.isNotEmpty()) {
            out += Pending(node, clipped, label, traits(node))
        }
        if (!consumedChildren) node.children.forEach { collect(it, screen, window, out, depth + 1) }
        else node.children.forEach { collectActionableOnly(it, screen, window, out, depth + 1) }
    }

    /** Under a labelled container, still surface nested actionable nodes (e.g. a delete icon in a row). */
    private fun collectActionableOnly(node: UiNode, screen: Box, window: UiWindow, out: MutableList<Pending>, depth: Int) {
        if (!node.visible || depth > 80) return
        if (node.actionable) {
            collect(node, screen, window, out, depth)
            return
        }
        node.children.forEach { collectActionableOnly(it, screen, window, out, depth + 1) }
    }

    private fun ownLabel(node: UiNode): String {
        val t = clean(node.text)
        val d = clean(node.contentDescription)
        return when {
            node.password && t.isNotEmpty() -> "••••"
            t.isNotEmpty() && d.isNotEmpty() && d != t -> "$t — $d"
            t.isNotEmpty() -> t
            d.isNotEmpty() -> d
            node.editable && node.hint.isNotBlank() -> ""
            else -> ""
        }.take(maxLabel)
    }

    private fun descendantText(node: UiNode): String {
        val parts = ArrayList<String>()
        fun walk(n: UiNode, depth: Int) {
            if (depth > 12 || parts.size >= 6) return
            for (c in n.children) {
                if (!c.visible) continue
                if (c.actionable) continue
                val t = clean(c.text).ifEmpty { clean(c.contentDescription) }
                if (t.isNotEmpty() && t !in parts) parts += t
                walk(c, depth + 1)
            }
        }
        walk(node, 0)
        return parts.joinToString(" · ")
    }

    private fun traits(n: UiNode): List<String> = buildList {
        if (n.clickable) add("click")
        if (n.longClickable) add("longclick")
        if (n.editable) add("edit")
        if (n.scrollable) add("scroll")
        if (n.checkable) add(if (n.checked) "checked" else "unchecked")
        if (n.selected) add("selected")
        if (n.focused) add("focused")
        if (n.password) add("password")
        if (!n.enabled) add("disabled")
    }

    private fun clean(s: String) = s.replace(WHITESPACE, " ").trim()

    private fun render(
        capture: ScreenCapture,
        target: String,
        pkg: String,
        title: String,
        windows: List<WindowSummary>,
        elements: List<UiElement>,
        keyboard: Boolean,
        omitted: Int,
        covered: Int,
        alerts: List<UiWindow>,
    ): String = buildString {
        append("Screen ").append(target).append(" (display ").append(capture.displayId).append(", ")
            .append(capture.width).append('x').append(capture.height).append(")")
        if (pkg.isNotEmpty()) append(" · foreground app: ").append(pkg)
        if (title.isNotEmpty()) append(" \"").append(fenced(clean(title).take(80))).append('"')
        append('\n')
        // Everything below comes from apps: fenced so the model can tell observed data from instructions.
        append(OPEN_TAG).append('\n')
        val bodyStart = length
        for (alert in alerts) append("System dialog from ").append(windowPackage(alert)).append(": \"").append(alertTitle(alert)).append("\"\n")
        val shown = windows.filter { it.kind == WindowKind.APPLICATION || it.kind == WindowKind.INPUT_METHOD || it.kind == WindowKind.SYSTEM }
        if (shown.size > 1 || keyboard) {
            append("Windows (top first): ")
            append(shown.joinToString(" | ") { w ->
                val kind = when (w.kind) {
                    WindowKind.APPLICATION -> "app"
                    WindowKind.INPUT_METHOD -> "keyboard"
                    WindowKind.SYSTEM -> "system"
                    else -> w.kind.name.lowercase()
                }
                buildString {
                    append(kind)
                    val name = w.title.ifBlank { w.packageName }
                    if (name.isNotBlank()) append(' ').append(clean(name).take(40))
                    if (w.kind == WindowKind.APPLICATION) append(' ').append(w.bounds)
                    if (w.focused) append(" *focused")
                }
            })
            append('\n')
        }
        if (keyboard) append("The on-screen keyboard is open.\n")
        if (elements.isEmpty()) {
            append("(no accessible elements: the app may draw its own UI — use screenshot or coordinates)\n")
        } else {
            append("Elements [index] type \"label\" id=… (center x,y) traits:\n")
            for (e in elements) appendElement(e)
        }
        if (covered > 0) append("(").append(covered).append(" elements hidden behind other windows)\n")
        if (omitted > 0) append("(").append(omitted).append(" more elements not listed — scroll or close overlays to see them)\n")
        val body = fenced(substring(bodyStart))
        setLength(bodyStart)
        append(body).append(CLOSE_TAG).append('\n')
    }

    /** App text can never open or close the fence itself. */
    private fun StringBuilder.appendElement(e: UiElement) {
        append('[').append(e.index).append("] ").append(e.className)
        if (e.label.isNotEmpty()) append(" \"").append(e.label.replace("\"", "'")).append('"')
        else if (e.hint.isNotBlank()) append(" hint=\"").append(clean(e.hint).take(60)).append('"')
        if (e.label.isNotEmpty() && e.editable && e.hint.isNotBlank() && clean(e.hint) != e.label) {
            append(" hint=\"").append(clean(e.hint).take(60)).append('"')
        }
        if (e.resourceId.isNotBlank()) append(" id=").append(e.resourceId.take(48))
        // no element size (the centre is what actions use; fewer tokens per listing).
        append(" (").append(e.centerX).append(',').append(e.centerY).append(')')
        if (e.traits.isNotEmpty()) append(' ').append(e.traits.joinToString(","))
        append('\n')
    }

    private fun capChars(text: String): Pair<String, Int> {
        if (text.length <= maxChars) return text to 0
        val cut = text.lastIndexOf('\n', maxChars)
        val kept = text.substring(0, if (cut > 0) cut else maxChars)
        val dropped = text.substring(kept.length).count { it == '\n' }
        val close = if (OPEN_TAG in kept && CLOSE_TAG !in kept) "$CLOSE_TAG\n" else ""
        return "$kept\n(output truncated: $dropped more lines — scroll or use a narrower view)\n$close" to dropped
    }

    companion object {
        const val OPEN_TAG = "<screen_content>"
        const val CLOSE_TAG = "</screen_content>"
        private val TAG_LIKE = Regex("(?i)<\\s*/?\\s*screen_content\\s*>?")
        private val generations = AtomicLong()
        private val alertPackages = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "android")
        private val alertMarkers = setOf("alertTitle", "parentPanel", "aerr_close", "aerr_wait")
        private val alertTitleIds = listOf("permission_message", "alertTitle", "aerr_title", "message", "title")

        /** Both full listings and diffs use this escape, including malformed app-supplied fence tags. */
        private fun fenced(s: String): String = s.replace(TAG_LIKE, "[screen-content]")

        fun fenceContent(body: String): String = "$OPEN_TAG\n${fenced(body).trimEnd()}\n$CLOSE_TAG\n"

        /** Keep executor state lines and window/dialog context when replacing only the element listing. */
        fun withChanges(state: ScreenState, changes: String): String {
            val open = state.text.indexOf(OPEN_TAG)
            val header = if (open >= 0) state.text.substring(0, open) else state.text.lineSequence().first() + "\n"
            val context = if (open >= 0) state.text.substring(open + OPEN_TAG.length).trimStart('\n')
                .lineSequence().takeWhile { !it.startsWith("Elements [index]") && !it.startsWith("(no accessible elements") && it != CLOSE_TAG }
                .joinToString("\n") else ""
            val body = listOf(context.takeIf { it.isNotBlank() },
                ScreenDiff.MARKER, changes,
                state.text.lineSequence().filter { it.startsWith("(") &&
                    (it.contains(" elements hidden behind other windows)") || it.contains(" more elements not listed")) }
                    .joinToString("\n").takeIf { it.isNotBlank() }).filterNotNull().joinToString("\n")
            return header + fenceContent(body)
        }

        /** Stable hash of what matters on screen: used by stuck/loop detection. */
        fun fingerprint(pkg: String, elements: List<UiElement>): String {
            val md = MessageDigest.getInstance("SHA-1")
            md.update(pkg.toByteArray())
            for (e in elements) {
                if (VolatileText.isVolatile(e.label)) continue // clocks, battery %, typing/online indicators
                md.update(e.className.toByteArray())
                md.update(e.label.toByteArray())
                md.update(e.traits.joinToString(",").toByteArray())
                // coarse position (16px grid) so sub-pixel animation noise does not change it
                md.update("${e.bounds.left / 16},${e.bounds.top / 16},${e.bounds.width / 16},${e.bounds.height / 16}".toByteArray())
            }
            return md.digest().take(8).joinToString("") { "%02x".format(it) }
        }

        /** Structured form for MCP get_screen_state. */
        fun toJson(state: ScreenState): JSONObject = JSONObject()
            .put("target", state.target)
            .put("displayId", state.displayId)
            .put("width", state.width)
            .put("height", state.height)
            .put("foregroundPackage", state.foregroundPackage)
            .put("windowTitle", state.windowTitle)
            .put("keyboardVisible", state.keyboardVisible)
            .put("windows", JSONArray().apply {
                state.windows.forEach { w ->
                    put(JSONObject().put("id", w.id).put("type", w.kind.name.lowercase()).put("title", w.title)
                        .put("package", w.packageName).put("bounds", boxJson(w.bounds)).put("focused", w.focused))
                }
            })
            .put("elements", JSONArray().apply {
                state.elements.forEach { e ->
                    put(JSONObject().put("index", e.index).put("class", e.className).put("label", e.label)
                        .put("text", e.text).put("description", e.description).put("hint", e.hint)
                        .put("resourceId", e.resourceId).put("bounds", boxJson(e.bounds))
                        .put("center", JSONArray().put(e.centerX).put(e.centerY))
                        .put("traits", JSONArray(e.traits)).put("package", e.packageName))
                }
            })
            .put("omittedElements", state.omittedElements)
            .put("coveredElements", state.coveredElements)

        private fun boxJson(b: Box) = JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom)
    }
}
