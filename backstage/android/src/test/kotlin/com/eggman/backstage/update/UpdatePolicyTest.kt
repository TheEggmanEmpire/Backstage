package com.eggman.backstage.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun versionsAreNumericAndBuildMetadataDoesNotAffectOrder() {
        assertTrue(UpdatePolicy.compare("v1.10.0", "1.9.99+dextop2.0.0") > 0)
        assertEquals(0, UpdatePolicy.compare("1.0", "1.0.0+39"))
        assertTrue(UpdatePolicy.compare("1.0.1", "1.0.0") > 0)
        assertEquals(null, UpdatePolicy.version("1.2.3-beta"))
        assertEquals(null, UpdatePolicy.version("not-a-version"))
    }
    @Test fun unknownVersionsAreNeverNewer() {
        for ((candidate, installed) in listOf("1.2.0" to "1.1.0-rc1", "next" to "1.0.0", "" to "", "1.0.0" to "unknown")) {
            assertEquals(0, UpdatePolicy.compare(candidate, installed))
            assertFalse(UpdatePolicy.shouldAnnounce(candidate, installed, null, true))
            assertFalse(UpdateState(installed, UpdateRelease(candidate, UpdateAssets(
                ReleaseAsset("apk", "", 1), ReleaseAsset("sums", "", 1)))).newer)
        }
    }
    @Test fun assetsMatchExactReleaseAndAbi() {
        val apk = ReleaseAsset("backstage-1.0.1-arm64-v8a-release.apk", "https://example.com/a", 200)
        val sums = ReleaseAsset("SHA256SUMS", "https://example.com/s", 100)
        assertEquals(UpdateAssets(apk, sums), UpdatePolicy.assets("1.0.1", listOf(
            apk.copy(name = "backstage-play-1.0.1-arm64-v8a-release.apk"), apk.copy(name = "backstage-1.0.1-debug.apk"), apk, sums)))
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.assets("1.0.1", listOf(apk)) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.assets("1.0.1", listOf(apk, apk, sums)) }
    }
    @Test fun checksumIsAnExactUnambiguousLine() {
        val hash = "ab".repeat(32)
        val name = "backstage-1.0.1-arm64-v8a-release.apk"
        assertEquals(hash, UpdatePolicy.checksum("${"cd".repeat(32)}  other.apk\n${hash.uppercase()} *$name\n", name))
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.checksum("$hash  ../$name", name) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.checksum("$hash  $name\n$hash  $name", name) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.checksum("nope  $name", name) }
    }
    private val installed = ApkIdentity("com.eggman.backstage", 100, setOf("signer-a"))
    private val candidate = installed.copy(versionCode = 101)
    private val hash = "ab".repeat(32)
    @Test fun verificationFailsClosedForEveryGate() {
        UpdatePolicy.verify(hash, hash, installed, candidate)
        listOf(
            candidate.copy(packageName = "other.package"),
            candidate.copy(versionCode = 99), candidate.copy(versionCode = 100),
            candidate.copy(signers = setOf("signer-b")), candidate.copy(signers = emptySet()),
            candidate.copy(signers = setOf("signer-a", "signer-b")),
        ).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.verify(hash, hash, installed, bad) } }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.verify(hash, "cd".repeat(32), installed, candidate) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.verify(hash, hash, installed.copy(signers = emptySet()), candidate) }
    }
    @Test fun announcementsWaitForNotificationPermissionAndRememberTheVersion() {
        assertTrue(UpdatePolicy.shouldAnnounce("1.0.1", "1.0.0", null, true))
        assertFalse(UpdatePolicy.shouldAnnounce("1.0.1", "1.0.0", "v1.0.1", true))
        assertFalse(UpdatePolicy.shouldAnnounce("1.0.1", "1.0.0", null, false))
        assertFalse(UpdatePolicy.shouldAnnounce("1.0.0", "1.0.0", null, true))
        assertFalse(UpdatePolicy.shouldAnnounce("1.0.1", "1.0.0", "1.0.2", true))
    }
    @Test fun errorBackoffIsBoundedAndHonorsServerCooldown() {
        assertEquals(15 * 60_000L, UpdatePolicy.backoff(1))
        assertEquals(30 * 60_000L, UpdatePolicy.backoff(2))
        assertEquals(UpdatePolicy.DAY, UpdatePolicy.backoff(40))
        assertEquals(7 * UpdatePolicy.DAY, UpdatePolicy.backoff(1, 10 * UpdatePolicy.DAY))
        assertFalse(UpdatePolicy.canCheck(100, 200, 0, true))
        assertFalse(UpdatePolicy.canCheck(1_000, 0, 999, true))
        assertTrue(UpdatePolicy.canCheck(61_000, 0, 0, true))
        assertFalse(UpdatePolicy.canCheck(61_000, UpdatePolicy.DAY, 0, false))
    }
}
