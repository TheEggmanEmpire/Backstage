package com.eggman.backstage.companion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionUpdatePolicyTest {
    private val signer = "ab".repeat(32)
    private val installed = ApkIdentity("com.eggman.backstage.agent", 10001039L, setOf(signer))

    @Test fun mixedReleaseSelectsOnlyTheCompanionApkAndChecksum() {
        fun asset(name: String) = ReleaseAsset(
            name, "https://github.com/TheEggmanEmpire/Backstage/releases/download/v1.0.1/$name", 200,
        )
        val github = asset("backstage-1.0.1-arm64-v8a-release.apk")
        val play = asset("backstage-play-1.0.1-arm64-v8a-release.apk")
        val companion = asset("backstage-play-agent-1.0.1-release.apk")
        val sums = asset("SHA256SUMS")
        val update = requireNotNull(CompanionUpdatePolicy.releaseAssets(listOf(github, play, companion, sums)))
        val hash = "ef".repeat(32)
        val manifest = listOf("cd".repeat(32) to play, "ab".repeat(32) to github, hash to companion)
            .joinToString("\n") { (digest, apk) -> "$digest  ${apk.name}" }
        assertEquals(CompanionRelease("1.0.1", companion, sums), update)
        assertEquals(hash, CompanionUpdatePolicy.checksum(manifest, update.apk.name))
        assertEquals(null, CompanionUpdatePolicy.releaseAssets(listOf(github, play, sums)))
    }

    @Test fun checksumRequiresOneExactFilenameAndAcceptsBothSha256sumMarkers() {
        val hash = "0123456789abcdef".repeat(4)
        assertEquals(hash, CompanionUpdatePolicy.checksum("$hash  backstage-play-agent-1.0.2-release.apk\n", "backstage-play-agent-1.0.2-release.apk"))
        assertEquals(hash, CompanionUpdatePolicy.checksum("$hash *backstage-play-agent-1.0.2-release.apk\n", "backstage-play-agent-1.0.2-release.apk"))
        assertTrue(runCatching {
            CompanionUpdatePolicy.checksum("$hash  other.apk\n", "backstage-play-agent-1.0.2-release.apk")
        }.isFailure)
        assertTrue(runCatching {
            CompanionUpdatePolicy.checksum("$hash  backstage-play-agent-1.0.2-release.apk\n$hash  backstage-play-agent-1.0.2-release.apk\n",
                "backstage-play-agent-1.0.2-release.apk")
        }.isFailure)
    }

    @Test fun checksumAndExactSignerSetAndPackageAndNewerCodeAreAllRequired() {
        val hash = "cd".repeat(32)
        val valid = installed.copy(versionCode = installed.versionCode + 1)
        CompanionUpdatePolicy.verify(hash, hash, installed, valid)
        assertTrue(runCatching { CompanionUpdatePolicy.verify(hash, "ef".repeat(32), installed, valid) }.isFailure)
        assertTrue(runCatching { CompanionUpdatePolicy.verify(hash, hash, installed, valid.copy(packageName = "other")) }.isFailure)
        assertTrue(runCatching { CompanionUpdatePolicy.verify(hash, hash, installed, valid.copy(versionCode = installed.versionCode)) }.isFailure)
        assertTrue(runCatching { CompanionUpdatePolicy.verify(hash, hash, installed, valid.copy(signers = setOf("ef".repeat(32)))) }.isFailure)
        assertTrue(runCatching { CompanionUpdatePolicy.verify(hash, hash, installed, valid.copy(signers = setOf(signer, "ef".repeat(32)))) }.isFailure)
        assertTrue(runCatching { CompanionUpdatePolicy.verify(hash, hash, installed.copy(signers = emptySet()), valid) }.isFailure)
    }

    @Test fun releaseTagsOnlyAdvertiseVersionsThatCompareNewer() {
        assertTrue(CompanionUpdatePolicy.compare("v1.0.2", "1.0.1") > 0)
        assertEquals(0, CompanionUpdatePolicy.compare("1.0", "1.0.0"))
        assertFalse(CompanionUpdatePolicy.compare("1.0.1", "1.0.2") > 0)
        assertFalse(CompanionUpdatePolicy.isNewer("1.0.1", "1.0.1"))
        assertTrue(CompanionUpdatePolicy.isNewer("1.0.2", "1.0.1"))
    }

    @Test fun sameVersionNameCanBeDownloadedForVersionCodeVerification() {
        val release = CompanionRelease(
            "1.0.1", ReleaseAsset("backstage-play-agent-1.0.1-release.apk", "https://github.com/asset", 10),
            ReleaseAsset("SHA256SUMS", "https://github.com/sums", 10),
        )
        val checked = CompanionUpdateState(installedName = "1.0.1", installedCode = 100, latest = release)
        assertTrue(checked.downloadCandidate)
        assertFalse(checked.newer)
        assertFalse(checked.copy(installedName = "1.0.2").downloadCandidate)
    }
}
