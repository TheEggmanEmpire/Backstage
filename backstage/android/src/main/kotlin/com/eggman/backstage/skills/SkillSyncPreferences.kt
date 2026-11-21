package com.eggman.backstage.skills

import android.content.Context
import android.content.SharedPreferences
import com.eggman.backstage.data.KeystoreCipher
import com.eggman.backstage.data.SecretCipher
import org.json.JSONObject

/** Tokens are kept outside config so settings export can handle them as secrets. */
class SkillSyncPreferences(private val prefs: SharedPreferences, private val cipher: SecretCipher = KeystoreCipher()) {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    fun config(): SkillSyncConfig = SkillSyncConfig.parse(prefs.getString("config", null))
    fun statuses(): Map<String, SkillSyncStatus> {
        val o = runCatching { JSONObject(prefs.getString("status", "{}").orEmpty()) }.getOrDefault(JSONObject())
        return o.keys().asSequence().mapNotNull { id -> o.optJSONObject(id)?.let { id to SkillSyncStatus.parse(it) } }.toMap()
    }

    fun token(id: String): String {
        val stored = prefs.getString("token_$id", "").orEmpty()
        if (stored.isEmpty()) return ""
        return cipher.decrypt(stored) ?: error("The GitHub token cannot be decrypted. Paste it again for this source.")
    }

    /** All validation/encryption completes before a single preference commit. No plaintext fallback. */
    fun save(config: SkillSyncConfig, tokenUpdates: Map<String, String> = emptyMap()) {
        config.validated()
        require(tokenUpdates.keys.all { id -> config.sources.any { it.id == id } }) { "Unknown GitHub source token." }
        val encrypted = tokenUpdates.mapValues { (_, token) ->
            SkillSyncInputs.checkToken(token)
            if (token.isEmpty()) "" else cipher.encrypt(token).also {
                check(it.startsWith(KeystoreCipher.PREFIX)) { "Device keystore unavailable; the GitHub token was not saved." }
            }
        }
        val old = config()
        val removed = old.sources.map { it.id }.toSet() - config.sources.map { it.id }.toSet()
        val statuses = JSONObject().also { o -> statuses().filterKeys { it !in removed }.forEach { (id, s) -> o.put(id, s.toJson()) } }
        val edit = prefs.edit().putString("config", config.toJson().toString()).putString("status", statuses.toString())
        encrypted.forEach { (id, token) -> if (token.isEmpty()) edit.remove("token_$id") else edit.putString("token_$id", token) }
        removed.forEach { edit.remove("token_$it") }
        check(edit.commit()) { "Could not save GitHub sync settings." }
    }

    fun saveStatus(id: String, status: SkillSyncStatus) {
        val statuses = JSONObject().also { o -> statuses().forEach { (key, s) -> o.put(key, s.toJson()) }; o.put(id, status.toJson()) }
        check(prefs.edit().putString("status", statuses.toString()).commit()) { "Could not save GitHub sync status." }
    }

    var nextSync: Long
        get() = prefs.getLong("nextSync", 0)
        set(value) { prefs.edit().putLong("nextSync", value).apply() }

    /** A settings restore asked for one run even with automatic sync off; cleared when that run starts. Runtime state. */
    internal var restorePending: Boolean
        get() = prefs.getBoolean("restorePending", false)
        set(value) { prefs.edit().putBoolean("restorePending", value).commit() }

    internal var periodicNextSync: Long
        get() = prefs.getLong("periodicNextSync", 0)
        set(value) { prefs.edit().putLong("periodicNextSync", value).apply() }

    companion object { const val FILE = "backstage_skill_sync" }
}
