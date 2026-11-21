package com.eggman.backstage.exec

import java.security.MessageDigest

/**
 * grouped screen markup:
 * one `<app package="…">` block per package group (top-most window first, windows of one package
 * merged), elements tagged with their simple class name, attributes `txt`, `dsc`, `nid`, flag words,
 * `b="l,t,r,b"` and `val`. Used by MCP get_screen_state to expose grouped screen elements.
 *
 * `nid` is a stable 16-hex id: first 8 bytes of SHA-256 over text + description + class + view id +
 * window id + bounds. It stays valid while the element's text and position are unchanged.
 */
object ScreenMarkup {
    private const val NODE_BUDGET = 1_000
    private const val MAX_DEPTH = 30
    const val SYSTEM_UI = "com.android.systemui"
    private val imePackages = setOf("com.google.android.inputmethod.latin", "com.samsung.android.honeyboard", "com.touchtype.swiftkey")

    fun isIme(pkg: String) = "inputmethod" in pkg || pkg in imePackages

    /** Same textual form as android.graphics.Rect#toString so ids match across implementations. */
    private fun rectString(b: Box) = "Rect(${b.left}, ${b.top} - ${b.right}, ${b.bottom})"

    fun nid(node: UiNode, windowId: Int): String {
        val s = node.shownText + node.contentDescription + node.className + node.resourceId + windowId + rectString(node.bounds)
        return MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    }

    /** One converted node: [full] = carries flags/text; reduced containers carry only id/desc/bounds. */
    class MNode(
        val tag: String,
        val text: String,
        val desc: String,
        val nid: String,
        val bounds: Box,
        val clickable: Boolean = false,
        val scrollable: Boolean = false,
        val editable: Boolean = false,
        val checked: Boolean = false,
        val focused: Boolean = false,
        val selected: Boolean = false,
        val value: String = "",
        val children: List<MNode> = emptyList(),
    ) {
        val interactive get() = clickable || editable
        val printable get() = clickable || scrollable || editable || checked || focused || selected ||
            text.isNotBlank() || desc.isNotBlank() || value.isNotBlank()
    }

    private fun isContainer(cls: String) = "Layout" in cls || "ViewGroup" in cls

    private val noise = Regex("^[\\s.·•…]*$")
    private fun meaningfulDesc(d: String) = d.isNotBlank() && d.trim().length > 2 && !noise.matches(d)

    private fun relevant(n: UiNode): Boolean {
        if (!n.visible) return false
        if ("TextView" in n.className) return true
        if (isContainer(n.className)) {
            if (n.clickable || n.editable) return true
            val meaningful = n.children.count { c ->
                c.visible && (c.text.isNotBlank() || c.contentDescription.isNotBlank() || c.clickable || c.editable ||
                    "TextView" in c.className || "ImageView" in c.className)
            }
            return meaningful >= 2 || meaningfulDesc(n.contentDescription)
        }
        return n.text.isNotEmpty() || n.contentDescription.isNotBlank() || n.clickable || n.editable
    }

    private fun simple(cls: String) = cls.substringAfterLast('.').ifBlank { "View" }

    private fun convert(n: UiNode, windowId: Int, children: List<MNode>, reduced: Boolean): MNode? {
        if (n.bounds.isEmpty && n.className.isEmpty() && n.text.isEmpty() && n.contentDescription.isEmpty()) return null
        val id = nid(n, windowId)
        return if (reduced) {
            MNode(simple(n.className), "", if (meaningfulDesc(n.contentDescription)) n.contentDescription else "", id, n.bounds, children = children)
        } else {
            MNode(
                simple(n.className), n.shownText, n.contentDescription, id, n.bounds,
                clickable = n.clickable, scrollable = n.scrollable, editable = n.editable, checked = n.checked,
                focused = n.focusable && n.focused, selected = n.selected,
                value = if (n.editable && !n.password) n.text else "",
                children = children,
            )
        }
    }

    private fun walk(n: UiNode, windowId: Int, depth: Int, budget: IntArray, out: MutableList<MNode>) {
        if (depth >= MAX_DEPTH || budget[0] <= 0) return
        budget[0]--
        val kids = ArrayList<MNode>()
        when {
            relevant(n) -> {
                n.children.forEach { walk(it, windowId, depth + 1, budget, kids) }
                convert(n, windowId, kids, reduced = isContainer(n.className) && !n.clickable && !n.editable)?.let { out += it }
            }
            isContainer(n.className) && n.children.isNotEmpty() -> {
                n.children.forEach { walk(it, windowId, depth + 1, budget, kids) }
                convert(n, windowId, kids, reduced = true)?.let { out += it }
            }
            else -> n.children.forEach { walk(it, windowId, depth + 1, budget, out) }
        }
    }

    /** Package groups in window z-order (top first); our own packages, keyboards and SystemUI (unless alone) are excluded. */
    fun groups(capture: ScreenCapture, ownPackages: Set<String>): LinkedHashMap<String, MutableList<MNode>> {
        fun build(allowSystemUi: Boolean): LinkedHashMap<String, MutableList<MNode>> {
            val map = LinkedHashMap<String, MutableList<MNode>>()
            capture.windows.sortedByDescending { it.layer }.forEach { w ->
                val root = w.root ?: return@forEach
                val pkg = root.packageName.ifBlank { w.packageName }.ifBlank { "unknown" }
                if (pkg in ownPackages || isIme(pkg) || w.kind == WindowKind.INPUT_METHOD || w.kind == WindowKind.ACCESSIBILITY_OVERLAY) return@forEach
                if (pkg == SYSTEM_UI && !allowSystemUi) return@forEach
                val out = ArrayList<MNode>()
                walk(root, w.id, 0, intArrayOf(NODE_BUDGET), out)
                if (out.isNotEmpty()) map.getOrPut(pkg) { ArrayList() } += out
            }
            return map
        }
        return build(false).takeIf { it.isNotEmpty() } ?: build(true)
    }

    fun render(capture: ScreenCapture, ownPackages: Set<String> = emptySet()): String {
        val g = groups(capture, ownPackages)
        if (g.isEmpty()) return "<app package=\"unknown\">\n</app>"
        val sb = StringBuilder()
        g.forEach { (pkg, nodes) ->
            sb.append("<app package=\"").append(esc(pkg)).append("\">\n")
            nodes.forEach { render(it, 1, false, sb) }
            sb.append("</app>\n")
        }
        return sb.toString().trimEnd('\n')
    }

    private fun render(n: MNode, level: Int, insideInteractive: Boolean, sb: StringBuilder) {
        if (!n.printable) {
            n.children.forEach { render(it, level, insideInteractive, sb) }
            return
        }
        val indent = "  ".repeat(level)
        sb.append(indent).append('<').append(n.tag)
        if (n.text.isNotBlank()) sb.append(" txt=\"").append(esc(n.text)).append('"')
        if (n.desc.isNotBlank()) sb.append(" dsc=\"").append(esc(n.desc)).append('"')
        sb.append(" nid=\"").append(n.nid).append('"')
        if (n.clickable) sb.append(" clickable")
        if (n.scrollable) sb.append(" scrollable")
        if (n.editable) sb.append(" editable")
        if (n.checked) sb.append(" checked")
        if (n.focused) sb.append(" focused")
        if (n.selected) sb.append(" selected")
        if (n.clickable || n.scrollable || n.editable || !insideInteractive) {
            sb.append(" b=\"").append(n.bounds.left).append(',').append(n.bounds.top).append(',').append(n.bounds.right).append(',').append(n.bounds.bottom).append('"')
        }
        if (n.value.isNotBlank()) sb.append(" val=\"").append(esc(n.value)).append('"')
        if (n.children.isEmpty()) {
            sb.append(" />\n")
            return
        }
        sb.append(">\n")
        val inside = insideInteractive || n.interactive
        n.children.forEach { render(it, level + 1, inside, sb) }
        sb.append(indent).append("</").append(n.tag).append(">\n")
    }

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\n' -> append("&#10;")
            '\r' -> append("&#13;")
            else -> append(c)
        }
    }

    /** Finds a node by nid anywhere in the capture (BFS over every window, no exclusions). */
    fun findByNid(capture: ScreenCapture, nid: String): Pair<UiNode, Int>? {
        for (w in capture.windows) {
            val root = w.root ?: continue
            val queue = ArrayDeque<UiNode>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                if (nid(n, w.id) == nid) return n to w.id
                queue.addAll(n.children)
            }
        }
        return null
    }
}
