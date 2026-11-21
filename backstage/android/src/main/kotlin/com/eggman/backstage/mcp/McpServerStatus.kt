package com.eggman.backstage.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the MCP server is doing: published by the agent service, shown by the settings page. */
data class McpServerState(
    val running: Boolean = false,
    /** Port the server listens on (running) or tried to bind (error). */
    val port: Int? = null,
    val lan: Boolean = false,
    /** Why the server is not running although it is enabled (bind failure), else null. */
    val error: String? = null,
)

object McpServerStatus {
    private val _state = MutableStateFlow(McpServerState())
    val state: StateFlow<McpServerState> = _state

    internal fun publish(s: McpServerState) { _state.value = s }

    /** Readable reason for a failed start; a port someone else listens on gets an actionable message. */
    fun startError(port: Int, error: Throwable): String =
        if (error is java.net.BindException) {
            "MCP server could not start: something else (another app, or a second copy of Backstage) listens on port $port. " +
                "Pick a different port under Settings → MCP server, or tap Retry once that port is free."
        } else {
            "MCP server could not start on port $port: ${error.message ?: error.javaClass.simpleName}"
        }
}
