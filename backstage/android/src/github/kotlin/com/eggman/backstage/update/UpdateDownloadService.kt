package com.eggman.backstage.update

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Streaming download with no external storage or automatic retry; cancel/process death => clean restart. */
internal class ApkDownload(
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(40, TimeUnit.MINUTES)
        .followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val onCall: (Call) -> Unit = {},
) {
    private val client = client.withGitHubRedirects()

    fun fetch(assets: UpdateAssets, file: File, version: String, freeBytes: () -> Long, progress: (Long) -> Unit): String {
        require(freeBytes() >= UpdatePolicy.requiredSpace(assets.apk.size)) {
            "Not enough storage: free at least ${UpdatePolicy.requiredSpace(assets.apk.size) / (1024 * 1024)} MB for the download and installation."
        }
        fun request(url: String) = client.newCall(Request.Builder().url(url).header("User-Agent", "Backstage/$version")
            .header("Accept-Encoding", "identity").build()).also(onCall)
        // Metadata gets its own short hard deadline, unlike the large APK.
        val sumsCall = request(assets.sums.url).apply { timeout().timeout(10, TimeUnit.SECONDS) }
        val expected = sumsCall.execute().use { response ->
            check(response.isSuccessful) { "SHA256SUMS download returned HTTP ${response.code}" }
            UpdatePolicy.checksum(response.boundedText(262_144), assets.apk.name)
        }
        try {
            // Use an empty destination. The existing file may have been marked read-only after verification.
            check(!file.exists() || file.delete()) { "Cannot clear previous update file" }
            request(assets.apk.url).execute().use { response ->
                check(response.isSuccessful) { "APK download returned HTTP ${response.code}" }
                val body = response.body ?: throw IOException("Empty APK response")
                require(body.contentLength() == -1L || body.contentLength() == assets.apk.size) { "APK download size changed" }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                file.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            require(count <= assets.apk.size) { "APK exceeds its published size" }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            progress(count)
                        }
                    }
                    output.fd.sync()
                }
                require(count == assets.apk.size) { "APK download was interrupted: size does not match" }
                require(digest.digest().hex() == expected) { "APK checksum does not match SHA256SUMS. Update rejected." }
            }
            check(file.setReadOnly()) { "Cannot protect downloaded APK" }
            return expected
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

/** User-started only. Both download and PackageInstaller staging survive the UI going into the background. */
class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var call: Call? = null
    @Volatile private var cancelled = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AppUpdates.enabled(this)) { stopSelf(); return START_NOT_STICKY }
        val repo = AppUpdates.repository(this)
        UpdateNotifications.ensureChannel(this)
        startForeground(UpdateNotifications.PROGRESS, UpdateNotifications.progress(this, repo.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive == true) return START_NOT_STICKY
        val installing = intent?.action == INSTALL
        val release = if (!installing) repo.beginDownload() else null
        if (!installing && release == null) { stopSelf(); return START_NOT_STICKY }
        job = scope.launch {
            try {
                if (installing) {
                    check(repo.state.value.phase == UpdatePhase.READY) { "Download and verify the update first" }
                    repo.change { it.copy(phase = UpdatePhase.INSTALLING, message = "Preparing Android installation…") }
                    render(repo)
                    UpdateInstaller.stage(this@UpdateDownloadService, repo, intent?.getStringExtra(APPROVED_TASK))
                } else {
                    val asset = requireNotNull(release).assets
                    var lastNotice = 0L
                    val downloadContext = coroutineContext
                    val hash = ApkDownload(onCall = { call = it; downloadContext.ensureActive() }).fetch(asset, repo.apk, repo.state.value.installed,
                        freeBytes = { StatFs(repo.directory.path).availableBytes }) { bytes ->
                        downloadContext.ensureActive()
                        // Avoid notification/binder/state spam for each network buffer.
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastNotice >= 500 || bytes == asset.apk.size) {
                            repo.change { it.copy(bytes = bytes) }
                            render(repo)
                            lastNotice = now
                        }
                    }
                    coroutineContext.ensureActive()
                    repo.change { it.copy(phase = UpdatePhase.VERIFYING) }
                    render(repo)
                    AndroidUpdateVerifier.verify(this@UpdateDownloadService, repo.apk, hash)
                    coroutineContext.ensureActive()
                    repo.ready(hash)
                    UpdateNotifications.notice(this@UpdateDownloadService, "Update verified. Tap to install.")
                }
            } catch (cancel: CancellationException) {
                repo.failed("Update cancelled. Tap Update to restart the download.")
            } catch (confirm: TaskChangedException) {
                repo.change { it.copy(phase = UpdatePhase.READY, message = confirm.message) }
                UpdateNotifications.notice(this@UpdateDownloadService, confirm.message ?: "Confirm the running task before installing.")
            } catch (error: Exception) {
                if (cancelled) {
                    repo.failed("Update cancelled. Tap Update to restart the download.")
                } else {
                    repo.failed(error.message?.take(300) ?: "Update failed")
                    UpdateNotifications.notice(this@UpdateDownloadService, repo.state.value.message ?: "Update failed")
                }
            } finally { finishOperation() }
        }.also { downloadJob ->
            // Cancellation can happen before launch executes its try/finally (for example an immediate Cancel tap).
            downloadJob.invokeOnCompletion { cause ->
                if (cause is CancellationException && repo.state.value.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING))
                    repo.failed("Update cancelled. Tap Update to restart the download.")
            }
        }
        return START_NOT_STICKY
    }

    private fun finishOperation() {
        call = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @android.annotation.SuppressLint("MissingPermission") // denied notification permission still permits user-started FGS; ordinary updates are permission-checked.
    private fun render(repo: UpdateRepository) {
        if (UpdateNotifications.allowed(this)) getSystemService(android.app.NotificationManager::class.java)
            ?.notify(UpdateNotifications.PROGRESS, UpdateNotifications.progress(this, repo.state.value))
    }
    override fun onDestroy() {
        cancelled = true
        call?.cancel()
        scope.cancel()
        super.onDestroy()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled = true
        call?.cancel()
        scope.cancel()
        stopSelf()
    }
    companion object {
        const val INSTALL = "com.eggman.backstage.update.INSTALL"
        const val APPROVED_TASK = "approved_task"
        fun start(context: Context, install: Boolean = false, approvedTask: String? = null) {
            if (!AppUpdates.enabled(context)) return
            try {
                context.startForegroundService(Intent(context, UpdateDownloadService::class.java)
                    .setAction(if (install) INSTALL else "download").putExtra(APPROVED_TASK, approvedTask))
            } catch (error: Exception) {
                AppUpdates.repository(context).change { it.copy(message = "Cannot start updater: ${error.message}") }
            }
        }
    }
}

class UpdateCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.stopService(Intent(context, UpdateDownloadService::class.java))
    }
}
