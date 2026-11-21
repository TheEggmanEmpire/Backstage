package com.eggman.backstage.entry

/**
 * Turns an incoming intent into a UI-only action. Nothing here ever starts a task: external
 * entry points (deep link, share, PROCESS_TEXT, ASSIST) only prefill the composer or arm the
 * microphone, and the user confirms by pressing Send.
 */
object EntryRouter {

    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
    const val ACTION_ASSIST = "android.intent.action.ASSIST"
    const val ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND"
    const val ACTION_MAIN = "android.intent.action.MAIN"

    /** Internal (non-exported meaning) extras used by our own tile/notification/bubble intents. */
    const val EXTRA_VOICE = "com.eggman.backstage.extra.VOICE"
    /** Per-process secret that marks launches from our own components (tile, widgets); other apps cannot know it. */
    const val EXTRA_TRUST = "com.eggman.backstage.extra.TRUST"
    val trustToken: String = java.util.UUID.randomUUID().toString()
    const val EXTRA_SCREEN = "com.eggman.backstage.extra.SCREEN"

    const val MAX_PREFILL = 4000

    enum class Screen { CHAT, HISTORY, DESKTOP, SCHEDULES, SETTINGS, SETUP, LICENSES, SKILLS }

    sealed interface Action {
        data object None : Action
        data class Prefill(val text: String, val attachment: Pair<String, String>? = null) : Action
        /** Arm the microphone and submit what is heard (our own tile only). */
        data object Voice : Action
        /** Arm the microphone but only fill the composer: the user presses Send (ASSIST, deep links, other apps). */
        data object VoiceDraft : Action
        data class Open(val screen: Screen) : Action
    }

    data class Incoming(
        val action: String?,
        val dataUri: String? = null,
        val type: String? = null,
        val sendText: String? = null,
        val sendSubject: String? = null,
        val processText: String? = null,
        val voiceExtra: Boolean = false,
        val screenExtra: String? = null,
        /** True when the intent came from our own process (tile/notification/bubble). */
        val internal: Boolean = false,
    )

    fun route(i: Incoming): Action = when (i.action) {
        ACTION_ASSIST, ACTION_VOICE_COMMAND -> Action.VoiceDraft
        ACTION_SEND -> {
            val text = listOfNotNull(i.sendSubject?.takeIf { it.isNotBlank() }, i.sendText?.takeIf { it.isNotBlank() })
                .joinToString("\n").trim()
            if (text.isEmpty()) Action.None
            else Action.Prefill(
                "Help me with this:\n" + text.take(MAX_PREFILL),
                "shared_text" to text.take(MAX_PREFILL),
            )
        }
        ACTION_PROCESS_TEXT -> i.processText?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { Action.Prefill("“${it.take(MAX_PREFILL)}”\n", "selected_text" to it.take(MAX_PREFILL)) } ?: Action.None
        ACTION_VIEW -> routeDeepLink(i.dataUri)
        else -> when {
            i.internal && i.voiceExtra -> Action.Voice
            i.internal && i.screenExtra != null -> screenOf(i.screenExtra)?.let { Action.Open(it) } ?: Action.None
            else -> Action.None
        }
    }

    /**
     * backstage://agent                     → chat
     * backstage://agent/<screen>            → chat|history|desktop|schedules|settings|setup|licenses
     * backstage://agent?prompt=<text>       → prefill only (never auto-run)
     * backstage://agent/voice               → arm the microphone
   */
    fun routeDeepLink(uri: String?): Action {
        if (uri == null) return Action.Open(Screen.CHAT)
        val m = Regex("^backstage://agent(/[^?#]*)?(\\?[^#]*)?").find(uri) ?: return Action.None
        val path = m.groupValues[1].trim('/').lowercase()
        val query = m.groupValues[2].removePrefix("?")
        val prompt = query.split('&').firstNotNullOfOrNull { kv ->
            val k = kv.substringBefore('=')
            if (k == "prompt" || k == "q") decode(kv.substringAfter('=', "")) else null
        }?.trim()
        return when {
            path == "voice" -> Action.VoiceDraft
            !prompt.isNullOrEmpty() -> Action.Prefill(prompt.take(MAX_PREFILL))
            path.isEmpty() -> Action.Open(Screen.CHAT)
            else -> screenOf(path)?.let { Action.Open(it) } ?: Action.Open(Screen.CHAT)
        }
    }

    /** Whether speech captured for [action] is submitted without a tap on Send (our own tile/widgets only). */
    fun autoSubmits(action: Action): Boolean = action == Action.Voice

    private fun screenOf(name: String): Screen? = Screen.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun decode(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
