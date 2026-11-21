package com.eggman.backstage.ui.settings

import com.eggman.backstage.automation.BroadcastApi

/** In-app documentation for the automation surfaces (original text). */
object AutomationDocs {

    fun adbExample(pkg: String, token: String) =
        "adb shell am broadcast -a ${BroadcastApi.ACTION_RUN} -p $pkg " +
            "--es ${BroadcastApi.EXTRA_TOKEN} $token --es ${BroadcastApi.EXTRA_TASK} \"Turn on airplane mode\" " +
            "--es ${BroadcastApi.EXTRA_TARGET} phone"

    fun broadcastReference(pkg: String): String = """
        Send an explicit broadcast to package $pkg.

        Start a task — action ${BroadcastApi.ACTION_RUN}
          ${BroadcastApi.EXTRA_TOKEN}         (string, required) access token from Settings → MCP server
          ${BroadcastApi.EXTRA_TASK}          (string, required) what the agent should do
          ${BroadcastApi.EXTRA_TARGET}        (string) auto | desktop | phone
          ${BroadcastApi.EXTRA_PLAN_MODE}     (boolean) override Plan Mode
          ${BroadcastApi.EXTRA_TIMEOUT_SEC}   (int) 10…${BroadcastApi.MAX_TIMEOUT_SEC}, default ${BroadcastApi.DEFAULT_TIMEOUT_SEC}
          ${BroadcastApi.EXTRA_REQUEST_ID}    (string) echoed back in the replies
          ${BroadcastApi.EXTRA_REPLY_PACKAGE} (string) deliver replies only to this package
          ${BroadcastApi.EXTRA_ALLOW_IRREVERSIBLE} (boolean) let the task send, pay or delete without a confirmation.
              Words like "without asking" in the task text are ignored here, because a macro may put other
              people's text (an SMS, a notification) into the task.

        Stop — action ${BroadcastApi.ACTION_STOP} (${BroadcastApi.EXTRA_TOKEN}, optional ${BroadcastApi.EXTRA_TASK_ID})
        Answer a question — action ${BroadcastApi.ACTION_ANSWER} (${BroadcastApi.EXTRA_TOKEN}, ${BroadcastApi.EXTRA_ANSWER})

        Replies (broadcasts you can catch with an "Intent received" trigger):
          ${BroadcastApi.ACTION_STARTED}  ${BroadcastApi.EXTRA_TASK_ID}, ${BroadcastApi.EXTRA_REQUEST_ID}
          ${BroadcastApi.ACTION_RESULT}   ${BroadcastApi.EXTRA_TASK_ID}, ${BroadcastApi.EXTRA_REQUEST_ID}, ${BroadcastApi.EXTRA_STATUS}, ${BroadcastApi.EXTRA_RESULT}, ${BroadcastApi.EXTRA_STEPS}, ${BroadcastApi.EXTRA_ERROR}

        Automation tasks never wait for you: questions time out and confirmations are denied.
    """.trimIndent()

    const val PLUGIN_HELP = "Tasker: Action → Plugin → Backstage. MacroDroid: Action → Plugins (Locale/Tasker) → Backstage. " +
        "Type the task (Tasker %variables are allowed), choose where it runs and whether to wait. When waiting, " +
        "Tasker receives %success, %result and %error plus %bs_status, %bs_task_id and %bs_steps. The timeout is in minutes " +
        "and may be a variable too. The action itself always succeeds once the task ran, so check %success in the next step. " +
        "Only the words you typed yourself can skip confirmations (\"…without asking\"); text that arrives in a %variable never does."

    const val PLUGIN_SECURITY = "Saved plugin actions are signed with a key that never leaves this phone, so other apps " +
        "cannot trigger Backstage with forged actions. Resetting the key disables every saved action until you open and save it again."
}
