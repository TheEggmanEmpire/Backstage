package com.eggman.backstage.tools

import com.eggman.backstage.exec.TargetKind
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.net.URLEncoder

data class SearchHit(val title: String, val url: String, val snippet: String)

/** Pure HTML/text helpers for the web tools (unit-tested with fixtures). */
object WebText {
    private val entity = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–",
        "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "euro" to "€",
        "copy" to "©", "reg" to "®", "deg" to "°", "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä",
        "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß", "eacute" to "é", "egrave" to "è",
    )

    fun decodeEntities(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> named[e] ?: m.value
        }
    }

    fun stripTags(html: String): String =
        decodeEntities(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    fun title(html: String): String =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let(::stripTags).orEmpty()

    /** Readable text: drops scripts/styles/nav chrome, keeps paragraph/list/heading breaks. */
    fun readable(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?is)<(script|style|noscript|svg|template|iframe|head)[^>]*>.*?</\\1>"), " ")
        val main = Regex("(?is)<(main|article)[^>]*>(.*?)</\\1>").find(s)?.groupValues?.get(2)
        if (main != null && stripTags(main).length > 400) s = main
        else s = s.replace(Regex("(?is)<(nav|footer|header|aside|form)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|li|tr|h[1-6]|section|article|blockquote|pre|table|ul|ol)>"), "\n")
        s = s.replace(Regex("(?i)<li[^>]*>"), "• ")
        s = s.replace(Regex("(?i)<h[1-6][^>]*>"), "\n## ")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = decodeEntities(s)
        return s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .filter { it.isNotEmpty() && it != "•" && it != "##" }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
    }

    fun parseDuckDuckGo(html: String, max: Int = 8): List<SearchHit> {
        val link = Regex("""(?s)<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""")
        val snippet = Regex("""(?s)class="result__snippet"[^>]*>(.*?)</(?:a|div|span)>""")
        val links = link.findAll(html).toList()
        val snippets = snippet.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        return links.mapIndexedNotNull { i, m ->
            var url = decodeEntities(m.groupValues[1])
            Regex("uddg=([^&]+)").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.startsWith("//")) url = "https:$url"
            if (url.contains("duckduckgo.com/y.js")) return@mapIndexedNotNull null // ads
            SearchHit(stripTags(m.groupValues[2]), url, snippets.getOrElse(i) { "" })
        }.distinctBy { it.url }.take(max)
    }

    fun parseSearxng(json: String, max: Int = 8): List<SearchHit> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            SearchHit(it.optString("title"), it.optString("url"), it.optString("content"))
        }.filter { it.url.isNotBlank() }.take(max)
    }

    /** Brave Search API (`web.results[]`). */
    fun parseBrave(json: String, max: Int): List<SearchHit> {
        val arr = JSONObject(json).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { SearchHit(stripTags(it.optString("title")), it.optString("url"), stripTags(it.optString("description"))) }
            .filter { it.url.isNotBlank() }.take(max)
    }

    /** Serper (Google) API (`organic[]`). */
    fun parseSerper(json: String, max: Int): List<SearchHit> {
        val arr = JSONObject(json).optJSONArray("organic") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { SearchHit(it.optString("title"), it.optString("link"), it.optString("snippet")) }
            .filter { it.url.isNotBlank() }.take(max)
    }

    /** Tavily API (`results[]`). */
    fun parseTavily(json: String, max: Int): List<SearchHit> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { SearchHit(it.optString("title"), it.optString("url"), it.optString("content")) }
            .filter { it.url.isNotBlank() }.take(max)
    }

    fun format(hits: List<SearchHit>): String = hits.mapIndexed { i, h ->
        "${i + 1}. ${h.title}\n   ${h.url}" + if (h.snippet.isNotBlank()) "\n   ${h.snippet.take(300)}" else ""
    }.joinToString("\n")

    fun encode(q: String): String = URLEncoder.encode(q, "UTF-8")
}

private const val UA = WebSearch.BROWSER_UA

class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "Look things up online (news, prices, opening hours, facts). Each hit comes back as title, URL and a short excerpt; mention the URLs your answer is based on. To read a whole page use fetch_url."
    override val schema = Schema.obj("query" to Schema.str("Search query"), "num_results" to Schema.int("1-20, default 5"), required = listOf("query"))
    override val targets = emptySet<TargetKind>()
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val q = args.string("query")
        val max = (args.optInt("num_results") ?: args.optInt("max_results") ?: 5).coerceIn(1, 20)
        val outcome = WebSearch.search(ctx.services, ctx.services.modules(), q, max)
        outcome.error?.let { return ToolResult.error(it) }
        val hits = outcome.hits
        val data = JSONObject().put("success", true).put("query", q).put("count", hits.size).put("results", org.json.JSONArray().apply {
            hits.forEach { put(JSONObject().put("title", it.title).put("link", it.url).put("snippet", it.snippet)) }
        })
        if (hits.isEmpty()) return ToolResult("No results for \"$q\".", data = data)
        return ToolResult(WebText.format(hits), data = data)
    }
}

/** Network destinations are checked before connecting, including the destination of every redirect. */
internal object FetchUrlPolicy {
    fun restricted(address: java.net.InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 16 && b.take(10).all { it == 0 } && b[10] == 255 && b[11] == 255) {
            return restricted(java.net.InetAddress.getByAddress(address.address.copyOfRange(12, 16)))
        }
        return when (b.size) {
            4 -> b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
                (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
                (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
            16 -> (b[0] and 254) == 252 || // IPv6 ULA, missed by InetAddress.isSiteLocalAddress
                // Deny transition addresses that could embed an unchecked private IPv4 destination.
                (b[0] == 0x20 && b[1] == 0x02) ||
                (b[0] == 0 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b) ||
                b.take(12).all { it == 0 }
            else -> true
        }
    }

    fun userNamedHost(host: String, userWords: List<String>?): Boolean {
        val canonical = host.trim('[', ']').trimEnd('.').lowercase(java.util.Locale.ROOT)
        if (canonical.isEmpty()) return false
        val suffix = if (':' in canonical) "(?![\\p{L}\\p{N}_.%:-])" else "\\.?(?![\\p{L}\\p{N}_.%-])"
        val exactHost = Regex("(?<![\\p{L}\\p{N}_.%:-])" + Regex.escape(canonical) + suffix, RegexOption.IGNORE_CASE)
        return userWords?.any { exactHost.containsMatchIn(it) } == true
    }

    fun addresses(host: String, userWords: List<String>?, resolve: (String) -> List<java.net.InetAddress>): List<java.net.InetAddress> {
        val resolved = resolve(host)
        if (resolved.isEmpty()) throw java.net.UnknownHostException("No addresses for $host")
        if (resolved.any(::restricted) && !userNamedHost(host, userWords)) {
            throw ToolArgumentException("Refused to fetch '$host': local/private network addresses require that exact host in the user's own task instructions.")
        }
        // An unspecified or multicast destination is never a readable host, even if named in the request.
        if (resolved.any { it.isAnyLocalAddress || it.isMulticastAddress }) {
            throw ToolArgumentException("Refused to fetch an unspecified or multicast network address.")
        }
        return resolved
    }
}

/** Separate from httpGet: its automatic redirects cannot enforce a destination gate before each request. */
internal object SafeUrlFetch {
    suspend fun get(url: String, userWords: List<String>?): HttpResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var next = url.toHttpUrlOrNull() ?: throw ToolArgumentException("Only valid http(s) URLs can be fetched")
        // A fresh pool and no inherited proxy/interceptors/cookies keep all connections behind this DNS check.
        val base = okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            kotlinx.coroutines.withTimeout(60_000L) {
                for (hop in 0..5) {
                    val target = next
                    val addresses = FetchUrlPolicy.addresses(target.host, userWords) { java.net.InetAddress.getAllByName(it).toList() }
                    val client = base.newBuilder().dns(object : okhttp3.Dns {
                        override fun lookup(hostname: String): List<java.net.InetAddress> {
                            if (hostname != target.host) throw java.net.UnknownHostException("Unexpected fetch host")
                            return addresses // DNS cannot change between check and connect.
                        }
                    }).build()
                    val request = okhttp3.Request.Builder().url(target).get().header("User-Agent", UA)
                        .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5").build()
                    val response = kotlinx.coroutines.suspendCancellableCoroutine<okhttp3.Response> { continuation ->
                        val call = client.newCall(request)
                        continuation.invokeOnCancellation { call.cancel() }
                        call.enqueue(object: okhttp3.Callback {
                            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                            }
                            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                continuation.resume(response) { _, abandoned, _ -> abandoned.close() }
                            }
                        })
                    }
                    response.use { r ->
                        if (r.code in setOf(301, 302, 303, 307, 308)) {
                            if (hop == 5) throw ToolArgumentException("Too many URL redirects (maximum 5)")
                            next = r.header("Location")?.let(target::resolve)
                                ?: throw ToolArgumentException("Redirect has no valid http(s) destination")
                        } else {
                            val body = r.body
                            val source = body?.source()
                            source?.request(2_000_001L)
                            if ((source?.buffer?.size ?: 0) > 2_000_000L) throw ToolArgumentException("Fetched page exceeds the 2000000-byte limit")
                            return@withTimeout HttpResult(r.code, body?.string().orEmpty(), r.header("Content-Type"), target.toString())
                        }
                    }
                }
                error("Redirect limit reached")
            }
        } finally {
            base.connectionPool.evictAll()
            base.dispatcher.executorService.shutdown()
        }
    }
}

class FetchUrlTool(
    private val fetch: suspend (String, List<String>?) -> HttpResult = SafeUrlFetch::get,
): Tool {
    override val name = "fetch_url"
    override val description = "Download a web page (http/https) and return its readable text. Faster than opening a browser when you only need to read."
    override val schema = Schema.obj("url" to Schema.str("http(s) URL"), "max_chars" to Schema.int("Default 8000, max 20000"), required = listOf("url"))
    override val targets = emptySet<TargetKind>()
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val url = args.string("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw ToolArgumentException("Only http(s) URLs can be fetched")
        val max = (args.optInt("max_chars") ?: 8_000).coerceIn(500, 20_000)
        val r = fetch(url, ctx.userWords)
        if (r.code !in 200..299) return ToolResult.error("HTTP ${r.code} for $url")
        val type = r.contentType.orEmpty().lowercase()
        val text = when {
            "json" in type || type.startsWith("text/plain") -> r.body
            "html" in type || r.body.trimStart().startsWith("<") -> {
                val t = WebText.title(r.body)
                (if (t.isNotBlank()) "# $t\n" else "") + WebText.readable(r.body)
            }
            else -> return ToolResult.error("Unsupported content type '$type' (only text/HTML/JSON can be read)")
        }
        val cut = text.length > max
        return ToolResult("URL: ${r.finalUrl.ifBlank { url }}\n" + text.take(max) + if (cut) "\n… (truncated, ${text.length - max} more characters)" else "")
    }
}

/**
 * Opens a web search in the user's default browser: with the "Browser search URL" from Settings, or, when none
 * is set, the browser's own search engine (ACTION_WEB_SEARCH aimed at the default browser).
 */
class BrowserSearchTool : Tool {
    override val name = "browser_search"
    override val description = "Show a web search to the user in their default browser (on the task's screen), using the search engine " +
        "they chose in Settings. Use it when the user wants to see a search in the browser; to just look something up, use web_search."
    override val schema = Schema.obj("query" to Schema.str("Search words"), Schema.target, required = listOf("query"))
    override val targets = BOTH
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val q = args.string("query").trim()
        if (q.isEmpty()) throw ToolArgumentException("query must not be empty")
        val template = ctx.services.modules().browserSearchUrl
        val exec = ctx.exec(args)
        BrowserSearch.problem(template)?.let { return ToolResult.error("The browser search URL in Settings is not usable ($it). Open the browser with launch_app {\"app\":\"browser\"} and type the query into its address bar.") }
        BrowserSearch.url(template, q)?.let { return Observe.after(ctx, exec, exec.openUri(it), settleMs = 4_000) }
        val browser = ctx.services.defaultApps().firstOrNull { it.role == com.eggman.backstage.exec.AppRole.BROWSER }?.packageName
        val fallback = "Open the browser with launch_app {\"app\":\"browser\"} and type the query into its address bar (its own search engine)."
        if (browser == null) return ToolResult.error("The default browser is not known. $fallback")
        val action = exec.openUri(BrowserSearch.webSearchIntent(q, browser))
        if (!action.ok) return ToolResult.error("The default browser did not accept a search request (${action.message}). $fallback")
        return Observe.after(ctx, exec, action, settleMs = 4_000)
    }
}
