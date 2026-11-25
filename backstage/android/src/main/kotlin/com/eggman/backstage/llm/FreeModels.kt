package com.eggman.backstage.llm

import com.eggman.backstage.data.ModelInfo
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * The built-in "Free models" provider: keyless free tiers that their operators offer without an account
 * (KiloCode Gateway `isFree` models, OVHcloud AI Endpoints anonymous access). Model ids carry their lane:
 * `kilo/<upstream id>`, `ovh/<upstream id>`, plus [AUTO] ("Free Auto").
 *
 * Free Auto sends each request to the best-ranked enabled free model and moves on to the next one when a
 * model fails (rate limit, outage, withdrawn model, network error).
 */
object FreeModels {
    const val AUTO = "auto"
    const val AUTO_NAME = "Free Auto"

    data class Lane(val key: String, val base: String, val label: String)

    val KILO = Lane("kilo", "https://api.kilo.ai/api/gateway", "KiloCode")
    val OVH = Lane("ovh", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1", "OVHcloud")
    val LANES = listOf(KILO, OVH)

    /** Enabled on a fresh install. */
    val SEED = listOf(
        AUTO, "ovh/DeepSeek-V4.1-Flash-1017", "kilo/reflection/beam-free", "kilo/kilo-auto/free",
        "kilo/nvidia/nemotron-3.5-lightning:free", "kilo/nvidia/nemotron-3-ultra-550b-a55b:free",
        "kilo/nvidia/nemotron-3.5-super-vl:free", "ovh/gpt-oss-120b", "ovh/Qwen3.8-27B",
    )

    val SEED_INFO: List<ModelInfo> = SEED.map {
        if (it == AUTO) ModelInfo(AUTO, AUTO_NAME, default = true, tools = true, vision = true, note = "best available, switches when one is rate limited")
        else ModelInfo(it, displayName(it), tools = true)
    }

    /** OVH lists speech, image, embedding and guard models next to its chat models. */
    private val OVH_NON_CHAT = Regex("whisper|tts|diffusion|^bge-|embedding|guard", RegexOption.IGNORE_CASE)

    data class Route(val lane: Lane, val model: String)

    /** The lane and upstream model of a free id; null for [AUTO] and unknown ids. */
    fun route(id: String): Route? {
        val i = id.indexOf('/')
        if (i <= 0) return null
        val lane = LANES.firstOrNull { it.key == id.substring(0, i) } ?: return null
        return Route(lane, id.substring(i + 1)).takeIf { it.model.isNotBlank() }
    }

    fun isFreeId(id: String): Boolean = id == AUTO || route(id) != null

    fun displayName(id: String): String = if (id == AUTO) AUTO_NAME else route(id)?.let { "${it.model} (${it.lane.label})" } ?: id

    // ---- Free Auto ranking -----------------------------------------------------------------------

    /**
     * Artificial Analysis Intelligence Index (artificialanalysis.ai/leaderboards/models), shipped with the app
     * and refreshed for each release. Free models without a score are placed by the median rule in [rank].
     */
    const val INTEL_AS_OF = "2026-11-24"
    val INTEL: Map<String, Double> = mapOf(
        "ovh/DeepSeek-V4.1-Flash-1017" to 33.0,
        "ovh/Qwen3.8-27B" to 20.0,
        "kilo/reflection/beam-free" to 18.0,
        "kilo/nvidia/nemotron-3.5-super-vl:free" to 14.0,
        "kilo/nvidia/nemotron-3-ultra-550b-a55b:free" to 12.0,
        "kilo/liquid/lfm-3-1.7b:free" to 6.0,
        "kilo/nvidia/nemotron-3.5-lightning:free" to 6.0,
        "ovh/gpt-oss-120b" to 4.0,
    )

    /** Routers that choose a model themselves; they are always tried last. */
    private val ROUTERS = Regex("^kilo/(kilo-auto/free|openrouter/free)$")

    data class Caps(val context: Long? = null, val vision: Boolean? = null)

    data class Candidate(
        val id: String,
        val base: String,
        val model: String,
        val lane: String,
        val score: Double,
        val intelligence: Double,
        /** True when [intelligence] is the median stand-in, not a published score. */
        val estimated: Boolean,
        val context: Long?,
        val vision: Boolean?,
    )

    private fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 15.0
        val a = xs.sorted()
        val k = a.size / 2
        return if (a.size % 2 == 1) a[k] else (a[k - 1] + a[k]) / 2
    }

    /**
     * score = intelligence + context + vision
     *   intelligence: [INTEL]; a model without one gets the median of the known scores among the candidates
     *   context: 2 points per doubling above 32K, 0..10 (1M ≈ 10)
     *   vision: +5
     * Routers go last; ties break by id.
     */
    fun rank(enabled: Collection<String>, caps: (String) -> Caps): List<Candidate> {
        val ids = enabled.filter { it != AUTO && route(it) != null }.distinct()
        val mid = median(ids.mapNotNull { INTEL[it] })
        return ids.map { id ->
            val r = route(id)!!
            val c = caps(id)
            val known = INTEL[id]
            val intel = known ?: mid
            val ctxPoints = c.context?.takeIf { it > 0 }?.let { (2 * ln(it / 32_768.0) / ln(2.0)).coerceIn(0.0, 10.0) } ?: 0.0
            val visionPoints = if (c.vision == true) 5.0 else 0.0
            val score = ((intel + ctxPoints + visionPoints) * 10).roundToLong() / 10.0
            Candidate(id, r.lane.base, r.model, r.lane.label, score, intel, known == null, c.context, c.vision)
        }.sortedWith(compareBy<Candidate> { if (ROUTERS.matches(it.id)) 1 else 0 }.thenByDescending { it.score }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.id })
    }

    /** A failed request moves to the next model, up to this many times (so at most RETRIES + 1 models per call). */
    const val RETRIES = 3

    /**
     * Context window assumed for Free Auto: the smallest known window among the enabled models (cooldowns and
     * screenshots can send a request to any of them), so a conversation never outgrows the model it fails over to.
     */
    fun autoContext(ranked: List<Candidate>, fallback: Long): Long =
        ranked.mapNotNull { it.context?.takeIf { c -> c > 0 } }.minOrNull() ?: fallback

    // ---- catalog ---------------------------------------------------------------------------------

    /** Context window and image input of one `/models` entry, across the common list shapes. */
    fun caps(m: JSONObject): Caps {
        val top = m.optJSONObject("top_provider")
        val ctx = listOf(m.optLong("context_length"), m.optLong("context_window"), m.optLong("max_model_len"), top?.optLong("context_length") ?: 0L)
            .firstOrNull { it > 0 }
        val inputs = m.optJSONObject("architecture")?.optJSONArray("input_modalities") ?: m.optJSONArray("input_modalities")
        val vision = inputs?.let { a -> (0 until a.length()).any { a.optString(it).contains("image", true) } }
        return Caps(ctx, vision)
    }

    /** Chat models of one lane's `/models` reply, as free ids. Kilo: `isFree` only. OVH: no speech/image/embedding/guard. */
    fun parseCatalog(lane: Lane, json: JSONObject): List<ModelInfo> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.mapNotNull { m ->
            val upstream = m.optString("id").ifBlank { return@mapNotNull null }
            when (lane) {
                KILO -> if (!m.optBoolean("isFree", false)) return@mapNotNull null
                OVH -> if (OVH_NON_CHAT.containsMatchIn(upstream) || (m.has("context_length") && m.optLong("context_length") <= 0)) return@mapNotNull null
            }
            val id = "${lane.key}/$upstream"
            val c = caps(m)
            val name = m.optString("name").takeIf { it.isNotBlank() && lane == KILO }?.let { "$it (${lane.label})" } ?: displayName(id)
            ModelInfo(id, name, tools = true, vision = c.vision, context = c.context)
        }
    }

    // ---- failover state --------------------------------------------------------------------------

    /** Models that failed sit out until this time (epoch ms), for the whole process. */
    val cooldown = ConcurrentHashMap<String, Long>()
    const val COOLDOWN_MS = 60_000L

    /** Free Auto gives each model this long to answer before moving on; a stalled model sits out [STALL_COOLDOWN_MS]. */
    const val STALL_MS = 60_000L
    const val STALL_COOLDOWN_MS = 5 * 60_000L

    /** Longest a rate-limited model sits out; an exhausted anonymous quota answers with a retry-after of weeks. */
    const val MAX_RATE_LIMIT_COOLDOWN_MS = 24 * 60 * 60_000L

    /** Models that rejected a request with images; image requests skip them for the rest of the process. */
    val rejectsImages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** A 400/404/415/422 on a request with images: the model (or every endpoint behind it) takes text only. */
    fun isImageRejection(e: LlmException): Boolean =
        e.httpCode in setOf(400, 404, 415, 422) && e.kind != LlmErrorKind.CONTEXT_LENGTH

    /** How long a model sits out after [e]; null = not at all (the next request may use it again). */
    fun cooldownFor(e: LlmException): Long? = when {
        e.kind == LlmErrorKind.RATE_LIMIT || e.httpCode == 429 -> (e.retryAfterMs ?: COOLDOWN_MS).coerceIn(1_000, MAX_RATE_LIMIT_COOLDOWN_MS)
        e.kind == LlmErrorKind.NOT_FOUND || e.httpCode == 404 || e.httpCode == 410 -> 30 * 60_000L
        e.kind == LlmErrorKind.TIMEOUT -> STALL_COOLDOWN_MS
        e.kind in setOf(LlmErrorKind.SERVER, LlmErrorKind.OVERLOADED, LlmErrorKind.NETWORK) -> COOLDOWN_MS
        else -> null
    }
}

/**
 * Free Auto: tries [candidates] best first. Any failure, or no answer within [stallMs], moves on to the next
 * model, up to [FreeModels.RETRIES] times; the last failure is thrown for the normal retry and error handling.
 * Requests carrying images go to models known to take images first, skip models known (or found) to be text
 * only, and a model's image rejection does not use up a retry. The reply's [LlmReply.model] names the model
 * that answered.
 */
class FreeAutoClient(
    private val candidates: List<FreeModels.Candidate>,
    private val vision: Boolean,
    private val clientFor: (FreeModels.Candidate, Boolean) -> LlmClient,
    private val stallMs: Long = FreeModels.STALL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : LlmClient {
    override val provider: ProviderId = ProviderId.FREE
    override val model: String = FreeModels.AUTO

    /** The model that answered the last successful request. */
    @Volatile var served: String? = null
        private set

    override suspend fun complete(request: LlmRequest): LlmReply {
        if (candidates.isEmpty()) throw LlmException(LlmErrorKind.CONFIG, "No free models are enabled (Settings → AI provider → Free models).")
        val images = vision && request.messages.any { it.hasImage }
        val usable = if (!images) candidates else candidates
            .filter { it.vision != false && it.id !in FreeModels.rejectsImages }
            .sortedBy { if (it.vision == true) 0 else 1 }
            .ifEmpty { candidates }
        val now = clock()
        val ready = usable.filter { (FreeModels.cooldown[it.id] ?: 0) <= now }
            // All cooling down: the ones that free up first.
            .ifEmpty { usable.sortedBy { FreeModels.cooldown[it.id] ?: 0 } }
        var last: LlmException? = null
        var failures = 0
        for (c in ready) {
            if (failures > FreeModels.RETRIES) break
            try {
                val reply = withTimeoutOrNull(stallMs) { clientFor(c, images && c.vision != false).complete(request) }
                    ?: throw LlmException(LlmErrorKind.TIMEOUT, "${FreeModels.displayName(c.id)} did not answer within ${stallMs / 1000} s")
                served = c.id
                return reply.copy(model = c.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                if (images && FreeModels.isImageRejection(e)) {
                    FreeModels.rejectsImages += c.id
                } else {
                    FreeModels.cooldownFor(e)?.let { FreeModels.cooldown[c.id] = clock() + it }
                    failures++
                }
                last = e
            } catch (e: Exception) {
                FreeModels.cooldown[c.id] = clock() + FreeModels.COOLDOWN_MS
                last = LlmException(LlmErrorKind.NETWORK, e.message ?: e.javaClass.simpleName, cause = e)
                failures++
            }
        }
        // The next model may be ready at once: a long retry-after from one model must not hold up the retry.
        val e = last!!
        throw LlmException(e.kind, e.message.orEmpty(), e.httpCode, retryAfterMs = null, cause = e.cause ?: e)
    }
}
