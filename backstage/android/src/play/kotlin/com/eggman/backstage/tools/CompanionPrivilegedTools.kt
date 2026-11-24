package com.eggman.backstage.tools

import android.os.Bundle
import com.eggman.backstage.a11y.CompanionConnection
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.exec.TargetKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal class CompanionPrivilegedTool(
    override val name: String,
    private val operation: String,
    private val argument: String,
    internal val capability: Int,
    override val description: String,
    argumentDescription: String,
    private val autoApprove: () -> Boolean = { true },
    private val request: () -> ((Bundle) -> Bundle)? = { companionPrivilegeRequest(capability) },
) : Tool {
    override val agentOnly = true
    override val targets = emptySet<TargetKind>()
    override val mutating = true
    override val schema = if (operation == "shell") Schema.obj(
        argument to Schema.str(argumentDescription).put("maxLength", 1000),
        "timeout_seconds" to Schema.int("Command timeout in seconds (5–300)").put("default", 60),
        required = listOf(argument),
    ) else Schema.obj(argument to Schema.str(argumentDescription), required = listOf(argument))

    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val raw = args.string(argument)
        val command = if (operation == "shell") {
            if (raw.length > 1_000) throw ToolArgumentException("ADB command must be at most 1000 characters")
            AdbCommand.normalize(raw).also {
                if (it.length > 1_000) throw ToolArgumentException("Normalized ADB command must be at most 1000 characters")
            }
        } else raw
        if (!autoApprove() && ctx.hooks?.confirmAlways("Run ADB command as shell: `$command`?") != true) {
            return ToolResult.error("ADB command was not approved in Backstage.")
        }
        val timeoutSeconds = (args.optInt("timeout_seconds") ?: 60).coerceIn(5, 300)
        val result = runCompanionPrivilegeRequest(request, timeoutSeconds) {
            putString("operation", operation)
            putString("argument", command)
            if (operation == "shell") putInt("timeoutSeconds", timeoutSeconds)
        }
        val code = result.exitCode ?: return ToolResult.error(result.message)
        val stdoutBudget = (12_000 - result.stderr.length.coerceAtMost(6_000)).coerceAtMost(result.stdout.length)
        val data = JSONObject().put("exit_code", code)
            .put("stdout", result.stdout.takeLast(stdoutBudget))
            .put("stderr", result.stderr.takeLast(12_000 - stdoutBudget))
        return ToolResult(data.toString(), isError = code != 0, data = data)
    }

    companion object {
        fun adbCommand(
            autoApprove: () -> Boolean = { com.eggman.backstage.init.Backstage.environment.settings.adbAutoApprove },
            request: () -> ((Bundle) -> Bundle)? = { companionPrivilegeRequest(CompanionProtocol.CAP_SHELL) },
        ) = CompanionPrivilegedTool(
            "adb_command", "shell", "command", CompanionProtocol.CAP_SHELL,
            "Run a device-side ADB shell command through Backstage-play-agent. Backstage asks first unless ADB Auto is enabled. The companion asks its owner to approve the command.",
            "Device-side shell command", autoApprove, request,
        )

        fun all() = listOf(
            adbCommand(),
            CompanionPrivilegedTool(
                "install_apk", "install", "path", CompanionProtocol.CAP_PACKAGES,
                "Request installation of an APK already present on the device. The phone owner must approve it in Backstage Agent.",
                "Absolute path to the local APK",
            ),
            CompanionPrivilegedTool(
                "uninstall_app", "uninstall", "package", CompanionProtocol.CAP_PACKAGES,
                "Request removal of an installed package. The phone owner must approve it in Backstage Agent.",
                "Android package ID",
            ),
        )
    }
}

internal fun companionPrivilegeRequest(capability: Int): ((Bundle) -> Bundle)? =
    CompanionConnection.privilegedEndpoint(capability)?.let { endpoint -> endpoint::privilegedRequest }

internal data class CompanionCommandResult(
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val message: String = "",
)

internal suspend fun runCompanionPrivilegeRequest(
    request: () -> ((Bundle) -> Bundle)?,
    timeoutSeconds: Int,
    arguments: Bundle.() -> Unit,
): CompanionCommandResult = withContext(Dispatchers.IO) {
    val invoke = request() ?: return@withContext CompanionCommandResult(message = "Open Backstage-play-agent and set up privileged access.")
    var requestId: String? = null
    var complete = false
    try {
        val result = withTimeoutOrNull(125_000L + timeoutSeconds * 1_000L) {
            var reply = invoke(Bundle().apply {
                putString("action", "start")
                arguments()
            })
            requestId = reply.getString("requestId")
            while (reply.getString("state") == "pending" && requestId != null) {
                delay(750)
                reply = invoke(Bundle().apply {
                    putString("action", "poll")
                    putString("requestId", requestId)
                })
            }
            reply
        } ?: return@withContext CompanionCommandResult(message =
            "Backstage-play-agent did not finish the request in time. Check the companion before repeating it.")
        complete = true
        if (result.getString("state") == "finished") CompanionCommandResult(
            result.getInt("exitCode", 127), result.getString("stdout").orEmpty(), result.getString("stderr").orEmpty(),
        ) else CompanionCommandResult(message = result.getString("message") ?: "Backstage-play-agent declined the request.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CompanionCommandResult(message = "Backstage-play-agent disconnected. The operation may have started. Check before repeating it.")
    } finally {
        val id = requestId
        if (!complete && id != null) withContext(NonCancellable) {
            runCatching {
                invoke(Bundle().apply {
                    putString("action", "cancel")
                    putString("requestId", id)
                })
            }
        }
    }
}

internal class ForceStopAppTool : Tool {
    override val name = "force_stop_app"
    override val description = "Force-stop an installed app on this device after the phone owner approves."
    override val schema = Schema.obj("package" to Schema.str("Exact Android package ID"), required = listOf("package"))
    override val targets = emptySet<TargetKind>()
    override val mutating = true
    override val irreversible = true

    override suspend fun approvalQuestion(ctx: ToolContext, args: Args) = "Force-stop ${args.string("package")}?"

    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val id = args.string("package")
        if (!validId.matches(id) || id in protected || ctx.services.installedApps().none { it.packageName == id }) {
            return ToolResult.error("Choose an installed app by its exact package ID.")
        }
        val result = Privileged.exec("am", "force-stop", id)
        return if (result.ok) ToolResult("Force-stopped $id.") else ToolResult.error(result.toString())
    }

    private companion object {
        val validId = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        val protected = setOf("android", "com.android.systemui", CompanionProtocol.PLAY_PACKAGE, CompanionProtocol.PACKAGE)
    }
}
