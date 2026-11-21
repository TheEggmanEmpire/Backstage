package com.eggman.backstage.update

import java.math.BigInteger
import java.security.MessageDigest

internal data class ReleaseAsset(val name: String, val url: String, val size: Long)
internal data class UpdateAssets(val apk: ReleaseAsset, val sums: ReleaseAsset)
internal data class UpdateRelease(val version: String, val assets: UpdateAssets)
internal data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** No Android dependencies: every release, identity and timing decision is exercised on the JVM. */
internal object UpdatePolicy {
    const val DAY = 24 * 60 * 60_000L
    const val MANUAL_GAP = 60_000L
    const val MAX_APK = 1024 * 1024 * 1024L
    private val numeric = Regex("[0-9]+(?:\\.[0-9]+)*")

    fun version(value: String): String? = value.trim().removePrefix("v").removePrefix("V")
        .substringBefore('+').takeIf { numeric.matches(it) && it.length <= 100 }

    fun compare(candidate: String, installed: String): Int {
        val left = (version(candidate) ?: return 0).split('.').map(::BigInteger)
        val right = (version(installed) ?: return 0).split('.').map(::BigInteger)
        repeat(maxOf(left.size, right.size)) { i ->
            val comparison = (left.getOrNull(i) ?: BigInteger.ZERO).compareTo(right.getOrNull(i) ?: BigInteger.ZERO)
            if (comparison != 0) return comparison
        }
        return 0
    }

    fun assets(version: String, assets: List<ReleaseAsset>): UpdateAssets {
        fun one(name: String) = assets.filter { it.name == name }.singleOrNull()
            ?: throw IllegalArgumentException("Release is missing a unique $name asset")
        val apk = one("backstage-$version-arm64-v8a-release.apk")
        require(apk.size in 1..MAX_APK) { "Invalid APK size" }
        val sums = one("SHA256SUMS")
        require(sums.size in 1..262_144) { "Invalid SHA256SUMS size" }
        return UpdateAssets(apk, sums)
    }

    fun checksum(text: String, filename: String): String {
        val matches = text.lineSequence().mapNotNull { line ->
            Regex("^([a-fA-F0-9]{64}) [ *](.+)$").matchEntire(line.trimEnd())?.let {
                if (it.groupValues[2] == filename) it.groupValues[1].lowercase() else null
            }
        }.toList()
        return requireNotNull(matches.singleOrNull()) { "SHA256SUMS has no unique checksum for $filename" }
    }

    fun verify(expectedHash: String, actualHash: String, installed: ApkIdentity, archive: ApkIdentity) {
        require(Regex("[a-f0-9]{64}").matches(expectedHash) && MessageDigest.isEqual(
            expectedHash.toByteArray(Charsets.US_ASCII), actualHash.lowercase().toByteArray(Charsets.US_ASCII),
        )) { "APK checksum does not match SHA256SUMS. Update rejected." }
        require(archive.packageName == installed.packageName) { "APK belongs to another app. Update rejected." }
        require(archive.versionCode > installed.versionCode) { "APK versionCode must be higher than the installed app. Update rejected." }
        require(installed.signers.isNotEmpty() && archive.signers == installed.signers) {
            "APK signing certificate differs from the installed app. Update rejected."
        }
    }

    fun shouldAnnounce(latest: String, installed: String, announced: String?, allowed: Boolean): Boolean =
        allowed && compare(latest, installed) > 0 && (announced == null || compare(latest, announced) > 0)

    fun backoff(failures: Int, serverDelayMs: Long = 0): Long =
        maxOf((15 * 60_000L * (1L shl (failures - 1).coerceIn(0, 7))).coerceAtMost(DAY), serverDelayMs)
            .coerceIn(15 * 60_000L, 7 * DAY)

    fun canCheck(now: Long, nextAuto: Long, lastAttempt: Long, manual: Boolean): Boolean =
        now >= lastAttempt + MANUAL_GAP && now >= if (manual) 0 else nextAuto

    fun requiredSpace(apkSize: Long): Long = apkSize * 3 + 64 * 1024 * 1024L
}
