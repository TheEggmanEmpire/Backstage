package com.eggman.backstage.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.ui.StatusText
import com.eggman.backstage.ui.theme.BackstageTheme
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal object AndroidUpdateVerifier {
    fun identity(info: PackageInfo): ApkIdentity = ApkIdentity(info.packageName, info.longVersionCode,
        info.signingInfo?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet() ?: emptySet())
    fun verify(context: Context, apk: File, expectedHash: String): ApkIdentity {
        val hash = apk.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            digest.digest().hex()
        }
        val pm = context.packageManager
        val installed = identity(pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
        val archive = requireNotNull(pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)) {
            "Android could not read the APK or its signing certificate. Update rejected."
        }
        val candidate = identity(archive)
        UpdatePolicy.verify(expectedHash, hash, installed, candidate)
        return candidate
    }
}

internal class TaskChangedException : IllegalStateException("The agent is working on a task. Tap Install and confirm that updating restarts the app.")
internal fun runningTaskKey(): String? = runCatching {
    val state = AgentAccess.state().value
    if (StatusText.isActive(state.current) || state.queueSize > 0) "${state.current?.id}:${state.queueSize}" else null
}.getOrNull()

internal object UpdateInstaller {
    const val STATUS = "com.eggman.backstage.update.STATUS"
    const val CONFIRM = "com.eggman.backstage.update.CONFIRM"

    fun stage(context: Context, repo: UpdateRepository, approvedTask: String?) {
        check(context.packageManager.canRequestPackageInstalls()) { "Allow Install unknown apps for Backstage, then try again." }
        val hash = requireNotNull(repo.expectedHash) { "Download and verify the update again" }
        // Repeat every gate at the installation boundary, including the running app's current signer set.
        val target = AndroidUpdateVerifier.verify(context, repo.apk, hash)
        if (runningTaskKey()?.let { it != approvedTask } == true) throw TaskChangedException()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(repo.apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        val token = "backstage-update://session/$id/${UUID.randomUUID()}"
        try {
            // Persist before commit: Android can restart our process to deliver a result.
            check(repo.prefs.edit().putInt("session", id).putString("sessionToken", token)
                .putLong("sessionAt", System.currentTimeMillis()).putLong("sessionVersionCode", target.versionCode)
                .putString("sessionApprovedTask", approvedTask).commit()) {
                "Cannot save installer session"
            }
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, repo.apk.length()).use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    repo.apk.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            output.write(buffer, 0, size)
                            digest.update(buffer, 0, size)
                        }
                    }
                    require(digest.digest().hex() == hash) { "APK changed while staging. Update rejected." }
                    session.fsync(output)
                }
                if (runningTaskKey()?.let { it != approvedTask } == true) throw TaskChangedException()
                val callback = Intent(context, UpdateStatusReceiver::class.java).setAction(STATUS).setData(Uri.parse(token))
                val pending = PendingIntent.getBroadcast(context, id, callback, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                // Publish before commit: an immediate status callback must never be overwritten by staging.
                repo.change { it.copy(phase = UpdatePhase.PENDING, message = "Waiting for Android installation approval.") }
                session.commit(pending.intentSender)
            }
            // PackageInstaller owns its copy during confirmation and across process death.
            repo.apk.delete()
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            clearSession(repo)
            throw error
        }
    }

    fun recover(context: Context, repo: UpdateRepository) {
        val id = repo.prefs.getInt("session", -1)
        if (id < 0) return
        val info = context.packageManager.packageInstaller.getSessionInfo(id)
        // Only our persisted updater session: never abandon Dextop's Linux shortcut installer sessions.
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val updated = installed >= repo.prefs.getLong("sessionVersionCode", Long.MAX_VALUE)
        val expired = System.currentTimeMillis() - repo.prefs.getLong("sessionAt", 0) > UpdatePolicy.DAY
        val resume = Intent(context, UpdateConfirmationActivity::class.java).setAction(CONFIRM)
            .setData(Uri.parse(repo.prefs.getString("sessionToken", ""))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val confirmation = if (!updated && info?.isCommitted == true && !expired)
            PendingIntent.getActivity(context, id, resume, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        else null
        if (confirmation == null) {
            if (info != null) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
            UpdateConfirmationActivity.pending?.cancel()
            UpdateConfirmationActivity.pending = null
            clearSession(repo)
            UpdateNotifications.clear(context)
            repo.failed(if (updated) "Backstage updated successfully." else "Previous installation ended. Tap Update to try again.")
        } else {
            UpdateConfirmationActivity.pending = confirmation
            repo.change { it.copy(phase = UpdatePhase.PENDING, message = "Android installation is pending. Continue installation or cancel it below.") }
        }
    }
    fun cancel(context: Context, repo: UpdateRepository) {
        val id = repo.prefs.getInt("session", -1)
        if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        UpdateConfirmationActivity.pending?.cancel()
        UpdateConfirmationActivity.pending = null
        clearSession(repo)
        repo.failed("Installation cancelled. Tap Update to download again.")
        UpdateNotifications.clear(context)
    }
    fun clearSession(repo: UpdateRepository) { repo.prefs.edit().remove("session").remove("sessionToken").remove("sessionAt").remove("sessionVersionCode").remove("sessionApprovedTask").apply() }
}

class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.STATUS || !AppUpdates.enabled(context)) return
        val repo = AppUpdates.repository(context)
        val id = repo.prefs.getInt("session", -1)
        if (id < 0 || intent.dataString != repo.prefs.getString("sessionToken", null) ||
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmation == null) {
                    UpdateInstaller.cancel(context, repo)
                    repo.failed("Android supplied no installation confirmation.")
                    return
                }
                val open = Intent(context, UpdateConfirmationActivity::class.java)
                    .setData(intent.data).setAction(UpdateInstaller.CONFIRM).putExtra(Intent.EXTRA_INTENT, confirmation)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pending = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                repo.change { it.copy(phase = UpdatePhase.PENDING, message = "Confirm the update in Android's installer.") }
                UpdateConfirmationActivity.pending = pending
                UpdateNotifications.notice(context, "Tap to confirm installation in Android.", pending)
                // A visible Agent UI can show the result. Background completion uses a notification.
                if (com.eggman.backstage.notify.TaskNotifier.appVisible) runCatching { pending.send() }
            }
            else -> {
                UpdateInstaller.clearSession(repo)
                repo.apk.delete()
                UpdateConfirmationActivity.pending = null
                val message = if (status == PackageInstaller.STATUS_SUCCESS) "Backstage updated successfully."
                    else "Installation failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(240) ?: "Cancelled or rejected by Android"}"
                repo.change { it.copy(phase = UpdatePhase.IDLE, message = message) }
                UpdateNotifications.clear(context)
                UpdateNotifications.notice(context, message)
            }
        }
    }
}

/** Private, user-visible bridge into the system installer. No automatic APK installation or silent privilege path. */
class UpdateConfirmationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = AppUpdates.repository(this)
        if (intent.action != UpdateInstaller.CONFIRM || intent.dataString != repo.prefs.getString("sessionToken", null)) { finish(); return }
        val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: run { finish(); return }
        fun launch() {
            pending = null
            runCatching { startActivity(confirmation) }.onFailure { repo.failed("Cannot open Android installer: ${it.message}") }
            finish()
        }
        // Re-check at confirmation, retaining the user's consent for the same still-running task.
        val task = runningTaskKey()
        if (task == null || task == repo.prefs.getString("sessionApprovedTask", null)) launch() else setContent {
            BackstageTheme {
                AlertDialog(onDismissRequest = { finish() }, title = { Text("Restart Backstage?") },
                    text = { Text("The agent is running a task. Updating restarts the app and interrupts that task.") },
                    confirmButton = { TextButton(onClick = { launch() }) { Text("Update anyway") } },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Wait") } })
            }
        }
    }
    companion object { internal var pending: PendingIntent? = null }
}
