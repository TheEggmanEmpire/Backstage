package com.eggman.backstage.omp.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException

/** Only a random, expiring claim crosses argv. Broker tokens never enter this protocol. */
class OmpBridgeClaim(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    val code: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    companion object { /** Long enough for a cold bun + omp start under proot on a phone. */ const val TTL_MS = 90_000L }
    private val deadline = clock() + TTL_MS
    private var used = false
    internal val remainingMs: Long get() = (deadline - clock()).coerceAtLeast(0)
    val expired: Boolean get() = remainingMs == 0L
    @Synchronized fun consume(candidate: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val matches = MessageDigest.isEqual(digest.digest(code.toByteArray(Charsets.UTF_8)), digest.digest(candidate.toByteArray(Charsets.UTF_8)))
        if (!matches || used || expired) return false
        used = true
        return true
    }
}

/** One listener/claim per process launch. It stops listening immediately after the claim succeeds. */
class OmpRpcBridgeServer(val claim: OmpBridgeClaim = OmpBridgeClaim()) : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 250 }
    private val handshaking = AtomicReference<Socket?>()
    val port: Int get() = server.localPort

    suspend fun awaitConnection(): Socket = suspendCancellableCoroutine { continuation ->
        // Socket reads do not react to coroutine cancellation. Close both blocking operations directly.
        continuation.invokeOnCancellation { close() }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (continuation.isActive && !claim.expired) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    handshaking.set(socket)
                    var accepted = false
                    try {
                        if (!continuation.isActive) return@launch
                        val frame = readClaim(socket)
                        if (frame?.optString("type") == "claim" && claim.consume(frame.optString("code"))) {
                            socket.getOutputStream().apply { write("{\"type\":\"claimed\"}\n".toByteArray()); flush() }
                            socket.soTimeout = 0; socket.tcpNoDelay = true
                            server.close()
                            handshaking.compareAndSet(socket, null)
                            continuation.resume(socket) { _, connection, _ -> connection.close() }
                            accepted = true
                            return@launch
                        }
                    } catch (_: java.io.IOException) { /* An unauthenticated peer gets no protocol details. */ }
                    finally {
                        handshaking.compareAndSet(socket, null)
                        if (!accepted) socket.close()
                    }
                }
                if (continuation.isActive) throw OmpProtocolException("OMP bridge claim expired")
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }
    }

    private fun readClaim(socket: Socket): org.json.JSONObject? {
        val deadline = System.nanoTime() / 1_000_000 + 2000
        val bytes = ByteArrayOutputStream()
        val input = socket.getInputStream()
        // Every byte, including blank lines, consumes the connection's total handshake budget.
        repeat(256) {
            val remaining = minOf(deadline - System.nanoTime() / 1_000_000, claim.remainingMs)
            if (remaining <= 0) throw SocketTimeoutException("OMP claim handshake expired")
            socket.soTimeout = minOf(500, remaining).toInt().coerceAtLeast(1)
            val b = input.read()
            if (System.nanoTime() / 1_000_000 >= deadline || claim.expired)
                throw SocketTimeoutException("OMP claim handshake expired")
            if (b == -1) return null
            if (b == 10) {
                if (bytes.size() > 0) return try { OmpJsonl.parse(bytes.toByteArray()) }
                    catch (_: Exception) { null }
            } else bytes.write(b)
        }
        throw OmpProtocolException("OMP claim exceeds 256 bytes")
    }

    override fun close() {
        try { server.close() } finally { handshaking.getAndSet(null)?.close() }
    }
}
