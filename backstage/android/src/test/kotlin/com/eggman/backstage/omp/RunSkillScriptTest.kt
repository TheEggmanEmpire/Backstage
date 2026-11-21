package com.eggman.backstage.omp

import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.Settings
import com.eggman.backstage.privileged.ShellResult
import com.eggman.backstage.skills.SkillStore
import com.eggman.backstage.tools.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RunSkillScriptTest {
    @get:Rule val tmp = TemporaryFolder()
    private var enabled = true
    private var auto = false
    private var calls = 0
    private val store by lazy {
        SkillStore(tmp.newFolder()).also {
            it.saveSimple("runner", "Run scripts", "Use bundled scripts")
            File(it.root, "runner/go.sh").writeText("echo done")
            File(it.root, "runner/go.js").writeText("console.log('done')")
            File(it.root, "runner/go.py").writeText("print('done')")
            it.setEnabled("runner", true)
        }
    }
    private fun tool(missing: String? = null) = RunSkillScriptTool(
        enabled = { enabled }, autoApprove = { auto }, unavailable = { missing },
        execute = { _, path, args, shell, seconds, _ ->
            calls++
            assertTrue(path in listOf("go.sh", "go.js", "go.py"))
            assertEquals(listOf("literal; $(false)", "a'b"), args)
            ShellResult(0, "$path $shell $seconds", "")
        },
    )
    private fun run(file: String = "go.sh", hooks: AgentHooks? = RecordingHooks(), shell: Boolean = false, seconds: Int = 120,
                    t: RunSkillScriptTool = tool()): ToolResult = runBlocking {
        val ctx = TestToolContext(hooks = hooks)
        ctx.services.skillStore = store
        ToolRegistry(listOf(t)).execute(ctx, t.name, JSONObject().put("skill", "runner").put("file", file)
            .put("args", JSONArray(listOf("literal; $(false)", "a'b"))).put("shell_user", shell).put("timeout_seconds", seconds))
    }
    @Test fun disabledUnavailableAndDenialDoNotExecute() {
        enabled = false
        assertTrue(run().text.contains("disabled"))
        enabled = true
        assertTrue(run(t = tool("Install OMP first")).text.contains("Install OMP"))
        val deny = RecordingHooks(approve = false)
        assertTrue(run(hooks = deny).isError)
        assertTrue(deny.asked.single().contains("runner/go.sh"))
        assertTrue(run(hooks = null).isError)
        assertEquals(0, calls)
    }
    @Test fun scriptSwappedWhileTheApprovalWaitsIsRefused() {
        val swapping = object : AgentHooks by RecordingHooks() {
            override suspend fun confirm(action: String): Boolean { File(store.root, "runner/go.sh").writeText("rm -rf /sdcard"); return true }
            override suspend fun confirmAlways(action: String): Boolean = confirm(action)
        }
        val result = run(hooks = swapping)
        assertTrue(result.isError)
        assertTrue(result.text.contains("changed while waiting for approval"))
        assertEquals(0, calls)
    }

    @Test fun shAndJsExecuteAfterApprovalAndAutoSkipsOnlyScriptApproval() {
        assertFalse(run().isError)
        auto = true
        val deny = RecordingHooks(approve = false)
        assertFalse(run("go.js", hooks = deny, seconds = 999).isError)
        assertTrue(deny.asked.isEmpty())
        assertEquals(2, calls)
    }
    @Test fun invalidPathsSymlinksAndUnsupportedInterpretersNeverAskOrRun() {
        val hooks = RecordingHooks()
        for (path in listOf("../go.sh", "/go.sh", "a/../../go.sh", "go.sh\u0000")) assertTrue(run(path, hooks).isError)
        val external = tmp.newFile().apply { writeText("echo leak") }
        Files.createSymbolicLink(File(store.root, "runner/link.sh").toPath(), external.toPath())
        assertTrue(run("link.sh", hooks).isError)
        File(store.root, "runner/go.rb").writeText("puts 'no'")
        assertTrue(run("go.rb", hooks).text.contains("Unsupported"))
        assertTrue(hooks.asked.isEmpty())
        assertEquals(0, calls)
    }
    @Test fun settingsStartOffAndPersistIndependently() {
        val kv = MemoryStore()
        val settings = Settings(kv)
        assertFalse(settings.skillScriptsEnabled)
        assertFalse(settings.skillScriptsAutoApprove)
        settings.skillScriptsEnabled = true
        settings.skillScriptsAutoApprove = true
        assertTrue(Settings(kv).skillScriptsEnabled)
        assertTrue(Settings(kv).skillScriptsAutoApprove)
        assertFalse(settings.adbAutoApprove)
        assertFalse(settings.ompAutoApprove)
    }
    @Test fun runtimeSelectsInterpreterAndShellModeUsesRelayCaps() = runBlocking {
        val runtimeCalls = mutableListOf<List<String>>()
        val relayCalls = mutableListOf<String>()
        val runner = OmpSkillScripts(
            runtimeExec = { argv, _ -> runtimeCalls += argv; ShellResult(0, "runtime", "") },
            shellExec = { command, seconds ->
                relayCalls += command
                assertEquals(300L, seconds)
                ShellResultDTO(0, "relay", "")
            }, libDir = "/native")
        assertEquals("runtime", runner.execute("runner", "go.sh", listOf("a'b"), false, 120).out)
        assertTrue(runtimeCalls.last().joinToString(" ").contains("go.sh"))
        assertEquals("sh", OmpSkillScripts.interpreter("go.sh"))
        runner.execute("runner", "go.js", emptyList(), false, 5)
        assertTrue(runtimeCalls.last().joinToString(" ").contains("bun"))
        runner.execute("runner", "go.py", emptyList(), false, 120)
        assertTrue(runtimeCalls.last().joinToString(" ").contains("python3"))
        assertEquals("relay", runner.execute("runner", "go.sh", listOf("a'b"), true, 600).out)
        assertEquals(1, relayCalls.size)
        assertTrue(relayCalls.single().contains("/data/local/tmp/backstage-omp/run.sh"))
        assertTrue(relayCalls.single().contains("'a'\\''b'"))
    }
    @Test fun approvedScriptDoesNotBypassTheRelayApproval() = runBlocking {
        var executed = false
        val relay = OmpShellRelay(enabled = { true }, autoApprove = { false }, available = { true },
            confirm = { false }, exec = { _, _ -> executed = true; ShellResult(0, "", "") })
        val runner = OmpSkillScripts({ _, _ -> error("must use relay") }, relay::run, "/native")
        try {
            runner.execute("runner", "go.sh", emptyList(), true, 120)
            fail("relay denial must refuse execution")
        } catch (e: OmpShellRefused) { assertTrue(e.message!!.contains("did not approve")) }
        assertFalse(executed)
    }

    @Test fun fakePosixRuntimeActuallyRunsShAndJsWithSiblingAssetsAndLiteralArguments() = runBlocking {
        val home = tmp.newFolder()
        val bin = File(home, "bin").apply { mkdirs() }
        val scratch = File(home, "tmp").apply { mkdirs() }
        val archive = File(home, "skills.zip")
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            fun entry(path: String, text: String) {
                zip.putNextEntry(java.util.zip.ZipEntry("skills/runner/$path")); zip.write(text.toByteArray()); zip.closeEntry()
            }
            entry(".backstage", "")
            entry("SKILL.md", "instructions")
            entry("asset.txt", "sibling")
            entry("go.sh", "cat asset.txt; printf '%s' \"\$1\"")
            entry("go.js", "process.stdout.write(require('fs').readFileSync('asset.txt','utf8') + process.argv[2])")
        }
        val pairing = File(home, ".config/backstage-omp/broker").apply { parentFile!!.mkdirs() }
        pairing.writeText("port=47821\ntoken=" + "S".repeat(43) + "\n")
        File(bin, "curl").apply { writeText("#!/bin/sh\ncat >/dev/null\ncat '${archive.path}'\n"); setExecutable(true) }
        // The fake runtime supplies its own bun executable; here Node executes the JavaScript fixture.
        val node = ProcessBuilder("sh", "-c", "command -v node").start().inputStream.bufferedReader().readText().trim()
        assertTrue("Node is needed for the fake JavaScript runtime", node.isNotBlank())
        File(bin, "bun").apply { writeText("#!/bin/sh\nexec '$node' \"\$@\"\n"); setExecutable(true) }
        val runner = OmpSkillScripts(runtimeExec = { argv, _ ->
            val process = ProcessBuilder(argv).apply {
                environment()["HOME"] = home.path
                environment()["TMPDIR"] = scratch.path
                environment()["PATH"] = bin.path + ":" + environment()["PATH"]
            }.start()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            ShellResult(process.waitFor(), out, err)
        }, shellExec = { _, _ -> error("must use fake runtime") }, libDir = "/native")
        for (file in listOf("go.sh", "go.js")) {
            val result = runner.execute("runner", file, listOf("; $(false) a'b"), false, 5)
            assertEquals(result.err, 0, result.code)
            assertEquals("sibling; $(false) a'b", result.out)
        }
        assertTrue(scratch.listFiles()!!.isEmpty())
    }

}
