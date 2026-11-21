package com.eggman.backstage.omp

import android.content.Context
import com.eggman.backstage.data.Provider
import com.eggman.backstage.data.RemoteMcpServer
import com.eggman.backstage.data.Settings
import com.eggman.backstage.skills.SkillStore
import kotlinx.coroutines.*

/** Process-owned runtime: no foreground service or additional app lifecycle is required. */
object OmpHarness {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    // Backstage passes its application context; the runtime deliberately lives for the process.
    @android.annotation.SuppressLint("StaticFieldLeak")
    @Volatile var runtime: OmpRuntime? = null; private set
    @Volatile var brokerMessage = ""; private set
    @Volatile private var broker: OmpKeyBroker? = null
    private var settings: Settings? = null
    private var skillSnapshot = OmpSkills.SnapshotCache()
    private val claims = OmpClaims()
    val jobs: OmpJobs get() = OmpJobs(checkNotNull(runtime) { "OMP harness not initialized" })
    fun unavailableReason(): String? {
        val r = runtime ?: return "OMP harness not initialized; open Backstage."
        return r.unavailableReason()
    }
    fun startUnavailableReason(): String? = unavailableReason()
        ?: if (broker?.running == true) null else "OMP key broker is unavailable; check OMP harness settings and its port."
    fun initialize(context: Context, settings: Settings) {
        if (runtime != null) return
        this.settings = settings
        // Same singleton used by AndroidDeviceServices and the skills settings screen.
        SkillStore.shared(context.filesDir).also { store ->
            val cacheDir = java.io.File(context.cacheDir, "omp-skills")
            // This process has not served any readers yet; remove files left by an interrupted previous process.
            cacheDir.listFiles()?.filter { it.name.startsWith("skills-") && it.extension in setOf("zip", "partial") }
                ?.forEach { it.delete() }
            skillSnapshot = OmpSkills.SnapshotCache(store, cacheDir)
            store.observe { runCatching { com.eggman.backstage.init.Backstage.controller.invalidateOmpEngine() } }
        }
        runtime = OmpRuntime(context, OmpPayload(context.assets), settings)
        settings.observe { key ->
            // Any setting change (including remote_mcp): restart main-chat omp with fresh config next task.
            // Termux also polls while running; omp_subagent refetches on launch.
            runCatching { com.eggman.backstage.init.Backstage.controller.invalidateOmpEngine() }
            when {
                key == "omp_harness_token" -> {
                    claims.clear()
                    scope.launch { runCatching { runtime?.writeBroker() } }
                }
                key == "omp_termux_token" -> Unit // Tokens are read fresh for every request.
                key in Settings.OMP_SERVER_KEYS -> scope.launch { refresh() }
            }
        }
        refresh()
        scope.launch {
            runCatching { runtime?.status(); runtime?.writeBroker() }
            while (isActive) {
                delay(5_000)
                if (settings.ompEnabled && runtime?.payload?.available == true && com.eggman.backstage.privileged.Privileged.available()) {
                    runCatching {
                        if (runtime?.installed == false) runtime?.status()
                        runtime?.writeBroker() // Retries a pending port/token write when privilege becomes available.
                    }
                }
            }
        }
    }
    @Synchronized fun refresh() {
        val s = settings ?: return
        val r = runtime ?: return
        broker?.stop(); broker = null
        claims.clear()
        if (!r.payload.available || (!s.ompEnabled && !s.ompTermuxEnabled)) { brokerMessage = "Stopped"; return }
        try {
            if (s.ompEnabled) s.ompHarnessToken
            if (s.ompTermuxEnabled) s.ompTermuxToken
            broker = OmpKeyBroker(s.ompBrokerPort,
                { if (s.ompEnabled) s.ompHarnessToken else "" },
                { if (s.ompTermuxEnabled) s.ompTermuxToken else "" },
                { OmpConfig.from(s) },
                { id ->
                    if (id.startsWith("mcp-")) mcpKey(id, s.remoteMcpServers)
                    else if (id == "openai-codex-account-id") s.providerConfig(Provider.CHATGPT).chatGpt?.tokens()?.accountId
                    else Provider.entries.find { OmpConfig.providerId(it) == id }?.let { p ->
                        if (p == Provider.CHATGPT) s.providerConfig(p).chatGpt?.tokens()?.accessToken else s.apiKey(p)
                    }
                }, r.payload, claims, shell = OmpShellRelay(
                    enabled = { s.termuxShellEnabled && s.ompTermuxEnabled },
                    autoApprove = { s.termuxShellAutoApprove },
                    confirm = { com.eggman.backstage.init.Backstage.controller.confirmExternalAction(it) },
                    generation = { runCatching { s.ompTermuxToken.hashCode() }.getOrDefault(0) },
                )::run, skills = skillSnapshot::open).also { it.start() }
            brokerMessage = "Listening on 127.0.0.1:${s.ompBrokerPort}"
        } catch (_: Exception) { brokerMessage = "Could not start broker; check its port and device keystore" }
        if (broker?.running == true) scope.launch { runCatching { r.writeBroker() } }
    }
    internal fun mcpKey(id: String, servers: List<RemoteMcpServer>): String? =
        servers.filter { OmpConfig.mcpSlug(it) == id }.singleOrNull()?.token?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    @Synchronized internal fun issueBrokerClaim(port: Int, contents: String): String {
        val s = checkNotNull(settings) { "OMP harness not initialized" }
        check(s.ompEnabled && broker?.running == true && broker?.port == port) { "OMP broker unavailable" }
        // Refuse a snapshot taken before a settings rotation; the background writer will retry.
        check(contents == "port=${s.ompBrokerPort}\ntoken=${s.ompHarnessToken}\n") { "OMP pairing changed" }
        return claims.issue(contents)
    }
    internal fun revokeBrokerClaim(code: String) = claims.revoke(code)
    fun setupLine(port: Int, token: String): String =
        "curl -fsSL -H 'Authorization: Bearer $token' http://127.0.0.1:$port/omp/v1/termux/setup | bash -s -- '$token'"
}
