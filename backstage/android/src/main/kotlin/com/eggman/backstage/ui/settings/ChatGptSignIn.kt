package com.eggman.backstage.ui.settings

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.eggman.backstage.llm.ChatGptAuth
import com.eggman.backstage.llm.ChatGptDeviceCode
import com.eggman.backstage.llm.ChatGptTokens
import com.eggman.backstage.llm.LlmErrorKind
import com.eggman.backstage.llm.LlmException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.UnknownHostException

/** Polls for approval in process scope while the user signs in in the browser. */
internal class ChatGptSignIn(
    private val save: (ChatGptTokens?) -> Unit,
    private val connectivityRestored: Flow<Unit>,
    private val auth: ChatGptAuth = ChatGptAuth(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    sealed interface State {
        data object Idle : State
        data object Requesting : State
        data class Code(val device: ChatGptDeviceCode, val networkError: String? = null) : State
        data class Failed(val message: String, val canRetry: Boolean) : State
        data object Done : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val retryVersion = MutableStateFlow(0L)
    private var job: Job? = null

    fun startCode() {
        cancel()
        _state.value = State.Requesting
        job = scope.launch {
            try {
                val device = auth.startDeviceCode()
                val tokens = withTimeoutOrNull(CODE_LIFETIME_MS) { waitForApproval(device) }
                ensureActive()
                if (tokens == null) {
                    _state.value = State.Failed("The code expired before it was approved. Get a new one.", canRetry = true)
                } else {
                    save(tokens)
                    _state.value = State.Done
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                val networkError = e.networkMessage()
                _state.value = State.Failed(networkError ?: e.message.orEmpty(), canRetry = networkError != null)
            } catch (e: Exception) {
                _state.value = State.Failed("ChatGPT sign-in failed. Get a new code.", canRetry = true)
            }
        }
    }

    private suspend fun CoroutineScope.waitForApproval(device: ChatGptDeviceCode): ChatGptTokens {
        val waiting = State.Code(device)
        val intervalMs = device.intervalSec * 1000
        val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
            connectivityRestored.collect { retryVersion.update { it + 1 } }
        }
        try {
            _state.value = waiting
            while (true) {
                delay(intervalMs)
                var attemptVersion = retryVersion.value
                val tokens = auth.pollDeviceCode(device) { error ->
                    ensureActive()
                    _state.value = waiting.copy(networkError = error.networkMessage())
                    attemptVersion = retryVersion.first { it != attemptVersion }
                    _state.value = waiting
                    delay(intervalMs)
                }
                if (tokens != null) return tokens
            }
        } finally {
            monitor.cancel()
        }
    }

    fun retry() {
        when (val current = _state.value) {
            is State.Code -> if (current.networkError != null) retryVersion.update { it + 1 }
            is State.Failed -> if (current.canRetry) startCode()
            else -> Unit
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    fun signOut() {
        cancel()
        save(null)
    }

    private fun LlmException.networkMessage(): String? = when {
        cause is UnknownHostException -> "Backstage could not resolve auth.openai.com. Check your connection or DNS settings and retry."
        kind == LlmErrorKind.NETWORK -> "Backstage could not connect to ChatGPT. Check your internet connection and retry."
        kind == LlmErrorKind.TIMEOUT -> "ChatGPT sign-in timed out. Check your internet connection and retry."
        else -> null
    }

    companion object {
        private const val CODE_LIFETIME_MS = 15 * 60_000L
    }
}

/** Emits when the app's default network becomes usable, including after Android lifts a UID network block. */
internal fun ConnectivityManager.signInConnectivityRestored(): Flow<Unit> = callbackFlow {
    fun NetworkCapabilities?.internetValidated(): Boolean =
        this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    val initialNetwork = activeNetwork
    val initiallyValidated = initialNetwork?.let(::getNetworkCapabilities).internetValidated()
    val callback = object : ConnectivityManager.NetworkCallback() {
        private var current = initialNetwork
        private var validated = initiallyValidated
        private var blocked = false
        private var usable = initialNetwork.takeIf { initiallyValidated }

        private fun update() {
            val next = current.takeIf { validated && !blocked }
            if (next != null && next != usable) trySend(Unit)
            usable = next
        }

        override fun onAvailable(network: Network) {
            if (current == network) return
            current = network
            validated = false
            blocked = false
            update()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network != current) return
            validated = capabilities.internetValidated()
            update()
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (network != current) return
            this.blocked = blocked
            update()
        }

        override fun onLost(network: Network) {
            if (network != current) return
            current = null
            update()
        }
    }
    registerDefaultNetworkCallback(callback)
    awaitClose { unregisterNetworkCallback(callback) }
}
