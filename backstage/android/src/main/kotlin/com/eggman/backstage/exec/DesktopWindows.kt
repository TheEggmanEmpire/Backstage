package com.eggman.backstage.exec

import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopTask
import com.eggman.backstage.host.DesktopTaskPolicy
import java.util.Locale

/** Which desktop windows [DesktopWindowCloser] closes. */
sealed class DesktopCloseScope {
    /** Every app window on the desktop display. */
    object All : DesktopCloseScope()
    /** The window the agent is working in (focused, else the top-most visible one). */
    object Focused : DesktopCloseScope()
    /** The windows of one app, by name or package ([query]). */
    data class App(val query: String) : DesktopCloseScope()
}

/**
 * Closes app windows on the desktop display, and only there (user request: "close all foreground apps" must only
 * close the DeX desktop's apps, never the phone's). The task list comes from the host for the desktop display only;
 * [DesktopTaskPolicy] drops anything else (the phone's display, the desktop's launcher, Backstage itself), and the host
 * checks each task again right before it removes it. Nothing is force-stopped.
 */
object DesktopWindowCloser {
    const val UNAVAILABLE = "Cannot list the desktop's windows right now (it needs Shizuku or wireless debugging). Close " +
        "a window with its own close button (X in its title bar) instead; never use Close all in the recent-apps view, " +
        "which closes the phone's apps too."

    /** Packages of [query]: the best installed-app match, plus desktop tasks whose label or package is exactly it. */
    fun packagesFor(query: String, tasks: List<DesktopTask>, installed: List<AppEntry>): Set<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptySet()
        val lower = q.lowercase(Locale.ROOT)
        val exact = tasks.filter { it.packageName.equals(q, true) || it.label?.trim()?.lowercase(Locale.ROOT) == lower }
            .map { it.packageName }
        val onDesktop = tasks.map { it.packageName }.toSet()
        // An installed app that is open on the desktop wins over a better-named one that is not.
        val matches = AppCatalog.match(installed, q)
        val catalog = (matches.firstOrNull { it.packageName in onDesktop } ?: matches.firstOrNull())?.packageName
        return (exact + listOfNotNull(catalog)).toSet()
    }

    fun close(
        extras: DesktopHostExtras?,
        desktopDisplayId: Int,
        ownPackages: Set<String>,
        scope: DesktopCloseScope,
        installed: List<AppEntry> = emptyList(),
    ): ActionResult {
        val tasks = extras?.let { runCatching { it.desktopTasks() }.getOrNull() } ?: return ActionResult.fail(UNAVAILABLE)
        val what: String
        val targets: List<DesktopTask> = when (scope) {
            DesktopCloseScope.All -> {
                what = "app windows"
                DesktopTaskPolicy.select(tasks, desktopDisplayId, ownPackages)
            }
            DesktopCloseScope.Focused -> {
                what = "focused window"
                listOfNotNull(DesktopTaskPolicy.focused(tasks, desktopDisplayId, ownPackages))
            }
            is DesktopCloseScope.App -> {
                what = "windows of \"${scope.query}\""
                val packages = packagesFor(scope.query, tasks.filter { it.displayId == desktopDisplayId }, installed)
                if (packages.isEmpty()) emptyList() else DesktopTaskPolicy.select(tasks, desktopDisplayId, ownPackages, packages)
            }
        }
        if (targets.isEmpty()) {
            val open = DesktopTaskPolicy.select(tasks, desktopDisplayId, ownPackages)
            val list = if (open.isEmpty()) "No app windows are open on the desktop." else "Open on the desktop: ${open.joinToString { it.name }}."
            return ActionResult.ok("No $what to close on the desktop. $list")
        }
        val closed = mutableListOf<DesktopTask>()
        val failed = mutableListOf<DesktopTask>()
        for (task in targets) {
            if (runCatching { extras.closeDesktopTask(task.taskId) }.getOrDefault(false)) closed += task else failed += task
        }
        val message = buildString {
            if (closed.isNotEmpty()) append("Closed ${closed.size} desktop window(s): ${closed.joinToString { it.name }}.")
            if (failed.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append("Could not close: ${failed.joinToString { it.name }} (use the window's own close button).")
            }
            append(" Apps on the phone were not touched.")
        }
        return if (closed.isEmpty()) ActionResult.fail(message) else ActionResult.ok(message)
    }
}
