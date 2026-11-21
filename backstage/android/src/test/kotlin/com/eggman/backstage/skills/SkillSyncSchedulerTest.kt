package com.eggman.backstage.skills

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.eggman.backstage.data.SecretCipher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkillSyncSchedulerTest {
    private lateinit var context: Context
    private lateinit var scheduler: JobScheduler
    private lateinit var prefs: SkillSyncPreferences
    private val source = SkillSyncSource("acme-skills", "acme", "skills")
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(SkillSyncPreferences.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        prefs = SkillSyncPreferences(context)
    }

    @Test fun periodicJobIsPersistedNetworkConstrainedAndUsesTheConfiguredInterval() {
        prefs.save(SkillSyncConfig(true, 120, listOf(source)))
        SkillSyncScheduler.reschedule(context)
        val job = scheduler.getPendingJob(SkillSyncScheduler.PERIODIC_JOB)!!
        assertTrue(job.isPersisted)
        assertTrue(job.isPeriodic)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(120 * 60_000L, job.intervalMillis)
        assertTrue(prefs.nextSync > System.currentTimeMillis())
        prefs.save(SkillSyncConfig(true, 15, listOf(source)))
        SkillSyncScheduler.reschedule(context)
        assertEquals(15 * 60_000L, scheduler.getPendingJob(SkillSyncScheduler.PERIODIC_JOB)!!.intervalMillis)
        // Restore omits runtime clocks while Android may retain the previous persisted job.
        prefs.nextSync = 0; prefs.periodicNextSync = 0
        SkillSyncScheduler.reschedule(context)
        assertTrue(prefs.nextSync > System.currentTimeMillis())
    }

    @Test fun disabledKeepsAPendingRestoreRunAndEmptyConfigCancelsBothJobs() {
        prefs.save(SkillSyncConfig(true, sources = listOf(source)))
        SkillSyncScheduler.syncSoon(context)
        assertNotNull(scheduler.getPendingJob(SkillSyncScheduler.SOON_JOB))
        prefs.save(SkillSyncConfig(false, sources = listOf(source)))
        SkillSyncScheduler.reschedule(context)
        assertNull(scheduler.getPendingJob(SkillSyncScheduler.PERIODIC_JOB))
        // A one-off queued after a restore must survive the app restart that follows it, even with auto-sync off.
        assertNotNull(scheduler.getPendingJob(SkillSyncScheduler.SOON_JOB))
        assertEquals(0L, prefs.nextSync)
        prefs.save(SkillSyncConfig(false, sources = emptyList()))
        SkillSyncScheduler.reschedule(context)
        assertTrue(scheduler.allPendingJobs.isEmpty())
        prefs.save(SkillSyncConfig(true))
        SkillSyncScheduler.syncSoon(context)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test fun restoreQueuesARunEvenWithAutomaticSyncOff() {
        prefs.save(SkillSyncConfig(false, sources = listOf(source)))
        SkillSyncScheduler.syncSoon(context)
        assertNull(scheduler.getPendingJob(SkillSyncScheduler.SOON_JOB))
        assertFalse(prefs.restorePending)
        SkillSyncScheduler.syncSoon(context, evenWhenOff = true)
        assertNotNull(scheduler.getPendingJob(SkillSyncScheduler.SOON_JOB))
        // Only this restore-requested run may ignore "automatic sync off"; ordinary one-offs obey it.
        assertTrue(prefs.restorePending)
        assertNull(scheduler.getPendingJob(SkillSyncScheduler.PERIODIC_JOB))
    }

    @Test fun syncSoonQueuesANetworkConstrainedOneShotForRestore() {
        prefs.save(SkillSyncConfig(true, sources = listOf(source)))
        SkillSyncScheduler.syncSoon(context)
        val job = scheduler.getPendingJob(SkillSyncScheduler.SOON_JOB)!!
        assertFalse(job.isPeriodic); assertTrue(job.isPersisted)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
    }

    @Test fun processStartupQueuesOnlyOverdueSources() {
        prefs.save(SkillSyncConfig(true, sources = listOf(source)))
        prefs.saveStatus(source.id, SkillSyncStatus(System.currentTimeMillis(), "ok"))
        SkillSyncScheduler.onProcessStart(context)
        assertNull(scheduler.getPendingJob(SkillSyncScheduler.SOON_JOB))
        prefs.saveStatus(source.id, SkillSyncStatus(1, "ok"))
        SkillSyncScheduler.onProcessStart(context)
        assertNotNull(scheduler.getPendingJob(SkillSyncScheduler.SOON_JOB))
    }

    @Test fun jobServiceIsDeclaredWithTheSystemBindPermission() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, SkillSyncJobService::class.java), PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        assertTrue(info.exported)
    }

    @Test fun secretsAreSeparateEncryptedAndNeverFallBackToPlaintext() {
        val storage = context.getSharedPreferences(SkillSyncPreferences.FILE, Context.MODE_PRIVATE)
        val fake = object : SecretCipher {
            override fun encrypt(plain: String) = "enc1:" + plain.reversed()
            override fun decrypt(stored: String) = stored.removePrefix("enc1:").reversed()
        }
        val secrets = SkillSyncPreferences(storage, fake)
        val config = SkillSyncConfig(true, sources = listOf(source))
        secrets.save(config, mapOf(source.id to "ghp_secret"))
        assertTrue(storage.getString("token_${source.id}", "")!!.startsWith("enc1:"))
        assertFalse(storage.getString("config", "")!!.contains("secret"))
        assertEquals("ghp_secret", secrets.token(source.id))
        val unsafe = SkillSyncPreferences(storage, SecretCipher.Plain)
        assertTrue(runCatching { unsafe.save(config.copy(intervalMinutes = 120), mapOf(source.id to "ghp_other")) }.isFailure)
        assertEquals(60, secrets.config().intervalMinutes)
        assertEquals("ghp_secret", secrets.token(source.id))
    }

    @Test fun sourceRemovalDropsItsRuntimeStatusAndTokenFromPreferences() {
        val storage = context.getSharedPreferences(SkillSyncPreferences.FILE, Context.MODE_PRIVATE)
        prefs.save(SkillSyncConfig(true, sources = listOf(source)))
        prefs.saveStatus(source.id, SkillSyncStatus(10, "ok"))
        storage.edit().putString("token_${source.id}", "enc1:test").commit()
        prefs.save(SkillSyncConfig())
        assertTrue(prefs.statuses().isEmpty())
        assertFalse(storage.contains("token_${source.id}"))
    }
}
