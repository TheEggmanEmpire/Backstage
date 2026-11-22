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
) : Tool {
    override val agentOnly = true
    override val targets = emptySet<TargetKind>()
    override val mutating = true
    override val schema = Schema.obj(argument to Schema.str(argumentDescription), required = listOf(argument))

    override suspend fun run(ctx: ToolContext, args: Args): ToolResult =
        withContext(Dispatchers.IO) {
            val endpoint = CompanionConnection.privilegedEndpoint(capability)
                ?: return@withContext ToolResult.error("Open Backstage Agent and set up its privileged access.")
            var requestId: String? = null
            var complete = false
            try {
                val result = withTimeoutOrNull(140_000) {
                    val started = endpoint.privilegedRequest(Bundle().apply {
                        putString("action", "start")
                        putString("operation", operation)
                        putString("argument", args.string(argument))
                    })
                    requestId = started.getString("requestId")
                    var reply = started
                    while (reply.getString("state") == "pending" && requestId != null) {
                        delay(750)
                        reply = endpoint.privilegedRequest(Bundle().apply {
                            putString("action", "poll")
                            putString("requestId", requestId)
                        })
                    }
                    reply
                } ?: return@withContext ToolResult.error("Backstage Agent did not finish the request in time. Check the companion before repeating it.")
                complete = true
                if (result.getString("state") != "finished") {
                    return@withContext ToolResult.error(result.getString("message") ?: "Backstage Agent declined the request.")
                }
                val code = result.getInt("exitCode", 127)
                val data = JSONObject().put("exit_code", code)
                    .put("stdout", result.getString("stdout").orEmpty().takeLast(6_000))
                    .put("stderr", result.getString("stderr").orEmpty().takeLast(6_000))
                ToolResult(data.toString(), isError = code != 0, data = data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ToolResult.error("Backstage Agent disconnected. The operation may have started. Check before repeating it.")
            } finally {
                val id = requestId
                if (!complete && id != null) withContext(NonCancellable) {
                    runCatching {
                        endpoint.privilegedRequest(Bundle().apply {
                            putString("action", "cancel")
                            putString("requestId", id)
                        })
                    }
                }
            }
        }

    companion object {
        fun all() = listOf(
            CompanionPrivilegedTool(
                "adb_command", "shell", "command", CompanionProtocol.CAP_SHELL,
                "Request a local device shell command. The phone owner must review and approve it in Backstage Agent.",
                "Device-side shell command",
            ),
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
