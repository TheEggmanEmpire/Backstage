package com.eggman.backstage.ui.settings

import android.content.Context
import com.eggman.backstage.data.MemoryScoring
import com.eggman.backstage.exec.AppCatalog
import com.eggman.backstage.exec.AppEntry
import com.eggman.backstage.mcp.McpClient
import com.eggman.backstage.tools.CalendarInfo
import com.eggman.backstage.tools.HttpResult
import com.eggman.backstage.tools.MemoryItem
import com.eggman.backstage.tools.MemoryService
import com.eggman.backstage.tools.ModuleConfig
import com.eggman.backstage.tools.SearchOutcome
import com.eggman.backstage.tools.WebSearch
import com.eggman.backstage.tools.BrowserSearch
import com.eggman.backstage.tools.CustomSearch
import com.eggman.backstage.tools.Weather
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

/**
 * Device services the module, rules and memory settings use. Defaults reach the running agent core; tests
 * replace them (no network, no SQLite, a fixed app list).
 */
object SettingsServices {
    private fun core() = runCatching {
        com.eggman.backstage.init.Backstage.takeIf { it.isInitialized }?.environment
    }.getOrNull()

    /** The agent's memory store, or null while memory is off (or the core is not running). */
    @Volatile var memory: (Context) -> MemoryService? = { core()?.services?.memory }

    @Volatile var httpGet: suspend (String) -> HttpResult? = { url -> core()?.services?.httpGet(url) }

    /** One web search with [cfg] (the Settings "Test" button); null while the core is not running. */
    @Volatile var webSearch: suspend (ModuleConfig, String) -> SearchOutcome? = { cfg, q ->
        core()?.services?.let { WebSearch.search(it, cfg, q, 5) }
    }

    /** Connects to a remote MCP server and lists its tools; returns a status line. */
    @Volatile var probeMcp: suspend (RemoteMcpServer) -> String = { s ->
        runCatching { McpClient(s.url, s.token.ifBlank { null }).listTools().size }
            .fold({ "Connected: $it tools" }, { "Failed: ${it.message ?: it.javaClass.simpleName}" })
    }

    /** The tool names a remote MCP server offers (Settings' per-tool switches); throws when it cannot be reached. */
    @Volatile var listMcpTools: suspend (RemoteMcpServer) -> List<String> = { s ->
        McpClient(s.url, s.token.ifBlank { null }).listTools().map { it.name }
    }

    @Volatile var launchableApps: (Context) -> List<AppEntry> = { ctx -> runCatching { AppCatalog.installedApps(ctx) }.getOrDefault(emptyList()) }

    /** The skill store: `<filesDir>/skills`, examples added once. */
    @Volatile var skills: (Context) -> com.eggman.backstage.skills.SkillStore = { ctx -> com.eggman.backstage.skills.SkillStore.shared(ctx.filesDir) }

    /** Downloads a skill archive to the given file (size-capped); throws with a readable message. */
    @Volatile var downloadSkill: suspend (com.eggman.backstage.skills.SkillDownload, java.io.File, Long) -> Unit = { d, f, max ->
        com.eggman.backstage.skills.SkillDownloader.download(d, f, max)
    }

    /** Calendars on the phone, empty without the calendar permission. */
    @Volatile var calendars: (Context) -> List<CalendarInfo> = { _ ->
        runCatching { core()?.services?.calendar?.takeIf { it.hasPermission(false) }?.calendars() }.getOrNull().orEmpty()
    }
}

/** Pure helpers behind the module, rules, remote MCP and memory settings (unit-tested). */
object ModuleSettingsLogic {

    fun searchNeedsKey(provider: String): Boolean = provider in WebSearch.KEYED

    fun searchLabel(provider: String): String = when (provider) {
        "duckduckgo" -> "DuckDuckGo"
        "searxng" -> "SearXNG"
        "brave" -> "Brave"
        "serper" -> "Serper (Google)"
        "tavily" -> "Tavily"
        "kagi" -> "Kagi"
        "custom" -> "Custom"
        else -> provider
    }

    /** Problems with the search configuration (shown under the fields); empty = OK. */
    fun searchProblems(provider: String, key: String, searxngUrl: String, customUrl: String = "", customHeaderName: String = ""): List<String> = buildList {
        if (searchNeedsKey(provider) && key.isBlank()) add("${searchLabel(provider)} needs an API key; until then web search uses DuckDuckGo.")
        if (provider == "searxng" && urlProblem(searxngUrl) != null) add("Enter your SearXNG address, e.g. https://search.example.org")
        if (provider == "custom") {
            CustomSearch.templateProblem(customUrl)?.let { add("$it; until then web search uses DuckDuckGo.") }
            if (customHeaderName.isNotBlank() && !customHeaderName.trim().all { it.isLetterOrDigit() || it == '-' || it == '_' })
                add("The header name may contain only letters, digits, - and _ (it is not sent otherwise).")
        }
    }

    /** Warnings that do not stop the configuration from working (plain http on the local network). */
    fun searchWarnings(provider: String, customUrl: String): List<String> =
        if (provider == "custom") listOfNotNull(CustomSearch.templateWarning(customUrl)) else emptyList()

    /** The module configuration the Settings "Test" button searches with (the values on screen). */
    fun searchTestConfig(provider: String, key: String, searxngUrl: String, customUrl: String, headerName: String, headerValue: String) =
        ModuleConfig(searchProvider = provider, searchApiKey = key.trim(), searxngUrl = searxngUrl.trim(), customSearchUrl = customUrl.trim(),
            customSearchHeaderName = headerName.trim(), customSearchHeaderValue = headerValue.trim())

    /** Text for the search "Test" result: the first hits, or why nothing came back. */
    fun searchTestResult(provider: String, outcome: SearchOutcome?, used: String): String = when {
        outcome == null -> "Backstage is still starting; try again in a moment."
        outcome.error != null -> outcome.error
        outcome.hits.isEmpty() -> "${searchLabel(used)} answered, but no results were found." +
            if (provider == "custom") " Check that the URL returns a JSON result list or an HTML page with result links." else ""
        else -> (if (used != provider) "${searchLabel(provider)} is not set up; this came from ${searchLabel(used)}.\n" else "Works (${searchLabel(used)}):\n") +
            outcome.hits.take(3).mapIndexed { i, h -> "${i + 1}. ${h.title.take(80)}\n   ${h.url.take(120)}" }.joinToString("\n")
    }

    /** Browser search: why the template cannot be used, or null. */
    fun browserSearchProblem(template: String): String? = BrowserSearch.problem(template)

    fun weatherTestUrl(key: String, location: String, units: String): String =
        "https://api.openweathermap.org/data/2.5/weather?q=${enc(location)}&appid=${enc(key)}&units=${Weather.units(units)}"

    /** One line describing a weather test call's answer. */
    fun weatherTestResult(result: HttpResult?, units: String): String = when {
        result == null -> "Backstage is still starting; try again in a moment."
        result.code == 200 -> runCatching {
            val w = Weather.current(JSONObject(result.body), Weather.units(units))
            "Works: ${w.optString("location")}, ${w.optString("temperature")}, ${w.optString("description").ifBlank { w.optString("condition") }}"
        }.getOrElse { "Unexpected answer from OpenWeatherMap." }
        result.code == 401 -> "OpenWeatherMap rejected the key (401). New keys can take a couple of hours to become active."
        result.code == 404 -> "OpenWeatherMap does not know that location (404)."
        result.code == 429 -> "Too many requests for this key (429)."
        else -> "OpenWeatherMap answered HTTP ${result.code}."
    }

    /** Why [url] cannot be used for a remote server, or null when it can. */
    fun urlProblem(url: String): String? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return "Not a valid address"
        if (u.scheme?.lowercase() !in setOf("http", "https")) return "The address must start with https:// (or http:// on your own network)"
        if (u.host.isNullOrBlank()) return "The address has no host"
        return null
    }

    /** Plain http to a public host would send the bearer token unencrypted. */
    fun insecureWarning(url: String): String? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (u.scheme?.lowercase() != "http") return null
        val h = u.host?.lowercase() ?: return null
        val local = h == "localhost" || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h) || h.endsWith(".local") || h.endsWith(".lan")
        return if (local) null else "Plain http: the token and every tool call travel unencrypted. Use https."
    }

    /** Adds or replaces (same id) a server; blank names fall back to the host. */
    fun upsertServer(list: List<RemoteMcpServer>, server: RemoteMcpServer): List<RemoteMcpServer> {
        val s = server.copy(
            id = server.id.ifBlank { UUID.randomUUID().toString().take(8) },
            name = server.name.trim().ifBlank { runCatching { URI(server.url.trim()).host }.getOrNull().orEmpty() },
            url = server.url.trim(),
            token = server.token.trim(),
        )
        return if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it } else list + s
    }

    /** One row of a remote server's tool list: on/off and whether the user has not seen it before. */
    data class RemoteToolRow(val name: String, val enabled: Boolean, val isNew: Boolean)

    /** Rows for [names] (sorted); nothing is "new" the first time a server's tools are listed. */
    fun remoteToolRows(server: RemoteMcpServer, names: List<String>): List<RemoteToolRow> =
        names.distinct().sorted().map { RemoteToolRow(it, it !in server.disabledTools, server.knownTools.isNotEmpty() && it !in server.knownTools) }

    fun setRemoteTool(server: RemoteMcpServer, name: String, on: Boolean): RemoteMcpServer =
        server.copy(disabledTools = if (on) server.disabledTools - name else server.disabledTools + name)

    /** All on clears the off-list (later tools stay on too); all off switches off every listed tool. */
    fun setAllRemoteTools(server: RemoteMcpServer, names: List<String>, on: Boolean): RemoteMcpServer =
        server.copy(disabledTools = if (on) emptySet() else server.disabledTools + names)

    /** The listed tools count as seen (their "new" mark goes after this listing). */
    fun acknowledgeRemoteTools(server: RemoteMcpServer, names: List<String>): RemoteMcpServer = server.copy(knownTools = server.knownTools + names)

    fun replaceServer(list: List<RemoteMcpServer>, server: RemoteMcpServer): List<RemoteMcpServer> = list.map { if (it.id == server.id) server else it }

    fun filterApps(apps: List<AppEntry>, query: String): List<AppEntry> {
        val q = query.trim().lowercase()
        val sorted = apps.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        return if (q.isEmpty()) sorted else sorted.filter { q in it.label.lowercase() || q in it.packageName.lowercase() }
    }

    /** Memory list filter: substring or keyword relevance (same scoring the agent uses). */
    fun filterMemories(items: List<MemoryItem>, query: String): List<MemoryItem> {
        val q = query.trim()
        if (q.isEmpty()) return items
        return items.filter { it.content.contains(q, ignoreCase = true) || MemoryScoring.score(q, it.content) > 0 }
    }

    /** Imports an export file into [memory]; returns how many entries are added (duplicates are skipped by the store). */
    fun importMemories(memory: MemoryService, json: String): Int {
        val before = memory.all().size
        MemoryScoring.import(JSONObject(json)).forEach { memory.save(it.content, it.source.ifBlank { "import" }) }
        return memory.all().size - before
    }

    fun exportMemories(memory: MemoryService, appVersion: String, now: Long): String =
        MemoryScoring.export(memory.all(), appVersion, now).toString(2)

    /** "15" → 15, "" → null (no reminder); anything else keeps the old value. */
    fun parseReminder(text: String, old: Int?): Int? = if (text.isBlank()) null else text.trim().toIntOrNull() ?: old

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
}
