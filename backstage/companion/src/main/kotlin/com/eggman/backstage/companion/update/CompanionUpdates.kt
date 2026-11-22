package com.eggman.backstage.companion.update

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.eggman.backstage.companion.R
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-update for Backstage Agent from this repository's GitHub releases. Network access happens only when the user
 * taps Check or Download in the update section; [available] answers from the last stored check and never connects.
 */
object CompanionUpdates {
    @SuppressLint("StaticFieldLeak") // holds only the application context
    @Volatile private var instance: CompanionUpdateRepository? = null
    private val sections = WeakHashMap<Activity, UpdateSection>()
    private val tracked = WeakHashMap<Activity, Boolean>()

    internal fun repository(context: Context): CompanionUpdateRepository = instance ?: synchronized(this) {
        instance ?: CompanionUpdateRepository(context.applicationContext).also { instance = it }
    }

    /** A verified update is ready for the user's installer. */
    fun available(context: Context): Boolean = repository(context).available()

    /** Appends the update section to [root]. Safe to call again whenever the activity rebuilds its content. */
    fun attach(activity: Activity, root: LinearLayout) {
        val repo = repository(activity)
        val resumed = synchronized(sections) {
            sections.remove(activity)?.let { previous -> previous.dispose(); previous.resumed } ?: false
        }
        val section = UpdateSection(activity, repo, root).also { it.resumed = resumed }
        synchronized(sections) { sections[activity] = section }
        if (synchronized(tracked) { tracked.put(activity, true) } == null) activity.registerActivityLifecycleCallbacks(Lifecycle())
        section.render(repo.state)
    }

    internal fun installerStatus(context: Context, intent: Intent) = repository(context).installerStatus(intent)

    private class Lifecycle : Application.ActivityLifecycleCallbacks {
        private fun section(activity: Activity) = synchronized(sections) { sections[activity] }
        override fun onActivityResumed(activity: Activity) { section(activity)?.resumed(true) }
        override fun onActivityPaused(activity: Activity) { section(activity)?.resumed = false }
        override fun onActivityDestroyed(activity: Activity) {
            synchronized(sections) { sections.remove(activity) }?.dispose()
            activity.unregisterActivityLifecycleCallbacks(this)
            synchronized(tracked) { tracked.remove(activity) }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}

internal enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, VERIFYING, READY, INSTALLING, PENDING }

internal data class Notice(val text: Int, val arg: String? = null)

internal data class CompanionUpdateState(
    val installedName: String,
    val installedCode: Long,
    val latest: CompanionRelease? = null,
    val checkedAt: Long = 0,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val bytes: Long = 0,
    val notice: Notice? = null,
    val confirmation: Boolean = false,
) {
    val newer: Boolean get() = CompanionUpdatePolicy.isNewer(latest?.version, installedName)
    val downloadCandidate: Boolean get() = latest?.let {
        CompanionUpdatePolicy.version(installedName) != null &&
            CompanionUpdatePolicy.compare(it.version, installedName) >= 0
    } == true
    val busy: Boolean get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)
}

internal class CompanionUpdateRepository(
    context: Context,
    private val transport: (String) -> UpdateTransport = { HttpsUpdateTransport(it) },
    private val worker: Executor = Executors.newSingleThreadExecutor { Thread(it, "companion-update").apply { isDaemon = true } },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext
    val prefs: SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val directory = File(app.noBackupFilesDir, "companion-update").apply { mkdirs() }
    private val apk = File(directory, "update.apk")
    private val listeners = CopyOnWriteArraySet<(CompanionUpdateState) -> Unit>()
    private val autoLaunch = AtomicBoolean(false)
    @Volatile private var active: UpdateTransport? = null
    @Volatile private var cancelRequested = false
    @Volatile private var expectedHash: String? = null
    @Volatile var confirmation: Intent? = null
        private set
    @Volatile var awaitingUnknownSources = false
    @Volatile var state: CompanionUpdateState
        private set

    init {
        // A download never survives a process restart; a file of unknown provenance is deleted, not trusted.
        directory.listFiles()?.forEach { it.delete() }
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val base = CompanionUpdateState(info.versionName ?: "0", info.longVersionCode, loadRelease(), prefs.getLong(CHECKED, 0))
        state = when (val recovered = CompanionUpdateInstaller.recover(app, prefs, info.longVersionCode)) {
            CompanionUpdateInstaller.Recovered.None -> base
            is CompanionUpdateInstaller.Recovered.Updated ->
                base.copy(notice = Notice(R.string.companion_update_updated, recovered.versionName.ifEmpty { base.installedName }))
            CompanionUpdateInstaller.Recovered.Waiting -> base.copy(phase = UpdatePhase.PENDING, notice = Notice(R.string.companion_update_pending_restored))
            CompanionUpdateInstaller.Recovered.Interrupted -> base.copy(notice = Notice(R.string.companion_update_interrupted))
        }
    }

    fun available(): Boolean = state.phase == UpdatePhase.READY

    fun addListener(listener: (CompanionUpdateState) -> Unit) { listeners += listener }
    fun removeListener(listener: (CompanionUpdateState) -> Unit) { listeners -= listener }

    @Synchronized private fun update(transform: (CompanionUpdateState) -> CompanionUpdateState): CompanionUpdateState {
        state = transform(state)
        return state
    }
    private fun publish(transform: (CompanionUpdateState) -> CompanionUpdateState) {
        val next = update(transform)
        listeners.forEach { it(next) }
    }
    fun notice(text: Int, arg: String? = null) = publish { it.copy(notice = Notice(text, arg)) }

    /** Reserves the phase under the state lock, so two taps never start two operations. */
    private fun begin(from: Set<UpdatePhase>, to: UpdatePhase, guard: (CompanionUpdateState) -> Boolean = { true }): Boolean {
        var started = false
        publish { current ->
            if (current.phase in from && guard(current)) { started = true; current.copy(phase = to, bytes = 0, notice = null) } else current
        }
        return started
    }

    fun check() {
        val now = clock()
        val attempt = prefs.getLong(ATTEMPT, 0)
        val notBefore = prefs.getLong(NOT_BEFORE, 0)
        if (!CompanionUpdatePolicy.canCheck(now, attempt, notBefore)) {
            val wait = (maxOf(attempt + CompanionUpdatePolicy.MIN_CHECK_GAP, notBefore) - now + 999) / 1000
            notice(R.string.companion_update_wait, wait.coerceAtLeast(1).toString())
            return
        }
        if (!begin(setOf(UpdatePhase.IDLE, UpdatePhase.READY), UpdatePhase.CHECKING)) return
        prefs.edit().putLong(ATTEMPT, now).apply()
        run(UpdatePhase.IDLE) { http ->
            val release = CompanionReleaseClient(http).latest()
            storeRelease(release, now)
            // A new check replaces any download prepared for an older selection.
            expectedHash = null
            apk.delete()
            publish {
                val next = it.copy(latest = release, checkedAt = now, phase = UpdatePhase.IDLE)
                next.copy(notice = Notice(when {
                    release == null -> R.string.companion_update_none
                    next.newer -> R.string.companion_update_available
                    else -> R.string.companion_update_current
                }, release?.version))
            }
        }
    }

    fun download() {
        if (!begin(setOf(UpdatePhase.IDLE), UpdatePhase.DOWNLOADING) { it.downloadCandidate }) return
        val release = requireNotNull(state.latest)
        run(UpdatePhase.IDLE) { http ->
            var shown = 0L
            val hash = CompanionApkDownload(http, { cancelRequested }).fetch(release, apk, StatFs(directory.path).availableBytes) { bytes ->
                // Publish about every 64 KiB rather than for each network buffer.
                if (bytes - shown >= 64 * 1024 || bytes == release.apk.size) { shown = bytes; publish { it.copy(bytes = bytes) } }
            }
            publish { it.copy(phase = UpdatePhase.VERIFYING) }
            CompanionApkVerifier.verify(app, apk, hash)
            if (cancelRequested) throw CompanionUpdateException(UpdateFailure.CANCELLED)
            expectedHash = hash
            publish { it.copy(phase = UpdatePhase.READY, notice = Notice(R.string.companion_update_ready, release.version)) }
        }
    }

    /** Called only from the Install button; Android's installer then asks the user to confirm. */
    fun install() {
        val hash = expectedHash
        if (hash == null || !apk.isFile) {
            publish { if (it.phase == UpdatePhase.READY) it.copy(phase = UpdatePhase.IDLE, notice = Notice(R.string.companion_update_download_again)) else it }
            return
        }
        if (!begin(setOf(UpdatePhase.READY), UpdatePhase.INSTALLING)) return
        val version = state.latest?.version.orEmpty()
        worker.execute {
            try {
                CompanionUpdateInstaller.stage(app, prefs, apk, hash, version) {
                    // Published before commit, so an immediate installer result is never overwritten afterwards.
                    publish { it.copy(phase = UpdatePhase.PENDING, notice = Notice(R.string.companion_update_pending)) }
                }
                expectedHash = null
            } catch (error: Exception) {
                val reason = reason(error)
                expectedHash = null
                apk.delete()
                publish { it.copy(phase = UpdatePhase.IDLE, notice = failureNotice(reason)) }
            }
        }
    }

    fun cancel() {
        if (state.phase !in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) return
        cancelRequested = true
        active?.cancel()
    }

    fun cancelInstall() {
        CompanionUpdateInstaller.cancel(app, prefs)
        confirmation = null
        publish { it.copy(phase = UpdatePhase.IDLE, confirmation = false, notice = Notice(R.string.companion_update_install_cancelled)) }
    }

    fun installerStatus(intent: Intent) {
        if (!CompanionUpdateInstaller.owns(prefs, intent)) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    CompanionUpdateInstaller.cancel(app, prefs)
                    confirmation = null
                    publish { it.copy(phase = UpdatePhase.IDLE, confirmation = false, notice = failureNotice(UpdateFailure.INSTALLER)) }
                    return
                }
                confirmation = confirm
                autoLaunch.set(true)
                publish { it.copy(phase = UpdatePhase.PENDING, confirmation = true, notice = Notice(R.string.companion_update_pending)) }
            }
            else -> {
                val target = CompanionUpdateInstaller.targetName(prefs)
                CompanionUpdateInstaller.clear(prefs)
                confirmation = null
                val notice = when (status) {
                    PackageInstaller.STATUS_SUCCESS -> Notice(R.string.companion_update_updated, target ?: state.installedName)
                    PackageInstaller.STATUS_FAILURE_ABORTED -> Notice(R.string.companion_update_install_cancelled)
                    else -> Notice(R.string.companion_update_install_failed,
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(200) ?: status.toString())
                }
                publish { it.copy(phase = UpdatePhase.IDLE, confirmation = false, notice = notice) }
            }
        }
    }

    /** One automatic opening of Android's confirmation per installer request, and only for a visible activity. */
    fun claimAutoLaunch(): Boolean = autoLaunch.getAndSet(false)

    private fun run(onFailure: UpdatePhase, body: (UpdateTransport) -> Unit) {
        cancelRequested = false
        val http = transport("BackstageAgent/${state.installedName}")
        active = http
        worker.execute {
            try {
                body(http)
            } catch (error: Exception) {
                val reason = reason(error)
                if (reason != UpdateFailure.CANCELLED) apk.delete()
                if (state.phase == UpdatePhase.CHECKING && error is CompanionUpdateException && error.retryAfterMs > 0)
                    prefs.edit().putLong(NOT_BEFORE, clock() + error.retryAfterMs).apply()
                apk.delete()
                expectedHash = null
                publish { it.copy(phase = onFailure, bytes = 0, notice = failureNotice(reason)) }
            } finally {
                if (active === http) active = null
            }
        }
    }

    private fun reason(error: Throwable): UpdateFailure = when {
        error is CompanionUpdateException -> error.reason
        cancelRequested -> UpdateFailure.CANCELLED
        error is org.json.JSONException || error is IllegalArgumentException -> UpdateFailure.METADATA
        error is IOException -> UpdateFailure.NETWORK
        else -> UpdateFailure.INSTALLER
    }

    private fun failureNotice(reason: UpdateFailure) = Notice(failureText(reason))

    private fun storeRelease(release: CompanionRelease?, checkedAt: Long) {
        val edit = prefs.edit().putLong(CHECKED, checkedAt).remove(NOT_BEFORE)
        if (release == null) RELEASE_KEYS.forEach(edit::remove) else edit
            .putString(APK_NAME, release.apk.name).putString(APK_URL, release.apk.url).putLong(APK_SIZE, release.apk.size)
            .putString(SUMS_URL, release.sums.url).putLong(SUMS_SIZE, release.sums.size)
        edit.apply()
    }

    /** The stored selection passes the same asset gates as a fresh API response, or it is discarded. */
    private fun loadRelease(): CompanionRelease? = runCatching {
        val name = prefs.getString(APK_NAME, null) ?: return null
        CompanionUpdatePolicy.releaseAssets(listOf(
            ReleaseAsset(name, requireNotNull(prefs.getString(APK_URL, null)), prefs.getLong(APK_SIZE, 0)),
            ReleaseAsset(CompanionUpdatePolicy.SUMS, requireNotNull(prefs.getString(SUMS_URL, null)), prefs.getLong(SUMS_SIZE, 0)),
        ))
    }.getOrNull()

    companion object {
        const val PREFS = "companion_updates"
        const val CHECKED = "checked_at"
        const val ATTEMPT = "attempt_at"
        const val NOT_BEFORE = "not_before"
        const val APK_NAME = "apk_name"
        const val APK_URL = "apk_url"
        const val APK_SIZE = "apk_size"
        const val SUMS_URL = "sums_url"
        const val SUMS_SIZE = "sums_size"
        private val RELEASE_KEYS = listOf(APK_NAME, APK_URL, APK_SIZE, SUMS_URL, SUMS_SIZE)

        fun failureText(reason: UpdateFailure): Int = when (reason) {
            UpdateFailure.NETWORK -> R.string.companion_update_error_network
            UpdateFailure.HTTP -> R.string.companion_update_error_http
            UpdateFailure.RATE_LIMITED -> R.string.companion_update_error_rate_limited
            UpdateFailure.METADATA -> R.string.companion_update_error_metadata
            UpdateFailure.NO_RELEASE -> R.string.companion_update_none
            UpdateFailure.UNTRUSTED_URL -> R.string.companion_update_error_untrusted_url
            UpdateFailure.UNTRUSTED_REDIRECT -> R.string.companion_update_error_untrusted_redirect
            UpdateFailure.TOO_LARGE -> R.string.companion_update_error_too_large
            UpdateFailure.LOW_STORAGE -> R.string.companion_update_error_low_storage
            UpdateFailure.SIZE_MISMATCH -> R.string.companion_update_error_size
            UpdateFailure.CHECKSUM_MISSING -> R.string.companion_update_error_checksum_missing
            UpdateFailure.CHECKSUM_MISMATCH -> R.string.companion_update_error_checksum
            UpdateFailure.UNREADABLE_APK -> R.string.companion_update_error_unreadable
            UpdateFailure.WRONG_PACKAGE -> R.string.companion_update_error_package
            UpdateFailure.NOT_NEWER -> R.string.companion_update_error_not_newer
            UpdateFailure.SIGNER_MISMATCH -> R.string.companion_update_error_signer
            UpdateFailure.UNKNOWN_SOURCES -> R.string.companion_update_unknown_sources_blocked
            UpdateFailure.INSTALLER -> R.string.companion_update_error_installer
            UpdateFailure.CANCELLED -> R.string.companion_update_cancelled
        }
    }
}

/** The visible update UI: status, progress and one explicit button per step. */
private class UpdateSection(private val activity: Activity, private val repo: CompanionUpdateRepository, root: LinearLayout) {
    private val listener: (CompanionUpdateState) -> Unit = { state -> activity.runOnUiThread { render(state) } }
    @Volatile var resumed = false
    private val density = activity.resources.displayMetrics.density
    private fun text(size: Float) = TextView(activity).apply { textSize = size; setPadding(0, 12, 0, 12) }
    private val title = text(21f).apply { setText(R.string.companion_update_title) }
    private val status = text(16f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
    private val privacy = text(14f).apply { setText(R.string.companion_update_privacy) }
    private fun button(label: Int, action: () -> Unit) = Button(activity).apply { setText(label); setOnClickListener { action() } }
    private val check = button(R.string.companion_update_check) { repo.check() }
    private val download = button(R.string.companion_update_download) { repo.download() }
    private val install = button(R.string.companion_update_install) { requestInstall() }
    private val resume = button(R.string.companion_update_continue) { launchConfirmation() }
    private val cancel = button(R.string.companion_update_cancel) { repo.cancel() }
    private val cancelInstall = button(R.string.companion_update_cancel_install) { repo.cancelInstall() }

    init {
        listOf(title, status, progress, check, download, install, resume, cancel, cancelInstall, privacy).forEach(root::addView)
        (progress.layoutParams as? LinearLayout.LayoutParams)?.apply { width = LinearLayout.LayoutParams.MATCH_PARENT; topMargin = (4 * density).toInt() }
        repo.addListener(listener)
    }

    fun dispose() = repo.removeListener(listener)

    fun resumed(value: Boolean) {
        resumed = value
        if (repo.awaitingUnknownSources) {
            repo.awaitingUnknownSources = false
            if (activity.packageManager.canRequestPackageInstalls()) repo.install() else repo.notice(R.string.companion_update_unknown_sources_blocked)
        }
        render(repo.state)
    }

    fun render(state: CompanionUpdateState) {
        val lines = mutableListOf(activity.getString(R.string.companion_update_installed, state.installedName))
        lines += when {
            state.latest != null -> activity.getString(R.string.companion_update_latest, state.latest.version)
            state.checkedAt == 0L -> activity.getString(R.string.companion_update_not_checked)
            else -> activity.getString(R.string.companion_update_latest_none)
        }
        if (state.checkedAt > 0) lines += activity.getString(R.string.companion_update_last_check,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.checkedAt)))
        val total = state.latest?.apk?.size ?: 0
        when (state.phase) {
            UpdatePhase.CHECKING -> lines += activity.getString(R.string.companion_update_checking)
            UpdatePhase.DOWNLOADING -> lines += activity.getString(R.string.companion_update_progress, (state.bytes / 1024).toString(), (total / 1024).toString())
            UpdatePhase.VERIFYING -> lines += activity.getString(R.string.companion_update_verifying)
            UpdatePhase.INSTALLING -> lines += activity.getString(R.string.companion_update_installing)
            else -> Unit
        }
        state.notice?.let { lines += if (it.arg == null) activity.getString(it.text) else activity.getString(it.text, it.arg) }
        status.text = lines.joinToString("\n")

        progress.visibility = if (state.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING, UpdatePhase.CHECKING)) View.VISIBLE else View.GONE
        progress.isIndeterminate = state.phase != UpdatePhase.DOWNLOADING || total <= 0
        if (total > 0) progress.progress = ((state.bytes * 1000) / total).toInt().coerceIn(0, 1000)
        check.isEnabled = state.phase == UpdatePhase.IDLE || state.phase == UpdatePhase.READY
        check.setText(if (state.phase == UpdatePhase.CHECKING) R.string.companion_update_checking_button else R.string.companion_update_check)
        download.visibility = visible(state.phase == UpdatePhase.IDLE && state.downloadCandidate)
        install.visibility = visible(state.phase == UpdatePhase.READY)
        resume.visibility = visible(state.phase == UpdatePhase.PENDING && state.confirmation)
        cancel.visibility = visible(state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING))
        cancelInstall.visibility = visible(state.phase == UpdatePhase.PENDING)
        if (resumed && state.phase == UpdatePhase.PENDING && state.confirmation && repo.claimAutoLaunch()) launchConfirmation()
    }

    private fun visible(shown: Boolean) = if (shown) View.VISIBLE else View.GONE

    /** Install unknown apps is the user's own grant in Android Settings; this only explains it and opens the page. */
    private fun requestInstall() {
        if (activity.packageManager.canRequestPackageInstalls()) { repo.install(); return }
        AlertDialog.Builder(activity)
            .setTitle(R.string.companion_update_unknown_sources_title)
            .setMessage(R.string.companion_update_unknown_sources_message)
            .setPositiveButton(R.string.companion_update_open_settings) { _, _ ->
                repo.awaitingUnknownSources = true
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }.onFailure {
                    repo.awaitingUnknownSources = false
                    repo.notice(R.string.companion_update_unknown_sources_unavailable)
                }
            }
            .setNegativeButton(R.string.companion_update_not_now, null)
            .show()
    }

    private fun launchConfirmation() {
        val confirmation = repo.confirmation ?: return
        runCatching { activity.startActivity(confirmation) }.onFailure { repo.notice(R.string.companion_update_error_installer) }
    }
}
