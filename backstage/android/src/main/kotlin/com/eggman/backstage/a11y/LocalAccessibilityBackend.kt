package com.eggman.backstage.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.os.PersistableBundle
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.eggman.backstage.exec.Box
import com.eggman.backstage.exec.ScreenCapture
import com.eggman.backstage.exec.UiNode
import com.eggman.backstage.exec.UiWindow
import com.eggman.backstage.exec.WindowKind
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume


/** Platform accessibility capture and actions for the agent. */
class LocalAccessibilityBackend(val service: AccessibilityService) : AccessibilityBackend {
    private val screenshotLock = Mutex()
    @Volatile private var lastScreenshotAt = 0L
    private val lastCaptureHadTree = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private val unrestoredClipboard = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    // ---- perception ---------------------------------------------------------------------------

    override fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> = runCatching {
        if (displayId == Display.DEFAULT_DISPLAY) service.windows.orEmpty()
        else service.windowsOnAllDisplays.get(displayId).orEmpty()
    }.getOrDefault(emptyList())

    /** Display ids the accessibility layer tracks (public displays with windows). */
    override fun trackedDisplays(): List<Int> = runCatching {
        val all = service.windowsOnAllDisplays
        (0 until all.size()).map { all.keyAt(it) }
    }.getOrDefault(emptyList())

    /** Raw snapshot of [displayId] as pure data; nodes keep their platform handle for actions. */
    override fun capture(displayId: Int, width: Int, height: Int): ScreenCapture {
        val start = (System.nanoTime() / 1_000_000)
        // AgentController uses Default and MCP uses IO. Public
        // callers can still invoke this on Main: take one snapshot there and never block its looper.
        val canWait = Looper.myLooper() != Looper.getMainLooper() && lastCaptureHadTree[displayId] == true
        while (true) {
            val capture = captureOnce(displayId, width, height)
            val hollow = capture.windows.none { it.kind == WindowKind.APPLICATION && it.root?.children?.isNotEmpty() == true }
            val remaining = 300 - ((System.nanoTime() / 1_000_000) - start)
            if (!hollow || !canWait || remaining <= 0) {
                lastCaptureHadTree[displayId] = !hollow
                return capture
            }
            try { Thread.sleep(minOf(20L, remaining)) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                lastCaptureHadTree[displayId] = false
                return capture
            }
        }
    }

    private fun captureOnce(displayId: Int, width: Int, height: Int): ScreenCapture {
        val budget = intArrayOf(MAX_NODES)
        val all = windowsOn(displayId)
        // the node budget goes to application windows first. The list is ordered top-down, so a big system
        // panel or keyboard above the app could use it up and silently cut the app's own elements; the windows keep
        // their order in the capture.
        val order = all.indices.sortedBy { if (kindOf(all[it].type) == com.eggman.backstage.exec.WindowKind.APPLICATION) 0 else 1 }
        val built = arrayOfNulls<UiWindow>(all.size)
        for (i in order) built[i] = all[i].let { w ->
            val bounds = Rect().also(w::getBoundsInScreen)
            val root = runCatching { w.root }.getOrNull()
            UiWindow(
                id = w.id,
                kind = kindOf(w.type),
                title = w.title?.toString().orEmpty(),
                packageName = root?.packageName?.toString().orEmpty(),
                layer = w.layer,
                bounds = bounds.toBox(),
                active = w.isActive,
                focused = w.isFocused,
                root = root?.let { toNode(it, 0, budget) },
            )
        }
        return ScreenCapture(displayId, width, height, built.filterNotNull(), System.currentTimeMillis())
    }

    private fun toNode(n: AccessibilityNodeInfo, depth: Int, budget: IntArray): UiNode {
        budget[0]--
        val children = if (depth >= MAX_DEPTH || budget[0] <= 0) emptyList() else buildList {
            for (i in 0 until n.childCount) {
                if (budget[0] <= 0) break
                val c = runCatching { n.getChild(i, CHILD_PREFETCH) }.getOrNull() ?: continue
                add(toNode(c, depth + 1, budget))
            }
        }
        @Suppress("DEPRECATION") val checked = n.isChecked
        return UiNode(
            className = n.className?.toString().orEmpty(),
            // Masked at capture time: no serializer, tool or MCP client ever sees a password's text.
            text = UiNode.maskedText(n.text?.toString().orEmpty(), n.isPassword),
            contentDescription = n.contentDescription?.toString().orEmpty(),
            hint = n.hintText?.toString().orEmpty(),
            resourceId = n.viewIdResourceName.orEmpty(),
            packageName = n.packageName?.toString().orEmpty(),
            stateDescription = n.stateDescription?.toString().orEmpty(),
            bounds = Rect().also(n::getBoundsInScreen).toBox(),
            clickable = n.isClickable,
            longClickable = n.isLongClickable,
            editable = n.isEditable,
            scrollable = n.isScrollable,
            checkable = n.isCheckable,
            checked = checked,
            selected = n.isSelected,
            focusable = n.isFocusable,
            focused = n.isFocused,
            enabled = n.isEnabled,
            visible = n.isVisibleToUser,
            password = n.isPassword,
            range = n.rangeInfo?.let { r -> Triple(r.min, r.max, r.current) },
            children = children,
            handle = n,
        )
    }

    // ---- node actions ---------------------------------------------------------------------------

    /** Click the node itself or its nearest clickable ancestor (labels inside buttons). */
    override fun click(handle: Any?, long: Boolean): Boolean {
        var node = handle as? AccessibilityNodeInfo ?: return false
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        repeat(6) {
            val able = if (long) node.isLongClickable else node.isClickable
            if (able && node.isEnabled && node.performAction(action)) return true
            node = node.parent ?: return false
        }
        return false
    }

    override fun setText(handle: Any?, displayId: Int, text: String): Boolean {
        unrestoredClipboard.remove(displayId)
        val requested = handle as? AccessibilityNodeInfo
        if (handle != null && requested == null) return false
        val focused = if (requested == null) focusedInput(displayId) else null
        var field = (requested ?: focused)?.let { editableWithin(it) } ?: return false
        if (!field.isFocused) runCatching { field.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        var previous = field.text?.toString().orEmpty()
        if (runCatching { field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)) {
            return waitForText(field, text, previous) == TextVerification.VERIFIED
        }
        if (readback(field, text) == TextVerification.VERIFIED) return true
        // WebView/page focus often names a container. Retry its actual focused editable child.
        val descendant = (requested ?: focusedInput(displayId))?.let { editableWithin(it, focusedOnly = true) }
        if (descendant != null && descendant !== field) {
            field = descendant
            previous = field.text?.toString().orEmpty()
            if (runCatching { field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)) {
                return waitForText(field, text, previous) == TextVerification.VERIFIED
            }
            if (readback(field, text) == TextVerification.VERIFIED) return true
        }
        val secret = TextInputSession.current.get()?.secret == true || TextInputPolicy.isSecretField(
            field.isPassword, field.isEditable, field.contentDescription?.toString().orEmpty(),
            field.hintText?.toString().orEmpty(), field.viewIdResourceName.orEmpty())
        return paste(field, text, secret, displayId)
    }

    fun clipboardCouldNotBeRestored(displayId: Int): Boolean = unrestoredClipboard[displayId] == true

    override fun textVerification(handle: Any?, displayId: Int, expected: String): TextVerification {
        val field = (handle as? AccessibilityNodeInfo)?.let { editableWithin(it) } ?: focusedEditable(displayId)
            ?: return TextVerification.FIELD_GONE
        return readback(field, expected)
    }

    private fun readback(field: AccessibilityNodeInfo, expected: String): TextVerification = runCatching {
        if (!field.refresh()) TextVerification.FIELD_GONE
        else TextInputPolicy.verify(expected, field.text?.toString().orEmpty(), field.isPassword)
    }.getOrDefault(TextVerification.FIELD_GONE)

    private fun waitForText(field: AccessibilityNodeInfo, expected: String, previous: String): TextVerification {
        val end = (System.nanoTime() / 1_000_000) + 1_000
        var status = readback(field, expected)
        val canWait = Looper.myLooper() != Looper.getMainLooper()
        while (status != TextVerification.VERIFIED && status != TextVerification.FIELD_GONE && canWait && (System.nanoTime() / 1_000_000) < end) {
            if (field.text?.toString().orEmpty() != previous || !pauseForClipboard()) break
            status = readback(field, expected)
        }
        return status
    }

    /** Bounded BFS; prefer the actual focused input even below a non-editable WebView container. */
    private fun editableWithin(node: AccessibilityNodeInfo, focusedOnly: Boolean = false): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var first: AccessibilityNodeInfo? = null
        var queued = 1
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 3_000) {
            val next = queue.removeFirst()
            if (next.isEditable) {
                if (next.isFocused) return next
                if (!focusedOnly && first == null) first = next
            }
            for (i in 0 until next.childCount) {
                if (queued >= 3_000) break
                runCatching { next.getChild(i, CHILD_PREFETCH) }.getOrNull()?.let { queue.addLast(it); queued++ }
            }
        }
        return first
    }

    private fun focusedInput(displayId: Int): AccessibilityNodeInfo? = windowsOn(displayId).firstNotNullOfOrNull {
        runCatching { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
    }

    override fun focusedEditable(displayId: Int): AccessibilityNodeInfo? = focusedInput(displayId)?.let { editableWithin(it) }

    /** Clipboard readers in WebView/Compose may run after ACTION_PASTE returns. Keep the clip
     * until readback changes (at most one second), and restore it even on service/permission loss.
     * A null/denied clipboard read cannot establish that the clipboard is empty. Preserve the
     * pasted clip in that case, except secrets, which must be cleared. Report restoration loss.
   */
    private fun paste(field: AccessibilityNodeInfo, text: String, secret: Boolean, displayId: Int): Boolean = synchronized(CLIPBOARD_LOCK) {
        val clipboard = runCatching { service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }.getOrNull() ?: return false
        val saved = runCatching { clipboard.primaryClip?.let(::ClipData) }.getOrNull()
        var installed = false
        var clearedSecret = false
        try {
            val previous = field.text?.toString().orEmpty()
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, previous.length)
            }
            // Never append on a failed select-all. The executor's ctrl+A key path is the last resort.
            if (previous.isNotEmpty() && !field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)) return false
            installed = true
            clipboard.setPrimaryClip(ClipData.newPlainText("", text).also { suppressOverlay(it, sensitive = true) })
            if (!field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return false
            val end = (System.nanoTime() / 1_000_000) + 1_000
            do {
                if (!field.refresh()) return false
                val current = field.text?.toString().orEmpty()
                if (current != previous || TextInputPolicy.verify(text, current, field.isPassword) == TextVerification.VERIFIED) {
                    return TextInputPolicy.verify(text, current, field.isPassword) == TextVerification.VERIFIED
                }
                if (Looper.myLooper() == Looper.getMainLooper() || !pauseForClipboard()) break
            } while ((System.nanoTime() / 1_000_000) < end)
            false
        } catch (_: RuntimeException) {
            // Clipboard writes can be refused when Backstage is not foregrounded.
            false
        } finally {
            if (installed) {
                val restored = runCatching {
                    if (saved != null) clipboard.setPrimaryClip(saved.also { suppressOverlay(it) })
                    else if (secret) { clipboard.clearPrimaryClip(); clearedSecret = true }
                    saved != null
                }.getOrDefault(false)
                if (!restored) {
                    unrestoredClipboard[displayId] = true
                    TextInputSession.current.get()?.clipboardNotice = TextInputSession.CLIPBOARD_NOTICE
                    // If restoring a readable clip failed too, still try to remove a secret.
                    if (secret && !clearedSecret) runCatching { clipboard.clearPrimaryClip() }
                }
            }
        }
    }

    private fun pauseForClipboard(): Boolean = try { Thread.sleep(10); true }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }

    private fun suppressOverlay(clip: ClipData, sensitive: Boolean = false) {
        clip.description.extras = PersistableBundle(clip.description.extras ?: PersistableBundle()).apply {
            putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true)
            if (sensitive) putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }

    /** Press the IME action (search/send/go/done) on the focused field; API 30+. */
    override fun imeEnter(displayId: Int): Boolean {
        val node = focusedEditable(displayId) ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    override fun scroll(handle: Any?, forward: Boolean): Boolean {
        var node = handle as? AccessibilityNodeInfo ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        repeat(4) {
            if (node.isScrollable && node.performAction(action)) return true
            node = node.parent ?: return false
        }
        return false
    }

    override fun focus(handle: Any?): Boolean = (handle as? AccessibilityNodeInfo)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true

    // ---- gestures & global actions -----------------------------------------------------------

    /**
     * Dispatches a gesture and waits for its outcome, at most the stroke time plus [CALLBACK_GRACE_MS]: if the
     * service is unbound mid-gesture (e.g. the enabled-services list is rewritten) the callback never comes.
   */
    override suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean = withTimeoutOrNull(durationMs.coerceIn(1, 59_000) + CALLBACK_GRACE_MS) {
        suspendCancellableCoroutine { cont ->
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 59_000))
            val gesture = GestureDescription.Builder().addStroke(stroke).setDisplayId(displayId).build()
            val ok = runCatching {
                service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                }, null)
            }.getOrDefault(false)
            if (!ok && cont.isActive) cont.resume(false)
        }
    } ?: false

    suspend fun tap(displayId: Int, x: Float, y: Float) = gesture(displayId, Path().apply { moveTo(x, y) }, 60)
    suspend fun longPress(displayId: Int, x: Float, y: Float, ms: Long = 700) = gesture(displayId, Path().apply { moveTo(x, y) }, ms)
    suspend fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        gesture(displayId, Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    override fun global(action: Int): Boolean = runCatching { service.performGlobalAction(action) }.getOrDefault(false)

    // ---- screenshots ----------------------------------------------------------------------------

    /**
     * Accessibility screenshot of a display (API 30+). The platform rate-limits this (about one per
     * second), so calls are serialized and spaced.
   */
    override suspend fun screenshot(displayId: Int): Bitmap? = screenshotLock.withLock {
        val wait = MIN_SCREENSHOT_INTERVAL_MS - (System.currentTimeMillis() - lastScreenshotAt)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        // Bounded: an unbound service never calls back, and this lock would then block every later screenshot.
        val bmp = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) { suspendCancellableCoroutine { cont ->
            runCatching {
                service.takeScreenshot(displayId, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        result.hardwareBuffer.close()
                        if (cont.isActive) cont.resume(soft) else soft?.recycle()
                    }

                    override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        } }
        lastScreenshotAt = System.currentTimeMillis()
        bmp
    }

    companion object {
        private val CLIPBOARD_LOCK = Any()
        private const val CHILD_PREFETCH = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
        private const val MAX_NODES = 2_500
        private const val MAX_DEPTH = 70
        private const val MIN_SCREENSHOT_INTERVAL_MS = 1_050L
        private const val CALLBACK_GRACE_MS = 5_000L
        private const val SCREENSHOT_TIMEOUT_MS = 5_000L

        fun kindOf(type: Int): WindowKind = when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APPLICATION
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.INPUT_METHOD
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.SYSTEM
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowKind.ACCESSIBILITY_OVERLAY
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> WindowKind.SPLIT_DIVIDER
            AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> WindowKind.MAGNIFICATION
            else -> WindowKind.OTHER
        }

        private fun Rect.toBox() = Box(left, top, right, bottom)
    }
}

/** Readback classifications contain no user text and are safe to include in tool results. */
enum class TextVerification(val message: String) {
    VERIFIED("verified"),
    LENGTH_LIMIT("field kept a prefix; possible length limit"),
    FILTERED("field kept a subsequence; characters filtered"),
    NOTHING_TAKEN("nothing taken by the field"),
    FIELD_GONE("field gone; observe and select the input again"),
    MISMATCH("field text differs from the requested text"),
}

/** Pure field classification and readback logic. */
object TextInputPolicy {
    private val secretWords = Regex("(?<![\\p{L}\\p{N}])(?:otp|one[ -]?time(?: password| code)?|verification code|security code|authentication code|auth code|sms code|login code|2fa|mfa|tan[ -]?nummer|transaktionsnummer|bestätigungscode|bestatigungscode|einmalcode|einmalpasswort|sicherheitscode)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val pinWord = Regex("(?<![\\p{L}\\p{N}])pin(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val pinCode = Regex("(?<![\\p{L}\\p{N}])pin\\s+code(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val postalField = Regex("(?<![\\p{L}\\p{N}])(?:postal|zip|address|postcode|pincode)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val credentialContext = Regex("(?<![\\p{L}\\p{N}])(?:password|passcode|otp|security|authentication|auth|login|credential|2fa|mfa)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val numericInput = Regex("\\+?[0-9]+")
    private val groupedComma = Regex("[+-]?[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?")
    private val groupedDot = Regex("[+-]?[0-9]{1,3}(?:\\.[0-9]{3})+(?:,[0-9]+)?")
    private val secretSubstrings = Regex("認証コード|確認コード|ワンタイム|暗証番号|验证码|校验码|动态密码|인증번호|인증코드|일회용|सत्यापन कोड|ओटीपी")

    fun isSecretField(password: Boolean = false, editable: Boolean = false, label: String = "", hint: String = "", resourceId: String = ""): Boolean {
        if (password) return true
        if (!editable) return false
        val names = listOf(label, hint, resourceId.substringAfterLast('/')).joinToString(" ")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ')
        val exactTan = listOf(label, hint, resourceId.substringAfterLast('/')).any { it.trim() == "TAN" }
        if (exactTan || secretWords.containsMatchIn(names) || secretSubstrings.containsMatchIn(names)) return true
        if (!pinWord.containsMatchIn(names)) return false
        if (credentialContext.containsMatchIn(names)) return true
        return !postalField.containsMatchIn(names) && !pinCode.containsMatchIn(names)
    }

    fun verify(expected: String, actual: String?, password: Boolean = false): TextVerification {
        if (actual == null) return TextVerification.FIELD_GONE
        if (if (password) expected.length == actual.length else expected == actual) return TextVerification.VERIFIED
        if (!password) {
            fun withoutSpaces(value: String) = value.filterNot { it.isWhitespace() || it == '\u00a0' || it == '\u2009' }
            val wanted = withoutSpaces(expected)
            val found = withoutSpaces(actual)
            if (wanted == found) return TextVerification.VERIFIED
            // Only digit-only inputs can gain phone/card punctuation. Existing decimal points,
            // signs, email separators, etc. must survive exactly; they cannot be erased as format.
            if (numericInput.matches(wanted) && !found.startsWith('-') && found.filterNot { it in "()-./" } == wanted) {
                return TextVerification.VERIFIED
            }
            // Strip only complete thousands groups, preserving an existing decimal and sign.
            if (',' !in wanted && groupedComma.matches(found) && found.replace(",", "") == wanted) return TextVerification.VERIFIED
            if ('.' !in wanted && groupedDot.matches(found) && found.replace(".", "") == wanted) return TextVerification.VERIFIED
        }
        if (actual.isEmpty()) return TextVerification.NOTHING_TAKEN
        if (password) return if (actual.length < expected.length) TextVerification.LENGTH_LIMIT else TextVerification.MISMATCH
        if (expected.startsWith(actual)) return TextVerification.LENGTH_LIMIT
        var pos = 0
        for (character in expected) if (pos < actual.length && actual[pos] == character) pos++
        return if (pos == actual.length) TextVerification.FILTERED else TextVerification.MISMATCH
    }
}
