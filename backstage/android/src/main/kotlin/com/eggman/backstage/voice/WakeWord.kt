package com.eggman.backstage.voice

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.eggman.backstage.entry.SurfaceScope
import com.eggman.backstage.notify.Notifications
import com.eggman.backstage.notify.NotifyChannels
import com.eggman.backstage.ui.settings.SettingsStores
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Entry points + shared state for the wake word feature. */
object WakeWord {

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Running(val modelId: String, val done: Long, val total: Long) : DownloadState
        data class Failed(val modelId: String, val message: String) : DownloadState
        data class Done(val modelId: String) : DownloadState
    }

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download
    private var downloadJob: Job? = null

    /** Human-readable engine status for the notification and Settings. */
    val status = MutableStateFlow("Off")

    fun installer(context: Context) = VoskModelInstaller(File(context.applicationContext.filesDir, "vosk"))

    fun selectedModel(context: Context): VoskModel {
        val s = SettingsStores.get(context)
        return VoskModelCatalog.byId(s.wakeModelId) ?: VoskModelCatalog.forLanguage(java.util.Locale.getDefault().toLanguageTag())
    }

    fun modelInstalled(context: Context) = installer(context).isInstalled(selectedModel(context))

    fun micGranted(context: Context) =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts the user-confirmed download of [model]. */
    @Synchronized
    fun startDownload(context: Context, model: VoskModel) {
        if (downloadJob?.isActive == true) return
        val app = context.applicationContext
        downloadJob = SurfaceScope.launch {
            _download.value = DownloadState.Running(model.id, 0, model.sizeBytes)
            _download.value = try {
                installer(app).install(model) { done, total -> _download.value = DownloadState.Running(model.id, done, total) }
                SettingsStores.get(app).wakeModelId = model.id
                DownloadState.Done(model.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                DownloadState.Idle
            } catch (e: Exception) {
                DownloadState.Failed(model.id, e.message ?: e.javaClass.simpleName)
            }
            if (_download.value is DownloadState.Done) refresh(app)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /**
     * Start or stop the service to match settings. Must be called while Backstage is in the
     * foreground (activity, bubble tap, tile or notification action): Android only grants
     * microphone access to foreground services started from a user-visible context.
   */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val s = SettingsStores.get(app)
        if (s.wakeWordEnabled && micGranted(app) && modelInstalled(app)) {
            runCatching { app.startForegroundService(Intent(app, WakeWordService::class.java)) }
                .onFailure { status.value = "Could not start: ${it.message}" }
        } else {
            app.stopService(Intent(app, WakeWordService::class.java))
            status.value = when {
                !s.wakeWordEnabled -> "Off"
                !micGranted(app) -> "Microphone permission missing"
                else -> "Speech model not downloaded"
            }
        }
    }

    /** Restart the service so a changed model or phrase takes effect (call from a foreground context). */
    fun restart(context: Context) {
        val app = context.applicationContext
        app.stopService(Intent(app, WakeWordService::class.java))
        refresh(app)
    }

    /**
     * After boot the microphone service may not be started from the background, so we post a
     * one-tap notification; its action counts as a user interaction.
   */
    fun onBoot(context: Context) {
        val app = context.applicationContext
        if (!SettingsStores.get(app).wakeWordEnabled || !modelInstalled(app)) return
        notifyRearm(app, "Wake word paused after restart",
            "Tap Resume to listen for \"${SettingsStores.get(app).wakePhrase}\" again.", action = "Resume")
    }

    /** A visible (not minimised) notification whose button restarts listening through [WakeWordResumeActivity]. */
    fun notifyRearm(context: Context, title: String, text: String, action: String = "Retry") {
        val app = context.applicationContext
        val resume = PendingIntent.getActivity(
            app, 7, Intent(app, WakeWordResumeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        app.getSystemService(android.app.NotificationManager::class.java)?.notify(
            Notifications.ID_WAKE_WORD_ALERT,
            android.app.Notification.Builder(app, NotifyChannels.WAKE_WORD_ALERTS)
                .setSmallIcon(com.eggman.backstage.R.drawable.bs_ic_agent)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(resume)
                .addAction(android.app.Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(app, com.eggman.backstage.R.drawable.bs_ic_agent), action, resume,
                ).build())
                .build(),
        )
    }
}
