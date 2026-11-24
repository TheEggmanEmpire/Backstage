package com.eggman.backstage.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The tokens of a ChatGPT sign-in. Stored encrypted by [com.eggman.backstage.data.Settings]; never shown to the user.
 * [accountId] goes into the `chatgpt-account-id` header of every model call.
 */
data class ChatGptTokens(
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val accountId: String,
    val email: String = "",
    val plan: String = "",
    /** Access token expiry (epoch ms); 0 = unknown. */
    val expiresAtMs: Long = 0,
) {
    fun toJson(): String = JSONObject()
        .put("id_token", idToken).put("access_token", accessToken).put("refresh_token", refreshToken)
        .put("account_id", accountId).put("email", email).put("plan", plan).put("expires_at", expiresAtMs)
        .toString()

    /** Never prints the tokens themselves. */
    override fun toString(): String = "ChatGptTokens(account=$accountId, email=$email, plan=$plan, expiresAt=$expiresAtMs)"

    fun expiresWithin(ms: Long, now: Long): Boolean = expiresAtMs > 0 && expiresAtMs - now < ms

    companion object {
        fun fromJson(text: String?): ChatGptTokens? {
            if (text.isNullOrBlank()) return null
            val j = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val access = j.optString("access_token")
            if (access.isBlank()) return null
            return ChatGptTokens(
                j.optString("id_token"), access, j.optString("refresh_token"), j.optString("account_id"),
                j.optString("email"), j.optString("plan"), j.optLong("expires_at"),
            )
        }
    }
}

/** Where a sign-in lives between app runs. */
interface ChatGptTokenStore {
    fun load(): ChatGptTokens?
    fun save(tokens: ChatGptTokens?)
}

/** PKCE pair (RFC 7636, S256). */
data class Pkce(val verifier: String, val challenge: String) {
    companion object {
        private val random = SecureRandom()
        private val b64 = Base64.getUrlEncoder().withoutPadding()

        fun create(): Pkce {
            val bytes = ByteArray(64).also { random.nextBytes(it) }
            val verifier = b64.encodeToString(bytes)
            return Pkce(verifier, challengeOf(verifier))
        }

        fun challengeOf(verifier: String): String =
            b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        fun state(): String = b64.encodeToString(ByteArray(32).also { random.nextBytes(it) })
    }
}

/** A pending code sign-in: show [userCode], send the user to [verificationUrl], poll every [intervalSec]. */
data class ChatGptDeviceCode(
    val deviceAuthId: String,
    val userCode: String,
    val intervalSec: Long,
    val verificationUrl: String,
)

/**
 * "Sign in with ChatGPT": the public OAuth flow of OpenAI's open-source Codex CLI, so a ChatGPT
 * subscription can pay for the agent instead of an API key.
 *
 * Two ways in:
 * - **Browser** (authorization code + PKCE): [authorizeUrl] opens in the phone's browser, which comes back to
 *   [LOOPBACK_REDIRECT] on this phone ([LoopbackCallback]); [exchangeCode] turns the code into tokens.
 * - **Code** (device authorization): [startDeviceCode] gives a short code the user enters at [ChatGptDeviceCode.verificationUrl]
 *   on any device; [pollDeviceCode] waits for the approval. Needs device-code sign-in allowed in the ChatGPT
 *   account's security settings.
 */
class ChatGptAuth(
    private val http: OkHttpClient = LlmHttp.sharedClient,
    val issuer: String = ISSUER,
    val clientId: String = CLIENT_ID,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun authorizeUrl(pkce: Pkce, state: String, redirectUri: String = LOOPBACK_REDIRECT): String =
        "$issuer/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", "openid profile email offline_access")
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("id_token_add_organizations", "true")
            .addQueryParameter("codex_cli_simplified_flow", "true")
            .addQueryParameter("state", state)
            .addQueryParameter("originator", ORIGINATOR)
            .build().toString()

    suspend fun exchangeCode(code: String, verifier: String, redirectUri: String = LOOPBACK_REDIRECT): ChatGptTokens {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        val j = call(Request.Builder().url("$issuer/oauth/token").post(form).build(), "Sign-in")
        return tokensFrom(j, previous = null)
    }

    /** New access token; keeps the old refresh/id token when the reply leaves them out. */
    suspend fun refresh(tokens: ChatGptTokens): ChatGptTokens {
        if (tokens.refreshToken.isBlank()) throw LlmException(LlmErrorKind.AUTH, "ChatGPT sign-in expired. Sign in again in Settings.")
        val body = JSONObject().put("client_id", clientId).put("grant_type", "refresh_token")
            .put("refresh_token", tokens.refreshToken).put("scope", "openid profile email")
        val j = call(Request.Builder().url("$issuer/oauth/token").post(body.toString().toRequestBody(JSON)).build(), "Refreshing the ChatGPT sign-in")
        return tokensFrom(j, previous = tokens)
    }

    suspend fun startDeviceCode(): ChatGptDeviceCode {
        val body = JSONObject().put("client_id", clientId)
        val j = call(Request.Builder().url("$issuer/api/accounts/deviceauth/usercode").post(body.toString().toRequestBody(JSON)).build(),
            "Requesting a sign-in code", deviceHint = true)
        val id = j.optString("device_auth_id")
        val code = j.optString("user_code").ifBlank { j.optString("usercode") }
        if (id.isBlank() || code.isBlank()) throw LlmException(LlmErrorKind.INVALID_RESPONSE, "No sign-in code in the reply")
        val interval = j.opt("interval")?.toString()?.trim()?.toLongOrNull()?.coerceIn(1, 30) ?: 5
        return ChatGptDeviceCode(id, code, interval, "$issuer/codex/device")
    }

    /** Null while the user has not approved yet; tokens once they have. */
    suspend fun pollDeviceCode(device: ChatGptDeviceCode): ChatGptTokens? = pollDeviceCode(device, awaitRetry = null)

    internal suspend fun pollDeviceCode(
        device: ChatGptDeviceCode,
        awaitRetry: (suspend (LlmException) -> Unit)?,
    ): ChatGptTokens? {
        val body = JSONObject().put("device_auth_id", device.deviceAuthId).put("user_code", device.userCode)
        val req = Request.Builder().url("$issuer/api/accounts/deviceauth/token").post(body.toString().toRequestBody(JSON)).build()
        val resp = withNetworkRecovery(awaitRetry) { LlmHttp.execute(http, req, MAX_AUTH_RESPONSE) }
        if (resp.code == 403 || resp.code == 404) return null
        if (resp.code !in 200..299) throw authError(resp.code, resp.body, "Waiting for the code approval")
        val j = runCatching { JSONObject(resp.body) }.getOrElse { throw LlmException(LlmErrorKind.INVALID_RESPONSE, "Unreadable sign-in reply") }
        val authCode = j.optString("authorization_code")
        val verifier = j.optString("code_verifier")
        if (authCode.isBlank() || verifier.isBlank()) throw LlmException(LlmErrorKind.INVALID_RESPONSE, "Incomplete sign-in reply")
        return withNetworkRecovery(awaitRetry) { exchangeCode(authCode, verifier, "$issuer/deviceauth/callback") }
    }

    private suspend fun <T> withNetworkRecovery(
        awaitRetry: (suspend (LlmException) -> Unit)?,
        request: suspend () -> T,
    ): T {
        while (true) {
            try {
                return request()
            } catch (e: LlmException) {
                if (awaitRetry == null || (e.kind != LlmErrorKind.NETWORK && e.kind != LlmErrorKind.TIMEOUT)) throw e
                awaitRetry(e)
            }
        }
    }

    private suspend fun call(request: Request, what: String, deviceHint: Boolean = false): JSONObject {
        val resp = LlmHttp.execute(http, request, MAX_AUTH_RESPONSE)
        if (resp.code !in 200..299) throw authError(resp.code, resp.body, what, deviceHint)
        return runCatching { JSONObject(resp.body) }.getOrElse { throw LlmException(LlmErrorKind.INVALID_RESPONSE, "$what: unreadable reply") }
    }

    private fun authError(code: Int, body: String, what: String, deviceHint: Boolean = false): LlmException {
        val msg = LlmHttp.errorMessage(body)
        val hint = if (deviceHint && (code == 403 || code == 404)) " Allow device code sign-in in ChatGPT → Settings → Security." else ""
        val kind = if (code == 400 || code == 401 || code == 403) LlmErrorKind.AUTH else LlmHttp.mapHttpError(code, body).kind
        return LlmException(kind, "$what failed (HTTP $code: $msg).$hint", code)
    }

    private fun tokensFrom(j: JSONObject, previous: ChatGptTokens?): ChatGptTokens {
        val access = j.optString("access_token").ifBlank { throw LlmException(LlmErrorKind.INVALID_RESPONSE, "No access token in the sign-in reply") }
        val id = j.optString("id_token").ifBlank { previous?.idToken.orEmpty() }
        val refresh = j.optString("refresh_token").ifBlank { previous?.refreshToken.orEmpty() }
        val idClaims = jwtClaims(id)
        val accessClaims = jwtClaims(access)
        val auth = idClaims?.optJSONObject(AUTH_CLAIM) ?: accessClaims?.optJSONObject(AUTH_CLAIM)
        val account = auth?.optString("chatgpt_account_id").orEmpty().ifBlank { previous?.accountId.orEmpty() }
        val email = idClaims?.optString("email").orEmpty().ifBlank {
            accessClaims?.optJSONObject("https://api.openai.com/profile")?.optString("email").orEmpty()
        }.ifBlank { previous?.email.orEmpty() }
        val plan = auth?.optString("chatgpt_plan_type").orEmpty().ifBlank { previous?.plan.orEmpty() }
        val exp = accessClaims?.optLong("exp")?.takeIf { it > 0 }?.times(1000)
            ?: j.optLong("expires_in").takeIf { it > 0 }?.let { clock() + it * 1000 }
            ?: 0
        return ChatGptTokens(id, access, refresh, account, email, plan, exp)
    }

    companion object {
        const val ISSUER = "https://auth.openai.com"
        /** The public client id of OpenAI's Codex CLI (its sign-in is registered for this loopback address). */
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val LOOPBACK_PORT = 1455
        const val LOOPBACK_REDIRECT = "http://localhost:$LOOPBACK_PORT/auth/callback"
        const val ORIGINATOR = "codex_cli_rs"
        private const val AUTH_CLAIM = "https://api.openai.com/auth"
        private const val MAX_AUTH_RESPONSE = 1L * 1024 * 1024
        private val JSON = "application/json".toMediaType()

        /** Payload of a JWT without checking its signature (only read for display and routing; the server checks it). */
        fun jwtClaims(token: String?): JSONObject? {
            val parts = token?.split('.') ?: return null
            if (parts.size < 2) return null
            return runCatching { JSONObject(String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8)) }.getOrNull()
        }

        /** Code and state from a pasted or received redirect address; null when it is not a callback. */
        fun parseCallback(url: String): Pair<String, String>? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val q = query(uri.rawQuery)
            val code = q["code"] ?: return null
            return code to q["state"].orEmpty()
        }

        private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        internal fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            decode(k) to decode(v)
        }
    }
}

/**
 * Keeps one sign-in fresh for model calls: refreshes the access token shortly before it expires (or when the
 * server rejects it) and saves the result.
 */
class ChatGptSession(
    private val store: ChatGptTokenStore,
    private val auth: ChatGptAuth = ChatGptAuth(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun tokens(forceRefresh: Boolean = false): ChatGptTokens = mutex.withLock {
        val t = store.load() ?: throw LlmException(LlmErrorKind.AUTH, "Not signed in to ChatGPT. Sign in in Settings → AI provider.")
        if (!forceRefresh && !t.expiresWithin(REFRESH_MARGIN_MS, clock())) return@withLock t
        val fresh = try {
            auth.refresh(t)
        } catch (e: LlmException) {
            if (e.kind == LlmErrorKind.AUTH) throw LlmException(LlmErrorKind.AUTH, "ChatGPT sign-in expired. Sign in again in Settings → AI provider.", e.httpCode)
            // An early refresh that could not reach the server: the current token still works until it expires.
            if (!forceRefresh && !t.expiresWithin(0, clock())) return@withLock t
            throw e
        }
        // Signed out or signed in again (another account) while refreshing: keep what the user chose.
        val now = store.load() ?: throw LlmException(LlmErrorKind.AUTH, "Not signed in to ChatGPT. Sign in in Settings → AI provider.")
        if (now.refreshToken != t.refreshToken || now.accountId != t.accountId) return@withLock now
        store.save(fresh)
        fresh
    }

    companion object {
        const val REFRESH_MARGIN_MS = 5 * 60_000L
    }
}

/**
 * The one-shot local web server the browser returns to after sign-in ([ChatGptAuth.LOOPBACK_REDIRECT]).
 * Listens on the loopback interface only, accepts the callback whose `state` matches, answers with a short page,
 * and stops. Cancelling the coroutine closes the socket.
 */
class LoopbackCallback(private val port: Int = ChatGptAuth.LOOPBACK_PORT) {

    /** Opens the port, so a busy port fails before the browser is opened. */
    fun bind(): ServerSocket = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
        }
    } catch (e: IOException) {
        throw LlmException(LlmErrorKind.CONFIG, "Port $port on this phone is in use (another app signing in?). Close it or sign in with a code.", cause = e)
    }

    /** Waits for the browser; returns the authorization code. */
    suspend fun await(server: ServerSocket, expectedState: String): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { server.close() } }
            try {
                while (cont.isActive) {
                    val socket = server.accept()
                    // One broken or idle connection (preconnect, another app) must not end the sign-in.
                    try { socket.use { s ->
                        s.soTimeout = 10_000
                        val line = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine().orEmpty()
                        val target = line.split(' ').getOrNull(1).orEmpty()
                        val path = target.substringBefore('?')
                        val q = ChatGptAuth.query(target.substringAfter('?', ""))
                        val out = s.getOutputStream()
                        when {
                            path != "/auth/callback" -> out.write(page(404, "Not found", "").toByteArray())
                            q["error"] != null -> {
                                out.write(page(400, "Sign-in cancelled", "You can close this tab and return to Backstage.").toByteArray())
                                out.flush()
                                if (cont.isActive) cont.resumeWith(Result.failure(LlmException(LlmErrorKind.AUTH,
                                    "Sign-in was not completed: ${q["error_description"] ?: q["error"]}")))
                                return@use
                            }
                            q["state"] != expectedState || q["code"].isNullOrBlank() ->
                                out.write(page(400, "Sign-in link does not match", "Start the sign-in again from Backstage.").toByteArray())
                            else -> {
                                out.write(page(200, "Signed in", "ChatGPT is connected. Close this tab and return to Backstage.").toByteArray())
                                out.flush()
                                if (cont.isActive) cont.resumeWith(Result.success(q["code"]!!))
                            }
                        }
                    } } catch (e: IOException) {
                        if (server.isClosed) throw e
                    }
                }
            } catch (e: SocketException) {
                if (cont.isActive) cont.resumeWith(Result.failure(LlmException(LlmErrorKind.NETWORK, "Sign-in listener stopped", cause = e)))
            } catch (e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(LlmHttp.mapIoError(e)))
            } finally {
                runCatching { server.close() }
            }
        }
    }

    private fun page(code: Int, title: String, text: String): String {
        val status = when (code) { 200 -> "200 OK"; 404 -> "404 Not Found"; else -> "400 Bad Request" }
        val html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
            "<title>Backstage</title></head><body style=\"font-family:sans-serif;padding:32px\"><h2>$title</h2><p>$text</p></body></html>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        return "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$html"
    }
}
