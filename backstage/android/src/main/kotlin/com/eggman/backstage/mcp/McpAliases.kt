package com.eggman.backstage.mcp

import com.eggman.backstage.tools.Schema
import org.json.JSONArray
import org.json.JSONObject

/**
 * Alternative tool names for MCP clients: each alias forwards to one of
 * Backstage's own low-level tools with its arguments translated. Names and argument keys are interoperability
 * identifiers; the descriptions are Backstage's own. Each alias is a tool of its own in tools/list and in the
 * per-tool switches, so a client can be given `tap_screen` without every other low-level tool.
 *
 * Not aliased: `task_completed` and `ask_user` end or steer an agent run; a direct MCP call has no run to end (use
 * execute_task for that). `get_screen_state`, `await_screen_change`, `type_text` and `launch_app` exist under the
 * same name already ([argumentCompat] accepts the other argument spelling).
 */
object McpAliases {
    class Alias(
        val name: String,
        val description: String,
        val schema: JSONObject,
        /** The Backstage tools this alias may call (all must be available for the alias to be listed). */
        val targets: Set<String>,
        val readOnly: Boolean = false,
        /** (target tool, its arguments) for the client's arguments. */
        val map: (JSONObject) -> Pair<String, JSONObject>,
    )

    private val reason = "reason" to Schema.str("Why this step (optional; shown in the phone's log)")
    private val target = "target" to Schema.enum("Where to act: desktop or phone (default: the running desktop, else the phone)", "desktop", "phone")

    private fun JSONObject.copyTarget(from: JSONObject): JSONObject = apply { if (from.has("target")) put("target", from.get("target")) }

    private fun num(a: JSONObject, key: String): Any = if (a.has(key)) a.get(key) else throw McpInvalidParams("Missing required parameter: $key")

    private fun key(name: String, key: String, what: String) = Alias(
        name, "Press the system $what button on the target screen.", Schema.obj(reason, target), setOf("press_key"),
    ) { a -> "press_key" to JSONObject().put("key", key).copyTarget(a) }

    val all: List<Alias> = listOf(
        Alias(
            "click_node", "Click the element with this nid (from get_screen_state's markup).",
            Schema.obj("nid" to Schema.str("Element id from the latest get_screen_state"), reason, "content" to Schema.str("Visible text of the element (optional)"),
                target, required = listOf("nid")),
            setOf("tap"),
        ) { a -> "tap" to JSONObject().put("nid", a.optString("nid").ifEmpty { throw McpInvalidParams("Missing required parameter: nid") }).copyTarget(a) },
        Alias(
            "long_click_node", "Long-press the element with this nid (from get_screen_state's markup).",
            Schema.obj("nid" to Schema.str("Element id from the latest get_screen_state"), reason, "content" to Schema.str("Visible text of the element (optional)"),
                target, required = listOf("nid")),
            setOf("long_press"),
        ) { a -> "long_press" to JSONObject().put("nid", a.optString("nid").ifEmpty { throw McpInvalidParams("Missing required parameter: nid") }).copyTarget(a) },
        Alias(
            "tap_screen", "Tap at a point in screen pixels (for things that have no element).",
            Schema.obj("x" to Schema.num("X in screen pixels"), "y" to Schema.num("Y in screen pixels"), reason,
                "content" to Schema.str("What is at that point (optional)"), target, required = listOf("x", "y")),
            setOf("tap"),
        ) { a -> "tap" to JSONObject().put("x", num(a, "x")).put("y", num(a, "y")).copyTarget(a) },
        Alias(
            "long_tap_screen", "Touch and hold at a point in screen pixels.",
            Schema.obj("x" to Schema.num("X in screen pixels"), "y" to Schema.num("Y in screen pixels"), "duration_ms" to Schema.int("Hold time (default 700)"),
                reason, "content" to Schema.str("What is at that point (optional)"), target, required = listOf("x", "y")),
            setOf("long_press"),
        ) { a ->
            "long_press" to JSONObject().put("x", num(a, "x")).put("y", num(a, "y"))
                .apply { if (a.has("duration_ms")) put("duration_ms", a.get("duration_ms")) }.copyTarget(a)
        },
        Alias(
            "swipe_screen", "Swipe from one point to another, in screen pixels.",
            Schema.obj("start_x" to Schema.num("Start x"), "start_y" to Schema.num("Start y"), "end_x" to Schema.num("End x"), "end_y" to Schema.num("End y"),
                "duration_ms" to Schema.int("Duration (default 300)"), reason, target, required = listOf("start_x", "start_y", "end_x", "end_y")),
            setOf("swipe"),
        ) { a ->
            "swipe" to JSONObject().put("x1", num(a, "start_x")).put("y1", num(a, "start_y")).put("x2", num(a, "end_x")).put("y2", num(a, "end_y"))
                .apply { if (a.has("duration_ms")) put("duration_ms", a.get("duration_ms")) }.copyTarget(a)
        },
        Alias(
            "submit_text", "Confirm the focused field (Enter), after typing text into it first when text is given.",
            Schema.obj("text" to Schema.str("Text to type before pressing Enter (optional)"),
                "secret" to Schema.bool("Required for passwords and one-time codes"), reason, target),
            setOf("type_text", "press_key"),
        ) { a ->
            val text = a.optString("text")
            if (text.isNotEmpty()) "type_text" to JSONObject().put("text", text).put("submit", true)
                .apply { if (a.has("secret")) put("secret", a.get("secret")) }.copyTarget(a)
            else "press_key" to JSONObject().put("key", "enter").copyTarget(a)
        },
        Alias(
            "find_app", "Find installed apps whose name or package contains one of the keywords.",
            Schema.obj("keywords" to Schema.arr("Words to look for", Schema.str("keyword")), reason, required = listOf("keywords")),
            setOf("list_apps"), readOnly = true,
        ) { a ->
            val k = a.optJSONArray("keywords") ?: a.optString("keywords").takeIf { it.isNotBlank() }?.let { JSONArray().put(it) }
                ?: throw McpInvalidParams("Missing required parameter: keywords")
            "list_apps" to JSONObject().put("keywords", k)
        },
        key("navigate_back", "back", "Back"),
        key("navigate_home", "home", "Home"),
        key("navigate_recents", "recents", "Recent apps"),
        Alias(
            "show_all_apps", "Open the list of all apps (app drawer) on the target screen.", Schema.obj(reason, target), setOf("press_key"),
        ) { a -> "press_key" to JSONObject().put("key", "all_apps").copyTarget(a) },
        Alias(
            "take_screenshot", "Capture the target screen as an image.",
            Schema.obj("quality" to Schema.int("Ignored: the phone's screenshot settings apply"), "scale" to Schema.num("Ignored: the phone's screenshot settings apply"),
                reason, target),
            setOf("screenshot"), readOnly = true,
        ) { a -> "screenshot" to JSONObject().copyTarget(a) },
        Alias(
            "capture_screenshot", "Capture the target screen as an image (same as take_screenshot).", Schema.obj(reason, target),
            setOf("screenshot"), readOnly = true,
        ) { a -> "screenshot" to JSONObject().copyTarget(a) },
    )

    val byName: Map<String, Alias> = all.associateBy { it.name }

    /** Other argument spellings for tools that share their name with alternative MCP clients. */
    fun argumentCompat(name: String, a: JSONObject): JSONObject = when {
        name == "launch_app" && !a.has("app") && a.has("package_name") -> JSONObject(a.toString()).put("app", a.get("package_name"))
        else -> a
    }
}
