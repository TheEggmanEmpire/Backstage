package com.eggman.backstage.agent

import kotlinx.coroutines.sync.Mutex

/**
 * One owner of the device executors at a time: an agent task, or a direct MCP tool call. An agent task waits
 * for a direct call in progress to finish; a direct call made while an agent task (or another direct call) holds the
 * lease is refused instead of queueing behind it, so it can never act in the middle of a task.
 */
object ExecutionLease {
    private val mutex = Mutex()

    /** Runs [block] as the agent's task; waits until a direct call in progress has finished. */
    suspend fun <T> agent(block: suspend () -> T): T {
        mutex.lock()
        try { return block() } finally { mutex.unlock() }
    }

    /** Runs [block] as a direct call, or returns null at once when the lease is held. */
    suspend fun <T> direct(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        try { return block() } finally { mutex.unlock() }
    }

    val held: Boolean get() = mutex.isLocked
}
