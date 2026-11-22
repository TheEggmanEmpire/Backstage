package com.eggman.backstage.a11y.companion

import java.security.SecureRandom

/**
 * Owns live nodes for one authenticated client. Generation/index handles cannot survive clear or recapture.
 * Only registry bookkeeping holds the lock: accessibility IPC runs outside it. Retired nodes stay alive until
 * their last in-flight action finishes, so revoke can invalidate immediately without recycling an active node.
 */
class NodeHandles<T>(seed: Long = SecureRandom().nextLong().ushr(18), private val release: (T) -> Unit = {}) {
    private class Entry<T>(val node: T, var users: Int = 0, var retired: Boolean = false)
    private var generation = seed.coerceIn(1, (1L shl 46) - 1)
    private val nodes = LinkedHashMap<Long, Entry<T>>()

    private fun retireLocked(): List<T> {
        check(generation < (1L shl 47) - 1) { "Accessibility capture generation exhausted" }
        generation++
        val idle = nodes.values.mapNotNull { entry ->
            entry.retired = true
            entry.node.takeIf { entry.users == 0 }
        }
        nodes.clear()
        return idle
    }

    private fun dispose(nodes: List<T>) { nodes.forEach { runCatching { release(it) } } }

    fun <R> replace(block: ((T) -> Long) -> R): R {
        val (epoch, idle) = synchronized(this) { val idle = retireLocked(); generation to idle }
        dispose(idle)
        val captured = LinkedHashMap<Long, Entry<T>>()
        var published = false
        try {
            val result = block { node ->
                check(captured.size < 65535) { "Accessibility node budget exceeded" }
                val id = (epoch shl 16) or (captured.size + 1).toLong()
                captured[id] = Entry(node)
                id
            }
            synchronized(this) {
                check(generation == epoch) { "Accessibility capture invalidated" }
                nodes.putAll(captured)
                published = true
            }
            return result
        } finally {
            if (!published) dispose(captured.values.map { it.node })
        }
    }

    fun <R> withNode(id: Long, action: (T) -> R): R? {
        val entry = synchronized(this) {
            if (id ushr 16 != generation) return null
            nodes[id]?.also { it.users++ }
        } ?: return null
        return try { action(entry.node) } finally {
            val retired = synchronized(this) { entry.users--; entry.retired && entry.users == 0 }
            if (retired) dispose(listOf(entry.node))
        }
    }

    fun clear() {
        val idle = synchronized(this) { retireLocked() }
        dispose(idle)
    }
}
