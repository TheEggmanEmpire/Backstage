package com.eggman.backstage.a11y.companion

import com.eggman.backstage.exec.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Compact, versioned screen data over a pipe, not Java serialization or platform node parceling. Lengths, depth,
 * node count and total bytes are bounded on both sides. Password masking is repeated at this trust boundary.
 */
object CaptureCodec {
    private const val MAGIC = 0x42534131 // BSA1
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_STRING_BYTES = 16 * 1024
    private const val MAX_NODES = 4096
    private const val MAX_WINDOWS = 128
    private const val MAX_DEPTH = 72

    fun write(capture: ScreenCapture, output: OutputStream, handleId: (Any?) -> Long = { (it as? RemoteNodeHandle)?.id ?: 0 }) {
        val out = DataOutputStream(LimitedOutput(output, MAX_BYTES))
        var nodes = 0
        fun string(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_STRING_BYTES) { "Accessibility string exceeds wire budget" }
            out.writeInt(bytes.size); out.write(bytes)
        }
        fun box(b: Box) { out.writeInt(b.left); out.writeInt(b.top); out.writeInt(b.right); out.writeInt(b.bottom) }
        fun node(n: UiNode, depth: Int) {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "Accessibility tree exceeds wire budget" }
            string(n.className); string(n.shownText); string(n.contentDescription); string(n.hint)
            string(n.resourceId); string(n.packageName); string(n.stateDescription); box(n.bounds)
            val flags = listOf(n.clickable, n.longClickable, n.editable, n.scrollable, n.checkable, n.checked,
                n.selected, n.focusable, n.focused, n.enabled, n.visible, n.password)
                .foldIndexed(0) { bit, acc, set -> acc or (if (set) 1 shl bit else 0) }
            out.writeInt(flags); out.writeLong(handleId(n.handle)); out.writeInt(n.children.size)
            n.children.forEach { node(it, depth + 1) }
        }
        require(capture.windows.size <= MAX_WINDOWS)
        out.writeInt(MAGIC); out.writeInt(CompanionProtocol.VERSION)
        out.writeInt(capture.displayId); out.writeInt(capture.width); out.writeInt(capture.height)
        out.writeLong(capture.timestampMs); out.writeInt(capture.windows.size)
        capture.windows.forEach { w ->
            out.writeInt(w.id); out.writeInt(w.kind.ordinal); string(w.title); string(w.packageName)
            out.writeInt(w.layer); box(w.bounds); out.writeBoolean(w.active); out.writeBoolean(w.focused)
            out.writeBoolean(w.root != null); w.root?.let { node(it, 0) }
        }
        out.flush()
    }

    fun read(input: InputStream): ScreenCapture {
        val data = DataInputStream(LimitedInput(input, MAX_BYTES))
        var nodes = 0
        fun count(max: Int): Int = data.readInt().also { require(it in 0..max) { "Invalid accessibility wire length" } }
        fun string(): String = ByteArray(count(MAX_STRING_BYTES)).also(data::readFully).toString(Charsets.UTF_8)
        fun box() = Box(data.readInt(), data.readInt(), data.readInt(), data.readInt())
        fun node(depth: Int): UiNode {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "Invalid accessibility tree depth or count" }
            val cls = string(); val text = string(); val desc = string(); val hint = string()
            val resource = string(); val pkg = string(); val state = string(); val bounds = box()
            val flags = data.readInt()
            fun flag(bit: Int) = flags and (1 shl bit) != 0
            val handle = data.readLong().let { if (it == 0L) null else RemoteNodeHandle(it) }
            val children = List(count(MAX_NODES - nodes)) { node(depth + 1) }
            return UiNode(cls, UiNode.maskedText(text, flag(11)), desc, hint, resource, pkg, state, bounds,
                flag(0), flag(1), flag(2), flag(3), flag(4), flag(5), flag(6), flag(7), flag(8), flag(9), flag(10), flag(11), children, handle)
        }
        require(data.readInt() == MAGIC && data.readInt() == CompanionProtocol.VERSION) { "Unsupported Backstage Agent capture format" }
        val display = data.readInt(); val width = data.readInt(); val height = data.readInt(); val timestamp = data.readLong()
        val windows = List(count(MAX_WINDOWS)) {
            val id = data.readInt(); val kind = WindowKind.entries.getOrNull(data.readInt()) ?: error("Invalid window kind")
            val title = string(); val pkg = string(); val layer = data.readInt(); val bounds = box()
            val active = data.readBoolean(); val focused = data.readBoolean()
            UiWindow(id, kind, title, pkg, layer, bounds, active, focused, if (data.readBoolean()) node(0) else null)
        }
        require(data.read() == -1) { "Trailing accessibility wire data" }
        return ScreenCapture(display, width, height, windows, timestamp)
    }
}

internal class LimitedOutput(private val out: OutputStream, private val max: Int) : OutputStream() {
    private var used = 0
    override fun write(b: Int) { require(++used <= max) { "Payload exceeds byte budget" }; out.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) {
        require(len <= max - used) { "Payload exceeds byte budget" }; used += len; out.write(b, off, len)
    }
    override fun flush() = out.flush()
}

internal class LimitedInput(private val input: InputStream, private val max: Int) : InputStream() {
    private var used = 0
    override fun read(): Int = input.read().also { if (it != -1) require(++used <= max) { "Payload exceeds byte budget" } }
    override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, minOf(len, max - used + 1)).also {
        if (it > 0) { used += it; require(used <= max) { "Payload exceeds byte budget" } }
    }
}
