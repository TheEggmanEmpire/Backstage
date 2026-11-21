package com.eggman.backstage.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts secrets (API keys) before they reach preferences. */
interface SecretCipher {
    fun encrypt(plain: String): String
    /** Null when the stored value cannot be decrypted (e.g. keystore reset). Plain legacy values pass through. */
    fun decrypt(stored: String): String?
    /** True only when the encryption key is confirmed absent; transient failures stay false. */
    fun keyMissing(): Boolean = false

    object Plain : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(stored: String) = stored
    }
}

/**
 * AES-256-GCM with a non-exportable Android Keystore key. Stored form: `enc1:` + base64(iv || ciphertext).
 * Values without the prefix are treated as legacy plaintext and re-encrypted on the next write.
 */
class KeystoreCipher(private val alias: String = "backstage_api_keys") : SecretCipher {
    private fun key(create: Boolean = true): SecretKey = synchronized(KEY_LOCK) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return@synchronized it.secretKey }
        check(create) { "Encryption key is missing" }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        gen.generateKey()
    }

    override fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            PREFIX + Base64.getEncoder().encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
        }.getOrElse { plain } // keystore unavailable: keep working rather than lose the key
    }

    override fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun keyMissing(): Boolean = runCatching {
        synchronized(KEY_LOCK) { !KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias) }
    }.getOrDefault(false)

    companion object {
        private val KEY_LOCK = Any()
        const val PREFIX = "enc1:"
    }
}
