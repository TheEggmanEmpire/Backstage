package com.eggman.backstage.tools

import com.eggman.backstage.exec.KeyNames

private val indexOrPoint = arrayOf(
    "index" to Schema.int("Element index from the latest screen listing (preferred)"),
    "nid" to Schema.str("Stable element id (nid) from the markup format, alternative to index"),
    "x" to Schema.num("X in screen pixels (when there is no index)"),
    "y" to Schema.num("Y in screen pixels (when there is no index)"),
    "space" to Schema.enum("Coordinate space of x/y: screen (default) or image (pixels of the last screenshot)", "screen", "image"),
    Schema.target,
)

class TapTool : Tool {
    override val name = "tap"
    override val description = "Tap an element by index (preferred) or at x,y. Returns the updated screen so you can verify the effect."
    override val schema = Schema.obj(*indexOrPoint)
    override val targets = BOTH
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val exec = ctx.exec(args)
        val r = Observe.target(exec, args)?.let { exec.clickElement(it) }
            ?: Observe.point(exec, args).let { (x, y) -> exec.tap(x, y) }
        return Observe.after(ctx, exec, r)
    }
}

class LongPressTool : Tool {
    override val name = "long_press"
    override val description = "Long-press an element by index or at x,y (context menus, selection, drag start)."
    override val schema = Schema.obj(*indexOrPoint, "duration_ms" to Schema.int("Hold time, default 700"))
    override val targets = BOTH
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val exec = ctx.exec(args)
        val ms = (args.optInt("duration_ms") ?: 700).toLong().coerceIn(300, 5_000)
        val r = Observe.target(exec, args)?.let { exec.clickElement(it, long = true) }
            ?: Observe.point(exec, args).let { (x, y) -> exec.longPress(x, y, ms) }
        return Observe.after(ctx, exec, r)
    }
}

class SwipeTool : Tool {
    override val name = "swipe"
    override val description = "Swipe/drag from (x1,y1) to (x2,y2) in screen pixels. For lists prefer scroll."
    override val schema = Schema.obj(
        "x1" to Schema.num("start x"), "y1" to Schema.num("start y"), "x2" to Schema.num("end x"), "y2" to Schema.num("end y"),
        "duration_ms" to Schema.int("default 300; use 800+ for drag-and-drop"),
        "space" to Schema.enum("screen (default) or image", "screen", "image"),
        Schema.target,
        required = listOf("x1", "y1", "x2", "y2"),
    )
    override val targets = BOTH
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val exec = ctx.exec(args)
        val (x1, y1) = Observe.point(exec, args, "x1", "y1")
        val (x2, y2) = Observe.point(exec, args, "x2", "y2")
        val ms = (args.optInt("duration_ms") ?: 300).toLong().coerceIn(50, 5_000)
        return Observe.after(ctx, exec, exec.swipe(x1, y1, x2, y2, ms))
    }
}

class ScrollTool : Tool {
    override val name = "scroll"
    override val description = "Scroll to reveal more content: direction is where you want to move in the content (down = further down the list). Optionally inside a scrollable element."
    override val schema = Schema.obj(
        "direction" to Schema.enum("Content direction to reveal", "down", "up", "left", "right"),
        "index" to Schema.int("Scrollable element index (optional)"),
        "times" to Schema.int("Repeat 1-5 times (default 1)"),
        Schema.target,
        required = listOf("direction"),
    )
    override val targets = BOTH
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val exec = ctx.exec(args)
        val dir = args.string("direction").lowercase()
        val el = args.optInt("index")?.let { Observe.element(exec, it) }
        val times = (args.optInt("times") ?: 1).coerceIn(1, 5)
        val before = exec.lastScreen?.fingerprint
        var r = exec.scrollElement(el, dir)
        for (i in 2..times) {
            if (!r.ok) break
            exec.awaitSettle(1_200)
            r = exec.scrollElement(el, dir)
        }
        val res = Observe.after(ctx, exec, r, settleMs = 1_500)
        if (r.ok && before != null && exec.lastScreen?.fingerprint == before) {
            return res.copy(text = res.text + "\n(Nothing moved — the content has most likely reached its end in that direction.)")
        }
        return res
    }
}

class TypeTextTool : Tool {
    override val name = "type_text"
    override val description = "Set text in an editable field or the focused field, replacing its content. Passwords and verification/PIN/OTP fields require secret=true. Set submit=true to press Enter afterwards."
    override val schema = Schema.obj(
        "text" to Schema.str("Exact text to enter"),
        "index" to Schema.int("Editable element index (optional; default focused field)"),
        "secret" to Schema.bool("Required for passwords and one-time codes; redacts the text from tool history"),
        "submit" to Schema.bool("Press Enter afterwards"),
        Schema.target,
        required = listOf("text"),
    )
    override val targets = BOTH
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val text = args.json.optString("text")
        var sensitive = args.bool("secret", false)
        try {
            val exec = ctx.exec(args)
            val el = args.optInt("index")?.let { Observe.element(exec, it) }
            // A fresh capture resolves focused editable nodes even when no index is supplied. It
            // also catches an editable child of a label/WebView container selected by index.
            val capture = if (el == null) exec.observe().second else exec.lastCapture
            val nodes = ArrayDeque<com.eggman.backstage.exec.UiNode>()
            capture?.windows?.mapNotNull { it.root }?.forEach(nodes::addLast)
            var matched: com.eggman.backstage.exec.UiNode? = null
            var focused: com.eggman.backstage.exec.UiNode? = null
            var count = 0
            while (nodes.isNotEmpty() && count++ < 3_000) {
                val n = nodes.removeFirst()
                if (n.editable && n.focused) focused = n
                if (el != null && el.handle != null && (n.handle === el.handle || runCatching { n.handle == el.handle }.getOrDefault(false))) matched = n
                n.children.forEach(nodes::addLast)
            }
            val candidates = ArrayDeque<com.eggman.backstage.exec.UiNode>()
            (matched ?: focused)?.let(candidates::addLast)
            var classifiedSecret = el?.let { requiresSecret(it) } == true
            count = 0
            while (candidates.isNotEmpty() && count++ < 3_000) {
                val n = candidates.removeFirst()
                classifiedSecret = classifiedSecret || com.eggman.backstage.a11y.TextInputPolicy.isSecretField(
                    n.password, n.editable, n.contentDescription, n.hint, n.resourceId)
                n.children.forEach(candidates::addLast)
            }
            // The local backend can refresh focus even if no prior listing exposed the input.
            val live = com.eggman.backstage.a11y.AgentAccessibility.instance?.focusedEditable(exec.displayId)
            if (el == null && live != null) classifiedSecret = classifiedSecret || com.eggman.backstage.a11y.TextInputPolicy.isSecretField(
                live.isPassword, live.isEditable, live.contentDescription?.toString().orEmpty(), live.hintText?.toString().orEmpty(), live.viewIdResourceName.orEmpty())
            sensitive = sensitive || classifiedSecret
            if (sensitive && text.length >= 2) Observe.registerSecret(ctx, exec, text)
            if (text.isNotEmpty() && classifiedSecret && !args.bool("secret", false)) {
                return ToolResult.error("This password or verification-code field requires secret=true. Resend type_text with secret=true; the text has not been entered.")
            }
            var r = com.eggman.backstage.a11y.TextInputSession.withSession(secret = sensitive) { exec.setText(el, text) }
            if (r.ok && args.bool("submit", false)) {
                exec.awaitSettle(800)
                val k = exec.key("enter")
                r = r.copy(message = r.message + if (k.ok) " and pressed enter" else " (enter failed)")
            }
            val result = Observe.after(ctx, exec, r, showScreen = !sensitive)
            // A non-password app may render an entered secret in its listing. Never return that
            // string or an automatic screenshot from this tool call.
            return if (sensitive) result.copy(
                text = if (text.isEmpty()) r.message else r.message.replace(text, "[redacted]"),
                image = null, data = null,
            ) else result
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (failure: Exception) {
            if (!sensitive) throw failure
            return ToolResult.error("Secret text entry failed; observe the field before retrying.")
        } finally {
            // Tool arguments are shared with the in-memory conversation. Keep only the length
            // once execution has consumed the text, including refused/failed secret calls.
            if (sensitive) {
                args.json.put("text", org.json.JSONObject().put("secret_len", text.length))
                args.json.remove("reason")
            }
        }
    }

    companion object {
        fun requiresSecret(element: com.eggman.backstage.exec.UiElement): Boolean = com.eggman.backstage.a11y.TextInputPolicy.isSecretField(
            password = "password" in element.traits, editable = element.editable,
            label = element.label, hint = element.hint, resourceId = element.resourceId)

        /** Safe copy for logs and retained tool-call arguments, including nested sequences. */
        fun redactedArguments(name: String, args: org.json.JSONObject, allTypedText: Boolean = false): org.json.JSONObject {
            val copy = org.json.JSONObject(args.toString())
            if (name.removePrefix("backstage_") == "type_text" && (allTypedText || Args(args).bool("secret", false))) {
                (args.opt("text") as? String)?.let { copy.put("text", org.json.JSONObject().put("secret_len", it.length)) }
                // Model-provided prose can repeat the secret verbatim.
                copy.remove("reason")
            }
            val steps = args.optJSONArray("steps")
            if (steps != null) copy.put("steps", org.json.JSONArray().apply {
                for (i in 0 until steps.length()) {
                    val step = steps.optJSONObject(i)
                    if (step == null) { put(steps.opt(i)); continue }
                    val stepCopy = org.json.JSONObject(step.toString())
                    val field = if (step.has("args")) "args" else "arguments"
                    step.optJSONObject(field)?.let { stepCopy.put(field, redactedArguments(step.optString("tool", step.optString("name")), it, allTypedText)) }
                    put(stepCopy)
                }
            })
            return copy
        }
    }
}

private val GLOBAL_KEYS = setOf("notifications", "quick_settings", "lock_screen", "dismiss_notifications", "all_apps", "split_screen")

class PressKeyTool : Tool {
    override val name = "press_key"
    override val description = "Press a key or system button: back, home, recents, all_apps, enter, delete, tab, escape, space, up/down/left/right, page_up/page_down, search, menu, volume_up/volume_down, media_play_pause, notifications, quick_settings, or a chord like ctrl+c (desktop). On the desktop the recent-apps view also lists the phone's apps: never close apps there (use desktop_close_windows)."
    override val schema = Schema.obj("key" to Schema.str("Key name or chord"), Schema.target, required = listOf("key"))
    override val targets = BOTH
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val exec = ctx.exec(args)
        val key = args.string("key")
        if (KeyNames.chord(key) == null && KeyNames.normalize(key) !in GLOBAL_KEYS) {
            return ToolResult.error("Unknown key '$key'. Known: ${KeyNames.codes.keys.joinToString(", ")}, ${GLOBAL_KEYS.joinToString(", ")}")
        }
        return Observe.after(ctx, exec, exec.key(key))
    }
}
