package com.eggman.backstage.exec

/** Integer rectangle in display pixels (pure replacement for android.graphics.Rect so tests run on the JVM). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0
    val area: Long get() = if (isEmpty) 0 else width.toLong() * height

    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    fun containsBox(o: Box) = o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom

    fun intersect(o: Box): Box? {
        val l = maxOf(left, o.left); val t = maxOf(top, o.top)
        val r = minOf(right, o.right); val b = minOf(bottom, o.bottom)
        return if (r > l && b > t) Box(l, t, r, b) else null
    }

    override fun toString() = "[$left,$top][$right,$bottom]"

    companion object {
        val EMPTY = Box(0, 0, 0, 0)
    }
}

/**
 * Snapshot of one accessibility node with its subtree. [handle] carries the platform node
 * (AccessibilityNodeInfo) so tools can act on an element by index; it is null in tests.
 */
data class UiNode(
    val className: String = "android.view.View",
    val text: String = "",
    val contentDescription: String = "",
    val hint: String = "",
    val resourceId: String = "",
    val packageName: String = "",
    val stateDescription: String = "",
    val bounds: Box = Box.EMPTY,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val password: Boolean = false,
    val children: List<UiNode> = emptyList(),
    val handle: Any? = null,
    /** Accessibility range (min, max, current); null for indeterminate progress. */
    val range: Triple<Float, Float, Float>? = null,
) {
    val actionable: Boolean get() = clickable || longClickable || editable || scrollable || checkable
    val shortClass: String get() = className.substringAfterLast('.').ifBlank { "View" }

    /**
     * [text] as every serializer may show it: a password field's content is never shown, only that it
     * holds something. The capture already masks it ([AgentAccessibility][com.eggman.backstage.a11y.AgentAccessibility]);
     * this keeps any other source of nodes (tests, future captures) from leaking it.
   */
    val shownText: String get() = maskedText(text, password)

    companion object {
        const val PASSWORD_MASK = "••••"

        /** The text of a node as it may leave the device: [PASSWORD_MASK] for a non-empty password field. */
        fun maskedText(text: String, password: Boolean): String = if (password && text.isNotEmpty()) PASSWORD_MASK else text
    }
}

enum class WindowKind { APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, SPLIT_DIVIDER, MAGNIFICATION, OTHER }

data class UiWindow(
    val id: Int,
    val kind: WindowKind,
    val title: String = "",
    val packageName: String = "",
    val layer: Int = 0,
    val bounds: Box = Box.EMPTY,
    val active: Boolean = false,
    val focused: Boolean = false,
    val root: UiNode? = null,
)

/** Raw capture of a display: what the accessibility layer saw, before filtering/indexing. */
data class ScreenCapture(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val windows: List<UiWindow>,
    val timestampMs: Long = 0,
)

/** One indexed element the model can refer to. */
data class UiElement(
    val index: Int,
    val className: String,
    val label: String,
    val text: String,
    val description: String,
    val hint: String,
    val resourceId: String,
    val bounds: Box,
    val traits: List<String>,
    val windowId: Int,
    val packageName: String,
    val handle: Any? = null,
    /** Stable id (grouped nid) — survives re-reads while text and position are unchanged. */
    val nid: String = "",
    val range: Triple<Float, Float, Float>? = null,
) {
    val centerX: Int get() = bounds.centerX
    val centerY: Int get() = bounds.centerY
    val editable: Boolean get() = "edit" in traits
    val scrollable: Boolean get() = "scroll" in traits
}

data class WindowSummary(
    val id: Int,
    val kind: WindowKind,
    val title: String,
    val packageName: String,
    val bounds: Box,
    val focused: Boolean,
)

/** Serialized, indexed view of a display handed to the model (and to MCP get_screen_state). */
data class ScreenState(
    val displayId: Int,
    val target: String,
    val width: Int,
    val height: Int,
    val foregroundPackage: String,
    val windowTitle: String,
    val windows: List<WindowSummary>,
    val elements: List<UiElement>,
    val keyboardVisible: Boolean,
    val omittedElements: Int,
    val coveredElements: Int,
    val fingerprint: String,
    val text: String,
    /** Equal positive generations mean indices survived every intervening capture without renumbering. */
    val indexGeneration: Long = 0,
    /** Highest allocated index in this generation, including elements absent from this capture. */
    val indexHighWaterMark: Int = -1,
    /** Shared reservations also cover non-committing observe branches of this generation. */
    internal val indexAllocation: java.util.concurrent.atomic.AtomicLong? = null,
) {
    fun element(index: Int): UiElement? = elements.getOrNull(index)?.takeIf { it.index == index } ?: elements.firstOrNull { it.index == index }
}
