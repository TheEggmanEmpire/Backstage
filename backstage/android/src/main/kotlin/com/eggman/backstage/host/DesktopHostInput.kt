package com.eggman.backstage.host

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** An injection began but its result is not observed; callers must not retry it as a failure. */
class DesktopInputOutcomeUnknown(message: String) : IllegalStateException(message)

/** Deadline arbitration for the desktop's single ordered input lane. */
object DesktopHostInput {
    fun run(executor: ExecutorService, timeoutMs: Long = 2_000, block: () -> Boolean): Boolean {
        val gate = Any()
        var abandoned = false
        var started = false
        val future = executor.submit<Boolean> {
            synchronized(gate) {
                if (abandoned) return@submit false
                started = true
            }
            block()
        }
        fun abandon(): Boolean {
            val running = synchronized(gate) { abandoned = true; started }
            // Never interrupt an injection mid-chord. A queued operation must never start later.
            future.cancel(false)
            if (running) throw DesktopInputOutcomeUnknown("Desktop input started but its result is uncertain; inspect the desktop before retrying")
            return false
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            abandon()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            abandon()
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }
}
