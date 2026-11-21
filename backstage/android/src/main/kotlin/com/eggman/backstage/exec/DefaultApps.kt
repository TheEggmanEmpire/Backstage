package com.eggman.backstage.exec

/**
 * The user's default apps.
 *
 * Generic requests ("open the browser", "take a photo", "open maps") must use the app Android itself would use, never
 * a package the model guesses. [DefaultAppsResolver] is pure: it decides from what a [DefaultAppsPlatform] reports
 * (role holders, the resolved default of each probe intent, every handler). The Android side is
 * [AndroidDefaultApps]; the cache that serves the prompt, the default_apps tool and launch_app is [DefaultAppsCache].
 */
enum class AppRole(val key: String, val title: String, val aliases: List<String>) {
    BROWSER("browser", "web browser", listOf("browser", "web browser", "internet browser", "internet", "web")),
    HOME("home", "home screen (launcher)", listOf("launcher", "home screen", "home app")),
    DIALER("dialer", "phone calls (dialer)", listOf("dialer", "dialler", "phone app", "phone", "calls")),
    SMS("sms", "text messages (SMS)", listOf("sms", "sms app", "messages", "messaging", "text messages")),
    ASSISTANT("assistant", "digital assistant", listOf("assistant", "digital assistant", "voice assistant")),
    CAMERA("camera", "camera", listOf("camera", "camera app")),
    EMAIL("email", "email", listOf("email", "e-mail", "mail", "email app", "mail app")),
    MAPS("maps", "maps and navigation", listOf("maps", "map", "maps app", "navigation")),
    MUSIC("music", "music player", listOf("music", "music player", "audio player")),
    VIDEO("video", "video player", listOf("video", "video player", "videos")),
    FILES("files", "files (file manager)", listOf("files", "file manager", "files app")),
    GALLERY("gallery", "gallery (photos and pictures)", listOf("gallery", "photo gallery", "pictures", "photo viewer")),
    CALENDAR("calendar", "calendar", listOf("calendar", "calendar app")),
    CONTACTS("contacts", "contacts", listOf("contacts", "address book", "contacts app")),
    CLOCK("clock", "clock and alarms", listOf("clock", "alarm clock", "clock app")),
    CALCULATOR("calculator", "calculator", listOf("calculator", "calculator app")),
    NOTES("notes", "notes", listOf("notes", "notes app", "notepad", "note taking")),
    ;

    companion object {
        private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        /** The role a generic word names ("browser", "the camera", "maps"); null for an app name the user named. */
        fun forQuery(query: String): AppRole? {
            val q = norm(query).removePrefix("the ").removePrefix("my ").removePrefix("default ").trim()
            return values().firstOrNull { r -> r.key == q || r.aliases.any { norm(it) == q } }
        }

        fun ofKey(key: String): AppRole? = values().firstOrNull { it.key == key.trim().lowercase() }

        /** The role whose default app opens links with [scheme] (open_url when Android would show a chooser). */
        fun forScheme(scheme: String): AppRole? = when (scheme.lowercase()) {
            "http", "https" -> BROWSER
            "mailto" -> EMAIL
            "geo", "google.navigation" -> MAPS
            "tel" -> DIALER
            "sms", "smsto", "mms", "mmsto" -> SMS
            else -> null
        }
    }
}

/**
 * One way to ask Android for a role's app: an Intent described as data (so the resolver stays pure). [defaultOnly]:
 * resolve like startActivity of an implicit intent (CATEGORY_DEFAULT). Probes without a default ([defaultOnly] false,
 * the MAIN + APP_* category ones) only contribute candidates.
 */
data class IntentProbe(
    val action: String,
    val data: String? = null,
    val type: String? = null,
    val categories: List<String> = emptyList(),
    val defaultOnly: Boolean = true,
    /** Minimum API level for this probe (e.g. APP_FILES: 29, CREATE_NOTE: 34). */
    val minSdk: Int = 0,
)

/** How a role's app is found. */
enum class DefaultHow {
    /** The user's (or the system's) default for that role. */
    DEFAULT,
    /** Only one installed app can do it. */
    ONLY,
    /** No default is set (Android would ask with a chooser): the best of several is picked. */
    CHOSEN,
}

data class DefaultApp(
    val role: AppRole,
    val packageName: String,
    val label: String,
    val how: DefaultHow,
    /** Other installed apps for the role (labels), for CHOSEN. */
    val others: List<String> = emptyList(),
) {
    /** One line for the prompt and the default_apps tool. */
    fun line(): String = "- ${role.key} (${role.title}): $label ($packageName)" + when (how) {
        DefaultHow.DEFAULT -> ""
        DefaultHow.ONLY -> ", the only one installed"
        DefaultHow.CHOSEN -> "; no default is set, picked as the best match" +
            if (others.isNotEmpty()) " (also installed: ${others.take(3).joinToString()})" else ""
    }
}

/** What the platform knows; the Android implementation is [AndroidDefaultApps]. */
interface DefaultAppsPlatform {
    val sdkInt: Int

    /** The holder of a role Android exposes directly (dialer, SMS, assistant), or null. */
    fun roleHolder(role: AppRole): String?

    /** The package Android would start for [probe] without asking, or null (no handler, or a chooser). */
    fun resolvedDefault(probe: IntentProbe): String?

    /** Every package that can handle [probe]. */
    fun handlers(probe: IntentProbe): List<String>

    fun label(packageName: String): String?

    /** Preinstalled with the system image (FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP). */
    fun isSystem(packageName: String): Boolean

    /** The device maker's own apps are preferred when no default is set (e.g. "samsung"). */
    val manufacturer: String get() = ""
}

object DefaultAppsResolver {
    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val ACTION_MAIN = "android.intent.action.MAIN"
    private const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"
    private fun appCategory(name: String) = IntentProbe(ACTION_MAIN, categories = listOf("android.intent.category.APP_$name"), defaultOnly = false)

    /** The intents asked for each role, most specific first. Every one of them is declared in the manifest's queries. */
    val probes: Map<AppRole, List<IntentProbe>> = mapOf(
        AppRole.BROWSER to listOf(
            IntentProbe(ACTION_VIEW, data = "https://example.com/", categories = listOf(CATEGORY_BROWSABLE)),
            IntentProbe(ACTION_VIEW, data = "http://example.com/", categories = listOf(CATEGORY_BROWSABLE)),
            appCategory("BROWSER"),
        ),
        AppRole.HOME to listOf(IntentProbe(ACTION_MAIN, categories = listOf("android.intent.category.HOME"))),
        AppRole.DIALER to listOf(IntentProbe("android.intent.action.DIAL", data = "tel:")),
        AppRole.SMS to listOf(IntentProbe("android.intent.action.SENDTO", data = "smsto:"), appCategory("MESSAGING")),
        AppRole.ASSISTANT to listOf(IntentProbe("android.intent.action.ASSIST"), IntentProbe("android.intent.action.VOICE_COMMAND")),
        AppRole.CAMERA to listOf(
            IntentProbe("android.media.action.IMAGE_CAPTURE"),
            IntentProbe("android.media.action.STILL_IMAGE_CAMERA"),
        ),
        AppRole.EMAIL to listOf(IntentProbe("android.intent.action.SENDTO", data = "mailto:"), appCategory("EMAIL")),
        AppRole.MAPS to listOf(IntentProbe(ACTION_VIEW, data = "geo:0,0?q=0,0"), appCategory("MAPS")),
        AppRole.MUSIC to listOf(IntentProbe(ACTION_VIEW, data = "content://media/external/audio/media/1", type = "audio/*"), appCategory("MUSIC")),
        AppRole.VIDEO to listOf(IntentProbe(ACTION_VIEW, data = "content://media/external/video/media/1", type = "video/*")),
        AppRole.FILES to listOf(appCategory("FILES").copy(minSdk = 29)),
        AppRole.GALLERY to listOf(IntentProbe(ACTION_VIEW, data = "content://media/external/images/media/1", type = "image/*"), appCategory("GALLERY")),
        AppRole.CALENDAR to listOf(IntentProbe(ACTION_VIEW, data = "content://com.android.calendar/time/0"), appCategory("CALENDAR")),
        AppRole.CONTACTS to listOf(IntentProbe(ACTION_VIEW, data = "content://com.android.contacts/contacts"), appCategory("CONTACTS")),
        AppRole.CLOCK to listOf(IntentProbe("android.intent.action.SHOW_ALARMS")),
        AppRole.CALCULATOR to listOf(appCategory("CALCULATOR")),
        AppRole.NOTES to listOf(IntentProbe("android.intent.action.CREATE_NOTE", minSdk = 34)),
    )

    /** Packages that are never a role's app: ours and the system's chooser/resolver. */
    private val NEVER = setOf("android", "com.android.intentresolver", "com.android.internal.app")

    fun resolve(platform: DefaultAppsPlatform, ownPackages: Set<String> = emptySet()): List<DefaultApp> =
        AppRole.values().mapNotNull { role -> runCatching { resolveRole(platform, role, ownPackages) }.getOrNull() }

    fun resolveRole(platform: DefaultAppsPlatform, role: AppRole, ownPackages: Set<String> = emptySet()): DefaultApp? {
        fun usable(p: String?) = p != null && p.isNotBlank() && p !in ownPackages && p !in NEVER
        fun app(p: String, how: DefaultHow, others: List<String> = emptyList()) =
            DefaultApp(role, p, platform.label(p) ?: p, how, others)

        platform.roleHolder(role)?.takeIf(::usable)?.let { return app(it, DefaultHow.DEFAULT) }
        val probes = probes[role].orEmpty().filter { platform.sdkInt >= it.minSdk }
        val counts = LinkedHashMap<String, Int>()
        for (probe in probes) {
            val handlers = platform.handlers(probe).filter(::usable).distinct()
            if (probe.defaultOnly) {
                // A default is only a default when it is one of the real handlers (not the chooser).
                val d = platform.resolvedDefault(probe)
                if (usable(d) && d in handlers) return app(d!!, DefaultHow.DEFAULT)
            }
            handlers.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        if (counts.isEmpty()) return null
        if (counts.size == 1) return app(counts.keys.first(), DefaultHow.ONLY)
        val ranked = rank(platform, counts)
        return app(ranked.first(), DefaultHow.CHOSEN, ranked.drop(1).map { platform.label(it) ?: it })
    }

    /**
     * No default set: the device maker's own preinstalled app first (Samsung's on a Samsung phone), then any other
     * preinstalled app, then the rest; within a group the app that handles more of the role's probes, then by label.
   */
    fun rank(platform: DefaultAppsPlatform, candidates: Map<String, Int>): List<String> {
        val maker = platform.manufacturer.lowercase()
        fun group(p: String): Int {
            val system = platform.isSystem(p)
            val own = isMakerPackage(p, maker)
            return when {
                system && own -> 0
                system -> 1
                else -> 2
            }
        }
        return candidates.keys.sortedWith(
            compareBy<String> { group(it) }.thenByDescending { candidates[it] ?: 0 }.thenBy { (platform.label(it) ?: it).lowercase() },
        )
    }

    /** Packages of the phone's maker: Samsung's are com.sec.* and com.samsung.*; others carry the maker's name. */
    fun isMakerPackage(packageName: String, manufacturer: String): Boolean {
        val p = packageName.lowercase()
        if (manufacturer.contains("samsung") || manufacturer.isBlank()) {
            if (p.startsWith("com.sec.") || p.startsWith("com.samsung.")) return true
        }
        val m = manufacturer.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        return m.isNotEmpty() && p.split('.').any { it == m }
    }

    /**
     * open_url when Android has no default for this link ([resolved] is not one of [handlers], i.e. it would show a
     * chooser): the role's app from [defaults] if it handles the link, else the best handler; null = leave the link to
     * Android (a real default or an app that verified the link).
   */
    fun pinForLink(scheme: String, resolved: String?, handlers: List<String>, defaults: List<DefaultApp>, platform: DefaultAppsPlatform?, ownPackages: Set<String>): String? {
        val real = handlers.filter { it !in ownPackages && it !in NEVER }.distinct()
        if (real.isEmpty()) return null
        if (resolved != null && resolved in real) return null
        val role = AppRole.forScheme(scheme)
        defaults.firstOrNull { it.role == role }?.packageName?.takeIf { it in real }?.let { return it }
        if (real.size == 1) return real.first()
        return platform?.let { rank(it, real.associateWith { 1 }).first() } ?: real.first()
    }

    /** The "Default apps" prompt section's lines (empty when nothing could be resolved). */
    fun promptLines(apps: List<DefaultApp>): String = apps.sortedBy { it.role.ordinal }.joinToString("\n") { it.line() }
}
