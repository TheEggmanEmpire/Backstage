package com.eggman.backstage.a11y.companion

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import java.security.MessageDigest

/** Pure allow-list and current-certificate-set equality. A historical signing certificate is not enough. */
object CallerPolicy {
    private val packages = setOf(CompanionProtocol.PLAY_PACKAGE)
    private val digest = Regex("[0-9a-fA-F]{64}")

    fun sameSigners(a: Set<String>, b: Set<String>): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && a.all { digest.matches(it) } && b.all { digest.matches(it) } &&
            a.map { it.lowercase(java.util.Locale.ROOT) }.toSet() == b.map { it.lowercase(java.util.Locale.ROOT) }.toSet()

    // Reject unexpected shared-UID siblings as well as unnamed/isolated callers: accepting just one permitted
    // package would lend that package's authority to every other package sharing the UID.
    fun allowed(callers: Map<String, Set<String>>, own: Set<String>): Boolean =
        callers.isNotEmpty() && callers.all { (name, signers) -> name in packages && sameSigners(signers, own) }
}

object CompanionSignatures {
    fun current(context: Context, pkg: String): Set<String> {
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        return info.signingInfo?.apkContentsSigners.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }.toSet()
    }

    fun companionMatches(context: Context): Boolean = runCatching {
        CallerPolicy.sameSigners(current(context, context.packageName), current(context, CompanionProtocol.PACKAGE))
    }.getOrDefault(false)

    /** Must run on the incoming Binder thread, before clearCallingIdentity or a coroutine/Handler hop. */
    fun enforceCaller(context: Context): Int {
        context.enforceCallingPermission(CompanionProtocol.PERMISSION, "Backstage Agent requires its signature permission")
        val uid = Binder.getCallingUid()
        val names = context.packageManager.getPackagesForUid(uid).orEmpty()
        val verified = runCatching {
            CallerPolicy.allowed(names.associateWith { current(context, it) }, current(context, context.packageName))
        }.getOrDefault(false)
        if (!verified) throw SecurityException("Backstage Agent rejected caller UID $uid")
        return uid
    }
}
