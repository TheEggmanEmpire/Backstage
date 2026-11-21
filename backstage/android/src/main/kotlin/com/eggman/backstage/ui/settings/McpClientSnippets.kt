package com.eggman.backstage.ui.settings

import org.json.JSONArray
import org.json.JSONObject

/** Ready-to-paste MCP client configurations (our own wording; JSON built with org.json). */
object McpClientSnippets {

    data class Snippet(val id: String, val title: String, val where: String, val body: String)

    fun endpoint(host: String, port: Int) = "http://${hostLiteral(host)}:$port/mcp"

    private fun hostLiteral(host: String) = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    fun all(host: String, port: Int, token: String, name: String = "backstage"): List<Snippet> {
        val url = endpoint(host, port)
        val local = host == "127.0.0.1" || host == "localhost"
        return listOf(
            Snippet(
                "claude-code", "Claude Code", "Run in a terminal on your computer",
                "claude mcp add --transport http $name $url --header \"Authorization: Bearer $token\"",
            ),
            Snippet(
                "claude-desktop", "Claude Desktop",
                "Merge into claude_desktop_config.json (needs Node.js; bridges HTTP through mcp-remote)",
                JSONObject().put(
                    "mcpServers",
                    JSONObject().put(
                        name,
                        JSONObject()
                            .put("command", "npx")
                            .put("args", JSONArray().apply {
                                put("-y"); put("mcp-remote"); put(url)
                                if (!local) put("--allow-http")
                                put("--header"); put("Authorization:\${BACKSTAGE_AUTH}")
                            })
                            .put("env", JSONObject().put("BACKSTAGE_AUTH", "Bearer $token")),
                    ),
                ).toString(2).unescapeSlashes(),
            ),
            Snippet(
                "cursor", "Cursor", "Merge into ~/.cursor/mcp.json (or .cursor/mcp.json in a project)",
                JSONObject().put(
                    "mcpServers",
                    JSONObject().put(
                        name,
                        JSONObject().put("url", url).put("headers", JSONObject().put("Authorization", "Bearer $token")),
                    ),
                ).toString(2).unescapeSlashes(),
            ),
            Snippet(
                // initialize is valid as the first request on every server (tools/list may need a session first).
                "curl", "Connectivity check", "Should print a JSON-RPC result naming the server",
                "curl -s -X POST $url -H \"Authorization: Bearer $token\" -H \"Content-Type: application/json\" " +
                    "-H \"Accept: application/json, text/event-stream\" " +
                    "-d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"," +
                    "\"capabilities\":{},\"clientInfo\":{\"name\":\"curl\",\"version\":\"1\"}}}'",
            ),
        )
    }

    /** org.json escapes "/" as "\\/": valid JSON, but it looks broken in a config file. */
    private fun String.unescapeSlashes() = replace("\\/", "/")

    /** USB fallback when LAN binding is off: forward the phone's localhost port to the computer. */
    fun adbForward(port: Int) = "adb forward tcp:$port tcp:$port"
}
