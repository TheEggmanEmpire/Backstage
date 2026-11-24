package com.eggman.backstage.tools

import android.os.Bundle
import com.eggman.backstage.skills.SkillStore
import com.eggman.backstage.testutil.FakeServices
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
class CompanionSkillScriptTest {
    @get:Rule val folder = TemporaryFolder()

    private fun skill() = SkillStore(folder.newFolder()).apply {
        val dir = File(root, "example").also { it.mkdir() }
        File(dir, "SKILL.md").writeText("---\nname: example\ndescription: Example skill\n---\nRun the script.")
        File(dir, "run.sh").writeText("cat sample.txt")
        File(dir, "sample.txt").writeText("hello")
        assertTrue(setEnabled("example", true))
    }

    private val args = Args(JSONObject().put("skill", "example").put("file", "run.sh"))

    @Test fun offAndDenialLeaveTheCompanionUntouched() = runBlocking {
        val store = skill()
        val services = object : FakeServices() {
            override val skills: SkillStore = store
        }
        val hooks = RecordingHooks(approve = false)
        val ctx = TestToolContext(services = services, hooks = hooks)
        val disabled = CompanionSkillScriptTool({ false }, { true }) { error("Disabled script reached companion") }
        val denied = CompanionSkillScriptTool({ true }, { false }) { error("Unapproved script reached companion") }
        assertTrue(disabled.run(ctx, args).isError)
        assertTrue(denied.run(ctx, args).isError)
        assertTrue(hooks.asked.single().contains("example/run.sh"))
    }

    @Test fun autoSendsTheEntrypointAndSiblingAssetsToTheCompanion() = runBlocking {
        val store = skill()
        val services = object : FakeServices() {
            override val skills: SkillStore = store
        }
        val tool = CompanionSkillScriptTool({ true }, { true }) {
            { request ->
                assertEquals("skill_script", request.getString("operation"))
                assertEquals("run.sh", request.getString("path"))
                val contents = mutableMapOf<String, String>()
                ZipInputStream(ByteArrayInputStream(requireNotNull(request.getByteArray("archive")))).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        contents[entry.name] = zip.readBytes().decodeToString()
                    }
                }
                assertEquals("cat sample.txt", contents["run.sh"])
                assertEquals("hello", contents["sample.txt"])
                Bundle().apply { putString("state", "finished"); putInt("exitCode", 2) }
            }
        }
        val result = tool.run(TestToolContext(services = services), args)
        assertTrue(result.isError)
        assertEquals(2, result.data?.getInt("exit_code"))
    }
}
