package com.eggman.backstage.omp

import com.eggman.backstage.privileged.ShellResult
import com.eggman.backstage.skills.SkillFileKinds

/** Runs only a validated entrypoint, from a fresh copy of its skill. Code keeps the runtime UID's real powers;
 * the directory checks constrain file selection/staging, not arbitrary code's filesystem access. */
class OmpSkillScripts(
    private val runtimeExec: suspend (List<String>, Long) -> ShellResult,
    private val shellExec: suspend (String, Long) -> ShellResultDTO,
    private val libDir: String,
) {
    companion object {
        fun interpreter(path: String): String = when (SkillFileKinds.extension(path)) {
            "sh", "bash" -> "sh"
            "js", "mjs", "ts" -> "bun"
            "py" -> "python3"
            else -> throw IllegalArgumentException("Unsupported skill script: use .sh, .bash, .js, .mjs, .ts or .py.")
        }

        // Credentials are read from the existing runtime pairing and sent to curl only over stdin. No keys
        // enter argv, logs, environment, or new files. The authenticated app-generated ZIP has safe paths and
        // no symlinks. Extract just this skill, preserving sibling scripts/assets, never execute in the store.
        internal const val STAGE = """
set -eu
set +x
set +a
umask 077
skill=${'$'}1; file=${'$'}2; interpreter=${'$'}3; shift 3
unset token
port= token=
while IFS='=' read -r key value; do
  case "${'$'}key" in port) port=${'$'}value;; token) token=${'$'}value;; esac
done < "${'$'}HOME/.config/backstage-omp/broker"
case "${'$'}port" in ''|*[!0-9]*) echo 'Invalid OMP broker pairing' >&2; exit 1;; esac
case "${'$'}token" in ''|*[!A-Za-z0-9_-]*) echo 'Invalid OMP broker pairing' >&2; exit 1;; esac
[ "${'$'}{#token}" = 43 ] || exit 1
stage=${'$'}(mktemp -d "${'$'}TMPDIR/bskill.XXXXXX")
trap 'rm -rf "${'$'}stage"' EXIT
printf 'header = "Authorization: Bearer %s"\n' "${'$'}token" |
  curl -q --noproxy '*' --config - -fsS --connect-timeout 3 --max-time 60 --max-filesize 2000000000 "http://127.0.0.1:${'$'}port/omp/v1/config/skills.zip" > "${'$'}stage/skills.zip" 2>/dev/null || {
  echo 'Skill staging failed: OMP broker unavailable' >&2; exit 1;
}
unset token
unzip -q "${'$'}stage/skills.zip" "skills/${'$'}skill/*" -d "${'$'}stage/unpacked" || exit 1
dir=${'$'}stage/unpacked/skills/${'$'}skill
[ -z "${'$'}(find "${'$'}dir" -type l -print -quit)" ] || exit 1
cd "${'$'}dir"
[ -f "./${'$'}file" ] || { echo 'Script missing from enabled skill sync snapshot' >&2; exit 1; }
command -v "${'$'}interpreter" >/dev/null 2>&1 || { echo "Skill interpreter ${'$'}interpreter is not installed in the OMP runtime" >&2; exit 127; }
"${'$'}interpreter" "./${'$'}file" "${'$'}@" < /dev/null
"""
    }

    suspend fun execute(skill: String, file: String, args: List<String>, shellUser: Boolean, timeoutSeconds: Int): ShellResult {
        require(skill.matches(Regex("[a-z0-9._-]{1,64}")) && skill !in setOf(".", "..")) { "Invalid staged skill directory" }
        require(OmpSkills.safePath(file) == file) { "Invalid path inside skill" }
        val interpreter = interpreter(file)
        val argv = listOf("sh", "-c", STAGE, "bskill", skill, file, interpreter) + args
        val seconds = timeoutSeconds.coerceIn(5, 600).toLong()
        return if (shellUser) {
            // Re-enter proot AFTER the relay obtains uid 2000, preserving the POSIX runtime and all relay policy.
            val command = (listOf("sh", "${OmpRuntime.ROOT}/run.sh", OmpRuntime.ROOT, libDir, "--") + argv)
                .joinToString(" ", transform = ::shellQuote)
            shellExec(command, seconds.coerceAtMost(300)).let { ShellResult(it.exitCode, it.stdout, it.stderr) }
        } else {
            // Used only by a runtime adapter that can actually provide app UID. The installed shell-only adapter
            // refuses this mode before approval; never misrepresent shell execution as app execution.
            val command = argv.joinToString(" ", transform = ::shellQuote)
            runtimeExec(OmpShellRelay.wrapperArgv(command, seconds), seconds * 1_000 + 10_000)
        }
    }
}
