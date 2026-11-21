package com.eggman.backstage.automation

import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Constant-time secret comparison (no early exit on the first differing byte). */
object TokenCheck {
    fun matches(expected: String?, provided: String?): Boolean {
        if (expected.isNullOrEmpty() || provided.isNullOrEmpty()) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), provided.trim().toByteArray(Charsets.UTF_8))
    }
}

fun parseTarget(value: String?): Target = when (value?.trim()?.lowercase()) {
    "desktop", "dex", "background" -> Target.DESKTOP
    "phone", "screen", "foreground" -> Target.PHONE
    else -> Target.AUTO
}

/** Public explicit-broadcast API (documented in Settings → Automation). */
object BroadcastApi {
    const val ACTION_RUN = "com.eggman.backstage.action.RUN_TASK"
    const val ACTION_STOP = "com.eggman.backstage.action.STOP_TASK"
    const val ACTION_ANSWER = "com.eggman.backstage.action.ANSWER_TASK"
    const val ACTION_STARTED = "com.eggman.backstage.action.TASK_STARTED"
    const val ACTION_RESULT = "com.eggman.backstage.action.TASK_RESULT"

    const val EXTRA_TOKEN = "token"
    const val EXTRA_TASK = "task"
    const val EXTRA_TARGET = "target"
    const val EXTRA_PLAN_MODE = "plan_mode"
    const val EXTRA_TIMEOUT_SEC = "timeout_sec"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_REPLY_PACKAGE = "reply_package"
    const val EXTRA_ANSWER = "answer"
    const val EXTRA_TASK_ID = "task_id"
    /** Boolean: the caller lets this task send, pay or delete without asking (the task text itself never can). */
    const val EXTRA_ALLOW_IRREVERSIBLE = "allow_irreversible"
    const val EXTRA_STATUS = "status"
    const val EXTRA_RESULT = "result"
    const val EXTRA_STEPS = "steps"
    const val EXTRA_ERROR = "error"

    /** The only extras a request may carry; the receiver never unparcels anything else (hostile apps send anything). */
    val REQUEST_KEYS = listOf(
        EXTRA_TOKEN, EXTRA_TASK, EXTRA_TARGET, EXTRA_PLAN_MODE, EXTRA_TIMEOUT_SEC, EXTRA_REQUEST_ID, EXTRA_REPLY_PACKAGE,
        EXTRA_ANSWER, EXTRA_TASK_ID, EXTRA_ALLOW_IRREVERSIBLE,
    )

    const val MAX_PROMPT = 4000
    const val DEFAULT_TIMEOUT_SEC = 600
    const val MAX_TIMEOUT_SEC = 3600

    sealed interface Command {
        data class Run(
            val prompt: String,
            val target: Target,
            val planMode: Boolean?,
            val timeoutSec: Int,
            val requestId: String?,
            val replyPackage: String?,
            /** Explicit [EXTRA_ALLOW_IRREVERSIBLE]; "without asking" in the task text does not count. */
            val allowIrreversible: Boolean = false,
        ) : Command
        data class Stop(val taskId: String?) : Command
        data class Answer(val text: String) : Command
        data class Rejected(val reason: String, val authFailure: Boolean = false) : Command
    }

    /** Parse an incoming broadcast. [extras] is a plain view of the Intent extras. */
    fun parse(action: String?, extras: Map<String, Any?>, expectedToken: String): Command {
        if (action !in setOf(ACTION_RUN, ACTION_STOP, ACTION_ANSWER)) return Command.Rejected("unknown action")
        if (!TokenCheck.matches(expectedToken, extras[EXTRA_TOKEN] as? String)) {
            return Command.Rejected("missing or wrong token", authFailure = true)
        }
        return when (action) {
            ACTION_STOP -> Command.Stop((extras[EXTRA_TASK_ID] as? String)?.takeIf { it.isNotBlank() })
            ACTION_ANSWER -> (extras[EXTRA_ANSWER] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { Command.Answer(it) } ?: Command.Rejected("missing answer")
            else -> {
                val prompt = (extras[EXTRA_TASK] as? String)?.trim().orEmpty()
                if (prompt.isEmpty()) return Command.Rejected("missing task")
                if (prompt.length > MAX_PROMPT) return Command.Rejected("task too long")
                val timeout = when (val t = extras[EXTRA_TIMEOUT_SEC]) {
                    is Int -> t
                    is Long -> t.toInt()
                    is String -> t.toIntOrNull()
                    else -> null
                } ?: DEFAULT_TIMEOUT_SEC
                val plan = when (val p = extras[EXTRA_PLAN_MODE]) {
                    is Boolean -> p
                    is String -> p.toBooleanStrictOrNull()
                    else -> null
                }
                Command.Run(
                    prompt = prompt,
                    target = parseTarget(extras[EXTRA_TARGET] as? String),
                    planMode = plan,
                    timeoutSec = timeout.coerceIn(10, MAX_TIMEOUT_SEC),
                    requestId = (extras[EXTRA_REQUEST_ID] as? String)?.take(200),
                    replyPackage = replyPackage(extras),
                    allowIrreversible = when (val a = extras[EXTRA_ALLOW_IRREVERSIBLE]) {
                        is Boolean -> a
                        is String -> a.trim().toBooleanStrictOrNull() == true
                        else -> false
                    },
                )
            }
        }
    }

    /** The validated reply_package extra (replies go only to that package), or null. */
    fun replyPackage(extras: Map<String, Any?>): String? =
        (extras[EXTRA_REPLY_PACKAGE] as? String)?.trim()?.takeIf { PACKAGE_RE.matches(it) }

    private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** [EXTRA_STATUS] of a start refused because another task is running (never queued silently). */
    const val STATUS_BUSY = "BUSY"

    /** Result extras of a refused start: status BUSY, error "busy". */
    fun busyExtras(requestId: String?): Map<String, Any?> = mapOf(
        EXTRA_REQUEST_ID to requestId,
        EXTRA_TASK_ID to null,
        EXTRA_STATUS to STATUS_BUSY,
        EXTRA_STEPS to 0,
        EXTRA_ERROR to PluginProtocol.BUSY,
    )

    /** Extras of the result broadcast. */
    fun resultExtras(snapshot: TaskSnapshot?, requestId: String?, includeText: Boolean, error: String? = null): Map<String, Any?> =
        buildMap {
            put(EXTRA_REQUEST_ID, requestId)
            put(EXTRA_TASK_ID, snapshot?.id)
            put(EXTRA_STATUS, (snapshot?.status ?: TaskStatus.FAILED).name)
            put(EXTRA_STEPS, snapshot?.step ?: 0)
            if (includeText) put(EXTRA_RESULT, snapshot?.result)
            if (error != null) put(EXTRA_ERROR, error)
        }
}


/** Locale (two forty four a.m.) plugin API + Tasker extensions, implemented from the public protocol docs. */
object PluginProtocol {
    // Locale host API
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    const val EXTRA_BREADCRUMB = "com.twofortyfouram.locale.intent.extra.BREADCRUMB"

    // Tasker plugin extensions (synchronous settings + return variables)
    const val EXTRA_REQUESTED_TIMEOUT = "net.dinglisch.android.tasker.extras.REQUESTED_TIMEOUT"
    const val EXTRA_COMPLETION_INTENT = "net.dinglisch.android.tasker.extras.COMPLETION_INTENT"
    const val EXTRA_RESULT_CODE = "net.dinglisch.android.tasker.extras.RESULT_CODE"
    const val EXTRA_VARIABLES_BUNDLE = "net.dinglisch.android.tasker.extras.VARIABLES"
    const val BUNDLE_KEY_VARIABLE_REPLACE_KEYS = "net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS"
    const val EXTRA_RELEVANT_VARIABLES = "net.dinglisch.android.tasker.RELEVANT_VARIABLES"

    const val RESULT_CODE_OK = -1            // Activity.RESULT_OK
    const val RESULT_CODE_FAILED = 2         // RESULT_FIRST_USER + 1
    const val RESULT_CODE_PENDING = 3        // RESULT_FIRST_USER + 2
    const val TIMEOUT_MAX_MS = 3_599_000

    /**
     * Variables returned to Tasker (lower case = task-local). %success/%result/%error are the
     * de-facto names automation users already know; %bs_* add Backstage details.
   */
    const val VAR_SUCCESS = "%success"
    const val VAR_RESULT = "%result"
    const val VAR_ERROR = "%error"
    const val VAR_TASK_ID = "%bs_task_id"
    const val VAR_STATUS = "%bs_status"
    const val VAR_STEPS = "%bs_steps"
    const val VAR_ERRMSG = "%errmsg"

    val relevantVariables = arrayOf(
        "$VAR_SUCCESS\nSuccess\ntrue when the agent finished the task",
        "$VAR_RESULT\nResult\nThe agent's final answer or summary",
        "$VAR_ERROR\nError\nWhy the task failed (empty on success)",
        "$VAR_STATUS\nStatus\nSUCCEEDED, FAILED, CANCELLED, TIMEOUT or BUSY",
        "$VAR_TASK_ID\nTask id\nIdentifier of the Backstage task",
        "$VAR_STEPS\nSteps\nNumber of agent steps used",
    )

    /**
     * Variable names keep their leading % inside the variables bundle (TaskerPlugin protocol: "%name").
     * [timeoutMin] names the limit in the timeout error.
   */
    fun resultVariables(snapshot: TaskSnapshot?, timedOut: Boolean, timeoutMin: Int? = null): Map<String, String> = buildMap {
        val ok = !timedOut && snapshot?.status == TaskStatus.SUCCEEDED
        val error = when {
            ok -> ""
            timedOut -> if (timeoutMin != null) "Backstage task timed out after $timeoutMin min" else "Backstage task timed out"
            snapshot == null -> "Backstage agent unavailable"
            else -> snapshot.result ?: "Task ${snapshot.status.name.lowercase()}"
        }
        put(VAR_SUCCESS, ok.toString())
        put(VAR_RESULT, if (ok) snapshot?.result.orEmpty() else "")
        put(VAR_ERROR, error)
        put(VAR_TASK_ID, snapshot?.id.orEmpty())
        put(VAR_STATUS, if (timedOut) "TIMEOUT" else (snapshot?.status ?: TaskStatus.FAILED).name)
        put(VAR_STEPS, (snapshot?.step ?: 0).toString())
        if (!ok) put(VAR_ERRMSG, error)
    }

    /**
     * Where a Tasker completion intent may be delivered: the completion comes from an exported broadcast
     * and is not covered by the bundle signature, and it is sent with this app's identity, so it must never reach this
     * app itself (its non-exported receivers would trust it). Returns the package to pin delivery to, or null to drop it:
     *  - an explicit component or package must name another app (and both, when set, the same one);
     *  - an implicit completion is pinned to the broadcast's sender ([senderPackage], Android 14+), else dropped.
   */
    fun completionPackage(componentPackage: String?, intentPackage: String?, senderPackage: String?, ownPackage: String): String? {
        val c = componentPackage?.takeIf { it.isNotBlank() }
        val p = intentPackage?.takeIf { it.isNotBlank() }
        if (c != null && p != null && c != p) return null
        val target = c ?: p ?: senderPackage?.takeIf { it.isNotBlank() } ?: return null
        return target.takeIf { it != ownPackage }
    }

    /** Variables for a task refused because another one is running (never queued silently). */
    fun busyVariables(): Map<String, String> = mapOf(
        VAR_SUCCESS to "false",
        VAR_RESULT to "",
        VAR_ERROR to BUSY,
        VAR_ERRMSG to BUSY_MESSAGE,
        VAR_STATUS to "BUSY",
        VAR_TASK_ID to "",
        VAR_STEPS to "0",
    )

    /** %error of a refused start (a fixed token automations can compare against). */
    const val BUSY = "busy"
    const val BUSY_MESSAGE = "Backstage is already working on a task; try again when it has finished"

    /**
     * A task that ran (whatever its outcome) always reports plugin success: automations branch on %success,
     * and an error result would make Tasker skip the variables. Only rejected (forged or broken) bundles fail.
   */
    @Suppress("UNUSED_PARAMETER")
    fun resultCode(snapshot: TaskSnapshot?, timedOut: Boolean) = RESULT_CODE_OK
}

/** What one configured plugin action does. Stored in the Locale bundle. */
data class PluginConfig(
    val prompt: String,
    val target: Target = Target.AUTO,
    val planMode: Boolean? = null,
    val waitForResult: Boolean = true,
    /** How long to wait for the task, in minutes (1..[MAX_TIMEOUT_MIN]). */
    val timeoutMin: Int = DEFAULT_TIMEOUT_MIN,
    /** Show the floating bubble while the task runs and remove it afterwards. */
    val showBubble: Boolean = false,
    /** The timeout as typed in the editor when it is a Tasker variable (e.g. "%wait"); null = [timeoutMin]. */
    val timeoutTemplate: String? = null,
    /**
     * The prompt as the user saved it in the editor (covered by the signature), before Tasker replaced its %variables.
     * null = [prompt] itself (an action being edited).
   */
    val template: String? = null,
) {
    /**
     * What the user wrote themselves: the saved template with every %variable cut out, so text arriving in a variable
     * (an SMS body, the clipboard) can never waive confirmations ([com.eggman.backstage.agent.api.TaskRequest.waiverText]).
   */
    val ownText: String get() = PluginBundleCodec.literalText(template ?: prompt)

    val blurb: String
        get() = buildString {
            append(prompt.take(60))
            if (prompt.length > 60) append('…')
            if (target != Target.AUTO) append(" · ").append(target.name.lowercase())
            if (waitForResult) append(" · wait ").append(timeoutTemplate ?: "$timeoutMin min")
        }

    /** Timeout requested from Tasker: agent timeout plus margin for result delivery (the maximum when variable). */
    val requestedTimeoutMs: Int
        get() = when {
            !waitForResult -> 0
            timeoutTemplate != null -> PluginProtocol.TIMEOUT_MAX_MS
            else -> ((timeoutMin * 60 + 15) * 1000).coerceAtMost(PluginProtocol.TIMEOUT_MAX_MS)
        }

    companion object {
        const val DEFAULT_TIMEOUT_MIN = 5
        const val MAX_TIMEOUT_MIN = 59

        /** Minutes from editor/Tasker text: a number (fractions round up), below 1 → 1; null when not a number. */
        fun parseMinutes(text: String?): Int? {
            val v = text?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
            if (v.isNaN()) return null
            return kotlin.math.ceil(v).coerceIn(1.0, MAX_TIMEOUT_MIN.toDouble()).toInt()
        }
    }
}

/**
 * Encodes [PluginConfig] into bundle-compatible key/values and authenticates them with an
 * HMAC keyed by a device-local secret, so other apps cannot fire forged FIRE_SETTING bundles.
 *
 * Tasker replaces %variables in the keys we list for replacement ([K_PROMPT] and [K_TIMEOUT_MIN]); the
 * signature therefore covers the un-replaced templates ([K_TEMPLATE], [K_TIMEOUT_MIN_TEMPLATE]) and each
 * received value must match its template where only the %variable parts may differ.
 */
object PluginBundleCodec {
    /** 2 = timeout in minutes (variable-replaceable); 1 = timeout in seconds (still accepted). */
    const val VERSION = 2
    private const val P = "com.eggman.backstage.plugin."
    const val K_VERSION = P + "version"
    const val K_PROMPT = P + "prompt"
    const val K_TEMPLATE = P + "prompt_template"
    const val K_TARGET = P + "target"
    const val K_PLAN = P + "plan_mode"          // "true" | "false" | "" (= user setting)
    const val K_WAIT = P + "wait"
    /** Version 1 only. */
    const val K_TIMEOUT = P + "timeout_sec"
    const val K_TIMEOUT_MIN = P + "timeout_min"
    const val K_TIMEOUT_MIN_TEMPLATE = P + "timeout_min_template"
    const val K_BUBBLE = P + "show_bubble"
    const val K_SIG = P + "sig"

    /** Every key a received bundle may carry; receivers read only these (never unknown, possibly hostile, keys). */
    val KEYS = listOf(
        K_VERSION, K_PROMPT, K_TEMPLATE, K_TARGET, K_PLAN, K_WAIT, K_TIMEOUT, K_TIMEOUT_MIN, K_TIMEOUT_MIN_TEMPLATE,
        K_BUBBLE, K_SIG,
    )

    fun encode(config: PluginConfig, secret: String): Map<String, Any> {
        val timeoutText = config.timeoutTemplate ?: config.timeoutMin.toString()
        val base = mapOf<String, Any>(
            K_VERSION to VERSION,
            K_PROMPT to config.prompt,
            K_TEMPLATE to config.prompt,
            K_TARGET to config.target.name,
            K_PLAN to (config.planMode?.toString() ?: ""),
            K_WAIT to config.waitForResult,
            K_TIMEOUT_MIN to timeoutText,
            K_TIMEOUT_MIN_TEMPLATE to timeoutText,
            K_BUBBLE to config.showBubble,
            PluginProtocol.BUNDLE_KEY_VARIABLE_REPLACE_KEYS to "$K_PROMPT $K_TIMEOUT_MIN",
        )
        return base + (K_SIG to sign(base, secret))
    }

    sealed interface Decoded {
        data class Ok(val config: PluginConfig) : Decoded
        data class Invalid(val reason: String) : Decoded
    }

    fun decode(values: Map<String, Any?>, secret: String): Decoded {
        val version = values[K_VERSION] as? Int
        if (version != 1 && version != VERSION) return Decoded.Invalid("unsupported bundle version")
        val template = values[K_TEMPLATE] as? String ?: return Decoded.Invalid("missing prompt")
        val prompt = (values[K_PROMPT] as? String ?: template).trim()
        val sig = values[K_SIG] as? String ?: return Decoded.Invalid("unsigned bundle")
        val expected = sign(values, secret)
        if (!TokenCheck.matches(expected, sig)) return Decoded.Invalid("bad signature (re-save the action in Backstage)")
        if (!matchesTemplate(template, prompt)) return Decoded.Invalid("prompt does not match the saved action")
        if (prompt.isEmpty()) return Decoded.Invalid("empty prompt")
        val plan = (values[K_PLAN] as? String)?.toBooleanStrictOrNull()
        var timeoutTemplate: String? = null
        val timeoutMin = if (version == 1) {
            // Seconds, rounded up to whole minutes.
            ((((values[K_TIMEOUT] as? Int) ?: 300) + 59) / 60).coerceIn(1, PluginConfig.MAX_TIMEOUT_MIN)
        } else {
            val tTemplate = (values[K_TIMEOUT_MIN_TEMPLATE] as? String)?.trim().orEmpty()
            val tValue = (values[K_TIMEOUT_MIN] as? String ?: tTemplate).trim()
            if (!matchesTemplate(tTemplate, tValue)) return Decoded.Invalid("timeout does not match the saved action")
            if (VAR.containsMatchIn(tTemplate)) timeoutTemplate = tTemplate
            // A variable Tasker left unset (or set to text) falls back to the default.
            PluginConfig.parseMinutes(tValue) ?: PluginConfig.DEFAULT_TIMEOUT_MIN
        }
        return Decoded.Ok(
            PluginConfig(
                prompt = prompt.take(BroadcastApi.MAX_PROMPT),
                target = runCatching { Target.valueOf(values[K_TARGET] as String) }.getOrDefault(Target.AUTO),
                planMode = plan,
                waitForResult = values[K_WAIT] as? Boolean ?: true,
                timeoutMin = timeoutMin,
                showBubble = values[K_BUBBLE] as? Boolean ?: false,
                timeoutTemplate = timeoutTemplate,
                template = template.trim(),
            ),
        )
    }

    /** Signature over the canonical, non-replaceable fields. */
    fun sign(values: Map<String, Any?>, secret: String): String {
        val timeoutField = if (values[K_VERSION] == 1) K_TIMEOUT else K_TIMEOUT_MIN_TEMPLATE
        val canonical = listOf(
            "v${values[K_VERSION]}",
            values[K_TEMPLATE]?.toString().orEmpty(),
            values[K_TARGET]?.toString().orEmpty(),
            values[K_PLAN]?.toString().orEmpty(),
            values[K_WAIT]?.toString().orEmpty(),
            values[timeoutField]?.toString().orEmpty(),
            values[K_BUBBLE]?.toString().orEmpty(),
        ).joinToString("\u001F")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private val VAR = Regex("%[A-Za-z][A-Za-z0-9_]*(\\([^)]*\\))?")

    /** True if [actual] equals [template] with each %variable replaced by any text. */
    fun matchesTemplate(template: String, actual: String): Boolean {
        val t = template.trim()
        if (!VAR.containsMatchIn(t)) return t == actual
        val pattern = buildString {
            var last = 0
            VAR.findAll(t).forEach { m ->
                append(Regex.escape(t.substring(last, m.range.first)))
                append("[\\s\\S]*")
                last = m.range.last + 1
            }
            append(Regex.escape(t.substring(last)))
        }
        return Regex(pattern).matches(actual)
    }

    /** [template] with each %variable replaced by a line break, so words on either side of a variable never join up. */
    fun literalText(template: String): String = template.replace(VAR, "\n")

    /**
     * True when the prompt is nothing but variables: whoever controls the variable (or replays the saved bundle
     * with its own value) decides the whole task. The editor asks for an explicit confirmation.
   */
    fun isWildcard(template: String): Boolean = VAR.containsMatchIn(template) && template.replace(VAR, "").isBlank()

    /** Automation hosts we recognise when the editor is opened (others get a warning). */
    val knownHosts = mapOf(
        "net.dinglisch.android.taskerm" to "Tasker",
        "net.dinglisch.android.tasker" to "Tasker",
        "com.arlosoft.macrodroid" to "MacroDroid",
        "com.twofortyfouram.locale" to "Locale",
        "com.llamalab.automate" to "Automate",
        "ch.gridvision.ppam.androidautomagic" to "Automagic",
        "com.joaomgcd.autoapps" to "AutoApps",
    )
}
