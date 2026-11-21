package com.eggman.backstage.ui.settings

import com.eggman.backstage.llm.ChatGptAuth
import com.eggman.backstage.llm.ChatGptDeviceCode
import com.eggman.backstage.llm.ChatGptTokens
import com.eggman.backstage.llm.LlmException
import com.eggman.backstage.llm.LoopbackCallback
import com.eggman.backstage.llm.Pkce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Sign in with ChatGPT" for Settings. Lives for the whole process (not the screen), because the browser
 * sign-in sends the user out of the app and back.
 *
 * [save] stores the tokens (Settings, encrypted); [openUrl] opens an address in the phone's browser.
 */
class ChatGptSignIn(
    private val save: (ChatGptTokens?) -> Unit,
    private val auth: ChatGptAuth = ChatGptAuth(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    sealed interface State {
        data object Idle : State
        /** Waiting for the browser to come back; [expectedState] guards a pasted address. */
        data class Browser(val url: String, internal val pkce: Pkce, internal val expectedState: String) : State
        data class Code(val device: ChatGptDeviceCode) : State
        data object Finishing : State
        data class Failed(val message: String) : State
        data class Done(val tokens: ChatGptTokens) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun startBrowser(openUrl: (String) -> Unit) = launchFlow {
        val pkce = Pkce.create()
        val st = Pkce.state()
        val url = auth.authorizeUrl(pkce, st)
        val listener = LoopbackCallback()
        val server = withContext(Dispatchers.IO) { listener.bind() }
        val code = try {
            _state.value = State.Browser(url, pkce, st)
            openUrl(url)
            withTimeoutOrNull(BROWSER_LIFETIME_MS) { listener.await(server, st) }
        } finally {
            runCatching { server.close() }
        }
        if (code == null) {
            _state.value = State.Failed("The browser sign-in timed out. Start it again.")
            return@launchFlow
        }
        finish { auth.exchangeCode(code, pkce.verifier) }
    }

    /** Fallback when the browser could not reach this phone's sign-in listener: the address it ended on. */
    fun submitRedirect(address: String) {
        val b = _state.value as? State.Browser ?: return
        val (code, st) = ChatGptAuth.parseCallback(address) ?: run {
            _state.value = State.Failed("That is not the sign-in address. It starts with ${ChatGptAuth.LOOPBACK_REDIRECT}?code=…")
            job?.cancel()
            return
        }
        if (st != b.expectedState) {
            job?.cancel()
            _state.value = State.Failed("That address belongs to another sign-in attempt. Start again.")
            return
        }
        launchFlow { finish { auth.exchangeCode(code, b.pkce.verifier) } }
    }

    fun startCode() = launchFlow {
        val device = auth.startDeviceCode()
        _state.value = State.Code(device)
        val deadline = System.currentTimeMillis() + CODE_LIFETIME_MS
        while (System.currentTimeMillis() < deadline) {
            delay(device.intervalSec * 1000)
            val tokens = auth.pollDeviceCode(device) ?: continue
            finish { tokens }
            return@launchFlow
        }
        _state.value = State.Failed("The code expired before it was approved. Get a new one.")
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    fun signOut() {
        cancel()
        save(null)
    }

    private suspend fun finish(block: suspend () -> ChatGptTokens) {
        _state.value = State.Finishing
        val tokens = block()
        save(tokens)
        _state.value = State.Done(tokens)
    }

    private fun launchFlow(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                _state.value = State.Failed(e.message ?: "Sign-in failed")
            } catch (e: Exception) {
                _state.value = State.Failed("Sign-in failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        const val CODE_LIFETIME_MS = 15 * 60_000L
        const val BROWSER_LIFETIME_MS = 10 * 60_000L
    }
}
