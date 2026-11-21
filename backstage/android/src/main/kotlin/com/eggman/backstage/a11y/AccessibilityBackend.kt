package com.eggman.backstage.a11y

import android.graphics.Bitmap
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.eggman.backstage.exec.ScreenCapture
import kotlinx.coroutines.asContextElement

/** Screen operations provided by the app's accessibility service. */
interface AccessibilityBackend {
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo>
    fun trackedDisplays(): List<Int>
    fun capture(displayId: Int, width: Int, height: Int): ScreenCapture
    fun click(handle: Any?, long: Boolean = false): Boolean
    fun setText(handle: Any?, displayId: Int, text: String): Boolean
    /** Null means the backend cannot verify the resulting text. */
    fun textVerification(handle: Any?, displayId: Int, expected: String): TextVerification? = null
    fun focusedEditable(displayId: Int): AccessibilityNodeInfo?
    fun imeEnter(displayId: Int): Boolean
    fun scroll(handle: Any?, forward: Boolean): Boolean
    fun focus(handle: Any?): Boolean
    suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean
    fun global(action: Int): Boolean
    suspend fun screenshot(displayId: Int): Bitmap?
}

/** A typing call's secret/clipboard state follows coroutine dispatcher changes, without changing
 * Executor's public setText signature. */
class TextInputSession(val secret: Boolean = false) {
    @Volatile var clipboardNotice: String? = null

    companion object {
        const val CLIPBOARD_NOTICE = "Clipboard could not be restored."
        val current = ThreadLocal<TextInputSession?>()

        suspend fun <T> withSession(secret: Boolean = false, block: suspend (TextInputSession) -> T): T {
            val inherited = current.get()
            val session = inherited?.takeIf { !secret || it.secret } ?: TextInputSession(secret)
            return kotlinx.coroutines.withContext(current.asContextElement(session)) { block(session) }
        }

        fun <T> onThread(secret: Boolean, block: () -> T): T {
            val previous = current.get()
            current.set(TextInputSession(secret))
            return try { block() } finally {
                if (previous == null) current.remove() else current.set(previous)
            }
        }
    }
}
