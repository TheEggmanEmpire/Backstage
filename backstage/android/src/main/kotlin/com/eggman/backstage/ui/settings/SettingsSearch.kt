package com.eggman.backstage.ui.settings

import java.util.Locale

/** Stable destinations shared by the catalogue and the settings cards. No Android dependency. */
internal enum class SettingsGroup(val title: String, val subtitle: String) {
    APP("App settings", "Display, input, Linux and other app preferences"),
    MODEL("Model & provider", "Choose the AI service, account and models used for tasks."),
    INSTRUCTIONS("Instructions", "Guide every task with instructions, app rules, memories and skills."),
    AGENT("How the agent works", "Choose how tasks are planned, where they run and when they stop."),
    SAFETY("Safety & approvals", "Control which actions need your permission and who may use the phone."),
    SCREEN("Screen & screenshots", "Control what the model sees and how the agent's actions are shown."),
    DESKTOP("Desktop popup", "Choose how the desktop appears, starts and behaves while locked."),
    ACCESSIBILITY("Accessibility & modules", "Manage accessibility access and optional weather, search and calendar tools."),
    VOICE("Voice", "Speak tasks, hear replies and set up on-device wake-word recognition."),
    BUBBLE("Bubble", "Choose the floating chat button and how task results appear."),
    CONNECTIONS("Connections (MCP)", "Connect external clients to Backstage and give the agent remote tools."),
    AUTOMATION("Automation", "Connect Tasker, MacroDroid and Android broadcasts."),
    OMP("OMP", "Set up the harness, subagents and Termux shell access."),
    UPDATES("Updates", "Check for and install Backstage app updates."),
    ABOUT("Backup & about", "Export or restore settings, revisit setup and read version and license information."),
}

internal data class SettingsSearchEntry(
    val id: String,
    val title: String,
    val description: String,
    val group: SettingsGroup,
    val keywords: List<String>,
)

/** Search only this public metadata. Saved keys, instructions and memory contents never enter the index. */
internal object SettingsSearch {
    val entries: List<SettingsSearchEntry> = buildList {
        fun entry(group: SettingsGroup, title: String, description: String, keywords: String) {
            add(SettingsSearchEntry(group.name.lowercase(Locale.ROOT) + ":" + title.lowercase(Locale.ROOT), title, description, group, words(keywords)))
        }
        fun model(t: String, d: String, k: String) = entry(SettingsGroup.MODEL, t, d, k)
        fun instructions(t: String, d: String, k: String) = entry(SettingsGroup.INSTRUCTIONS, t, d, k)
        fun agent(t: String, d: String, k: String) = entry(SettingsGroup.AGENT, t, d, k)
        fun safety(t: String, d: String, k: String) = entry(SettingsGroup.SAFETY, t, d, k)
        fun screen(t: String, d: String, k: String) = entry(SettingsGroup.SCREEN, t, d, k)
        fun desktop(t: String, d: String, k: String) = entry(SettingsGroup.DESKTOP, t, d, k)
        fun modules(t: String, d: String, k: String) = entry(SettingsGroup.ACCESSIBILITY, t, d, k)
        fun voice(t: String, d: String, k: String) = entry(SettingsGroup.VOICE, t, d, k)
        fun bubble(t: String, d: String, k: String) = entry(SettingsGroup.BUBBLE, t, d, k)
        fun connection(t: String, d: String, k: String) = entry(SettingsGroup.CONNECTIONS, t, d, k)
        fun automation(t: String, d: String, k: String) = entry(SettingsGroup.AUTOMATION, t, d, k)
        fun omp(t: String, d: String, k: String) = entry(SettingsGroup.OMP, t, d, k)
        fun about(t: String, d: String, k: String) = entry(SettingsGroup.ABOUT, t, d, k)
        entry(SettingsGroup.APP, "App settings", "Display, input, Linux and other app preferences. Opens Dextop settings.", "theme appearance dark light keyboard mouse touch resolution linux terminal preferences flutter")
        model("Agent engine", "Choose Backstage or OMP for chat tasks.", "harness native loop")
        model("AI provider", "Choose the service that receives AI requests.", "model llm ai openai anthropic claude google gemini chatgpt openrouter deepseek ollama local lan custom compatible endpoint service venice opencode go proxy gateway")
        model("API key", "API key (optional) for the selected provider; show or hide the saved key.", "token secret credential password authentication")
        model("ChatGPT account", "Sign in with ChatGPT, Use a code instead, or Sign out. Address from the browser can complete sign-in.", "subscription plus pro business enterprise login oauth account browser device code")
        model("Models", "Enable models, All on, All off, Reload models, Search models and Only enabled. Select the main model.", "llm switching list refresh selection")
        model("Main model / add model ID", "Enter a model identifier and tap Use to add or select it.", "llm name custom selection")
        model("Base URL", "Set the provider API endpoint or Reset to defaults.", "host address endpoint server custom proxy")
        model("Context window (tokens)", "Automatic context size or a manual override.", "limit capacity length history memory budget")
        model("Model vision", "Vision Auto, On or Off overrides the model's screenshot support.", "pictures images capability multimodal screenshot")
        instructions("Your standing instructions", "Instructions applied to every task, including reply style and boundaries.", "system prompt rules custom tone language")
        instructions("Global instructions", "Shared by Backstage and OMP; Save or Reset to default.", "system prompt shared guidance default")
        instructions("Rules for apps", "Add a rule for an app, Search apps, edit instructions or Remove rule.", "application package restriction behaviour prompt")
        instructions("Use memory", "Enable saving and looking up facts about you.", "remember recall facts long term knowledge")
        instructions("Search memories", "Browse saved facts; Delete individual memories.", "remember recall find browse")
        instructions("Memory import & export", "Export, Import or Delete all saved memories.", "backup restore save clear forget json")
        instructions("Skills", "Manage skills: import from file, link or GitHub; sync repositories and edit reusable instructions.", "github sync plugins skill markdown reusable install repository")
        agent("Plan Mode", "Writes a checklist first and works through it step by step.", "checklist steps plan todo planning")
        agent("Safety limit: actions per task (20–200)", "Maximum number of actions in one task.", "steps limit cap stop budget")
        agent("Time limit per task", "Optional time limit in minutes; waiting for the user does not count.", "timeout duration stop deadline minutes")
        agent("Chat history: chats kept (5–500)", "How many chat threads are retained.", "conversation retention archive messages storage")
        agent("Where the agent works", "DeX desktop, Phone or Auto chooses the task's screen.", "target dex display workspace location default")
        agent("Fast mode", "Shorter waits for the screen to settle and no cursor lead.", "speed quick latency performance delay")
        safety("Confirm before irreversible actions", "Sending, posting, buying, deleting or calling waits for your approval.", "approve permission ask safety dangerous confirmation consent")
        safety("When nobody can confirm", "Deny, Ask in a notification or Allow unattended confirmations.", "approve permission ask safety unattended schedules notification automation")
        safety("ADB commands", "Ask each time or Auto for shell-user commands.", "shell command terminal approve permission stellar shizuku wireless debugging")
        safety("Remote callers may use the phone screen", "Permit MCP clients and the broadcast API to use the phone instead of only DeX.", "external permission access remote screen security")
        safety("Run skill scripts", "Off, Ask or Auto for native scripts; commands run with shell-user access.", "code execution approve permission safety plugin shell")
        screen("Vision", "Send screenshots to the model when the element list is not enough.", "image picture screenshot multimodal screen")
        screen("Screenshots", "JPEG quality and Scale control screenshot image quality and resolution.", "images pictures vision quality resolution compression size")
        screen("JPEG quality (30-100)", "Compression quality of screenshots sent to the provider.", "image picture screenshot resolution compression tokens")
        screen("Scale in % (10-100)", "Resize screenshots as a percentage of display size.", "image picture screenshot resolution size zoom pixels")
        screen("Show agent cursor", "Animated pointer shows taps, typing and scrolling.", "pointer mouse animation overlay")
        screen("Cursor leads each action by", "Delay before each action; not in fast mode.", "pointer mouse lead delay milliseconds timing")
        screen("Fresh desktop for automations", "Restart the desktop before scheduled, Tasker, broadcast or MCP runs; unsaved work is lost.", "restart clean reset windows schedule automation")
        desktop("Desktop popup", "How the desktop shows while you use other apps; popup window or background.", "dex window mini picture in picture pip floating background")
        desktop("Pop out when leaving the app", "Bring the running desktop back as a popup when you leave Backstage.", "dex window floating background exit")
        desktop("Start desktop when Backstage opens", "Start the desktop in the Home player when it is not running.", "dex launch startup automatic boot")
        desktop("Mini window shape", "Rounded or Circle desktop popup.", "dex window circle rounded crop appearance")
        desktop("Keep the desktop running while locked", "Keep tasks and desktop apps awake while the phone is locked.", "dex lockscreen awake background automation")
        desktop("Locked mode", "Desktop lock diagnostics, Restart the desktop now or Allow waking the desktop.", "lockscreen wake permission repair display")
        desktop("Desktop focus", "Own or Shared keyboard focus, applied the next time the desktop starts.", "keyboard typing dex input quiet")
        modules("Consent to accessibility use", "Allow or withdraw Backstage accessibility consent.", "service permission disclosure privacy access")
        modules("Turn the service back on automatically", "Re-enable accessibility when the desktop resets the list.", "service restart accessibility stellar shizuku debugging")
        modules("Pause while a detector app is in front", "Temporarily switch accessibility off for detector apps.", "service detection accessibility warning banking")
        modules("Re-check seconds", "Detector pause re-check interval.", "poll delay accessibility detection")
        modules("Detector packages", "Package names, colon- or comma-separated.", "app detection accessibility banking")
        modules("Weather (OpenWeatherMap)", "Optional weather tool configuration and Test.", "forecast temperature climate module")
        modules("OpenWeatherMap API key", "Weather service credential; without it the agent uses web search.", "token secret credential password forecast")
        modules("Home location", "Weather location used when no place is named.", "weather city place address")
        modules("Weather units", "Choose °C, m/s or °F, mph.", "celsius fahrenheit metric imperial temperature")
        modules("Web search", "Search provider: DuckDuckGo, SearXNG, Brave, Serper, Tavily, Kagi or Custom. Test search.", "internet engine module google lookup")
        modules("Search API key", "Brave API key, Serper API key, Tavily API key or Kagi API key.", "token secret credential password search")
        modules("SearXNG address", "Base address of a SearXNG instance.", "search server url endpoint self hosted")
        modules("Search URL with {query}", "Custom search URL template and result count.", "engine endpoint custom internet")
        modules("Header name (optional)", "Authentication header name for custom search.", "authorization credential search")
        modules("Header value (optional, stored encrypted)", "Secret value of the custom search authentication header.", "token secret credential password bearer search")
        modules("Browser searches", "Choose the engine for searches shown in the default browser.", "google duckduckgo kagi bing startpage engine internet")
        modules("Browser search URL with {query}", "Custom browser search URL; empty uses the browser's engine.", "internet engine template custom")
        modules("Alarms and timers", "Configure alarm vibration and whether the clock app opens.", "clock timer reminder wake")
        modules("Calendar", "Choose calendars, event limits and default reminders.", "agenda events meetings schedule")
        modules("Vibrate", "Vibration for alarms and timers.", "alarm timer haptic buzz")
        modules("Set without opening the clock app", "Create alarms and timers without opening the clock UI.", "alarm timer silent skip clock")
        modules("Events per answer (1-100)", "Maximum calendar events returned in an answer.", "agenda calendar limit meetings")
        modules("Reminder for new events, minutes before (empty = none)", "Default calendar reminder timing.", "agenda calendar notification alert meeting")
        modules("Calendars the agent reads (none ticked = all)", "Choose calendars used for reading events.", "agenda account calendar selection permission")
        modules("New events go to", "Choose the default calendar for new events.", "agenda account calendar default meeting")
        voice("Read replies aloud", "Speak results of voice commands.", "speech microphone talk voice tts sound audio")
        voice("Prefer on-device recognition", "Keep voice on the phone when the recogniser supports it.", "speech microphone offline private stt")
        voice("Wake word", "Say the phrase, then your command; works without the bubble.", "speech microphone talk wake word listen handsfree")
        voice("Wake phrase", "Words that activate the listener; Save phrase applies changes.", "speech microphone wake word trigger hey computer")
        voice("Speech model", "Select a Vosk language model, download or delete the model.", "recognition language offline download vosk microphone english us german deutsch french français spanish español italian italiano")
        voice("Pause while the screen is off", "Stop wake-word listening while the screen is off to save battery.", "microphone listen power sleep")
        voice("Pause while the phone is locked", "While locked, only stop is accepted; other voice commands need unlock.", "microphone listen privacy lockscreen")
        voice("Only while charging", "Listen only when the phone is plugged in.", "microphone listen power battery charger")
        bubble("Show the bubble", "Floating button over other apps; tap for chat, long-press to speak, drag to hide. Show it again restores it.", "floating overlay chat head microphone button")
        bubble("When a task ends", "Open the chat with the result or Notification only.", "result completion expanded compact display")
        bubble("Close the result after 5 seconds", "Dismiss the expanded result automatically.", "timeout auto dismiss result")
        bubble("Result notifications", "Notify when tasks started outside the app finish.", "alert completion notification result")
        connection("Enable MCP server", "Let MCP clients run tasks and read device status over Streamable HTTP.", "server tools connector integration client claude cursor")
        connection("Tools offered to clients", "All on or All off, Run a task (execute_task), Read the screen (get_screen_state), Device status (get_device_status).", "mcp server tools connector allowlist integration")
        connection("Experimental: low-level tools", "Show or hide experimental tools, Wake the screen (wake_device), Every low-level tool or individual tool switches.", "mcp tools connector tap_screen click_node shell desktop")
        connection("Allow access from the local network", "Off allows only this phone; use adb forward over USB from a computer.", "mcp lan wifi network remote address")
        connection("Port (1024–65535)", "Set the MCP server port and Apply changes.", "mcp server endpoint listen network")
        connection("Access token", "Show, Copy or New token; also authorises the broadcast API.", "mcp server secret credential password bearer security rotate")
        connection("MCP client configuration", "Endpoint and connection snippets for Claude Code, Claude Desktop and other clients.", "mcp server connector integration setup config usb adb")
        connection("Remote MCP servers", "Add a server, enable or disable servers, Test, Tools, Edit or Remove. Name, Server URL, Bearer token (optional); switches enable individual remote tools.", "server tools connector integration remote endpoint credential")
        automation("Plugin", "Tasker, MacroDroid and Locale-compatible automation setup.", "schedule trigger automation integration")
        automation("Reset plugin signing key", "Revoke saved Tasker and MacroDroid actions until saved again.", "secret credential security rotate automation")
        automation("Broadcast API", "Android broadcast reference and Copy adb example.", "intent tasker macrodroid integration command automation")
        automation("Include result text in open broadcasts", "Without reply_package, every app receives results; off sends status only.", "privacy notification broadcast automation result")
        omp("Enable OMP harness", "Enable shell-user subagents and the local key broker.", "subagent runtime code shell bun harness")
        omp("Chat engine", "Choose Backstage or OMP for chats.", "agent native loop harness")
        omp("Broker port", "Local OMP credential broker port.", "server key endpoint network harness")
        omp("Install / Repair / Update omp", "Install bundled runtime pins, repair the harness or download an update.", "runtime upgrade download bun harness")
        omp("OMP approvals", "Ask each time or Auto for shell-user subagent tasks.", "approve permission safety dangerous shell command")
        omp("Rotate harness token", "Revoke the old OMP harness credential.", "secret credential password security")
        omp("Enable Termux pairing", "Allow Termux to fetch runtime and provider keys; Copy setup line or Rotate pairing.", "terminal shell setup secret credential password")
        omp("Shell access (uid 2000)", "Allow Termux pairing token holders to request shell-user commands.", "terminal adb command permission security")
        omp("Termux shell approvals", "Ask each time or Auto for Termux shell commands.", "approve permission safety dangerous command terminal")
        entry(SettingsGroup.UPDATES, "App updates", "Installed and latest versions, last check, Check now and Update.", "upgrade release download install apk github version checksum notification")
        about("Backup", "Save or restore all Backstage settings and user skills as a .zip file.", "export import restore save archive zip")
        about("Export settings", "Write a backup containing readable API keys and tokens.", "backup save export zip archive")
        about("Import settings", "Replace settings and user skills with a trusted backup; restart to reload.", "backup import restore load zip")
        about("Setup checklist", "Revisit permissions and device setup.", "onboarding configuration access permission")
        about("Open-source licenses", "Offline licenses, copyright and source information.", "about legal gpl version build revision source")
    }

    private data class Document(val entry: SettingsSearchEntry, val fields: List<Pair<List<String>, Int>>)
    private fun document(entry: SettingsSearchEntry) =
        Document(entry, listOf(words(entry.title) to 100, words(entry.description) to 45,
            words(entry.group.title) to 35, entry.keywords to 60))

    private val documents = entries.map(::document)

    /** Runtime tool names are public metadata too; keep the core search independent of ToolRegistry/Android. */
    fun mcpTools(names: Collection<String>): List<SettingsSearchEntry> = names.distinct().map { name ->
        SettingsSearchEntry("mcp-tool:$name", name, "Allow external MCP clients to use this tool.",
            SettingsGroup.CONNECTIONS, listOf("mcp", "tool", "connector", "integration"))
    }

    /** AND across query words, OR across fields. Exact title hits outrank synonyms; ties retain catalogue order. */
    fun search(query: String, extraEntries: List<SettingsSearchEntry> = emptyList()): List<SettingsSearchEntry> {
        val queryWords = words(query).distinct()
        if (queryWords.isEmpty()) return if (query.isBlank()) entries + extraEntries else emptyList()
        return (documents + extraEntries.map(::document)).mapNotNull { document ->
            var score = 0
            for (queryWord in queryWords) {
                val best = document.fields.maxOf { (candidates, weight) ->
                    candidates.maxOfOrNull { candidate -> match(queryWord, candidate, weight) } ?: 0
                }
                if (best == 0) return@mapNotNull null
                score += best
            }
            document.entry to score
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun words(value: String): List<String> = Regex("[a-z0-9]+").findAll(value.lowercase(Locale.ROOT)).map { it.value }.toList()

    private fun stem(word: String): String {
        var value = when {
            word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
            word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
            else -> word
        }
        val suffix = when {
            value.length > 5 && value.endsWith("ing") -> 3
            value.length > 4 && value.endsWith("ed") -> 2
            else -> 0
        }
        if (suffix != 0) {
            value = value.dropLast(suffix)
            if (value.length > 2 && value.last() == value[value.lastIndex - 1]) value = value.dropLast(1)
        }
        return value
    }

    private fun match(query: String, candidate: String, weight: Int): Int {
        if (query == candidate) return weight
        if (candidate.startsWith(query)) return weight - 5
        if (candidate.contains(query)) return weight - 10
        val root = stem(query)
        val other = stem(candidate)
        if (root == other || (root.length >= 3 && other.contains(root))) return weight - 12
        if (query.length >= 5 && oneEditApart(query, candidate)) return weight - 20
        return 0
    }

    /** Levenshtein distance at most one, without allocating a matrix. */
    private fun oneEditApart(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            if (a.length >= b.length) i++
            if (b.length >= a.length) j++
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
