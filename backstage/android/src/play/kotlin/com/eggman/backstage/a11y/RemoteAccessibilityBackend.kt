package com.eggman.backstage.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.eggman.backstage.a11y.companion.*
import com.eggman.backstage.companion.IAgentCompanion
import com.eggman.backstage.exec.ScreenCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** No access to MirrorService, even for a screenshot fallback. Never replay an uncertain Binder action. */
internal class RemoteAccessibilityBackend(
    private val remote: IAgentCompanion,
    private val lost: () -> Unit,
    /** Owns/consumes the capture descriptor; production uses the bounded pipe transport. */
    private val readCapture: (android.os.ParcelFileDescriptor) -> ScreenCapture,
) : AccessibilityBackend {
    constructor(remote: IAgentCompanion, lost: () -> Unit) : this(remote, lost, { fd -> PipeTransport.consume(fd, CaptureCodec::read) })
    private val ids = AtomicLong()
    private val gestures = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    @Volatile private var active = true
    private data class InputCapture(val width: Int, val height: Int, val focusedEditable: Boolean)
    private val inputCaptures = ConcurrentHashMap<Int, InputCapture>()

    private fun <T> call(fallback: T, action: () -> T): T {
        if (!active) return fallback
        return try { action() } catch (_: android.os.RemoteException) { disconnect(); lost(); fallback }
        catch (_: SecurityException) { disconnect(); lost(); fallback }
    }
    fun serviceStopped() { gestures.values.forEach { it.complete(false) }; gestures.clear() }
    fun disconnect() { active = false; serviceStopped() }
    fun gestureResult(id: Long, completed: Boolean) { gestures.remove(id)?.complete(completed) }

    override fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> = call(emptyList()) { remote.windowsOn(displayId).orEmpty() }
    override fun trackedDisplays(): List<Int> = call(emptyList()) { remote.trackedDisplays()?.toList().orEmpty() }
    override fun capture(displayId: Int, width: Int, height: Int): ScreenCapture = call(ScreenCapture(displayId, width, height, emptyList())) {
        val fd = remote.capture(displayId, width, height) ?: return@call ScreenCapture(displayId, width, height, emptyList())
        runCatching { readCapture(fd) }
            .getOrDefault(ScreenCapture(displayId, width, height, emptyList()))
    }.also { snapshot ->
        val nodes = ArrayDeque<com.eggman.backstage.exec.UiNode>()
        snapshot.windows.mapNotNull { it.root }.forEach(nodes::addLast)
        var focused = false
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.editable && node.focused && node.enabled) { focused = true; break }
            node.children.forEach(nodes::addLast)
        }
        inputCaptures[displayId] = InputCapture(width, height, focused)
    }

    private fun needsSecretCompanionUpdate(displayId: Int): Boolean {
        val version = runCatching { call<Int?>(null) { remote.protocolVersion() } }.getOrNull()
        if (!active) return false
        if (version != null && version < CompanionProtocol.VERSION) return true
        // Peers without action-level capability reporting can signal unsupported input by refusing it.
        val previous = inputCaptures[displayId] ?: return false
        capture(displayId, previous.width, previous.height)
        return active && inputCaptures[displayId]?.focusedEditable == true
    }

    private fun action(handle: Any?, action: Int, display: Int = -1, text: String? = null, allowFocused: Boolean = false): Boolean {
        // A stale/nonremote handle must never silently turn into "type into whatever is focused".
        val id = (handle as? RemoteNodeHandle)?.id ?: if (allowFocused && handle == null) 0L else return false
        if (handle != null && id <= 0L) return false
        return call(false) { remote.nodeAction(id, action, display, text) }
    }
    override fun click(handle: Any?, long: Boolean) = action(handle, if (long) CompanionProtocol.LONG_CLICK else CompanionProtocol.CLICK)
    private val typingLock = Any()
    override fun setText(handle: Any?, displayId: Int, text: String): Boolean = synchronized(typingLock) {
        if (text.length > 32768 || (handle != null && (handle !is RemoteNodeHandle || handle.id <= 0L))) return false
        val session = TextInputSession.current.get()
        session?.failureHint = null
        val kind = if (session?.secret == true) CompanionProtocol.SET_SECRET_TEXT else CompanionProtocol.SET_TEXT
        val accepted = action(handle, kind, displayId, text, allowFocused = true)
        if (!accepted && kind == CompanionProtocol.SET_SECRET_TEXT && active && needsSecretCompanionUpdate(displayId)) {
            session?.failureHint = TextInputSession.SECRET_COMPANION_UPDATE
            return false
        }
        // Boolean metadata only, scoped by display. The single client serializes type+notice calls.
        if (call(false) { remote.nodeAction(0L, CompanionProtocol.CLIPBOARD_UNRESTORED, displayId, null) }) {
            session?.clipboardNotice = TextInputSession.CLIPBOARD_NOTICE
        }
        accepted
    }
    // Live platform node objects stay in the companion. Callers act on opaque capture handles instead.
    override fun focusedEditable(displayId: Int): AccessibilityNodeInfo? = null
    override fun imeEnter(displayId: Int) = call(false) { remote.imeEnter(displayId) }
    override fun scroll(handle: Any?, forward: Boolean) = action(handle, if (forward) CompanionProtocol.SCROLL_FORWARD else CompanionProtocol.SCROLL_BACKWARD)
    override fun focus(handle: Any?) = action(handle, CompanionProtocol.FOCUS)
    override fun global(action: Int) = call(false) { remote.globalAction(action) }

    override suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        val points = runCatching { GesturePoints.sample(path) }.getOrNull() ?: return@withContext false
        val id = ids.incrementAndGet()
        val result = CompletableDeferred<Boolean>()
        gestures[id] = result
        try {
            val sent = call(false) { remote.gesture(id, displayId, points, durationMs.coerceIn(1, 59_000)); true }
            sent && (withTimeoutOrNull(durationMs.coerceIn(1, 59_000) + 5_000) { result.await() } ?: false)
        } finally { gestures.remove(id) }
    }

    override suspend fun screenshot(displayId: Int): Bitmap? = withContext(Dispatchers.IO) {
        call(null) {
            val fd = remote.screenshot(displayId) ?: return@call null
            runCatching {
                val bytes = PipeTransport.imageBytes(fd)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                // A same-signed but incompatible peer must not turn a tiny compressed payload into an unbounded bitmap.
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_777_216)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }
}

/**
 * Context for callers that use the accessibility facade. This object has no platform
 * accessibility connection. Final Android accessibility methods consequently have no local screen access.
 * Background activity starts have the main app's normal restrictions; no companion privilege is implied here.
 */
internal class RemoteAccessibilityContext(context: Context) : AccessibilityService() {
    init { attachBaseContext(context.applicationContext ?: context) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun getWindows(): List<AccessibilityWindowInfo> = emptyList()
    override fun getRootInActiveWindow(): AccessibilityNodeInfo? = null
}
