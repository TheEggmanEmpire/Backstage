package com.eggman.backstage.shell

/** Wire contract with patch 0005. Indices are the four-item shell, not Dextop's three internal pages. */
enum class ShellPage(val wireName: String, val navigationIndex: Int) {
    HOME("home", 0), LINUX("linux", 2), SETTINGS("settings", 3);
    companion object {
        const val EXTRA = "com.eggman.backstage.extra.SHELL_PAGE"
        fun parse(value: String?): ShellPage? = entries.firstOrNull { it.wireName == value }
    }
}

fun shellWideLayout(widthDp: Int, desktopWindow: Boolean, landscape: Boolean): Boolean =
    widthDp >= 840 || (desktopWindow && landscape)
