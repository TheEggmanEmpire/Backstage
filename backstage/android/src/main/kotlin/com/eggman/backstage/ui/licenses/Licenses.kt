package com.eggman.backstage.ui.licenses

/** Open-source notices shown in the app (keep in sync with NOTICE.md). */
object Licenses {

    data class Component(val name: String, val license: String, val url: String, val note: String = "")

    const val APP_LICENSE = "GPL-3.0-or-later"

    /** Backstage's own notice, shown in Settings → About. */
    const val OWN_COPYRIGHT = "Backstage — Copyright © 2026 TheEggmanEmpire."

    /**
     * All copyright notices (GPL-3.0 §5d "Appropriate Legal Notices"): the licenses screen, reachable from Settings →
     * About, must keep the upstream Dextop notice (GPL-3.0 §4, §5a, §7b).
   */
    const val COPYRIGHT =
        "$OWN_COPYRIGHT\n" +
            "Dextop — Copyright © NarYuki and the Dextop contributors."

    const val APP_NOTICE =
        "Backstage is free software: you can redistribute it and/or modify it under the terms of the GNU General " +
            "Public License as published by the Free Software Foundation, either version 3 of the License, or (at your " +
            "option) any later version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; " +
            "without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU " +
            "General Public License for more details."

    /** Where the corresponding source of every build lives (the tag or commit of its version). */
    const val SOURCE_URL = "https://github.com/TheEggmanEmpire/Backstage"

    /** Native-library notices and every license text they name, generated into the APK (build.gradle.kts). */
    const val NOTICES_ASSET = "backstage/THIRD_PARTY_NOTICES.txt"

    /** The full GPL text bundled in the app (res/raw), so it can be read offline. */
    const val LICENSE_TEXT_RESOURCE = "backstage_license_gpl3"

    val desktop = Component(
        "Dextop", "GPL-3.0-or-later", "https://github.com/NarYuki/Dextop",
        "The desktop (DeX-style) experience is upstream Dextop by NarYuki and contributors, modified by the Backstage " +
            "project: background and mini-window presentation, fork identity (package-specific shell paths, update " +
            "check and repository link, Car Companion pinning, device-report address), the Agent tab and the desktop player on the home " +
            "screen, and the Backstage glue compiled into the app. Every change is published as patches with the " +
            "source; see NOTICE.md there.",
    )

    val thirdParty = listOf(
        Component("AndroidX (Compose, Material 3, Activity, Lifecycle, Core, SavedState)", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
        Component("Kotlin standard library & kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
        Component("OkHttp & Okio", "Apache-2.0", "https://square.github.io/okhttp/"),
        Component("Shizuku API", "MIT", "https://github.com/RikkaApps/Shizuku-API"),
        Component("AndroidHiddenApiBypass", "Apache-2.0", "https://github.com/LSPosed/AndroidHiddenApiBypass"),
        Component("Vosk speech recognition (vosk-android)", "Apache-2.0", "https://github.com/alphacep/vosk-api"),
        Component("Java Native Access (JNA)", "Apache-2.0 (dual-licensed LGPL-2.1-or-later / Apache-2.0)", "https://github.com/java-native-access/jna"),
        Component(
            "Vosk small speech models (downloaded on demand)", "Apache-2.0", "https://alphacephei.com/vosk/models",
            "Not bundled. Downloaded from alphacephei.com only after you confirm, verified by size and SHA-256.",
        ),
    )

    /**
     * Closed-source libraries that still ship in the APK (inherited from upstream Dextop; keep in sync with NOTICE.md).
     * Firebase Analytics and Play Core in-app updates are removed by the build; the cast client remains.
   */
    val closedSource = listOf(
        Component(
            "Google Play services client libraries (cast framework and its base libraries)",
            "Closed-source (Google's terms, not open source)", "https://developers.google.com/cast",
            "Used only by the desktop's cast feature (show the desktop on a Chromecast). Their source is not available; the " +
                "GPL-3.0 section 7 permission in NOTICE.md allows combining them with this program.",
        ),
    )

    /** "Source: <url> (version x)": the version names the release tag the source corresponds to. */
    fun sourceLine(versionName: String?, revision: String? = null, url: String = SOURCE_URL): String =
        "Corresponding source: $url" +
            (revision?.takeIf { it.isNotBlank() && it != "unknown" }?.let { " at commit $it" } ?: "") +
            (versionName?.takeIf { it.isNotBlank() }?.let { " — this build: version $it" } ?: "")

    /** Link to the exact source tree of this build, or the repository when the revision is unknown or not clean. */
    fun sourceTreeUrl(revision: String?, url: String = SOURCE_URL): String {
        val rev = revision?.trim().orEmpty()
        return if (Regex("^[0-9a-f]{7,40}$").matches(rev)) "$url/tree/$rev" else url
    }

    /** The notices asset split into sections ("---- path ----" / "== lib ==" headings), so a long text renders in parts. */
    fun noticeSections(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (line in text.lineSequence()) {
            if ((line.startsWith("---- ") || line.startsWith("==== ")) && cur.isNotBlank()) {
                out += cur.toString().trimEnd(); cur.clear()
            }
            cur.append(line).append('\n')
        }
        if (cur.isNotBlank()) out += cur.toString().trimEnd()
        return out
    }

    const val SERVICES_NOTE =
        "Backstage has no cloud backend of its own. " +
            "Requests go to the AI provider " +
            "you choose and, only when you turn them on in Settings, to the services named there: the web search " +
            "provider (DuckDuckGo, Brave, Serper, Tavily, Kagi, your SearXNG or the custom search URL you enter), " +
            "OpenWeatherMap for weather, remote MCP " +
            "servers you add, and alphacephei.com for the wake-word model download. The desktop's update check asks " +
            "api.github.com for new Backstage releases."
}
