package com.eggman.backstage.voice

import org.json.JSONObject
import java.text.Normalizer

/**
 * Pure wake-phrase logic: normalisation and spotting in recogniser output. The recogniser decodes freely (no
 * grammar): Vosk silently drops grammar words missing from a model's vocabulary, which made a phrase never match.
 */
object WakePhrase {

    const val MIN_WORDS = 1
    const val MAX_WORDS = 4

    /** Lower-case, punctuation removed, accents kept (Vosk vocabularies contain accented words). */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
            .replace(Regex("[^\\p{L}\\p{N}'\\[\\] ]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** [normalize] plus accent folding, used only for comparisons. */
    fun fold(text: String): String =
        Normalizer.normalize(normalize(text), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun words(phrase: String): List<String> = normalize(phrase).split(' ').filter { it.isNotEmpty() }

    private fun foldedWords(text: String): List<String> = fold(text).split(' ').filter { it.isNotEmpty() }

    /** Problems with a configured phrase (shown in Settings); empty = OK. */
    fun validate(phrase: String): List<String> = buildList {
        val w = words(phrase)
        if (w.size < MIN_WORDS) add("Enter a wake phrase.")
        if (w.size > MAX_WORDS) add("Use at most $MAX_WORDS words.")
        if (w.size == 1 && w[0].length < 6) add("A single short word triggers by accident; use two words such as \"hey computer\".")
    }

    /** Extracts "text" (final) or "partial" from a Vosk JSON result; tolerant of junk. */
    fun recognizedText(voskJson: String?): String {
        if (voskJson.isNullOrBlank()) return ""
        return runCatching {
            val o = JSONObject(voskJson)
            o.optString("text").ifEmpty { o.optString("partial") }
        }.getOrDefault("")
    }

    /** True if [heard] contains the wake phrase words in order (adjacent). */
    fun matches(heard: String, phrase: String): Boolean {
        val target = foldedWords(phrase).filter { it != "[unk]" }
        if (target.isEmpty()) return false
        val h = foldedWords(heard)
        if (h.size < target.size) return false
        for (i in 0..h.size - target.size) {
            if ((target.indices).all { h[i + it] == target[it] }) return true
        }
        return false
    }

    /** Text spoken after the wake phrase in the same utterance (free-form recognisers only). */
    fun trailingCommand(heard: String, phrase: String): String {
        val target = foldedWords(phrase)
        if (target.isEmpty()) return ""
        val h = foldedWords(heard)
        for (i in 0..h.size - target.size) {
            if (target.indices.all { h[i + it] == target[it] }) return h.drop(i + target.size).joinToString(" ")
        }
        return ""
    }
}

/** Battery/privacy policy deciding whether the wake word engine should hold the microphone. */
object WakeWordPolicy {
    data class Inputs(
        val enabled: Boolean,
        val modelInstalled: Boolean,
        val micPermission: Boolean,
        val screenOn: Boolean,
        val charging: Boolean,
        val pauseWhenScreenOff: Boolean,
        val onlyWhileCharging: Boolean,
        val agentBusy: Boolean,
        val inCall: Boolean = false,
        /** The keyguard is locked (secure lock screen): whoever holds the phone is not known to be its owner. */
        val locked: Boolean = false,
        val pauseWhenLocked: Boolean = true,
    )

    data class Decision(val listen: Boolean, val reason: String)

    fun decide(i: Inputs): Decision = when {
        !i.enabled -> Decision(false, "Wake word is off")
        !i.micPermission -> Decision(false, "Microphone permission missing")
        !i.modelInstalled -> Decision(false, "Speech model not downloaded")
        i.inCall -> Decision(false, "Paused during a call")
        i.onlyWhileCharging && !i.charging -> Decision(false, "Paused: only listens while charging")
        i.pauseWhenScreenOff && !i.screenOn -> Decision(false, "Paused while the screen is off")
        i.pauseWhenLocked && i.locked -> Decision(false, "Paused while the phone is locked")
        i.agentBusy -> Decision(false, "Paused while a voice command is handled")
        else -> Decision(true, "Listening")
    }
}

/** One running wake-word capture (microphone + recogniser). [stop] releases everything it holds, native memory too. */
interface WakeSession {
    fun stop()
}

/** Starts wake-word captures; text callbacks carry raw recogniser output (partial or final). */
fun interface WakeEngine {
    fun start(onText: (String?) -> Unit, onError: (Throwable?) -> Unit): WakeSession
}

/**
 * Restart policy after recogniser errors: wait attempt × [stepMs] (2, 4, 6 s), give up on the error after
 * [maxRetries] retries, and count again from zero once [quietResetMs] passed without an error.
 */
class WakeRestartPolicy(
    private val maxRetries: Int = 3,
    private val stepMs: Long = 2_000L,
    private val quietResetMs: Long = 30_000L,
) {
    private var attempts = 0
    private var lastErrorAt: Long? = null

    /** Delay before the next restart, or null to give up. */
    fun onError(nowMs: Long): Long? {
        val last = lastErrorAt
        if (last != null && nowMs - last >= quietResetMs) attempts = 0
        lastErrorAt = nowMs
        attempts++
        return if (attempts > maxRetries) null else attempts * stepMs
    }

    fun reset() {
        attempts = 0
        lastErrorAt = null
    }
}

/**
 * Owns the wake-word capture: at most one [WakeSession] at a time, every session stopped (and so its native
 * recogniser freed) before another starts, and restarts with backoff after errors. [onRetry] is called when a
 * backoff delay ends; the owner re-checks its policy and calls [start] again if listening is still wanted.
 */
class WakeLoop(
    private val engine: () -> WakeEngine?,
    private val clock: () -> Long,
    private val schedule: (delayMs: Long, block: () -> Unit) -> () -> Unit,
    private val onText: (String?) -> Unit,
    private val onRetry: () -> Unit,
    private val onStatus: (Status) -> Unit = {},
    private val policy: WakeRestartPolicy = WakeRestartPolicy(),
) {
    sealed interface Status {
        data object Listening : Status
        data class Retrying(val delayMs: Long, val cause: String) : Status
        data class GaveUp(val cause: String) : Status
    }

    private var session: WakeSession? = null
    private var cancelRetry: (() -> Unit)? = null
    /** Generation counter: callbacks from a stopped session are ignored. */
    private var generation = 0

    var gaveUp = false
        private set

    val listening: Boolean get() = session != null

    /** True while a restart is pending after an error. */
    val backingOff: Boolean get() = cancelRetry != null

    /** Starts listening unless already listening, waiting for a retry, or given up. Returns true when listening. */
    fun start(): Boolean {
        if (session != null) return true
        if (gaveUp || cancelRetry != null) return false
        val e = engine() ?: return false
        val gen = ++generation
        session = try {
            e.start(onText = { if (gen == generation) onText(it) }, onError = { if (gen == generation) failed(it) })
        } catch (t: Exception) {
            failed(t)
            return false
        }
        onStatus(Status.Listening)
        return true
    }

    /** Stops and releases the current session (also cancels a pending retry when [cancelRetries]). */
    fun stop(cancelRetries: Boolean = false) {
        generation++
        session?.let { runCatching { it.stop() } }
        session = null
        if (cancelRetries) {
            cancelRetry?.invoke()
            cancelRetry = null
        }
    }

    /** A fresh start (user action, screen on): forget earlier errors. */
    fun reset() {
        cancelRetry?.invoke()
        cancelRetry = null
        gaveUp = false
        policy.reset()
    }

    private fun failed(t: Throwable?) {
        stop()
        val cause = t?.message ?: t?.javaClass?.simpleName ?: "microphone error"
        val delay = policy.onError(clock())
        if (delay == null) {
            gaveUp = true
            onStatus(Status.GaveUp(cause))
            return
        }
        onStatus(Status.Retrying(delay, cause))
        cancelRetry = schedule(delay) {
            cancelRetry = null
            onRetry()
        }
    }
}

/**
 * Holds one natively backed object (the Vosk model) that must be closed exactly once, even when its owner is
 * destroyed while the object is still being built on another thread. The loader hands the result over with
 * [adopt] on the loading thread itself, so it is never lost with a cancelled coroutine's discarded result:
 * either it is kept (and [close] frees it later) or, when the owner is already gone, it is closed at once.
 */
class OwnedResource<T : Any>(private val release: (T) -> Unit) {
    private var value: T? = null
    private var closed = false

    fun get(): T? = synchronized(this) { value }

    /** Keeps [v] (closing a different value it replaces), or closes [v] when [close] already ran. Returns whether it is kept. */
    fun adopt(v: T): Boolean {
        var drop: T? = null
        val kept = synchronized(this) {
            if (closed) {
                drop = v
                false
            } else {
                drop = value?.takeIf { it !== v }
                value = v
                true
            }
        }
        drop?.let { runCatching { release(it) } }
        return kept
    }

    /** The owner is gone: closes the held value; every later [adopt] closes its value instead of keeping it. */
    fun close() {
        val v = synchronized(this) {
            closed = true
            value.also { value = null }
        }
        v?.let { runCatching { release(it) } }
    }
}
