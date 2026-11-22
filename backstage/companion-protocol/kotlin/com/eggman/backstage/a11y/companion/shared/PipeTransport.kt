package com.eggman.backstage.a11y.companion

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pipe payloads never occupy Binder's shared transaction buffer. Both ends use nonblocking IO plus poll deadlines:
 * merely cancelling a coroutine or closing an fd on another thread does not reliably interrupt a blocked read/write.
 */
object PipeTransport {
    const val TIMEOUT_MS = 10_000L
    const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

    fun produce(scope: CoroutineScope, maxBytes: Int, finished: () -> Unit = {}, block: suspend (OutputStream) -> Unit): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val cleaned = AtomicBoolean()
        fun cleanup() {
            if (cleaned.compareAndSet(false, true)) { runCatching { pipe[1].close() }; finished() }
        }
        val writer = scope.launch(Dispatchers.IO) {
            try {
                val output = LimitedOutput(PollOutput(pipe[1]), maxBytes)
                block(output)
            } catch (_: Exception) {
                // No screen text or exception payload enters logs or the error pipe.
                runCatching { pipe[1].closeWithError("Backstage Agent payload unavailable") }
            } finally { cleanup() }
        }
        // Cancellation before the coroutine starts would otherwise bypass its finally block and leak the fd.
        writer.invokeOnCompletion { cleanup() }
        return pipe[0]
    }

    fun <T> consume(fd: ParcelFileDescriptor, block: (InputStream) -> T): T = fd.use {
        val result = block(PollInput(fd))
        fd.checkError()
        result
    }

    fun imageBytes(fd: ParcelFileDescriptor): ByteArray = consume(fd) { input ->
        val out = ByteArrayOutputStream()
        LimitedInput(input, MAX_IMAGE_BYTES).copyTo(out)
        out.toByteArray()
    }

    private abstract class Polled(private val pfd: ParcelFileDescriptor, private val events: Int) {
        private val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        init {
            val flags = Os.fcntlInt(pfd.fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(pfd.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }
        fun ready() {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0 || Thread.currentThread().isInterrupted) throw IOException("Backstage Agent pipe timed out")
            val poll = StructPollfd().apply { fd = pfd.fileDescriptor; this.events = events.toShort() }
            if (Os.poll(arrayOf(poll), remaining.toInt()) <= 0) throw IOException("Backstage Agent pipe timed out")
        }
    }
    private class PollInput(private val pfd: ParcelFileDescriptor) : InputStream() {
        private val poll = object : Polled(pfd, OsConstants.POLLIN) {}
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) == -1) -1 else it[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                poll.ready()
                try { return Os.read(pfd.fileDescriptor, b, off, len).let { if (it == 0) -1 else it } }
                catch (e: ErrnoException) { if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) throw IOException(e) }
            }
        }
    }
    private class PollOutput(private val pfd: ParcelFileDescriptor) : OutputStream() {
        private val poll = object : Polled(pfd, OsConstants.POLLOUT) {}
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var written = 0
            while (written < len) {
                poll.ready()
                try { written += Os.write(pfd.fileDescriptor, b, off + written, minOf(8192, len - written)) }
                catch (e: ErrnoException) { if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) throw IOException(e) }
            }
        }
    }
}
