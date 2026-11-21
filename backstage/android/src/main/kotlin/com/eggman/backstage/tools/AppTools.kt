package com.eggman.backstage.tools

import com.eggman.backstage.exec.AppCatalog
import com.eggman.backstage.exec.AppEntry
import com.eggman.backstage.exec.DefaultApp
import com.eggman.backstage.exec.DefaultHow
import com.eggman.backstage.exec.Box
import com.eggman.backstage.exec.LaunchWindow
import com.eggman.backstage.exec.PopupPosition
import com.eggman.backstage.exec.SafeIntents
import com.eggman.backstage.exec.TargetKind

/** Named window slots Dextop's WorkspaceLayoutEngine understands. */
val DESKTOP_SLOTS = listOf(
    "left", "right", "top", "bottom", "half_left", "half_right", "top_left", "top_right", "bottom_left", "bottom_right",
    "center", "maximized",
)

internal fun parseBounds(args: Args): Box? {
    val a = args.optArray("bounds") ?: return null
    if (a.length() != 4) throw ToolArgumentException("bounds must be [left, top, right, bottom]")
    val b = Box(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
    if (b.isEmpty) throw ToolArgumentException("bounds is empty")
    return b
}

/** launch_app's note on categories. */
internal const val CATEGORY_HINT = "For a generic request (\"open the browser\", \"take a photo\", \"open maps\") pass the " +
    "category as app (browser, camera, email, maps, gallery, calculator, ...): it opens the user's default app for it. Use an " +
    "app's own name only when the user named that app."

class LaunchAppTool(
    override val name: String = "launch_app",
    private val forceDesktop: Boolean = false,
) : Tool {
    override val description = if (forceDesktop) {
        "Open an installed app by name as a window on the DeX desktop (or bring its open window to the front), optionally in a " +
            "named slot (${DESKTOP_SLOTS.joinToString()}) or at explicit bounds (bounds also move an already open window). " +
            "window \"maximized\" makes its window fill the desktop (\"open YouTube full screen\" means this; the desktop itself " +
            "is not changed). The result says whether the window was verified on the desktop. The fastest way to open or switch " +
            "apps: use it instead of looking for the app in the app drawer. $CATEGORY_HINT"
    } else {
        "Open an installed app by name or package on a screen (faster than finding it in the app drawer). screen: desktop (the " +
            "DeX desktop, the default when it runs) or phone (the phone's own screen; only when the user asked for the phone). " +
            "window: on the phone fullscreen (default, a normal app) or popup (a small floating window, ONLY on the user's request " +
            "for a pop-up/floating window; it stays inside the status and navigation bars, position picks a corner); on the " +
            "desktop maximized fills the desktop with the app's window. Example: {\"app\":\"YouTube\",\"window\":\"maximized\"}. " +
            "On the desktop you may also pass a window slot or bounds. The result says on which screen the app was verified. " +
            CATEGORY_HINT
    }
    override val schema = Schema.obj(
        "app" to Schema.str("App name or package as the user named it, or a category for the user's default app: " +
            com.eggman.backstage.exec.AppRole.values().joinToString { it.key }),
        *(if (forceDesktop) emptyArray() else arrayOf(
            "screen" to Schema.enum("Where to open it: desktop (DeX desktop) or phone (the phone's own screen). Default: the task's screen.", "desktop", "phone"),
        )),
        "window" to Schema.enum("fullscreen (phone default), popup (small floating window, only on request), maximized (desktop: fills the desktop)", "fullscreen", "popup", "maximized"),
        "position" to Schema.enum("Phone pop-up position (window popup)", "center", "top_left", "top_right", "bottom_left", "bottom_right"),
        "slot" to Schema.enum("Desktop window slot (desktop only)", *DESKTOP_SLOTS.toTypedArray()),
        "bounds" to Schema.arr("Desktop window bounds [left, top, right, bottom] (desktop only)", Schema.int("px")),
        required = listOf("app"),
    )
    override val targets = if (forceDesktop) setOf(TargetKind.DESKTOP) else BOTH
    override val mutating = true

    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val query = args.string("app")
        val window = LaunchWindow.parse(args.optString("window"))
            ?: throw ToolArgumentException("window must be fullscreen, popup or maximized")
        if (args.has("position") && PopupPosition.parse(args.optString("position")) == null) {
            throw ToolArgumentException("position must be center, top_left, top_right, bottom_left or bottom_right")
        }
        val apps = ctx.services.installedApps()
        // A generic word ("browser", "camera", "maps") means the user's default app for it, never a guessed package.
        val role = com.eggman.backstage.exec.AppRole.forQuery(query)
        val default = role?.let { r -> ctx.services.defaultApps().firstOrNull { it.role == r } }
        val matches = if (default != null) listOf(AppEntry(default.label, default.packageName)) else AppCatalog.match(apps, query)
        val app = matches.firstOrNull()
            ?: return ToolResult.error(
                if (role != null) "No ${role.title} app was found on this phone. Use list_apps to see what is installed."
                else "No installed app matches \"$query\". Use list_apps to see what is installed.",
            )
        val exec = if (forceDesktop) ctx.executor(TargetKind.DESKTOP) else ctx.executor(screenArg(args))
        val slot = args.optString("slot")?.lowercase()
        if (slot != null && slot !in DESKTOP_SLOTS) throw ToolArgumentException("Unknown slot '$slot'")
        val bounds = parseBounds(args)
        if ((slot != null || bounds != null) && exec.kind != TargetKind.DESKTOP) {
            return ToolResult.error("Window slots/bounds only apply on the desktop. On the phone use window popup (and position).")
        }
        val r = exec.launchApp(app.packageName, slot, bounds, window, PopupPosition.parse(args.optString("position")) ?: PopupPosition.CENTER)
        val note = if (default != null) " (the user's ${default.role.title}: ${DefaultAppsTool.howText(default)})"
        else if (matches.size > 1 && !matches[0].label.equals(query, true)) " (matched \"${app.label}\"; others: ${matches.drop(1).take(3).joinToString { it.label }})" else ""
        return Observe.after(ctx, exec, r.copy(message = r.message.replace(app.packageName, "${app.label} (${app.packageName})") + note), settleMs = 4_000)
    }
}

/** The screen a tool call names: `screen` (desktop|phone), or the older `target`; null = the task's current screen. */
internal fun screenArg(args: Args): TargetKind? = parseTarget(args.optString("screen") ?: args.optString("target"))

class ListAppsTool : Tool {
    override val name = "list_apps"
    override val description = "Find installed apps: with keywords, up to 10 apps whose name or package contains any keyword; without, the full list (name — package)."
    override val schema = Schema.obj(
        "keywords" to Schema.arr("Words to look for in app names/packages, e.g. [\"bank\", \"pay\"]", Schema.str("keyword")),
        "filter" to Schema.str("Single substring filter (alternative to keywords)"),
    )
    override val targets = emptySet<TargetKind>()
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val keywords = args.stringList("keywords").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (args.has("keywords")) {
            if (keywords.isEmpty()) throw ToolArgumentException("keywords must contain at least one non-empty word")
            val hits = ctx.services.installedApps()
                .filter { a -> keywords.any { k -> a.label.lowercase().contains(k) || a.packageName.lowercase().contains(k) } }
                .map { "${it.label} (${it.packageName})" }.distinct().sorted().take(10)
            if (hits.isEmpty()) return ToolResult("No installed app matches ${keywords.joinToString(", ") { "\"$it\"" }}.")
            return ToolResult("Found ${hits.size} app(s):\n" + hits.joinToString("\n") { "- $it" })
        }
        val f = args.optString("filter")?.lowercase()
        val apps = ctx.services.installedApps().filter { f == null || it.label.lowercase().contains(f) || it.packageName.lowercase().contains(f) }
        if (apps.isEmpty()) return ToolResult("No apps match${f?.let { " \"$it\"" } ?: ""}.")
        val shown = apps.take(300)
        return ToolResult(shown.joinToString("\n") { "${it.label} — ${it.packageName}" } + if (apps.size > shown.size) "\n… ${apps.size - shown.size} more" else "")
    }
}

class OpenUrlTool : Tool {
    override val name = "open_url"
    override val description = "Open a URL or deep link (https:, mailto:, tel:, geo:, sms:, market:, app-specific schemes or intent: URIs) in the matching app: web links open in the user's default browser (on the task's screen), mailto: in their " +
        "email app, geo: in their maps app. Prefer this over navigating menus when a link exists."
    override val schema = Schema.obj("url" to Schema.str("URL or URI"), Schema.target, required = listOf("url"))
    override val targets = BOTH
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val url = args.string("url").trim()
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme.isEmpty() || scheme.any { !it.isLetterOrDigit() && it != '+' && it != '-' && it != '.' }) {
            throw ToolArgumentException("'$url' is not a URI (expected scheme:...)")
        }
        if (scheme in SafeIntents.blockedSchemes) return ToolResult.error("$scheme: URIs are not allowed")
        val exec = ctx.exec(args)
        return Observe.after(ctx, exec, exec.openUri(url), settleMs = 4_000)
    }
}

/** The user's default apps: what generic requests must use. */
class DefaultAppsTool : Tool {
    override val name = "default_apps"
    override val description = "List the user's default apps as Android resolves them (browser, home screen, dialer, SMS, " +
        "assistant, camera, email, maps, music, video, files, gallery, calendar, contacts, clock, calculator, notes). Generic " +
        "requests use these apps; launch_app accepts the category names."
    override val schema = Schema.obj()
    override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val apps = ctx.services.defaultApps()
        if (apps.isEmpty()) return ToolResult("The default apps are not known on this device. Use list_apps.")
        return ToolResult("The user's default apps:\n" + com.eggman.backstage.exec.DefaultAppsResolver.promptLines(apps))
    }

    companion object {
        fun howText(a: DefaultApp): String = "${a.label}" + when (a.how) {
            DefaultHow.DEFAULT -> ", the default"
            DefaultHow.ONLY -> ", the only one installed"
            DefaultHow.CHOSEN -> "; no default is set, so the best match was picked" +
                if (a.others.isNotEmpty()) " (others: ${a.others.take(3).joinToString()})" else ""
        }
    }
}
