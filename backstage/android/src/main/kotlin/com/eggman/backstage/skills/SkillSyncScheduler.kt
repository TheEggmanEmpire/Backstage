package com.eggman.backstage.skills

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes config mutations, source removal, foreground sync and scheduled sync in the app process. */
object SkillSync {
    private val mutex = Mutex()
    private val mutableProgress = MutableStateFlow<SkillSyncProgress?>(null)
    val progress = mutableProgress.asStateFlow()

    suspend fun saveConfig(context: Context, config: SkillSyncConfig, tokens: Map<String, String> = emptyMap()) = mutex.withLock {
        val prefs = SkillSyncPreferences(context)
        require(prefs.config().sources.all { old -> config.sources.any { it.id == old.id } }) { "Confirm source removal before deleting its mirrored skills." }
        prefs.save(config, tokens)
        rescheduleAfterSave(context)
    }

    /** Applies [change] to the stored config under the sync lock, so a screen's stale copy can never resurrect a removed source. */
    suspend fun updateConfig(context: Context, tokens: Map<String, String> = emptyMap(), change: (SkillSyncConfig) -> SkillSyncConfig) = mutex.withLock {
        val prefs = SkillSyncPreferences(context)
        val current = prefs.config()
        val next = change(current).validated()
        require(current.sources.all { old -> next.sources.any { it.id == old.id } }) { "Confirm source removal before deleting its mirrored skills." }
        prefs.save(next, tokens)
        rescheduleAfterSave(context)
    }

    /** The settings are committed already; a scheduler refusal must not be reported as a failed save. */
    private fun rescheduleAfterSave(context: Context) {
        runCatching { SkillSyncScheduler.reschedule(context) }.onFailure {
            throw IllegalStateException("Saved, but Android did not schedule the sync (${it.message}). Use Sync now, or save again later.")
        }
    }

    /** Caller must present the confirmation before invoking this method. */
    suspend fun removeSource(context: Context, id: String) = mutex.withLock {
        val prefs = SkillSyncPreferences(context)
        val config = prefs.config()
        SkillStore.shared(context.filesDir).removeSource(id)
        prefs.save(config.copy(sources = config.sources.filterNot { it.id == id }))
        SkillSyncScheduler.reschedule(context)
    }

    suspend fun syncNow(context: Context, sourceId: String? = null, scheduled: Boolean = false) = mutex.withLock {
        val prefs = SkillSyncPreferences(context)
        val config = prefs.config()
        if (scheduled && !config.enabled) return@withLock
        val engine = SkillSyncEngine(SkillStore.shared(context.filesDir), context.cacheDir, sourceIsCurrent = { source ->
            val current = prefs.config()
            (!scheduled || current.enabled) && current.sources.any { it.configKey == source.configKey }
        })
        var completed = false
        try {
            for (source in config.sources.filter { sourceId == null || it.id == sourceId }) {
                val status = try {
                    engine.sync(source, prefs.token(source.id), prefs.statuses()[source.id]) { mutableProgress.value = it }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { SkillSyncStatus(System.currentTimeMillis(), "error", e.message?.takeIf { it.isNotBlank() } ?: "The GitHub token could not be loaded. Paste it again for this source.") }
                if (prefs.config().sources.any { it.configKey == source.configKey }) prefs.saveStatus(source.id, status)
            }
            completed = true
        } finally {
            mutableProgress.value = null
            val current = prefs.config()
            if (completed && scheduled && current.enabled && current.sources.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (prefs.periodicNextSync <= now) prefs.periodicNextSync = now + current.intervalMinutes * 60_000L
                prefs.nextSync = prefs.periodicNextSync
            }
        }
    }
}

/** Background jobs are network constrained and persisted by Android, including across reboot. */
object SkillSyncScheduler {
    internal const val PERIODIC_JOB = 0x42535301
    internal const val SOON_JOB = 0x42535302

    @Synchronized fun reschedule(context: Context) {
        val app = context.applicationContext ?: context
        val prefs = SkillSyncPreferences(app)
        val config = prefs.config()
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        if (!config.enabled || config.sources.isEmpty()) {
            scheduler.cancel(PERIODIC_JOB)
            // A pending one-off (queued after a restore) survives auto-sync being off; it goes only with the last source.
            if (config.sources.isEmpty()) scheduler.cancel(SOON_JOB)
            prefs.nextSync = 0
            prefs.periodicNextSync = 0
            return
        }
        val interval = config.intervalMinutes * 60_000L
        val current = scheduler.getPendingJob(PERIODIC_JOB)
        if (current == null || current.intervalMillis != interval || prefs.periodicNextSync <= 0) {
            val job = JobInfo.Builder(PERIODIC_JOB, ComponentName(app, SkillSyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(interval).build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Android could not schedule GitHub sync." }
            prefs.nextSync = System.currentTimeMillis() + interval
            prefs.periodicNextSync = prefs.nextSync
        }
    }

    /** [evenWhenOff]: one run although automatic sync is off (after a settings restore, which drops mirrored folders). */
    @Synchronized fun syncSoon(context: Context, evenWhenOff: Boolean = false) {
        val app = context.applicationContext ?: context
        reschedule(app)
        val config = SkillSyncPreferences(app).config()
        if (!config.enabled && !evenWhenOff || config.sources.isEmpty()) return
        if (!config.enabled) SkillSyncPreferences(app).restorePending = true
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(SOON_JOB, ComponentName(app, SkillSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setMinimumLatency(0).build()
        check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Android could not schedule GitHub sync." }
        SkillSyncPreferences(app).nextSync = System.currentTimeMillis()
    }

    internal fun onProcessStart(context: Context) {
        reschedule(context)
        val prefs = SkillSyncPreferences(context)
        val config = prefs.config()
        val statuses = prefs.statuses()
        if (config.enabled && config.sources.any { System.currentTimeMillis() - (statuses[it.id]?.lastRun ?: 0) >= config.intervalMinutes * 60_000L }) syncSoon(context)
    }
}

class SkillSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        jobs[params.jobId] = scope.launch {
            var retry = false
            // Only the run a settings restore asked for may ignore "automatic sync off"; every other job obeys it.
            val prefs = SkillSyncPreferences(applicationContext)
            val restoreRun = params.jobId == SkillSyncScheduler.SOON_JOB && prefs.restorePending
            // Cleared only after the run completed: a stopped or failed attempt is retried as a restore run again.
            try { SkillSync.syncNow(applicationContext, scheduled = !restoreRun); if (restoreRun) prefs.restorePending = false }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { retry = true }
            finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (jobs.remove(params.jobId) != null) jobFinished(params, retry)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Independent process-start hook; no Application subclass or user screen is needed. */
class SkillSyncInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let { app ->
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { SkillSyncScheduler.onProcessStart(app) } }
        }
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
