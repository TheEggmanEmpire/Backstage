package com.eggman.backstage.host

import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Wire names used between the library, the Dextop glue and MirrorService.Backstage ("full"|"mini"|"hidden"|"in_app"|"none"). */
object PresentationNames {
    const val FULL = "full"
    const val MINI = "mini"
    const val HIDDEN = "hidden"
    const val IN_APP = "in_app"
    const val NONE = "none"

    fun wireName(presentation: DesktopHost.Presentation): String = when (presentation) {
        DesktopHost.Presentation.FULL -> FULL
        DesktopHost.Presentation.MINI -> MINI
        DesktopHost.Presentation.HIDDEN -> HIDDEN
        DesktopHost.Presentation.IN_APP -> IN_APP
        DesktopHost.Presentation.NONE -> NONE
    }

    /** Unknown or missing names map to NONE (no session / not presentable). */
    fun parse(name: String?): DesktopHost.Presentation = when (name?.trim()?.lowercase(Locale.ROOT)) {
        FULL -> DesktopHost.Presentation.FULL
        MINI -> DesktopHost.Presentation.MINI
        HIDDEN -> DesktopHost.Presentation.HIDDEN
        IN_APP -> DesktopHost.Presentation.IN_APP
        else -> DesktopHost.Presentation.NONE
    }
}

/** Decoding of shell fallbacks (screencap piped through base64, since the privileged shell returns text). */
object ShellPayloads {
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Base64 (any line wrapping) → bytes; null when empty or not base64. */
    fun decodeBase64(text: String): ByteArray? {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        return runCatching { java.util.Base64.getDecoder().decode(compact) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun isPng(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size > PNG_MAGIC.size && PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] }

    /** `screencap` argument vector for a display, piped through base64 by `sh -c`. */
    fun screencapCommand(displayId: Int): Array<String> =
        arrayOf("sh", "-c", "screencap -d $displayId -p | base64")
}

/**
 * One frame handed from a producer thread (an ImageReader listener) to a consumer that may stop waiting.
 * The producer's work ([offer]: acquire + copy) and the consumer's release ([close]: ImageReader.close, which frees
 * the buffers a copy reads) run under one lock, so the reader is never closed in the middle of a copy and nothing is
 * produced once it is closed. A value the consumer never took is handed to [discard] (e.g. Bitmap.recycle).
 */
class FrameHandoff<T : Any>(private val discard: (T) -> Unit = {}) {
    private val lock = Any()
    private val ready = CountDownLatch(1)
    private var value: T? = null
    private var taken = false
    private var closed = false

    /** Producer: runs [produce] under the lock unless closed, taken or already filled; true when a value is stored. */
    fun offer(produce: () -> T?): Boolean = synchronized(lock) {
        if (closed || taken || value != null) return false
        val produced = produce() ?: return false
        value = produced
        ready.countDown()
        true
    }

    /** Consumer: waits up to [timeoutMs] for a value and takes it; every later [offer] is refused. */
    fun take(timeoutMs: Long): T? {
        ready.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        return synchronized(lock) {
            taken = true
            value.also { value = null }
        }
    }

    /** Runs [release] under the lock (never during an [offer]); afterwards nothing is produced. Idempotent. */
    fun close(release: () -> Unit = {}) {
        synchronized(lock) {
            if (closed) return
            closed = true
            value?.let { untaken -> runCatching { discard(untaken) } }
            value = null
            release()
        }
    }
}
