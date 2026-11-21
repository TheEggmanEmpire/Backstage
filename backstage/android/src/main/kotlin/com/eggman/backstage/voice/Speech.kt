package com.eggman.backstage.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** Outcome of one push-to-talk capture. */
sealed interface SpeechResult {
    data class Text(val text: String) : SpeechResult
    data object NoMatch : SpeechResult
    data class Error(val message: String) : SpeechResult
}

/**
 * Push-to-talk speech-to-text through Android's SpeechRecognizer. Prefers the on-device
 * recogniser (no audio leaves the phone) when available and allowed.
 */
object SpeechCapture {

    fun available(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context) ||
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service needs a network connection"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recogniser is busy"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language not available for speech input"
        else -> "Speech recognition failed ($code)"
    }

    /**
     * True when a failed on-device attempt should be retried with the regular recogniser: the on-device service
     * exists but has no model for the language (or is otherwise unusable for this request).
   */
    fun shouldRetryOffDevice(onDevice: Boolean, error: Int): Boolean = onDevice && error in setOf(
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
    )

    /** App-side end of capture: this long without a new partial result after speech began. */
    const val SILENCE_TIMEOUT_MS = 5_000L

    /** Listen once; must be called with a main-thread-capable context. Cancel the coroutine to abort. */
    suspend fun listenOnce(
        context: Context,
        preferOnDevice: Boolean,
        language: Locale = Locale.getDefault(),
        onPartial: (String) -> Unit = {},
        onListening: (Boolean) -> Unit = {},
    ): SpeechResult = withContext(Dispatchers.Main) {
        val onDevice = preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            return@withContext SpeechResult.Error("No speech recogniser installed")
        }
        val first = attempt(context, onDevice, preferOnDevice, language, onPartial, onListening)
        if (first is Attempt.Failed && shouldRetryOffDevice(onDevice, first.code) && SpeechRecognizer.isRecognitionAvailable(context)) {
            // No on-device language pack (or a broken on-device service): fall back to the regular recogniser.
            return@withContext attempt(context, false, preferOnDevice, language, onPartial, onListening).result
        }
        first.result
    }

    private sealed interface Attempt {
        val result: SpeechResult
        data class Done(override val result: SpeechResult) : Attempt
        data class Failed(val code: Int, override val result: SpeechResult) : Attempt
    }

    private suspend fun attempt(
        context: Context,
        onDevice: Boolean,
        preferOffline: Boolean,
        language: Locale,
        onPartial: (String) -> Unit,
        onListening: (Boolean) -> Unit,
    ): Attempt {
        val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val stopForSilence = Runnable { runCatching { recognizer.stopListening() } }
        try {
            return suspendCancellableCoroutine { cont ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = onListening(true)
                    override fun onBeginningOfSpeech() {
                        main.removeCallbacks(stopForSilence)
                        main.postDelayed(stopForSilence, SILENCE_TIMEOUT_MS)
                    }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = onListening(false)
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) {
                        // Each new partial restarts the silence timer.
                        main.removeCallbacks(stopForSilence)
                        main.postDelayed(stopForSilence, SILENCE_TIMEOUT_MS)
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }?.let(onPartial)
                    }
                    override fun onResults(results: Bundle?) {
                        onListening(false)
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                        if (cont.isActive) cont.resume(Attempt.Done(if (text.isNullOrEmpty()) SpeechResult.NoMatch else SpeechResult.Text(text)))
                    }
                    override fun onError(error: Int) {
                        onListening(false)
                        val r = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            Attempt.Done(SpeechResult.NoMatch)
                        } else Attempt.Failed(error, SpeechResult.Error(errorText(error)))
                        if (cont.isActive) cont.resume(r)
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Dictation-style end-pointing: a pause of a second ends the command, very short noises do not count.
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                recognizer.startListening(intent)
                cont.invokeOnCancellation { runCatching { recognizer.cancel() } }
            }
        } finally {
            main.removeCallbacks(stopForSilence)
            runCatching { recognizer.destroy() }
        }
    }

    /** Short beep confirming that Backstage is listening (wake word / tile). */
    fun earcon() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 400)
        }
    }
}

/**
 * Text-to-speech for agent replies (lazily initialised, process-wide). Utterances are queued (never flushed by
 * the next one) and spoken one at a time; [stop] is the barge-in (wake word, new task, panel dismissed).
 * The engine is released [IDLE_RELEASE_MS] after the last utterance: a bound TTS engine keeps its
 * process (often 50-150 MB) at raised importance for as long as it stays bound. The next reply binds it again.
 */
object Speaker {
    const val RATE = 0.9f
    const val PITCH = 1.0f
    const val IDLE_RELEASE_MS = 60_000L

    private var tts: TextToSpeech? = null
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val idleRelease = Runnable { releaseIfIdle() }

    /** True while a TTS engine is bound (tests, diagnostics). */
    val engineBound: Boolean
        @Synchronized get() = tts != null
    private var ready = false
    private val pending = ArrayDeque<String>()
    private var nextId = 0L
    private val inFlight = mutableSetOf<String>()

    private val _speaking = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** True while an utterance is being spoken. */
    val speaking: kotlinx.coroutines.flow.StateFlow<Boolean> = _speaking

    /** Cleans markdown-ish output so it reads naturally. */
    fun speakable(text: String, maxChars: Int = 600): String =
        text.replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*_`#>|]+"), " ")
            .replace(Regex("https?://\\S+"), "link")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { if (it.length > maxChars) it.take(maxChars).substringBeforeLast(' ') + "…" else it }

    /** The voice language: the device locale when the engine has it, else US English. */
    fun chooseLocale(device: Locale, availability: (Locale) -> Int): Locale =
        if (availability(device) >= TextToSpeech.LANG_AVAILABLE) device else Locale.US

    @Synchronized
    fun speak(context: Context, text: String) {
        val clean = speakable(text)
        if (clean.isEmpty()) return
        main.removeCallbacks(idleRelease)
        val engine = tts
        if (engine == null) {
            pending.addLast(clean)
            tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
            return
        }
        if (ready) say(clean) else pending.addLast(clean)
    }

    @Synchronized
    private fun onInit(status: Int) {
        val engine = tts ?: return
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            // Release the failed engine, so the next reply tries a fresh one instead of queueing behind it forever.
            pending.clear()
            runCatching { engine.shutdown() }
            tts = null
            return
        }
        engine.language = chooseLocale(Locale.getDefault()) { engine.isLanguageAvailable(it) }
        engine.setSpeechRate(RATE)
        engine.setPitch(PITCH)
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { _speaking.value = true }
            override fun onDone(utteranceId: String) = finished(utteranceId)
            @Suppress("OVERRIDE_DEPRECATION") // abstract in the platform class, so it must be implemented
            override fun onError(utteranceId: String) = finished(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)
        })
        while (pending.isNotEmpty()) say(pending.removeFirst())
        scheduleReleaseIfIdle()
    }

    private fun finished(id: String) = synchronized(this) {
        inFlight.remove(id)
        if (inFlight.isEmpty()) _speaking.value = false
        scheduleReleaseIfIdle()
    }

    /** Arms the idle release once nothing is pending or being spoken (re-armed by each finished utterance). */
    private fun scheduleReleaseIfIdle() {
        if (tts == null || inFlight.isNotEmpty() || pending.isNotEmpty()) return
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    @Synchronized
    private fun releaseIfIdle() {
        // Still initialising, or speaking again: the next finished utterance re-arms the release.
        if (tts == null || !ready || inFlight.isNotEmpty() || pending.isNotEmpty()) return
        shutdown()
    }

    private fun say(text: String) {
        val id = "bs-${nextId++}"
        inFlight += id
        // QUEUE_ADD: a result never cuts off a question that is still being read (and vice versa).
        if (tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) inFlight.remove(id)
    }

    @Synchronized
    fun stop() {
        pending.clear()
        inFlight.clear()
        tts?.stop()
        _speaking.value = false
        scheduleReleaseIfIdle()
    }

    @Synchronized
    fun shutdown() {
        main.removeCallbacks(idleRelease)
        tts?.shutdown()
        tts = null
        ready = false
        pending.clear()
        inFlight.clear()
        _speaking.value = false
    }
}
