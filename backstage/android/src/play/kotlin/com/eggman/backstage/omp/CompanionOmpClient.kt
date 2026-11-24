package com.eggman.backstage.omp

import android.os.Bundle
import com.eggman.backstage.a11y.CompanionConnection
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.data.Provider
import com.eggman.backstage.ui.settings.BackstageSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

object CompanionOmpClient {
    suspend fun request(request: Bundle): Bundle = withContext(Dispatchers.IO) {
        val endpoint = CompanionConnection.privilegedEndpoint(CompanionProtocol.CAP_OMP)
            ?: error("Needs a connected Backstage-play-agent with OMP support")
        endpoint.ompRequest(request)
    }

    fun configRequest(store: BackstageSettingsStore): Bundle {
        val entries = Provider.entries.filter { provider ->
            provider.name == store.providerId || store.apiKey(provider.name).isNotBlank() ||
                (!provider.requiresKey && store.baseUrl(provider.name).isNotBlank())
        }.flatMap { provider ->
            store.enabledModels(provider.name).sorted().map { model ->
                OmpConfig.Entry(
                    provider = provider,
                    baseUrl = store.baseUrl(provider.name),
                    model = model,
                    hasKey = store.apiKey(provider.name).isNotBlank(),
                    context = store.contextOverride(provider.name) ?: store.contextWindow(provider.name).takeIf { it > 0 },
                    maxTokens = null,
                    vision = store.visionOverride(provider.name) ?: store.visionSupported(provider.name),
                )
            }
        }
        val current = Provider.parse(store.providerId) ?: Provider.OPENAI
        val model = store.model(current.name)
        val files = OmpConfig.generate(entries, current, model, store.remoteMcpServers)
            .copy(instructions = store.effectiveGlobalInstructions())
        val keys = JSONObject()
        Provider.entries.forEach { provider ->
            val key = store.apiKey(provider.name)
            if (key.isNotBlank()) keys.put(OmpConfig.providerId(provider), key)
        }
        store.chatGptTokens?.let { tokens ->
            if (tokens.accessToken.isNotBlank()) keys.put("openai-codex", tokens.accessToken)
            if (tokens.accountId.isNotBlank()) keys.put("openai-codex-account-id", tokens.accountId)
        }
        store.remoteMcpServers.forEach { server ->
            server.token.takeIf(String::isNotBlank)?.let { keys.put(OmpConfig.mcpSlug(server), "Bearer $it") }
        }
        return Bundle().apply {
            putString("action", "configure")
            putString("models", files.models)
            putString("config", files.config)
            putString("mcp", files.mcp)
            putString("instructions", files.instructions)
            putString("keys", keys.toString())
            putInt("brokerPort", store.ompBrokerPort)
            putString("harnessToken", store.ompHarnessToken)
            putString("termuxToken", store.ompTermuxToken)
            putBoolean("ompEnabled", store.ompEnabled)
            putBoolean("termuxEnabled", store.ompTermuxEnabled)
            putBoolean("shellEnabled", store.termuxShellEnabled)
            putBoolean("shellAutoApprove", store.termuxShellAutoApprove)
        }
    }

    fun requestConfigured(store: BackstageSettingsStore, request: Bundle): Bundle {
        val config = configRequest(store)
        return Bundle(request).apply {
            for (key in listOf("models", "config", "mcp", "instructions", "keys", "harnessToken", "termuxToken"))
                putString(key, config.getString(key))
            putInt("brokerPort", config.getInt("brokerPort"))
            putBoolean("ompEnabled", config.getBoolean("ompEnabled"))
            putBoolean("termuxEnabled", config.getBoolean("termuxEnabled"))
            putBoolean("shellEnabled", config.getBoolean("shellEnabled"))
            putBoolean("shellAutoApprove", config.getBoolean("shellAutoApprove"))
        }
    }

    suspend fun requestLong(store: BackstageSettingsStore, verb: String, progress: (String) -> Unit = {}): Bundle {
        val id = UUID.randomUUID().toString()
        val start = action(verb) { putString("requestId", id) }
        var reply = request(requestConfigured(store, start))
        if (!reply.getBoolean("ok", true)) return reply
        val deadline = System.currentTimeMillis() + 20 * 60_000L
        while (reply.getString("state") in setOf("pending", "running")) {
            reply.getString("message")?.let(progress)
            check(System.currentTimeMillis() < deadline) { "OMP operation timed out" }
            delay(750)
            reply = request(action("poll") { putString("requestId", id) })
        }
        return reply
    }

    fun action(name: String, block: Bundle.() -> Unit = {}): Bundle = Bundle().apply {
        putString("action", name)
        block()
    }
}
