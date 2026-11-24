package com.eggman.backstage.llm

import com.eggman.backstage.data.ModelIds
import com.eggman.backstage.data.ModelInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Outcome of a live model-list fetch. */
sealed interface ModelListResult {
    data class Loaded(val models: List<ModelInfo>, val hidden: List<ModelInfo> = emptyList()) : ModelListResult
    data class KeyRejected(val message: String) : ModelListResult
    data class Unavailable(val message: String) : ModelListResult
}

/** What the model picker shows: recommended first (enriched with live data), then everything else. */
data class ProviderModels(val recommended: List<ModelInfo>, val all: List<ModelInfo>, val hidden: List<ModelInfo>)

/** Result of a real one-shot request with a key + model ("reply OK"). */
data class ValidationResult(val ok: Boolean, val kind: Kind, val message: String) {
    enum class Kind { OK, NETWORK, AUTH, MODEL, OTHER }
}

/**
 * Live model lists per provider (with key validation side effect), a 6-hour cache keyed by
 * provider + key hash + URL, recommended/live merging and the save-time validation call.
 * Provider credentials and model lists are configured by the user.
 */
class ModelCatalog(
    base: OkHttpClient = LlmHttp.sharedClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 6 * 3_600_000L,
) {
    private val http = base.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, Pair<Long, ModelListResult.Loaded>>()

    private fun cacheKey(c: ProviderConfig): String {
        val h = MessageDigest.getInstance("SHA-256").digest(c.apiKey.toByteArray()).joinToString("") { "%02x".format(it) }
        return "${c.provider.name}|$h|${c.baseUrl}"
    }

    suspend fun fetch(config: ProviderConfig, force: Boolean = false): ModelListResult {
        val key = cacheKey(config)
        if (!force) cache[key]?.let { (at, v) -> if (clock() - at < ttlMs) return v }
        val r = try {
            fetchLive(config)
        } catch (e: LlmException) {
            if (e.kind == LlmErrorKind.AUTH) ModelListResult.KeyRejected(e.message ?: "rejected") else ModelListResult.Unavailable(e.message ?: "unavailable")
        }
        if (r is ModelListResult.Loaded) cache[key] = clock() to r
        return r
    }

    private suspend fun get(url: String, headers: Map<String, String>): JSONObject {
        val b = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> b.header(k, v) }
        val resp = LlmHttp.execute(http, b.build())
        if (resp.code !in 200..299) {
            val e = LlmHttp.mapHttpError(resp.code, resp.body)
            val lower = resp.body.lowercase()
            if (resp.code == 401 || resp.code == 403 || "api_key_invalid" in lower || "insufficient permissions" in lower || "missing scopes" in lower) {
                throw LlmException(LlmErrorKind.AUTH, e.message ?: "rejected", resp.code)
            }
            throw e
        }
        val t = resp.body.trim()
        return if (t.startsWith("[")) JSONObject().put("data", JSONArray(t)) else JSONObject(t)
    }

    private fun bearer(c: ProviderConfig) = if (c.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${c.apiKey}")

    private suspend fun fetchLive(c: ProviderConfig): ModelListResult {
        val base = c.baseUrl.trimEnd('/')
        return when (c.provider) {
            ProviderId.ANTHROPIC -> {
                val root = base.removeSuffix("/v1")
                val j = get("$root/v1/models?limit=1000", mapOf("x-api-key" to c.apiKey, "anthropic-version" to AnthropicClient.API_VERSION))
                loaded(arr(j, "data").map {
                    Entry(
                        ModelInfo(it.optString("id"), it.optString("display_name").ifBlank { it.optString("id") }, tools = true,
                            vision = it.optJSONObject("capabilities")?.optJSONObject("image_input")?.optBoolean("supported"),
                            context = it.optLong("max_input_tokens").takeIf { n -> n > 0 }),
                        created = it.optString("created_at"),
                    )
                })
            }
            ProviderId.GEMINI -> {
                val root = base.substringBefore("/v1beta").substringBefore("/v1/").removeSuffix("/v1") + "/v1beta"
                val all = ArrayList<JSONObject>()
                var token: String? = null
                var pages = 0
                do {
                    val j = get("$root/models?pageSize=1000" + (token?.let { "&pageToken=$it" } ?: ""), mapOf("x-goog-api-key" to c.apiKey))
                    all += arr(j, "models")
                    token = j.optString("nextPageToken").takeIf { it.isNotBlank() }
                } while (token != null && ++pages < 10)
                loaded(all.filter { m ->
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                }.map {
                    val id = it.optString("name").removePrefix("models/")
                    Entry(ModelInfo(id, it.optString("displayName").ifBlank { id }, tools = true, vision = true,
                        context = it.optLong("inputTokenLimit").takeIf { n -> n > 0 }))
                }.filter { geminiKeep(it.info.id) })
            }
            ProviderId.OPENROUTER -> {
                get("$base/key", bearer(c)) // validates the key
                loaded(arr(get("$base/models", bearer(c)), "data").filter {
                    val out = it.optJSONObject("architecture")?.optJSONArray("output_modalities")
                    out == null || (0 until out.length()).any { i -> out.optString(i) == "text" }
                }.map {
                    val params = it.optJSONArray("supported_parameters")
                    val inputs = it.optJSONObject("architecture")?.optJSONArray("input_modalities")
                    Entry(
                        ModelInfo(it.optString("id"), it.optString("name").ifBlank { it.optString("id") },
                            tools = params?.let { p -> (0 until p.length()).any { i -> p.optString(i) == "tools" } },
                            vision = inputs?.let { p -> (0 until p.length()).any { i -> p.optString(i) == "image" } },
                            context = it.optLong("context_length").takeIf { n -> n > 0 }),
                        createdEpoch = it.optLong("created"),
                    )
                })
            }
            ProviderId.VENICE -> {
                get("$base/api_keys/rate_limits", bearer(c))
                loaded(arr(get("$base/models?type=text", bearer(c)), "data").filter { it.optJSONObject("model_spec")?.optBoolean("offline") != true }.map {
                    val spec = it.optJSONObject("model_spec")
                    val caps = spec?.optJSONObject("capabilities")
                    Entry(
                        ModelInfo(it.optString("id"), spec?.optString("name")?.ifBlank { null } ?: it.optString("id"),
                            tools = caps?.optBoolean("supportsFunctionCalling"), vision = caps?.optBoolean("supportsVision"),
                            context = (spec?.optLong("availableContextTokens") ?: 0L).takeIf { n -> n > 0 } ?: it.optLong("context_length").takeIf { n -> n > 0 }),
                        createdEpoch = it.optLong("created"),
                    )
                })
            }
            ProviderId.FREE -> {
                // Both lanes; one being down still lists the other.
                val errors = ArrayList<String>()
                val models = FreeModels.LANES.flatMap { lane ->
                    try { FreeModels.parseCatalog(lane, get("${lane.base}/models", emptyMap())) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { errors += "${lane.label}: ${e.message}"; emptyList() }
                }
                if (models.isEmpty() && errors.isNotEmpty()) ModelListResult.Unavailable(errors.joinToString("; "))
                else ModelListResult.Loaded(models.sortedBy { it.id.lowercase() })
            }
            // No model list for subscription sign-ins: the catalog's recommendations are the choices.
            ProviderId.CHATGPT -> ModelListResult.Loaded(c.provider.recommended)
            ProviderId.OLLAMA -> {
                val server = base.removeSuffix("/v1")
                loaded(arr(get("$server/api/tags", emptyMap()), "models").map {
                    val name = it.optString("name")
                    Entry(ModelInfo(name, name, tools = null, vision = name.contains("vl") || name.contains("vision") || name.contains("llava")))
                })
            }
            else -> {
                // OpenAI-shaped list: OpenAI, DeepSeek, OpenCode, Custom.
                val headers = bearer(c) + if (c.provider == ProviderId.OPENCODE) mapOf("User-Agent" to c.userAgent) else emptyMap()
                val items = arr(get(OpenAiCompatClient.modelsUrl(c.provider, c.baseUrl), headers), "data").map {
                    val id = it.optString("id")
                    Entry(ModelInfo(id, id, tools = if (c.provider == ProviderId.OPENCODE) true else null, vision = visionGuess(c.provider, id)),
                        createdEpoch = it.optLong("created"))
                }
                if (c.provider == ProviderId.OPENAI) {
                    val kept = items.filter { openAiKeep(it.info.id) }
                    val (hidden, shown) = kept.partition { openAiSnapshot.containsMatchIn(it.info.id) }
                    ModelListResult.Loaded(sort(shown), sort(hidden))
                } else loaded(items)
            }
        }
    }

    private data class Entry(val info: ModelInfo, val createdEpoch: Long = 0, val created: String = "")

    private fun sort(list: List<Entry>): List<ModelInfo> =
        list.filter { it.info.id.isNotBlank() }
            .sortedWith(compareByDescending<Entry> { it.createdEpoch }.thenByDescending { it.created }.thenByDescending { ModelIds.version(it.info.id) })
            .map { it.info }.distinctBy { it.id }

    private fun loaded(list: List<Entry>) = ModelListResult.Loaded(sort(list))

    private fun arr(j: JSONObject, key: String): List<JSONObject> =
        j.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

    /** Live data enriches the bundled recommendations; recommended ids missing from the list still show. */
    fun merge(provider: ProviderId, result: ModelListResult?): ProviderModels {
        val loaded = result as? ModelListResult.Loaded
        val live = loaded?.models.orEmpty()
        val rec = provider.recommended.map { r ->
            val l = live.firstOrNull { ModelIds.same(it.id, r.id) }
            if (l == null) r else r.copy(context = r.context ?: l.context, vision = r.vision ?: l.vision, tools = r.tools ?: l.tools)
        }
        val rest = live.filter { m -> rec.none { ModelIds.same(it.id, m.id) } }
        return ProviderModels(rec, rest, loaded?.hidden.orEmpty())
    }

    /** Save-time check: a minimal real request with this key and model. */
    suspend fun validate(config: ProviderConfig, factory: (ProviderConfig) -> LlmClient = LlmFactory::create): ValidationResult = try {
        val client = factory(config)
        client.complete(LlmRequest("", listOf(Message.user("Answer with OK and nothing else.")), maxTokens = 16, toolChoice = ToolChoice.NONE))
        ValidationResult(true, ValidationResult.Kind.OK, "${config.provider.label} accepted the key; ${config.model} works.")
    } catch (e: LlmException) {
        classify(config, e.kind, e.message ?: "")
    } catch (e: Exception) {
        classify(config, null, e.message ?: e.javaClass.simpleName)
    }

    companion object {
        private val openAiDrop = listOf(
            "translate", "diarize", "embedding", "whisper", "tts", "dall-e", "image", "audio", "realtime", "transcribe", "search",
            "moderation", "instruct", "codex", "-pro", "computer-use", "deep-research", "live", "sora",
        )
        private val openAiSnapshot = Regex("-(\\d{4}-\\d{2}-\\d{2}|\\d{4})$")

        fun openAiKeep(id: String): Boolean {
            val l = id.lowercase()
            val family = listOf("gpt-", "chatgpt-", "o1", "o3", "o4").any { l.startsWith(it) }
            return family && openAiDrop.none { it in l }
        }

        private val geminiDrop = listOf("omni", "aqa", "embedding", "image", "tts", "live", "audio", "transcribe", "computer-use", "robotics", "translate")
        fun geminiKeep(id: String): Boolean = id.startsWith("gemini-") && geminiDrop.none { it in id.lowercase() }

        fun visionGuess(provider: ProviderId, id: String): Boolean? {
            val l = id.lowercase()
            return when (provider) {
                ProviderId.OPENAI -> listOf("gpt-6", "gpt-5", "gpt-4.1", "gpt-4o", "o1", "o3", "o4").any { l.startsWith(it) } && !l.endsWith("-mini") || l.startsWith("o4-mini")
                ProviderId.DEEPSEEK -> listOf("flash", "vision", "v4").any { it in l }
                ProviderId.OPENCODE -> "vision" in l
                else -> null
            }
        }

        fun classify(config: ProviderConfig, kind: LlmErrorKind?, message: String): ValidationResult {
            val m = message.lowercase()
            val label = config.provider.label
            return when {
                kind == LlmErrorKind.NETWORK || kind == LlmErrorKind.TIMEOUT ||
                    listOf("unable to resolve host", "timeout", "timed out", "failed to connect").any { it in m } ->
                    ValidationResult(false, ValidationResult.Kind.NETWORK, "Couldn't reach $label: $message")
                kind == LlmErrorKind.AUTH || listOf("401", "invalid x-api-key", "incorrect api key", "api_key_invalid").any { it in m } ->
                    ValidationResult(false, ValidationResult.Kind.AUTH, "$label rejected this key.")
                kind == LlmErrorKind.NOT_FOUND || listOf("404", "not_found", "does not exist", "model_not_found").any { it in m } ->
                    ValidationResult(false, ValidationResult.Kind.MODEL, "This key can't use ${config.model}; pick another model.")
                kind == LlmErrorKind.CONFIG -> ValidationResult(false, ValidationResult.Kind.OTHER, message)
                else -> ValidationResult(false, ValidationResult.Kind.OTHER, "Couldn't use ${config.model} with this key: $message")
            }
        }
    }
}
