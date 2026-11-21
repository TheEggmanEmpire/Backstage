package com.eggman.backstage.tools

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** Result of one search call: hits, or a message the user/model can act on. */
data class SearchOutcome(val hits: List<SearchHit>, val error: String? = null)

/**
 * The web_search providers (adds Kagi and a user-defined engine). One entry point, [WebSearch.search], serves the
 * agent's web_search tool and the "Test" button in Settings, so both always behave the same.
 */
object WebSearch {
    val KEYED = setOf("brave", "serper", "tavily", "kagi")

    /** The provider actually used: a keyed provider without a key, or a custom engine without a template, uses DuckDuckGo. */
    fun effectiveProvider(cfg: ModuleConfig): String {
        val p = cfg.searchProvider.lowercase()
        return when {
            p in KEYED && cfg.searchApiKey.isBlank() -> "duckduckgo"
            p == "custom" && CustomSearch.templateProblem(cfg.customSearchUrl) != null -> "duckduckgo"
            else -> p
        }
    }

    suspend fun search(services: DeviceServices, cfg: ModuleConfig, q: String, max: Int): SearchOutcome {
        val key = cfg.searchApiKey
        return try {
            when (effectiveProvider(cfg)) {
                "brave" -> {
                    val r = services.httpGet("https://api.search.brave.com/res/v1/web/search?q=${WebText.encode(q)}&count=$max",
                        mapOf("Accept" to "application/json", "X-Subscription-Token" to key))
                    if (r.code != 200) SearchOutcome(emptyList(), "Brave search failed (HTTP ${r.code}).") else SearchOutcome(WebText.parseBrave(r.body, max))
                }
                "serper" -> {
                    val r = services.httpPost("https://google.serper.dev/search", mapOf("X-API-KEY" to key), JSONObject().put("q", q).put("num", max).toString())
                    if (r.code != 200) SearchOutcome(emptyList(), "Serper search failed (HTTP ${r.code}).") else SearchOutcome(WebText.parseSerper(r.body, max))
                }
                "tavily" -> {
                    val r = services.httpPost("https://api.tavily.com/search", mapOf("Authorization" to "Bearer $key"),
                        JSONObject().put("query", q).put("max_results", max).toString())
                    if (r.code != 200) SearchOutcome(emptyList(), "Tavily search failed (HTTP ${r.code}).") else SearchOutcome(WebText.parseTavily(r.body, max))
                }
                "kagi" -> KagiSearch.search(services, key, q, max)
                "custom" -> CustomSearch.search(services, cfg, q, max)
                "searxng" -> {
                    val base = cfg.searxngUrl.ifBlank { return SearchOutcome(emptyList(), "No SearXNG URL configured.") }
                    val r = services.httpGet(base.trimEnd('/') + "/search?format=json&q=" + WebText.encode(q), mapOf("Accept" to "application/json"))
                    if (r.code != 200) SearchOutcome(emptyList(), "Search failed (HTTP ${r.code}). Is JSON output enabled on the SearXNG instance?")
                    else SearchOutcome(WebText.parseSearxng(r.body, max))
                }
                else -> {
                    val r = services.httpGet("https://html.duckduckgo.com/html/?q=" + WebText.encode(q), mapOf("User-Agent" to BROWSER_UA))
                    if (r.code != 200) SearchOutcome(emptyList(), "Search failed (HTTP ${r.code})") else SearchOutcome(WebText.parseDuckDuckGo(r.body, max))
                }
            }
        } catch (e: JSONException) {
            SearchOutcome(emptyList(), "Unreadable search response: ${e.message}")
        }
    }

    const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Mobile Safari/537.36"
}

/**
 * Kagi Search API. The current API is `POST https://kagi.com/api/v1/search` (bearer token, JSON body `query`/`limit`,
 * results under `data.search`); keys made for the older v0 API (`GET /api/v0/search?q=&limit=`, `Authorization: Bot`,
 * a `data` array of `t:0` results and `t:1` related searches) are tried there when v1 refuses them.
 */
object KagiSearch {
    const val V1 = "https://kagi.com/api/v1/search"
    const val V0 = "https://kagi.com/api/v0/search"

    suspend fun search(services: DeviceServices, key: String, q: String, max: Int): SearchOutcome {
        val v1 = services.httpPost(V1, mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"),
            JSONObject().put("query", q).put("limit", max).toString())
        if (v1.code == 200) return SearchOutcome(parse(v1.body, max))
        if (v1.code in setOf(401, 403, 404, 405)) {
            val v0 = services.httpGet("$V0?q=${WebText.encode(q)}&limit=$max", mapOf("Authorization" to "Bot $key", "Accept" to "application/json"))
            if (v0.code == 200) return SearchOutcome(parse(v0.body, max))
            if (v0.code != 404) return SearchOutcome(emptyList(), errorMessage(v0.code, v0.body))
        }
        return SearchOutcome(emptyList(), errorMessage(v1.code, v1.body))
    }

    /** Search results of either API version; related searches (v0 `t:1`) and other result kinds are skipped. */
    fun parse(json: String, max: Int): List<SearchHit> {
        val root = JSONObject(json)
        val items: List<JSONObject> = when (val data = root.opt("data")) {
            is JSONArray -> objects(data).filter { it.optInt("t", 0) == 0 }
            is JSONObject -> objects(data.optJSONArray("search") ?: JSONArray())
            else -> emptyList()
        }
        return items.map { SearchHit(WebText.stripTags(it.optString("title")), it.optString("url"), WebText.stripTags(it.optString("snippet"))) }
            .filter { it.url.startsWith("http") }.distinctBy { it.url }.take(max)
    }

    /** A readable reason for a failed call ([body] may carry `error: [{code, msg|message}]`). */
    fun errorMessage(code: Int, body: String): String {
        val detail = runCatching {
            val e = JSONObject(body).optJSONArray("error")?.optJSONObject(0)
            e?.optString("msg")?.takeIf { it.isNotBlank() } ?: e?.optString("message")?.takeIf { it.isNotBlank() && it != "null" } ?: e?.optString("code")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val base = when (code) {
            401 -> "Kagi rejected the API key (401). Check the key at kagi.com/api/keys."
            402 -> "Kagi has no API credit left for this key (402). Add funds in Kagi's API billing settings."
            403 -> "Kagi refused the request (403): the key may be limited to other IP addresses."
            429 -> "Kagi rate limit or usage limit reached (429). Try again later or check the API balance."
            else -> "Kagi search failed (HTTP $code)."
        }
        return if (detail != null) "$base Kagi says: ${detail.take(200)}" else base
    }

    private fun objects(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

/**
 * A search engine the user defines with a URL template (`{query}` URL-encoded, optional `{count}`), an optional
 * header, and automatic response handling: JSON (a result array under a common key) or else HTML/text (links and the
 * text around them).
 */
object CustomSearch {
    private const val MAX_SCAN = 400_000
    private val ARRAY_PATHS = listOf("results", "data", "items", "web.results", "organic", "data.search", "hits", "value", "webPages.value")
    private val TITLE = listOf("title", "name", "heading")
    private val URL = listOf("url", "link", "href", "uri")
    private val SNIPPET = listOf("snippet", "content", "description", "body", "summary", "text")

    /** Query encoding for templates: UTF-8 percent-encoding with %20 for spaces (valid in paths and query strings). */
    fun encodeQuery(q: String): String = URLEncoder.encode(q, "UTF-8").replace("+", "%20")

    fun fill(template: String, q: String, count: Int): String =
        template.trim().replace("{query}", encodeQuery(q)).replace("{count}", count.toString())

    /** Why [template] cannot be used, or null. */
    fun templateProblem(template: String): String? {
        val t = template.trim()
        if (t.isEmpty()) return "Enter the search URL, e.g. https://search.example.org/api?q={query}"
        if (!t.contains("{query}")) return "The URL must contain {query} where the search words go"
        val u = runCatching { URI(fill(t, "test", 5)) }.getOrNull() ?: return "Not a valid address"
        val scheme = u.scheme?.lowercase()
        if (u.host.isNullOrBlank()) return "The address has no host"
        if (scheme == "https") return null
        if (scheme == "http" && isLocal(u.host)) return null
        return "The address must start with https:// (http:// only for your own network)"
    }

    /** A warning for plain http on the local network (allowed, but unencrypted). */
    fun templateWarning(template: String): String? {
        val u = runCatching { URI(fill(template, "test", 5)) }.getOrNull() ?: return null
        return if (u.scheme?.lowercase() == "http" && isLocal(u.host.orEmpty()))
            "Plain http: queries (and the header below) travel unencrypted on your network." else null
    }

    fun isLocal(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h) || h.endsWith(".local") || h.endsWith(".lan") ||
            h.endsWith(".home.arpa") || h == "[::1]" || h == "::1"
    }

    suspend fun search(services: DeviceServices, cfg: ModuleConfig, q: String, max: Int): SearchOutcome {
        templateProblem(cfg.customSearchUrl)?.let { return SearchOutcome(emptyList(), "Custom search engine: $it") }
        val url = fill(cfg.customSearchUrl, q, max)
        val headers = buildMap {
            put("User-Agent", WebSearch.BROWSER_UA)
            put("Accept", "application/json, text/html;q=0.9, text/plain;q=0.8")
            val name = cfg.customSearchHeaderName.trim()
            if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }) put(name, cfg.customSearchHeaderValue.trim())
        }
        val r = services.httpGet(url, headers)
        if (r.code !in 200..299) return SearchOutcome(emptyList(), "Custom search engine answered HTTP ${r.code}.")
        val hits = parse(r.body, r.contentType, max, base = r.finalUrl.ifBlank { url })
        return SearchOutcome(hits)
    }

    /** JSON when the body is JSON, else HTML/text extraction. */
    fun parse(body: String, contentType: String?, max: Int, base: String): List<SearchHit> {
        val trimmed = body.trimStart()
        val looksJson = contentType.orEmpty().contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")
        if (looksJson) {
            val hits = runCatching { parseJson(trimmed, max) }.getOrNull()
            if (hits != null) return hits
        }
        return if (trimmed.contains('<')) parseHtml(body, max, base) else parseText(body, max)
    }

    fun parseJson(json: String, max: Int): List<SearchHit> {
        val root: Any = if (json.startsWith("[")) JSONArray(json) else JSONObject(json)
        val arr = findArray(root) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val url = first(o, URL) ?: return@mapNotNull null
            if (!url.startsWith("http")) return@mapNotNull null
            SearchHit(WebText.stripTags(first(o, TITLE) ?: url), url, WebText.stripTags(first(o, SNIPPET).orEmpty()).take(500))
        }.distinctBy { it.url }.take(max)
    }

    private fun first(o: JSONObject, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it is String && it.isNotBlank() } as String? }

    private fun at(root: Any, path: String): Any? = path.split('.').fold<String, Any?>(root) { cur, k -> (cur as? JSONObject)?.opt(k) }

    private fun isResultArray(a: JSONArray): Boolean =
        (0 until minOf(a.length(), 5)).any { i -> a.optJSONObject(i)?.let { o -> URL.any { o.opt(it) is String } } == true }

    /** A known result path first, then the first array of result-like objects (breadth-first, depth 3). */
    private fun findArray(root: Any): JSONArray? {
        if (root is JSONArray) return root.takeIf(::isResultArray)
        ARRAY_PATHS.forEach { p -> (at(root, p) as? JSONArray)?.takeIf(::isResultArray)?.let { return it } }
        var level = listOf(root)
        repeat(3) {
            val next = mutableListOf<Any>()
            level.forEach { node ->
                val children: List<Any?> = when (node) {
                    is JSONObject -> node.keys().asSequence().map { node.opt(it) }.toList()
                    is JSONArray -> (0 until node.length()).map { node.opt(it) }
                    else -> emptyList()
                }
                children.forEach { c ->
                    if (c is JSONArray && isResultArray(c)) return c
                    if (c is JSONObject || c is JSONArray) next += c
                }
            }
            level = next
        }
        return null
    }

    /**
     * Links with their surrounding text. Links to other sites come first (a results page links out; its own links
     * are mostly navigation); relative links are resolved against [base].
   */
    fun parseHtml(html: String, max: Int, base: String): List<SearchHit> {
        var s = html.take(MAX_SCAN)
        s = s.replace(Regex("(?is)<(script|style|noscript|svg|template|head)[^>]*>.*?</\\1>"), " ")
        val baseUri = runCatching { URI(base) }.getOrNull()
        val ownHost = baseUri?.host?.lowercase()?.removePrefix("www.")
        val anchor = Regex("(?is)<a\\s[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")
        val found = anchor.findAll(s).mapNotNull { m ->
            val href = WebText.decodeEntities(m.groupValues[1]).trim()
            val abs = runCatching { if (baseUri != null) baseUri.resolve(href).toString() else href }.getOrNull() ?: return@mapNotNull null
            if (!abs.startsWith("http://") && !abs.startsWith("https://")) return@mapNotNull null
            val title = WebText.stripTags(m.groupValues[2])
            if (title.length < 3) return@mapNotNull null
            val after = s.substring(m.range.last + 1, minOf(s.length, m.range.last + 1 + 1_200)).substringBefore("<a ").substringBefore("<A ")
            val host = runCatching { URI(abs).host?.lowercase()?.removePrefix("www.") }.getOrNull()
            Triple(SearchHit(title.take(200), abs, WebText.stripTags(after).take(300)), host != null && host != ownHost, abs)
        }.toList()
        val external = found.filter { it.second }.map { it.first }
        val hits = external.ifEmpty { found.map { it.first } }
        return hits.distinctBy { it.url.substringBefore('#') }.take(max)
    }

    /** Plain text: every URL with the rest of its line as the snippet. */
    fun parseText(text: String, max: Int): List<SearchHit> {
        val url = Regex("https?://[^\\s<>\"')\\]]+")
        return text.take(MAX_SCAN).lines().flatMap { line ->
            url.findAll(line).map { m -> SearchHit(line.replace(m.value, "").trim().trim('-', ':', '|', ' ').take(200).ifBlank { m.value }, m.value, "") }.toList()
        }.distinctBy { it.url }.take(max)
    }
}

/**
 * Where browser_search sends the user's default browser: its own search engine (empty template) or a URL template
 * with `{query}` (a preset or the user's own).
 */
object BrowserSearch {
    data class Preset(val id: String, val label: String, val template: String)

    val PRESETS = listOf(
        Preset("browser", "Browser's own", ""),
        Preset("kagi", "Kagi", "https://kagi.com/search?q={query}"),
        Preset("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q={query}"),
        Preset("google", "Google", "https://www.google.com/search?q={query}"),
        Preset("bing", "Bing", "https://www.bing.com/search?q={query}"),
        Preset("startpage", "Startpage", "https://www.startpage.com/do/search?query={query}"),
    )

    /** The preset whose template equals [template], or null for a custom one. */
    fun presetOf(template: String): Preset? = PRESETS.firstOrNull { it.template == template.trim() }

    /** The URL to open, or null when the browser's own search is used. */
    fun url(template: String, query: String): String? =
        template.trim().takeIf { it.isNotEmpty() }?.let { CustomSearch.fill(it, query, 10) }

    /** Why a custom browser template cannot be used (must contain {query}; https, or http on the local network). */
    fun problem(template: String): String? = if (template.isBlank()) null else CustomSearch.templateProblem(template)

    /**
     * An `intent:` URI for ACTION_WEB_SEARCH with the query, aimed at [browserPackage] (the user's default browser)
     * so the browser runs it with its own search engine.
   */
    fun webSearchIntent(query: String, browserPackage: String): String =
        "intent:#Intent;action=android.intent.action.WEB_SEARCH;package=$browserPackage;S.query=${CustomSearch.encodeQuery(query)};end"
}
