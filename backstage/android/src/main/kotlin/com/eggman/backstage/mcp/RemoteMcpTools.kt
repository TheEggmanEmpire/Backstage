package com.eggman.backstage.mcp

import android.util.Log
import com.eggman.backstage.data.RemoteMcpServer
import com.eggman.backstage.tools.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Connects the enabled remote MCP servers and caches their tool lists (5 minutes) so each task
 * gets them without reconnecting. A server that fails to answer within 15 s is skipped.
 *
 * (task start latency): an expired list is served at once while it is refreshed in the background
 * (stale-while-refresh), and a server that just failed is skipped for [failTtlMs] instead of costing every task up to
 * 15 s (it is retried in the background meanwhile).
 */
class RemoteMcpTools(
    private val clientFactory: (RemoteMcpServer) -> McpClient = { McpClient(it.url, it.token) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 5 * 60_000L,
    private val failTtlMs: Long = 2 * 60_000L,
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Entry(val key: String, val at: Long, val tools: List<Tool>)
    private class Failure(val key: String, val at: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val failures = ConcurrentHashMap<String, Failure>()
    private val refreshing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Per-server connection status for the settings screen. */
    val status = ConcurrentHashMap<String, String>()

    /** The enabled servers' tools, minus the ones switched off per server. */
    suspend fun tools(servers: List<RemoteMcpServer>): List<Tool> = coroutineScope {
        servers.filter { it.enabled }.map { s -> async { allTools(s).filter { t -> (t as? RemoteMcpTool)?.remoteName !in s.disabledTools } } }
            .flatMap { it.await() }
    }

    /** Every tool [s] offers (cached like [tools]), switched off or not: the list behind the Settings switches. */
    suspend fun allTools(s: RemoteMcpServer): List<Tool> = coroutineScope {
        listOf(s).map { s ->
            async {
                val key = "${s.url}|${s.token.hashCode()}"
                val entry = cache[s.id]?.takeIf { it.key == key }
                if (entry != null) {
                    if (clock() - entry.at >= ttlMs) refreshLater(s, key)
                    return@async entry.tools
                }
                failures[s.id]?.let { f ->
                    if (f.key == key && clock() - f.at < failTtlMs) {
                        refreshLater(s, key)
                        return@async emptyList()
                    }
                }
                fetch(s, key).orEmpty()
            }
        }.flatMap { it.await() }
    }

    private suspend fun fetch(s: RemoteMcpServer, key: String): List<Tool>? {
        val tools = withTimeoutOrNull(15_000) {
            runCatching {
                val client = clientFactory(s)
                client.listTools().map { RemoteMcpTool(s.name.ifBlank { client.serverName.ifBlank { "remote" } }, it, client) }
            }.onFailure {
                status[s.id] = "failed: ${it.message}"
                runCatching { Log.w("RemoteMcp", "${s.url}: ${it.message}") }
            }.getOrNull()
        }
        if (tools != null) {
            status[s.id] = "connected (${tools.size} tools)"
            cache[s.id] = Entry(key, clock(), tools)
            failures.remove(s.id)
        } else {
            if (status[s.id]?.startsWith("failed") != true) status[s.id] = "failed: no answer within 15 s"
            failures[s.id] = Failure(key, clock())
        }
        return tools
    }

    private fun refreshLater(s: RemoteMcpServer, key: String) {
        if (!refreshing.add(s.id)) return
        refreshScope.launch {
            try { fetch(s, key) } finally { refreshing.remove(s.id) }
        }
    }

    fun invalidate() { cache.clear(); failures.clear() }
}
