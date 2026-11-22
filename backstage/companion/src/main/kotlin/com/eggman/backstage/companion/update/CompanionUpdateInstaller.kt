package com.eggman.backstage.companion.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Reads package identities through PackageManager and applies [CompanionUpdatePolicy.verify]. */
internal object CompanionApkVerifier {
    private val signingFlags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

    fun identity(info: PackageInfo): ApkIdentity = ApkIdentity(info.packageName, info.longVersionCode,
        // apkContentsSigners is the current signer set: the rotated-to key for a v3 lineage, every signer otherwise.
        info.signingInfo?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet().orEmpty())

    fun installed(context: Context): ApkIdentity = identity(context.packageManager.getPackageInfo(context.packageName, signingFlags))

    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        digest.digest().hex()
    }

    fun verify(context: Context, apk: File, expectedHash: String): ApkIdentity {
        val actual = sha256(apk)
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, signingFlags)
            ?: throw CompanionUpdateException(UpdateFailure.UNREADABLE_APK)
        val candidate = identity(archive)
        CompanionUpdatePolicy.verify(expectedHash, actual, installed(context), candidate)
        return candidate
    }
}

/**
 * Stages a verified APK in a PackageInstaller session restricted to this package. The session always requires the
 * user's confirmation in Android's installer; nothing here installs silently.
 */
internal object CompanionUpdateInstaller {
    const val STATUS = "com.eggman.backstage.agent.update.STATUS"
    private const val SESSION = "session"
    private const val TOKEN = "session_token"
    private const val STARTED = "session_at"
    private const val TARGET = "session_version_code"
    private const val TARGET_NAME = "session_version_name"
    private const val SESSION_LIFETIME = 24 * 60 * 60_000L

    fun stage(
        context: Context, prefs: SharedPreferences, apk: File, expectedHash: String,
        versionName: String, onPrepared: () -> Unit,
    ) {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) throw CompanionUpdateException(UpdateFailure.UNKNOWN_SOURCES)
        // Every gate runs again at the installation boundary, against the running app's current signer set.
        val target = CompanionApkVerifier.verify(context, apk, expectedHash)
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        val token = "backstage-agent-update://session/$id/${UUID.randomUUID()}"
        try {
            // Persist before commit: Android can deliver the result to a restarted process.
            check(prefs.edit().putInt(SESSION, id).putString(TOKEN, token).putLong(STARTED, System.currentTimeMillis())
                .putLong(TARGET, target.versionCode).putString(TARGET_NAME, versionName).commit()) { "Cannot save installer session" }
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    apk.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                    }
                    if (!CompanionUpdatePolicy.hashMatches(expectedHash, digest.digest().hex()))
                        throw CompanionUpdateException(UpdateFailure.CHECKSUM_MISMATCH, "APK changed while staging")
                    session.fsync(output)
                }
                val callback = Intent(context, CompanionUpdateReceiver::class.java).setAction(STATUS)
                    .setPackage(context.packageName).setData(Uri.parse(token))
                // Mutable so PackageInstaller can add its status extras; explicit to a non-exported receiver.
                val pending = PendingIntent.getBroadcast(context, id, callback, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                onPrepared()
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            clear(prefs)
            throw error
        }
        // PackageInstaller holds its own copy.
        apk.delete()
    }

    /** True when the broadcast carries this process's persisted session id and its unguessable token. */
    fun owns(prefs: SharedPreferences, intent: Intent): Boolean {
        val id = prefs.getInt(SESSION, -1)
        return id >= 0 && intent.action == STATUS && intent.dataString == prefs.getString(TOKEN, null) &&
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == id
    }

    fun targetName(prefs: SharedPreferences): String? = prefs.getString(TARGET_NAME, null)

    sealed interface Recovered {
        data object None : Recovered
        data class Updated(val versionName: String) : Recovered
        data object Waiting : Recovered
        data object Interrupted : Recovered
    }

    /**
     * Cold-start reconciliation of the one session this app persisted. A committed session younger than a day stays
     * open because Android's installer dialog can outlive this process; anything else is abandoned.
     */
    fun recover(context: Context, prefs: SharedPreferences, installedCode: Long): Recovered {
        val id = prefs.getInt(SESSION, -1)
        if (id < 0) return Recovered.None
        val installer = context.packageManager.packageInstaller
        val info = installer.mySessions.firstOrNull { it.sessionId == id }
        if (installedCode >= prefs.getLong(TARGET, Long.MAX_VALUE)) {
            val name = targetName(prefs).orEmpty()
            if (info != null) runCatching { installer.abandonSession(id) }
            clear(prefs)
            return Recovered.Updated(name)
        }
        val fresh = System.currentTimeMillis() - prefs.getLong(STARTED, 0) in 0..SESSION_LIFETIME
        if (info != null && info.isCommitted && fresh) return Recovered.Waiting
        if (info != null) runCatching { installer.abandonSession(id) }
        clear(prefs)
        return Recovered.Interrupted
    }

    fun cancel(context: Context, prefs: SharedPreferences) {
        val id = prefs.getInt(SESSION, -1)
        if (id >= 0 && context.packageManager.packageInstaller.mySessions.any { it.sessionId == id })
            runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        clear(prefs)
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(SESSION).remove(TOKEN).remove(STARTED).remove(TARGET).remove(TARGET_NAME).apply()
    }
}

/** Receives only this app's own PackageInstaller status PendingIntent; declared non-exported. */
class CompanionUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CompanionUpdateInstaller.STATUS) CompanionUpdates.installerStatus(context, intent)
    }
}
