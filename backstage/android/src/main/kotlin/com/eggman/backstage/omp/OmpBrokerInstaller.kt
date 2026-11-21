package com.eggman.backstage.omp

import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.privileged.ShellResult

/** The privileged transport may log argv on failure: only a short-lived claim may cross it. */
class OmpBrokerInstaller(
    private val issue: (Int, String) -> String,
    private val revoke: (String) -> Unit,
    private val exec: suspend (List<String>, Long) -> ShellResult = { argv, timeout ->
        Privileged.exec(*argv.toTypedArray(), timeoutMs = timeout)
    },
) {
    suspend fun write(root: String, libDir: String, port: Int, contents: String): ShellResult {
        val code = issue(port, contents)
        try {
            // run.sh supplies Termux bash/curl. curl reads the claim header from stdin, and its
            // response goes straight to the protected pairing file, never the exec result/logs.
            return exec(listOf("sh", "$root/run.sh", root, libDir, "--", "bash", "-c", CLAIM_SCRIPT,
                "backstage-omp-claim", code, port.toString()), 15_000)
        } finally { revoke(code) }
    }
    private companion object {
        val CLAIM_SCRIPT = """
            set +x
            set -eu
            umask 077
            claim=${'$'}1
            port=${'$'}2
            dir=${'$'}HOME/.config/backstage-omp
            mkdir -p "${'$'}dir"
            chmod 700 "${'$'}dir"
            temporary=${'$'}(mktemp "${'$'}dir/broker.XXXXXX")
            trap 'rm -f "${'$'}temporary"' EXIT
            printf 'header = "X-Backstage-Claim: %s"\n' "${'$'}claim" |
              curl -q --noproxy '*' --config - --fail --silent --show-error --connect-timeout 1 --max-time 5 \
                "http://127.0.0.1:${'$'}port/omp/v1/claim" > "${'$'}temporary"
            test -s "${'$'}temporary"
            chmod 600 "${'$'}temporary"
            mv "${'$'}temporary" "${'$'}dir/broker"
        """.trimIndent()
    }
}
