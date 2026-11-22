package com.eggman.backstage.update

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.robolectric.Robolectric
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class UpdaterRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        AppUpdates::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        UpdateConfirmationActivity.pending = null
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.requestedPermissions = arrayOf(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        shadowOf(app.packageManager).installPackage(info)
        app.getSharedPreferences("backstage_updates", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }
    private fun seed() {
        val release = """{"tag_name":"v99.0.0","draft":false,"prerelease":false,"assets":[
          {"name":"backstage-99.0.0-arm64-v8a-release.apk","size":200000000,"browser_download_url":"https://github.com/TheEggmanEmpire/Backstage/releases/download/v99.0.0/backstage-99.0.0-arm64-v8a-release.apk"},
          {"name":"SHA256SUMS","size":200,"browser_download_url":"https://github.com/TheEggmanEmpire/Backstage/releases/download/v99.0.0/SHA256SUMS"}]}"""
        val now = System.currentTimeMillis()
        app.getSharedPreferences("backstage_updates", Context.MODE_PRIVATE).edit().putString("release", release)
            .putLong("attempt", now).putLong("next", now + UpdatePolicy.DAY).commit()
    }
    @Test fun missingNotificationPermissionKeepsUpdateInAppAndAnnouncementIsOnceAcrossInstances() {
        seed()
        var repo = UpdateRepository(app)
        repo.check(manual = false) // cached cooldown; must not touch the network
        assertTrue(repo.state.value.newer)
        assertNull(repo.prefs.getString("announced", null))
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(UpdateNotifications.AVAILABLE))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        repo.check(manual = false)
        assertEquals("99.0.0", repo.prefs.getString("announced", null))
        assertNotNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(UpdateNotifications.AVAILABLE))
        app.getSystemService(NotificationManager::class.java).cancel(UpdateNotifications.AVAILABLE)
        repo = UpdateRepository(app)
        repo.check(manual = false)
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(UpdateNotifications.AVAILABLE))
    }
    @Test fun startupRearmsThePersistedDeadlineAndDiscardsInterruptedFiles() {
        seed()
        val dir = File(app.noBackupFilesDir, "updates").apply { mkdirs() }
        val partial = File(dir, "update.apk").apply { writeText("partial bytes") }
        val repo = UpdateRepository(app)
        assertFalse(partial.exists())
        val next = repo.prefs.getLong("next", 0)
        repo.check(manual = false)
        val alarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.last()
        assertEquals(next, alarm.triggerAtTime)
        assertTrue(alarm.allowWhileIdle)
        assertEquals(next, repo.prefs.getLong("next", 0))
    }
    @Test fun checkingAndDownloadingCannotOverlapAndChangeTheSelectedRelease() {
        seed()
        val repo = UpdateRepository(app)
        repo.change { it.copy(checking = true) }
        assertNull(repo.beginDownload())
        repo.change { it.copy(checking = false) }
        assertEquals("99.0.0", repo.beginDownload()?.version)
        repo.check(manual = true) // a user check during a download stays on the selected release; no API call
        assertEquals(UpdatePhase.DOWNLOADING, repo.state.value.phase)
        assertEquals("99.0.0", repo.state.value.latest?.version)
        repo.failed("test cleanup")
    }

    @Test fun readyAndPendingAlarmsStayDailyAcrossRepeatedBroadcasts() {
        seed()
        val repo = AppUpdates.repository(app)
        for (phase in listOf(UpdatePhase.READY, UpdatePhase.PENDING)) {
            repo.change { it.copy(phase = phase) }
            repo.prefs.edit().putLong("next", System.currentTimeMillis() - 1).commit()
            repeat(2) {
                val before = System.currentTimeMillis()
                val receiver = UpdateAlarmReceiver()
                shadowOf(receiver).onReceive(app, Intent(UpdateAlarmReceiver.CHECK), AtomicBoolean())
                await { shadowOf(receiver).wentAsync() }
                repo.check(false)
                val alarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.last()
                assertTrue("alarm delay ${alarm.triggerAtTime - before}", alarm.triggerAtTime in
                    (before + UpdatePolicy.DAY)..(System.currentTimeMillis() + UpdatePolicy.DAY))
            }
        }
    }

    private fun session(committed: Boolean): Pair<UpdateRepository, Int> {
        seed()
        val repo = UpdateRepository(app)
        val installer = app.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val info = installer.getSessionInfo(id)!!
        org.robolectric.util.ReflectionHelpers.setField(info, "isCommitted", committed)
        repo.prefs.edit().putInt("session", id).putLong("sessionAt", System.currentTimeMillis())
            .putLong("sessionVersionCode", Long.MAX_VALUE).putString("sessionToken", "backstage-update://session/$id/test").commit()
        return repo to id
    }

    @Test fun uncommittedSessionsAreAbandonedAndAllowAnotherDownload() {
        val (repo, id) = session(false)
        repo.apk.writeText("staged bytes")
        UpdateInstaller.recover(app, repo)
        assertNull(app.packageManager.packageInstaller.getSessionInfo(id))
        assertEquals(-1, repo.prefs.getInt("session", -1))
        assertFalse(repo.apk.exists())
        assertEquals(UpdatePhase.IDLE, repo.state.value.phase)
        assertNotNull(repo.beginDownload())
    }

    @Test fun missingConfirmationAllowsRestartInsteadOfLeavingPending() {
        val (repo, id) = session(true)
        UpdateInstaller.recover(app, repo)
        assertNull(app.packageManager.packageInstaller.getSessionInfo(id))
        assertNull(UpdateConfirmationActivity.pending)
        assertEquals(UpdatePhase.IDLE, repo.state.value.phase)
        assertNotNull(repo.beginDownload())
    }

    @Test fun committedSessionWithConfirmationCanContinue() {
        val (repo, id) = session(true)
        val confirmation = PendingIntent.getActivity(app, id, Intent(app, UpdateConfirmationActivity::class.java)
            .setAction(UpdateInstaller.CONFIRM).setData(android.net.Uri.parse(repo.prefs.getString("sessionToken", "")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        UpdateInstaller.recover(app, repo)
        assertEquals(UpdatePhase.PENDING, repo.state.value.phase)
        assertEquals(confirmation, UpdateConfirmationActivity.pending)
        assertNotNull(app.packageManager.packageInstaller.getSessionInfo(id))
        UpdateInstaller.cancel(app, repo)
    }

    @Test fun cancellingBlockedNetworkIoDoesNotPostFailureNotification() {
        seed()
        val repo = AppUpdates.repository(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            repo.change { it.copy(latest = UpdateRelease("99.0.0", UpdateAssets(
                ReleaseAsset("update.apk", server.url("/apk").toString(), 5),
                ReleaseAsset("SHA256SUMS", server.url("/sums").toString(), 100)))) }
            org.robolectric.shadows.ShadowStatFs.registerStats(repo.directory.path, 1_000_000, 1_000_000, 1_000_000)
            val service = Robolectric.buildService(UpdateDownloadService::class.java).create()
            service.get().onStartCommand(Intent(app, UpdateDownloadService::class.java), 0, 1)
            assertNotNull(repo.state.value.toString(), server.takeRequest(5, TimeUnit.SECONDS))
            service.destroy()
            await { repo.state.value.phase == UpdatePhase.IDLE }
            assertTrue(repo.state.value.message.orEmpty(), repo.state.value.message.orEmpty().startsWith("Update cancelled"))
            assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(UpdateNotifications.INSTALL))
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("operation completed", condition())
    }

    @Test fun startupHonorsFutureDeadlineAndErrorCooldown() {
        for (failures in listOf(0, 2)) {
            seed()
            val prefs = app.getSharedPreferences("backstage_updates", Context.MODE_PRIVATE)
            val attempt = System.currentTimeMillis() - 120_000
            val next = System.currentTimeMillis() + UpdatePolicy.DAY
            prefs.edit().putLong("attempt", attempt).putLong("next", next).putInt("failures", failures).commit()
            var calls = 0
            val repo = UpdateRepository(app) { calls++; null }
            AppUpdates::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, repo)
            val manager = app.getSystemService(AlarmManager::class.java)
            shadowOf(manager).scheduledAlarms.toList().forEach { alarm -> alarm.operation?.let { manager.cancel(it) } }
            AppUpdates.start(app)
            await { shadowOf(manager).scheduledAlarms.isNotEmpty() }
            repo.check(false)
            assertEquals(0, calls)
            assertEquals(attempt, prefs.getLong("attempt", 0))
            assertEquals(next, prefs.getLong("next", 0))
        }
    }

    @Test fun startupChecksWhenDueOrNeverCheckedAndManualCheckCanBypassDailyDeadline() {
        for (past in listOf(0L, System.currentTimeMillis() - 1)) {
            seed()
            val prefs = app.getSharedPreferences("backstage_updates", Context.MODE_PRIVATE)
            prefs.edit().putLong("next", past).putLong("attempt", 0).commit()
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val repo = UpdateRepository(app) { calls.incrementAndGet(); null }
            AppUpdates::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, repo)
            AppUpdates.start(app)
            await { calls.get() == 1 }
            repo.check(false)
            assertEquals(1, calls.get())
            prefs.edit().putLong("attempt", System.currentTimeMillis() - 120_000).commit()
            repo.check(true)
            assertEquals(2, calls.get())
        }
    }

    @Test fun cachedReleaseWithNonNumericInstalledVersionCannotCrashOrAnnounce() {
        seed()
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.versionName = "1.1.0-rc1"
        shadowOf(app.packageManager).installPackage(info)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val repo = UpdateRepository(app) { error("Cached checks must not fetch") }
        repo.check(false)
        assertFalse(repo.state.value.newer)
        assertNull(repo.state.value.checkError)
        assertNull(repo.prefs.getString("announced", null))
    }

    @Test fun cachedAnnouncementFailuresUseErrorBackoff() {
        seed()
        var failNotifications = false
        val context = object : android.content.ContextWrapper(app) {
            override fun getSystemService(name: String): Any? {
                if (failNotifications && name == Context.NOTIFICATION_SERVICE) error("Notification service unavailable")
                return super.getSystemService(name)
            }
        }
        val repo = UpdateRepository(context) { error("Cached checks must not fetch") }
        repo.change { it.copy(latest = null) }
        failNotifications = true
        val before = System.currentTimeMillis()
        repo.check(false)
        assertEquals("Notification service unavailable", repo.state.value.checkError)
        assertEquals(1, repo.prefs.getInt("failures", 0))
        assertTrue(repo.state.value.nextCheck >= before + 15 * 60_000)
    }

    @Test fun signerAdapterUsesCurrentApkSignersAndFailsClosedWithoutThem() {
        val certificate = Signature(byteArrayOf(1, 2, 3))
        val signing = SigningInfo()
        shadowOf(signing).setSignatures(arrayOf(certificate))
        val info = PackageInfo().apply { packageName = "com.eggman.backstage"; longVersionCode = 101; signingInfo = signing }
        val actual = AndroidUpdateVerifier.identity(info)
        assertEquals(101L, actual.versionCode)
        assertEquals(setOf(MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).hex()), actual.signers)
        assertTrue(AndroidUpdateVerifier.identity(PackageInfo().apply { packageName = "com.eggman.backstage" }).signers.isEmpty())
    }
}
