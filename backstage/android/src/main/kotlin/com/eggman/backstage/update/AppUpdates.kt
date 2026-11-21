package com.eggman.backstage.update

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.eggman.backstage.entry.EntryRouter
import com.eggman.backstage.ui.AgentScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

internal enum class UpdatePhase { IDLE, DOWNLOADING, VERIFYING, READY, INSTALLING, PENDING }
internal data class UpdateState(
    val installed: String,
    val latest: UpdateRelease? = null,
    val checkedAt: Long = 0,
    val nextCheck: Long = 0,
    val checkError: String? = null,
    val checking: Boolean = false,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val bytes: Long = 0,
    val total: Long = 0,
    val message: String? = null,
) {
    val newer: Boolean get() = latest?.let { UpdatePolicy.compare(it.version, installed) > 0 } == true
    val busy: Boolean get() = phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)
}

/** Entry point for update checks, scheduling and user-confirmed installation. */
object AppUpdates {
    const val EXTRA_OPEN = "com.eggman.backstage.extra.UPDATES"
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @android.annotation.SuppressLint("StaticFieldLeak") // repository receives only applicationContext
    @Volatile private var instance: UpdateRepository? = null

    // Inspect the app manifest as well as the source-set choice, guarding accidental cross-distribution wiring.
    fun enabled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES) == true
    }.getOrDefault(false)

    internal fun repository(context: Context): UpdateRepository = instance ?: synchronized(this) {
        instance ?: UpdateRepository(context.applicationContext).also { instance = it }
    }

    fun start(context: Context) {
        if (!enabled(context)) return
        val app = context.applicationContext
        // Startup uses the persisted daily deadline and error cooldown.
        scope.launch { repository(app).check(manual = false) }
    }

    /** Called on the shell worker thread; never blocks Flutter's platform thread. */
    fun checkStatus(context: Context, manual: Boolean): Map<String, Any?> {
        if (!enabled(context)) return mapOf("enabled" to false)
        val repo = repository(context)
        repo.check(manual)
        val state = repo.state.value
        return mapOf("enabled" to true, "installed" to state.installed, "latest" to state.latest?.version,
            "newer" to state.newer, "checkedAt" to state.checkedAt, "error" to state.checkError)
    }

    fun open(context: Context) {
        if (!enabled(context)) return
        context.startActivity(Intent(context, AgentScreenActivity::class.java)
            .putExtra(EntryRouter.EXTRA_TRUST, EntryRouter.trustToken)
            .putExtra(EntryRouter.EXTRA_SCREEN, "settings").putExtra(EXTRA_OPEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
}

internal class UpdateRepository(
    val context: Context,
    private val fetchLatest: (String) -> UpdateRelease? = { GitHubReleaseClient(it).latest() },
) {
    val prefs = context.getSharedPreferences("backstage_updates", Context.MODE_PRIVATE)
    val directory = File(context.noBackupFilesDir, "updates").apply { mkdirs() }
    val apk = File(directory, "update.apk")
    private val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    val state = MutableStateFlow(UpdateState(installed,
        latest = runCatching { decode(prefs.getString("release", null)) }.getOrNull(),
        checkedAt = prefs.getLong("checked", 0), nextCheck = prefs.getLong("next", 0),
        checkError = prefs.getString("error", null)))
    var expectedHash: String? = null
        private set

    init {
        // Interrupted downloads/verified files are never trusted after process death: a fresh tap restarts cleanly.
        directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        UpdateInstaller.recover(context, this)
        UpdateNotifications.ensureChannel(context)
    }

    @Synchronized fun change(transform: (UpdateState) -> UpdateState) { state.value = transform(state.value) }

    /** Reserve the next attempt before IO, so cold starts, Home and alarms cannot multiply requests. */
    private val checkLock = Any()
    fun check(manual: Boolean) = synchronized(checkLock) {
        val now = System.currentTimeMillis()
        val next = prefs.getLong("next", 0)
        val failures = prefs.getInt("failures", 0)
        val last = prefs.getLong("attempt", 0)
        try {
            if (state.value.phase != UpdatePhase.IDLE) { arm(now + UpdatePolicy.DAY); return }
            // Reset a clock that moved backwards; keep a normal persisted server/error cooldown intact.
            val clockReset = last > now + UpdatePolicy.DAY
            if (!clockReset && (!UpdatePolicy.canCheck(now, next, last, manual) || (failures > 0 && now < next))) {
                arm(next)
                announce()
                return
            }
            // Reserve against beginDownload under the same state lock: a check already in flight cannot replace
            // the selected release halfway through an update download.
            synchronized(this) {
                if (state.value.phase != UpdatePhase.IDLE) { arm(now + UpdatePolicy.DAY); return }
                change { it.copy(checking = true) }
            }
            val reserved = now + UpdatePolicy.DAY
            prefs.edit().putLong("attempt", now).putLong("next", reserved).apply()
            arm(reserved) // watchdog-safe: even process death during the request leaves a future alarm
            val release = fetchLatest(installed)
            prefs.edit().putString("release", release?.let(::encode)).putLong("checked", now)
                .putLong("next", reserved).putInt("failures", 0).remove("error").apply()
            change { it.copy(latest = release, checkedAt = now, nextCheck = reserved, checkError = null, checking = false) }
            announce()
        } catch (error: Exception) {
            val delay = UpdatePolicy.backoff((failures + 1).coerceAtMost(100), (error as? UpdateHttpException)?.retryAfterMs ?: 0)
            val message = error.message?.take(300) ?: "Update check failed"
            prefs.edit().putLong("checked", now).putLong("next", now + delay)
                .putInt("failures", (failures + 1).coerceAtMost(100)).putString("error", message).apply()
            change { it.copy(checkedAt = now, nextCheck = now + delay, checkError = message, checking = false) }
            arm(now + delay)
        }
    }

    fun arm(at: Long = prefs.getLong("next", 0)) {
        val intent = Intent(context, UpdateAlarmReceiver::class.java).setAction(UpdateAlarmReceiver.CHECK)
        val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val now = System.currentTimeMillis()
        val deadline = if (at > now) at else now + UpdatePolicy.DAY
        context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            deadline.coerceAtLeast(now + UpdatePolicy.MANUAL_GAP), pi)
    }

    private fun announce() {
        val release = state.value.latest
        if (release == null || !state.value.newer) {
            context.getSystemService(android.app.NotificationManager::class.java)?.cancel(UpdateNotifications.AVAILABLE)
            return
        }
        if (!UpdatePolicy.shouldAnnounce(release.version, installed, prefs.getString("announced", null),
                UpdateNotifications.allowed(context))) return
        if (UpdateNotifications.available(context, release.version))
            prefs.edit().putString("announced", release.version).apply()
    }

    @Synchronized fun beginDownload(): UpdateRelease? {
        val value = state.value
        if (value.checking || !value.newer || value.phase != UpdatePhase.IDLE || prefs.getInt("session", -1) >= 0) return null
        val release = value.latest ?: return null
        expectedHash = null
        apk.delete()
        change { it.copy(phase = UpdatePhase.DOWNLOADING, bytes = 0, total = release.assets.apk.size, message = null) }
        return release
    }

    fun ready(hash: String) {
        expectedHash = hash
        change { it.copy(phase = UpdatePhase.READY, message = "Verified. Ready to install.") }
    }

    fun failed(message: String) {
        expectedHash = null
        apk.delete()
        change { it.copy(phase = UpdatePhase.IDLE, message = message) }
    }

    private fun encode(release: UpdateRelease): String {
        fun asset(a: ReleaseAsset) = JSONObject().put("name", a.name).put("browser_download_url", a.url).put("size", a.size)
        return JSONObject().put("tag_name", release.version).put("draft", false).put("prerelease", false)
            .put("assets", org.json.JSONArray().put(asset(release.assets.apk)).put(asset(release.assets.sums))).toString()
    }
    private fun decode(value: String?): UpdateRelease? = value?.let { GitHubReleaseClient.parse(JSONObject(it)) }
}

/** Daily inexact alarm + boot/package-replaced recovery; never starts a download or a foreground service. */
class UpdateAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AppUpdates.enabled(context)) return
        if (intent.action !in setOf(CHECK, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED)) return
        val repo = AppUpdates.repository(context)
        repo.arm(System.currentTimeMillis() + UpdatePolicy.DAY)
        val pending = goAsync()
        AppUpdates.scope.launch {
            try { repo.check(manual = false) } finally { pending.finish() }
        }
    }
    companion object { const val CHECK = "com.eggman.backstage.update.CHECK" }
}

/** Private notification trampoline: mints the current process trust token on a cold launch. */
class UpdateOpenActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        AppUpdates.open(this)
        finish()
    }
}
