package com.eggman.backstage.tools

/**
 * Safety net behind the prompt rule "confirm before irreversible actions": when the model is about
 * to tap something that looks like send/pay/buy/delete/post without a recent approved
 * confirm_action, the tap is refused with an explanation so the model asks first. (Tools flagged
 * [Tool.irreversible] are not handled here: ToolRegistry asks before each of their calls, whatever
 * this guard's setting.)
 *
 * Pure logic; the agent loop resolves the label of the element being acted on.
 */
class SensitiveActionGuard(
    private val enabled: Boolean,
    /** The request itself authorised irreversible actions (e.g. "send it without asking"). */
    private val preAuthorized: Boolean,
    private val approvalWindowSteps: Int = 8,
) {
    private var approvedAtStep = Int.MIN_VALUE / 2

    fun noteApproval(step: Int) { approvedAtStep = step }

    fun recentlyApproved(step: Int) = step - approvedAtStep <= approvalWindowSteps

    /** The kind of irreversible action this call looks like, or null when it may proceed without approval. */
    fun kind(step: Int, tool: String, label: String?, submitText: Boolean = false, fieldHint: String? = null): String? {
        if (!enabled || preAuthorized || recentlyApproved(step)) return null
        return when {
            tool in setOf("tap", "long_press", "desktop_right_click") && label != null -> classify(label)
            tool == "type_text" && submitText -> classifySubmit(fieldHint)
            // sms_send, phone_call and delete_calendar_event are Tool.irreversible: ToolRegistry asks for every call.
            tool == "delete_scheduled_task" -> "delete"
            else -> null
        }
    }

    /** Returns a refusal message, or null when the action may proceed. */
    fun check(step: Int, tool: String, label: String?, submitText: Boolean = false, fieldHint: String? = null): String? {
        val kind = kind(step, tool, label, submitText, fieldHint) ?: return null
        return "Blocked: \"${label ?: fieldHint ?: tool}\" looks like an irreversible $kind action. Call confirm_action describing exactly what will happen, then repeat the action if approved."
    }

    /**
     * the approval question the loop asks the user directly (no extra model turns), worded from the tool and
     * the element it acts on, never from model prose alone; null when no approval is needed.
   */
    fun approvalQuestion(step: Int, tool: String, label: String?, submitText: Boolean, fieldHint: String?, typed: String?, app: String?): String? {
        val kind = kind(step, tool, label, submitText, fieldHint) ?: return null
        val where = app?.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()
        val what = when (tool) {
            "tap" -> "Tap “${label.orEmpty().take(80)}”$where"
            "long_press" -> "Long-press “${label.orEmpty().take(80)}”$where"
            "desktop_right_click" -> "Right-click “${label.orEmpty().take(80)}”$where"
            "type_text" -> "Submit the field$where"
            else -> "Run $tool$where"
        }
        return "$what? This looks like an irreversible $kind action."
    }

    companion object {
        /** Whole-word match that also works for non-ASCII letters (Java's \b is ASCII-only). */
        private fun words(alternatives: String) = Regex("(?<![\\p{L}\\p{N}])($alternatives)(?![\\p{L}\\p{N}])")

        private val rules: List<Pair<String, Regex>> = listOf(
            "account" to words("account verwijderen|account wissen|conta excluir|excluir conta|apagar conta|eliminar conta|eliminar cuenta|elimina account|supprimer (le )?compte|konto löschen|delete account|close account"),
            "send" to words("send|send now|post|publish|tweet|share now|submit|absenden|senden|posten|veröffentlichen|envoyer|publier|enviar|publicar|invia|pubblica|verzenden|versturen"),
            "payment" to words("pay|pay now|buy|buy now|purchase|place order|order now|checkout|check out|confirm (and )?pay|subscribe|book now|reserve|transfer|zahlungspflichtig bestellen|jetzt kaufen|kaufen|bezahlen|bestellen|buchen|überweisen|payer|acheter|commander|pagar|comprar|reservar|paga|acquista|betalen|kopen|bestellen|nu kopen|comprar agora|finalizar compra|finalizar pedido"),
            "delete" to words("delete|delete all|remove|erase|uninstall|clear data|factory reset|reset|discard|löschen|entfernen|deinstallieren|zurücksetzen|supprimer|effacer|eliminar|borrar|elimina|cancella|excluir|apagar|verwijderen"),
            "account" to words("sign out|log out|deactivate|close account|delete account|abmelden|konto löschen|se déconnecter|cerrar sesión"),
            "call" to words("call|dial|video call|anrufen|appeler|llamar|ligar|bellen"),
        )

        // CJK and Devanagari labels commonly attach particles/suffixes without word separators.
        private val substringRules = listOf(
            "account" to Regex("删除账户|删除账号|注销账户|注销账号|アカウント.{0,3}削除|アカウント.{0,3}閉鎖|계정\\s*삭제|계정\\s*탈퇴|खाता\\s*हटाएं|खाता\\s*हटाएँ|खाता\\s*मिटाएं"),
            "send" to Regex("送信|发送|전송|보내기|भेजें|भेजें अभी"),
            "delete" to Regex("(?:送信)?履歴を削除|削除|删除|삭제|हटाएं|हटाएँ|मिटाएं"),
            "payment" to Regex("支付|购买|立即购买|提交订单|付款|今すぐ買う|買う|購入|支払う|支払い|注文を確定|今すぐ購入(?:する)?|결제|구매|주문하기|भुगतान|अभी खरीदें|खरीदें"),
        )

        private val imperativeRules = substringRules.associate { (kind, rule) ->
            kind to Regex("(?:${rule.pattern})(?:する|します|하기|\\s*करें)?")
        }

        private val navigationOrNarrative = Regex("履歴|履歷|내역|记录|紀錄|記録|历史|歷史|方式|方法|방법|设置|設定|설정|已[经經]?|了$|しました|済み")

        /** Category of an irreversible action suggested by a button label, or null. */
        fun classify(label: String): String? {
            val l = label.lowercase().trim()
            if (l.isEmpty() || l.length > 60) return null // long labels are content (list rows, messages), not buttons
            val letters = l.count { it.isLetterOrDigit() }
            substringRules.firstOrNull { (kind, rule) ->
                // History/method words disqualify payment navigation, not destructive actions.
                val paymentNavigation = kind == "payment" && navigationOrNarrative.containsMatchIn(l)
                val shortLabel = letters <= 6
                val knownImperative = letters <= 16 && imperativeRules.getValue(kind).matches(l)
                !paymentNavigation && (shortLabel || knownImperative) && rule.containsMatchIn(l)
            }?.let { return it.first }
            return rules.firstOrNull { it.second.containsMatchIn(l) }?.first
        }

        private val messageField = Regex("(message|nachricht|reply|antwort|comment|kommentar|tweet|post|chat|mensaje|messaggio)")

        fun classifySubmit(fieldHint: String?): String? =
            if (fieldHint != null && messageField.containsMatchIn(fieldHint.lowercase())) "send" else null

        private val preAuth = Regex(
            "(without (asking|confirmation|confirming)|don'?t ask|no need to (ask|confirm)|skip confirmation|ohne (nachfrage|rückfrage|zu fragen|bestätigung)|auto[- ]?confirm)",
            RegexOption.IGNORE_CASE,
        )

        /** True when the user's request explicitly waives confirmations. */
        fun requestWaivesConfirmation(prompt: String) = preAuth.containsMatchIn(prompt)
    }
}
