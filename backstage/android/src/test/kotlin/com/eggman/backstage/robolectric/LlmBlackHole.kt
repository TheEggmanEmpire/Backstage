package com.eggman.backstage.robolectric

import com.eggman.backstage.data.Provider
import com.eggman.backstage.data.Settings
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * An "LLM endpoint" on loopback that accepts the request and never answers: a task that reaches its first model
 * call stays RUNNING until it is cancelled. [requests] counts accepted connections (proof the task got that far).
 */
class LlmBlackHole : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val held = CopyOnWriteArrayList<Socket>()
    val requests: Int get() = held.size

    init {
        thread(isDaemon = true, name = "llm-black-hole") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                held += s
            }
        }
    }

    /** Points the agent's provider (Ollama: no key needed) at this endpoint. */
    fun install(settings: Settings) {
        settings.provider = Provider.OLLAMA
        settings.setBaseUrl(Provider.OLLAMA, "http://127.0.0.1:${server.localPort}")
        settings.setModel(Provider.OLLAMA, "test-model")
        // Nothing to switch on through Shizuku in a unit test.
        settings.autoEnableAccessibility = false
    }

    override fun close() {
        runCatching { server.close() }
        held.forEach { runCatching { it.close() } }
    }
}
