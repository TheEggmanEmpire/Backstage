package com.eggman.backstage.companion.update

import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest

internal data class ReleaseAsset(val name: String, val url: String, val size: Long)
internal data class CompanionRelease(val version: String, val apk: ReleaseAsset, val sums: ReleaseAsset)
internal data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** Why an update step stopped. Each reason maps to one user-facing string in update_strings.xml. */
internal enum class UpdateFailure {
    NETWORK, HTTP, RATE_LIMITED, METADATA, NO_RELEASE, UNTRUSTED_URL, UNTRUSTED_REDIRECT, TOO_LARGE, LOW_STORAGE,
    SIZE_MISMATCH, CHECKSUM_MISSING, CHECKSUM_MISMATCH, UNREADABLE_APK, WRONG_PACKAGE, NOT_NEWER, SIGNER_MISMATCH,
    UNKNOWN_SOURCES, INSTALLER, CANCELLED,
}

internal class CompanionUpdateException(val reason: UpdateFailure, detail: String? = null, val retryAfterMs: Long = 0) :
    Exception(detail ?: reason.name)

/** Every release, URL, checksum and identity decision, with no Android dependency so the JVM tests cover it. */
internal object CompanionUpdatePolicy {
    const val OWNER_REPO = "TheEggmanEmpire/Backstage"
    const val RELEASES = "https://api.github.com/repos/$OWNER_REPO/releases?per_page=30"
    const val SUMS = "SHA256SUMS"
    const val MAX_APK = 64L * 1024 * 1024
    const val MAX_SUMS = 256L * 1024
    const val MAX_METADATA = 4 * 1024 * 1024
    const val MIN_CHECK_GAP = 60_000L
    const val MAX_COOLDOWN = 24 * 60 * 60_000L
    private val numeric = Regex("[0-9]+(?:\\.[0-9]+)*")
    private val agentApk = Regex("backstage-play-agent-([0-9]+(?:\\.[0-9]+)*)-release\\.apk")
    private val sumLine = Regex("^([a-fA-F0-9]{64}) [ *](.+)$")
    private val hex64 = Regex("[a-f0-9]{64}")
    // GitHub serves release metadata from the API host and redirects asset downloads to its storage hosts.
    private val trustedHosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

    fun version(value: String): String? = value.trim().removePrefix("v").removePrefix("V")
        .substringBefore('+').takeIf { it.length <= 100 && numeric.matches(it) }

    /** Numeric dotted comparison; anything that is not a plain version compares equal and is never "newer". */
    fun compare(candidate: String, installed: String): Int {
        val left = (version(candidate) ?: return 0).split('.').map(::BigInteger)
        val right = (version(installed) ?: return 0).split('.').map(::BigInteger)
        repeat(maxOf(left.size, right.size)) { i ->
            val order = (left.getOrNull(i) ?: BigInteger.ZERO).compareTo(right.getOrNull(i) ?: BigInteger.ZERO)
            if (order != 0) return order
        }
        return 0
    }

    fun isNewer(latest: String?, installed: String): Boolean = latest != null && compare(latest, installed) > 0

    fun assetVersion(name: String): String? = agentApk.matchEntire(name)?.groupValues?.get(1)

    /**
     * One release's update assets: exactly one `backstage-play-agent-<version>-release.apk` and exactly one `SHA256SUMS`,
     * both with bounded sizes and download URLs inside this repository's release downloads. Null when the release
     * carries no companion APK; an exception when it carries one but is ambiguous or untrusted.
     */
    fun releaseAssets(assets: List<ReleaseAsset>): CompanionRelease? {
        val apks = assets.filter { assetVersion(it.name) != null }
        if (apks.isEmpty()) return null
        val apk = apks.singleOrNull() ?: throw CompanionUpdateException(UpdateFailure.METADATA, "Several companion APKs in one release")
        val sums = assets.filter { it.name == SUMS }.singleOrNull()
            ?: throw CompanionUpdateException(UpdateFailure.CHECKSUM_MISSING, "Release has no unique $SUMS")
        if (apk.size !in 1..MAX_APK) throw CompanionUpdateException(UpdateFailure.TOO_LARGE, "Invalid APK size ${apk.size}")
        if (sums.size !in 1..MAX_SUMS) throw CompanionUpdateException(UpdateFailure.METADATA, "Invalid $SUMS size ${sums.size}")
        listOf(apk, sums).forEach { if (!trustedAssetUrl(it.url, it.name)) throw CompanionUpdateException(UpdateFailure.UNTRUSTED_URL, it.url) }
        return CompanionRelease(requireNotNull(assetVersion(apk.name)), apk, sums)
    }

    /** The highest version wins; on equal versions the earlier (newer-published) release in the list is kept. */
    fun newest(releases: List<CompanionRelease>): CompanionRelease? =
        releases.fold(null as CompanionRelease?) { best, next -> if (best == null || compare(next.version, best.version) > 0) next else best }

    fun trustedAssetUrl(url: String, name: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.rawPath ?: return false
        return uri.scheme == "https" && uri.host == "github.com" && (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && path.startsWith("/$OWNER_REPO/releases/download/") &&
            path.substringAfterLast('/') == name && !path.contains("/../") && !path.contains("/./")
    }

    /** Applied to the first request and to every redirect target before any connection is made to it. */
    fun trustedTransportUrl(uri: URI): Boolean = uri.scheme == "https" && uri.host in trustedHosts &&
        (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null

    fun checksum(text: String, filename: String): String {
        val matches = text.lineSequence().mapNotNull { line ->
            sumLine.matchEntire(line.trimEnd())?.takeIf { it.groupValues[2] == filename }?.groupValues?.get(1)?.lowercase()
        }.toList()
        return matches.singleOrNull()
            ?: throw CompanionUpdateException(UpdateFailure.CHECKSUM_MISSING, "$SUMS has no unique line for $filename")
    }

    fun hashMatches(expected: String, actual: String): Boolean = hex64.matches(expected) &&
        MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), actual.lowercase().toByteArray(Charsets.US_ASCII))

    /** Same package, strictly higher versionCode, and a current signer set identical to the running companion's. */
    fun verify(expectedHash: String, actualHash: String, installed: ApkIdentity, archive: ApkIdentity) {
        if (!hashMatches(expectedHash, actualHash)) throw CompanionUpdateException(UpdateFailure.CHECKSUM_MISMATCH)
        if (archive.packageName != installed.packageName) throw CompanionUpdateException(UpdateFailure.WRONG_PACKAGE, archive.packageName)
        if (archive.versionCode <= installed.versionCode) throw CompanionUpdateException(UpdateFailure.NOT_NEWER, "${archive.versionCode}")
        if (installed.signers.isEmpty() || archive.signers != installed.signers) throw CompanionUpdateException(UpdateFailure.SIGNER_MISMATCH)
    }

    fun requiredSpace(apkSize: Long): Long = apkSize * 3 + 16L * 1024 * 1024

    /** Manual checks are spaced by a minute and also respect a server-requested cooldown. */
    fun canCheck(now: Long, lastAttempt: Long, notBefore: Long): Boolean =
        lastAttempt > now || (now >= lastAttempt + MIN_CHECK_GAP && now >= notBefore)

    fun cooldown(retryAfterMs: Long): Long = retryAfterMs.coerceIn(0, MAX_COOLDOWN)
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
