package com.eggman.backstage.voice

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.notify.Notifications
import com.eggman.backstage.notify.NotifyChannels
import com.eggman.backstage.ui.settings.SettingsStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Set while another surface (chat/bubble push-to-talk) uses the microphone. */
object VoiceSession {
    val active = MutableStateFlow(false)
}

/** Vosk capture: one recogniser + one microphone reader per session; [WakeSession.stop] frees both. */
internal class VoskWakeEngine(private val model: Model, private val sampleRate: Float) : WakeEngine {
    override fun start(onText: (String?) -> Unit, onError: (Throwable?) -> Unit): WakeSession {
        // Free decoding (no grammar): grammar mode silently drops words the model does not know.
        val recognizer = Recognizer(model, sampleRate)
        val speech = try {
            SpeechService(recognizer, sampleRate)
        } catch (t: Throwable) {
            recognizer.close()
            throw t
        }
        val text = onText
        val fail = onError // not shadowed by the listener's own onError below
        val listener = object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) = text(hypothesis)
            override fun onResult(hypothesis: String?) = text(hypothesis)
            override fun onFinalResult(hypothesis: String?) = text(hypothesis)
            override fun onError(exception: Exception?) = fail(exception)
            override fun onTimeout() = Unit
        }
        if (!speech.startListening(listener)) {
            speech.shutdown()
            recognizer.close()
            throw IllegalStateException("microphone busy")
        }
        return object : WakeSession {
            private var stopped = false
            override fun stop() {
                if (stopped) return
                stopped = true
                runCatching { speech.stop() }
                runCatching { speech.shutdown() }
                // SpeechService.shutdown releases only the AudioRecord; the native decoder is freed here.
                runCatching { recognizer.close() }
            }
        }
    }
}

/**
 * Microphone foreground service that spots the wake phrase on-device with Vosk, then captures the command with
 * the system recogniser and submits it as a task (or answers the pending question, or stops the task).
 *
 * It needs only its persistent notification — no overlay, bubble or widget must be visible.
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The loaded model: closed in onDestroy, or by the loader itself when the service died during the load. */
    private val vosk = OwnedResource<Model> { it.close() }
    private val model: Model? get() = vosk.get()
    private val loop = WakeLoop(
        engine = { model?.let { VoskWakeEngine(it, SAMPLE_RATE) } },
        clock = { android.os.SystemClock.elapsedRealtime() },
        schedule = { delayMs, block ->
            val job = scope.launch { delay(delayMs); block() }
            ({ job.cancel() })
        },
        onText = { json -> scope.launch { heard(json) } },
        onRetry = { evaluate() },
        onStatus = { st -> onLoopStatus(st) },
    )
    private var screenOn = true
    private var charging = false
    private var inCall = false
    private var handlingCommand = false
    private var loading = false
    private var modeListener: AudioManager.OnModeChangedListener? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // A fresh chance after the microphone is given up (another app held it).
                    loop.reset()
                }
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_USER_PRESENT -> Unit // unlocked: re-evaluated below (listening resumes when paused while locked)
                Intent.ACTION_POWER_CONNECTED -> charging = true
                Intent.ACTION_POWER_DISCONNECTED -> charging = false
            }
            evaluate()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        charging = getSystemService(BatteryManager::class.java)?.isCharging ?: false
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, RECEIVER_NOT_EXPORTED)
        getSystemService(AudioManager::class.java)?.let { am ->
            val l = AudioManager.OnModeChangedListener { mode ->
                inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
                scope.launch { evaluate() }
            }
            am.addOnModeChangedListener(mainExecutor, l)
            modeListener = l
        }
        scope.launch { VoiceSession.active.collect { evaluate() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            SettingsStores.get(this).wakeWordEnabled = false
            WakeWord.status.value = "Off"
            stopSelf()
            return START_NOT_STICKY
        }
        // Any explicit (re)start, e.g. the "Retry" notification action, forgets earlier microphone errors.
        loop.reset()
        getSystemService(android.app.NotificationManager::class.java)?.cancel(Notifications.ID_WAKE_WORD_ALERT)
        val ok = runCatching {
            startForeground(Notifications.ID_WAKE_WORD, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }.isSuccess
        if (!ok) {
            // Started from the background without a user interaction: Android refuses mic FGS.
            WakeWord.status.value = "Open Backstage to start listening"
            stopSelf()
            return START_NOT_STICKY
        }
        loadModel()
        return START_STICKY
    }

    private fun notification(text: String) = Notifications.foreground(
        this, NotifyChannels.WAKE_WORD,
        "Listening for \"${SettingsStores.get(this).wakePhrase}\"", text,
        listOf(
            "Turn off" to PendingIntent.getService(
                this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_TURN_OFF),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ),
    )

    private fun updateNotification(text: String) {
        WakeWord.status.value = text
        getSystemService(android.app.NotificationManager::class.java)?.notify(Notifications.ID_WAKE_WORD, notification(text))
    }

    private fun loadModel() {
        if (model != null || loading) return evaluate()
        loading = true
        val installer = WakeWord.installer(this)
        val selected = WakeWord.selectedModel(this)
        scope.launch {
            // The native constructor cannot be interrupted. The model is adopted on the loading thread: when the service
            // is destroyed meanwhile (restart after a phrase/model change, "Turn off"), it is closed there instead of
            // being dropped with this coroutine's cancelled result (org.vosk.Model has no finalizer).
            val loaded = withContext(Dispatchers.IO) {
                runCatching { Model(installer.dir(selected).absolutePath) }.map { vosk.adopt(it) }
            }
            loading = false
            loaded.onSuccess { kept -> if (kept) evaluate() }
                .onFailure { updateNotification("Speech model failed to load: ${it.message}"); stopSelf() }
        }
    }

    private fun evaluate() {
        val s = SettingsStores.get(this)
        val decision = WakeWordPolicy.decide(
            WakeWordPolicy.Inputs(
                enabled = s.wakeWordEnabled,
                modelInstalled = model != null || WakeWord.modelInstalled(this),
                micPermission = WakeWord.micGranted(this),
                screenOn = screenOn,
                charging = charging,
                pauseWhenScreenOff = s.wakePauseWhenScreenOff,
                onlyWhileCharging = s.wakeOnlyWhileCharging,
                agentBusy = handlingCommand || VoiceSession.active.value,
                inCall = inCall,
                locked = deviceLocked(),
                pauseWhenLocked = s.wakePauseWhenLocked,
            ),
        )
        if (!s.wakeWordEnabled || !WakeWord.micGranted(this)) {
            stopListening()
            stopSelf()
            return
        }
        if (decision.listen && model != null) loop.start() else loop.stop()
        when {
            loop.gaveUp -> Unit // status set by onLoopStatus
            loop.backingOff -> Unit
            model == null && decision.listen -> updateNotification("Loading speech model…")
            else -> updateNotification(decision.reason)
        }
    }

    private fun stopListening() = loop.stop()

    private fun onLoopStatus(st: WakeLoop.Status) {
        when (st) {
            WakeLoop.Status.Listening -> Unit
            is WakeLoop.Status.Retrying -> updateNotification("Microphone busy, retrying in ${st.delayMs / 1000} s")
            is WakeLoop.Status.GaveUp -> {
                updateNotification("Microphone unavailable: ${st.cause}")
                WakeWord.notifyRearm(this, "Wake word stopped", "The microphone was busy. Tap Retry to listen again.")
            }
        }
    }

    private fun heard(json: String?) {
        if (handlingCommand) return
        if (WakePhrase.matches(WakePhrase.recognizedText(json), SettingsStores.get(this).wakePhrase)) onWake()
    }

    private fun onWake() {
        handlingCommand = true
        stopListening()
        // Barge-in: a reply being read aloud stops when the user speaks the wake phrase.
        Speaker.stop()
        updateNotification("Listening for your command…")
        // A running task waits (between steps) while the user speaks; a pending question is answered by voice.
        val paused = AgentAccess.controller()?.pause() == true
        scope.launch {
            try {
                SpeechCapture.earcon()
                val s = SettingsStores.get(this@WakeWordService)
                val result = SpeechCapture.listenOnce(this@WakeWordService, s.preferOnDeviceStt)
                when (result) {
                    is SpeechResult.Text -> {
                        val current = AgentAccess.state().value.current
                        val command = VoiceCommand.classify(result.text, current)
                        val locked = deviceLocked()
                        // Locked: a new task only with both opt-ins ("Keep the desktop running while locked", and this
                        // wake word listening while locked), and only for a desktop that can work while locked.
                        val desktopWhileLocked = locked && !s.wakePauseWhenLocked && runCatching {
                            com.eggman.backstage.agent.LockedDesktop.worksWhileLocked(
                                AgentAccess.host(), com.eggman.backstage.data.Settings(this@WakeWordService).mayAutoStartDesktop,
                            )
                        }.getOrDefault(false)
                        if (locked && !VoiceCommand.allowedWhileLocked(command, desktopWhileLocked)) {
                            // Checked when the command is heard, not only when listening started: the phone may have
                            // locked meanwhile, and the user may have chosen to keep listening while locked.
                            toast("Unlock the phone to give Backstage a command")
                            return@launch
                        }
                        when (command) {
                            is VoiceCommand.Answer -> { AgentAccess.answer(command.text); toast("Answered: ${result.text}") }
                            VoiceCommand.Stop -> { AgentAccess.cancel(current?.id); toast("Stopped") }
                            // said while the wake paused a running task, it corrects that task (same
                            // conversation) instead of queueing a new one behind it.
                            is VoiceCommand.NewTask -> if (locked) {
                                // Never the phone while it is locked: the desktop only.
                                val handle = AgentAccess.submit(TaskRequest(command.text, Source.WAKE_WORD,
                                    target = com.eggman.backstage.agent.api.Target.DESKTOP, waiverText = null))
                                toast(if (handle == null) "Backstage agent is not ready" else "On it (on the desktop)")
                            } else if (paused && AgentAccess.controller()?.resume(current?.id, command.text) == true) {
                                toast("Added to the running task: ${result.text}")
                            } else {
                                val handle = AgentAccess.submit(TaskRequest(command.text, Source.WAKE_WORD,
                                    target = com.eggman.backstage.entry.CurrentTarget.forMessage(s.defaultTarget, command.text),
                                    waiverText = command.text))
                                if (handle == null) toast("Backstage agent is not ready")
                                else toast("On it: ${result.text}")
                            }
                        }
                    }
                    is SpeechResult.Error -> toast(result.message)
                    SpeechResult.NoMatch -> Unit
                }
            } finally {
                if (paused) AgentAccess.controller()?.resume()
                // Hand the microphone back to the wake word a little after the command capture released it.
                delay(MIC_HANDOFF_MS)
                handlingCommand = false
                evaluate()
            }
        }
    }

    /** A secure keyguard is up (swipe-only lock screens are never "locked"). */
    private fun deviceLocked(): Boolean = getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked == true

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        loop.stop(cancelRetries = true)
        runCatching { unregisterReceiver(receiver) }
        modeListener?.let { l -> getSystemService(AudioManager::class.java)?.removeOnModeChangedListener(l) }
        vosk.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TURN_OFF = "com.eggman.backstage.voice.TURN_OFF"
        private const val SAMPLE_RATE = 16000f
        /** Pause between the end of a command capture and the wake word listening again. */
        private const val MIC_HANDOFF_MS = 1_500L
    }
}
