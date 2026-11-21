package com.eggman.backstage.omp

import com.eggman.backstage.privileged.Privileged
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID

class OmpJobs(private val runtime: OmpRuntime) {
    companion object {
        fun validateId(id: String): String {
            require(id.matches(Regex("[a-z0-9]{8,32}"))) { "Job id must be 8..32 lowercase letters or digits" }
            return id
        }
        fun validateTask(task: String) {
            require(task.isNotBlank() && task.length <= 32_000 && '\u0000' !in task) { "Task must contain 1..32000 characters without NUL" }
        }
    }
    suspend fun create(task: String, model: String?, cwd: String?): String {
        validateTask(task)
        listOfNotNull(model, cwd).forEach { require(it.length <= 4_000 && '\u0000' !in it) { "Model/cwd too long or contains NUL" } }
        val id = UUID.randomUUID().toString().replace("-", "")
        val dir = shellQuote("${OmpRuntime.ROOT}/jobs/$id")
        val lock = shellQuote("${OmpRuntime.ROOT}.install-lock")
        val command = "set -e; umask 077; LOCK=$lock; " +
            "if [ -d \"\$LOCK\" ]; then owner=\$(cat \"\$LOCK/pid\" 2>/dev/null || :); " +
            "if [ -z \"\$owner\" ]; then sleep 0.2; owner=\$(cat \"\$LOCK/pid\" 2>/dev/null || :); fi; " +
            "case \"\$owner\" in ''|*[!0-9]*) rm -rf \"\$LOCK\";; *) kill -0 \"\$owner\" 2>/dev/null || rm -rf \"\$LOCK\";; esac; fi; " +
            "mkdir \"\$LOCK\" || { echo 'OMP install or job creation already running' >&2; exit 1; }; " +
            "printf '%s\\n' \"\$\$\" > \"\$LOCK/pid\"; trap 'rm -rf \"\$LOCK\"' EXIT; " +
            "mkdir $dir; printf '%s' ${shellQuote(task)} > $dir/task; " +
            "printf '%s' ${shellQuote(model.orEmpty())} > $dir/model; printf '%s' ${shellQuote(cwd.orEmpty())} > $dir/cwd; " +
            "sh ${shellQuote("${OmpRuntime.ROOT}/job.sh")} ${shellQuote(OmpRuntime.ROOT)} ${shellQuote(runtime.libDir)} start ${shellQuote(id)}"
        val r = Privileged.exec("sh", "-c", command)
        check(r.ok) { "Could not create/start OMP job $id: ${r.err.takeLast(300)}. Check this job before retrying." }
        return id
    }
    suspend fun status(id: String, waitSeconds: Int): JSONObject {
        validateId(id)
        val deadline = System.nanoTime() + waitSeconds.coerceIn(0, 600) * 1_000_000_000L
        while (true) {
            val r = Privileged.exec("sh", "${OmpRuntime.ROOT}/job.sh", OmpRuntime.ROOT, runtime.libDir, "status", id)
            check(r.ok) { "Unknown or unreadable OMP job" }
            val data = JSONObject(r.out).put("job_id", id)
            val finished = data.getString("state") !in setOf("starting", "running")
            if (finished || System.nanoTime() >= deadline) {
                if (finished) {
                    val dir = shellQuote("${OmpRuntime.ROOT}/jobs/$id")
                    for (file in listOf("out", "err")) {
                        val tail = Privileged.exec("sh", "-c", "test ! -f $dir/$file || tail -c 16384 $dir/$file")
                        data.put(if (file == "out") "answer" else "stderr", tail.out)
                    }
                }
                return data
            }
            delay(500)
        }
    }
    suspend fun cancel(id: String): JSONObject {
        validateId(id)
        val r = Privileged.exec("sh", "${OmpRuntime.ROOT}/job.sh", OmpRuntime.ROOT, runtime.libDir, "cancel", id)
        check(r.ok) { "Could not cancel OMP job" }
        return JSONObject(r.out).put("job_id", id)
    }
}
