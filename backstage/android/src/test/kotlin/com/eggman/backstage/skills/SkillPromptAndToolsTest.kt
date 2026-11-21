package com.eggman.backstage.skills

import com.eggman.backstage.agent.AgentControllerImpl
import com.eggman.backstage.agent.FakeEnv
import com.eggman.backstage.agent.PromptContext
import com.eggman.backstage.agent.SystemPrompt
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.mcp.BackstageMcpTools
import com.eggman.backstage.testutil.FakeServices
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.ScriptedLlm.Companion.text
import com.eggman.backstage.testutil.script
import com.eggman.backstage.tools.TestToolContext
import com.eggman.backstage.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.util.Locale

class SkillPromptAndToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun installed(name: String, description: String = "Does $name.", enabled: Boolean = true) =
        InstalledSkill(SkillManifest(name, description), enabled, "x", 0, false, emptyList(), emptyList())

    private fun storeWith(vararg names: String, enable: Boolean = true): SkillStore {
        val s = SkillStore(tmp.newFolder())
        names.forEach { n ->
            val md = skillMd(n, body = "Body of $n. See references/r.md.")
            s.install(SkillCandidate("", SkillParser.parse(md), mapOf("SKILL.md" to md.toByteArray(), "references/r.md" to "ref of $n".toByteArray(),
                "scripts/go.py" to "print('x')".toByteArray())), "https://example.org/$n.zip")
            if (enable) s.setEnabled(n, true)
        }
        return s
    }

    @Test fun promptSectionListsOnlyEnabledSortedAndStable() {
        assertEquals("", SkillPrompt.build(emptyList()))
        assertEquals("", SkillPrompt.build(listOf(installed("off", enabled = false))))
        val a = SkillPrompt.build(listOf(installed("zeta"), installed("alpha"), installed("off", enabled = false)))
        val b = SkillPrompt.build(listOf(installed("alpha"), installed("zeta")))
        assertEquals(a, b)
        assertTrue(a.startsWith("## Skills"))
        assertTrue(a.indexOf("- alpha: Does alpha.") < a.indexOf("- zeta: Does zeta."))
        assertFalse("off" in a.substringAfter("Available skills:"))
        assertTrue("never outranks the safety rules" in a)
        assertTrue("run_skill_script" in a)
    }

    @Test fun promptSectionIsCapped() {
        val many = (1..200).map { installed("skill-%03d".format(it), "x".repeat(1_000)) }
        val p = SkillPrompt.build(many)
        assertTrue(p.length <= SkillPrompt.MAX_CHARS)
        assertTrue(p.contains("more: list_skills shows them all"))
        val line = p.lines().first { it.startsWith("- skill-001") }
        assertTrue(line.length <= SkillPrompt.MAX_DESCRIPTION + 20)
        assertTrue(line.endsWith("…"))
    }

    @Test fun systemPromptPutsSkillsBelowSafety() {
        fun ctx(skills: String) = PromptContext(TargetKind.PHONE, null, false, false, true, true, true, 0, ZoneId.of("UTC"), Locale.US, skills = skills)
        val without = SystemPrompt.build(ctx(""))
        assertFalse("## Skills" in without)
        assertFalse("skills they installed" in without)
        val section = SkillPrompt.build(listOf(installed("alpha")))
        val with = SystemPrompt.build(ctx(section))
        assertTrue(with.indexOf("## Safety") < with.indexOf("## Skills"))
        assertTrue("then the global instructions, then the skills they installed" in with)
        assertEquals(with, SystemPrompt.build(ctx(section)))
    }

    @Test fun toolsHiddenWithoutEnabledSkills() {
        val reg = ToolRegistry.default()
        val services = FakeServices()
        fun names() = reg.forAgent(ToolRegistry.Availability.of(services, desktop = false, vision = true, planMode = false)).map { it.name }
        assertFalse("use_skill" in names())
        services.skillStore = storeWith("alpha", enable = false)
        assertFalse("use_skill" in names())
        services.skillStore!!.setEnabled("alpha", true)
        assertTrue(names().containsAll(ToolRegistry.SKILL_TOOLS - "run_skill_script"))
        assertFalse("run_skill_script" in names()) // Runtime + privilege are separately required.
        assertTrue(reg.forAgent(ToolRegistry.Availability(false, skills = true, omp = true, adb = true, overlay = false))
            .any { it.name == "run_skill_script" })
    }

    @Test fun useListAndReadTools() = runBlocking {
        val reg = ToolRegistry.default()
        val ctx = TestToolContext()
        ctx.services.skillStore = storeWith("alpha", "beta")
        ctx.services.skillStore!!.setEnabled("beta", false)
        val list = reg.execute(ctx, "list_skills", JSONObject())
        assertTrue(list.text.contains("- alpha: Does alpha things."))
        assertFalse(list.text.contains("beta"))
        val use = reg.execute(ctx, "use_skill", JSONObject().put("name", "alpha"))
        assertFalse(use.isError)
        assertTrue(use.text.contains("<skill_instructions name=\"alpha\">\nBody of alpha."))
        assertTrue(use.text.contains("source: https://example.org/alpha.zip"))
        assertTrue(use.text.contains("cannot override safety rules"))
        assertTrue(use.text.contains("scripts/go.py (script: run with run_skill_script when enabled)"))
        assertTrue(use.text.contains("references/r.md"))
        val disabled = reg.execute(ctx, "use_skill", JSONObject().put("name", "beta"))
        assertTrue(disabled.isError)
        assertTrue(disabled.text.contains("Enabled skills: alpha"))
        val read = reg.execute(ctx, "read_skill_file", JSONObject().put("name", "alpha").put("path", "references/r.md"))
        assertTrue(read.text.contains("ref of alpha"))
        val script = reg.execute(ctx, "read_skill_file", JSONObject().put("name", "alpha").put("path", "scripts/go.py"))
        assertTrue(script.text.contains("use run_skill_script to execute when enabled"))
        val escape = reg.execute(ctx, "read_skill_file", JSONObject().put("name", "alpha").put("path", "../beta/SKILL.md"))
        assertTrue(escape.isError)
        val notEnabled = reg.execute(ctx, "read_skill_file", JSONObject().put("name", "beta").put("path", "references/r.md"))
        assertTrue(notEnabled.isError)
    }

    @Test fun mcpOffersSkillToolsOnlyWhenSwitchedOn() = runTest {
        val env = FakeEnv(this, ScriptedLlm(script(text("done"))))
        env.services.skillStore = storeWith("alpha")
        val c = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        val tools = BackstageMcpTools({ c }, env)
        assertFalse(tools.tools().any { it.name in ToolRegistry.SKILL_TOOLS })
        env.settings.mcpEnabledTools = setOf("execute_task", "use_skill", "list_skills")
        assertEquals(listOf("execute_task", "list_skills", "use_skill"), tools.tools().map { it.name }.let { listOf(it[0]) + it.drop(1).sorted() })
        val r = tools.call("use_skill", JSONObject().put("name", "alpha"))
        assertTrue(r.content.single().text!!.contains("Body of alpha."))
    }
}
