package com.eggman.backstage.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class SkillStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L
    private fun store(limits: SkillLimits = SkillLimits()) = SkillStore(tmp.newFolder(), { now++ }, limits)

    private fun candidate(name: String, vararg extra: Pair<String, String>, body: String = "Steps for $name.") =
        SkillCandidate("", SkillParser.parse(skillMd(name, body = body)), (listOf("SKILL.md" to skillMd(name, body = body)) + extra).associate { it.first to it.second.toByteArray() })

    @Test fun installStartsDisabledAndEnableDisableDelete() {
        val s = store()
        assertTrue(s.install(candidate("alpha"), "https://example.org/a.zip") is InstallOutcome.Installed)
        val a = s.get("alpha")!!
        assertFalse(a.enabled)
        assertEquals("https://example.org/a.zip", a.source)
        assertTrue(s.enabled().isEmpty())
        assertTrue(s.setEnabled("alpha", true))
        assertEquals(listOf("alpha"), s.enabled().map { it.name })
        // State survives a new store instance on the same folder.
        assertTrue(SkillStore(s.root).get("alpha")!!.enabled)
        assertTrue(s.setEnabled("alpha", false))
        assertTrue(s.enabled().isEmpty())
        assertFalse(s.setEnabled("missing", true))
        assertTrue(s.delete("alpha"))
        assertNull(s.get("alpha"))
        assertFalse(s.delete("alpha"))
    }

    @Test fun duplicatesNeedReplaceAndKeepTheSwitch() {
        val s = store()
        s.install(candidate("dup", body = "v1"), "one")
        s.setEnabled("dup", true)
        assertTrue(s.install(candidate("dup", body = "v2"), "two") is InstallOutcome.Duplicate)
        assertEquals("v1", s.body("dup"))
        val r = s.install(candidate("dup", "extra.md" to "x", body = "v2"), "two", replace = true)
        assertTrue(r is InstallOutcome.Installed && r.replaced)
        assertEquals("v2", s.body("dup"))
        assertTrue(s.get("dup")!!.enabled)
        assertEquals("two", s.get("dup")!!.source)
        assertEquals(listOf("SKILL.md", "extra.md"), s.get("dup")!!.files.map { it.path })
        assertTrue(s.root.listFiles()!!.none { it.name.startsWith(".tmp") || it.name.startsWith(".old") })
    }

    @Test fun invalidAndOversizedCandidatesFail() {
        val s = store(SkillLimits(maxSkillBytes = 200))
        val bad = SkillCandidate("x", SkillParser.parse("nope"), mapOf("SKILL.md" to "nope".toByteArray()))
        assertTrue(s.install(bad, "x") is InstallOutcome.Failed)
        assertTrue(s.install(candidate("big", "a.txt" to "y".repeat(500)), "x") is InstallOutcome.Failed)
        assertTrue(s.list().isEmpty())
    }

    @Test fun readFileIsTraversalSafeTextOnlyAndCapped() {
        val s = store(SkillLimits(maxReadChars = 10))
        s.install(SkillCandidate("", SkillParser.parse(skillMd("files")), mapOf(
            "SKILL.md" to skillMd("files").toByteArray(),
            "references/guide.md" to "0123456789abcdef".toByteArray(),
            "assets/logo.png" to byteArrayOf(-119, 80, 78, 71, 0, 0),
            "scripts/run.sh" to "echo hi".toByteArray(),
        )), "x")
        File(s.root, "secret.txt").writeText("outside")
        val ok = s.readFile("files", "references\\guide.md") as SkillStore.FileRead.Text
        assertEquals("0123456789", ok.text)
        assertTrue(ok.truncated)
        assertTrue(s.readFile("files", "scripts/run.sh") is SkillStore.FileRead.Text)
        listOf("../secret.txt", "/etc/passwd", "references/../../secret.txt", "").forEach {
            assertTrue(it, s.readFile("files", it) is SkillStore.FileRead.Refused)
        }
        assertTrue((s.readFile("files", "assets/logo.png") as SkillStore.FileRead.Refused).reason.contains("not a text file"))
        assertTrue((s.readFile("files", "nope.md") as SkillStore.FileRead.Refused).reason.contains("references/guide.md"))
        assertTrue(s.readFile("other", "SKILL.md") is SkillStore.FileRead.Refused)
        // A symbolic link inside the skill cannot lead out of it.
        val link = File(s.root, "files/references/link.md")
        runCatching { Files.createSymbolicLink(link.toPath(), File(s.root, "secret.txt").toPath()) }.onSuccess {
            assertTrue(s.readFile("files", "references/link.md") is SkillStore.FileRead.Refused)
        }
        val info = s.get("files")!!.files.associateBy { it.path }
        assertFalse(info.getValue("assets/logo.png").text)
        assertTrue(info.getValue("scripts/run.sh").script)
        assertTrue(s.get("files")!!.hasScripts)
    }

    @Test fun createEditAndRenameSimpleSkills() {
        val s = store()
        assertTrue(s.saveSimple("Bad Name", "d", "i").any { "lowercase" in it })
        assertTrue(s.saveSimple("ok", "", "").containsAll(listOf("the description is empty", "the instructions are empty")))
        assertEquals(emptyList<String>(), s.saveSimple("weekly-report", "Writes the weekly report.\nUse on Fridays.", "1. Open notes."))
        val w = s.get("weekly-report")!!
        assertEquals("Writes the weekly report. Use on Fridays.", w.description)
        assertEquals(SkillStore.SOURCE_CREATED, w.source)
        assertEquals("1. Open notes.", s.body("weekly-report"))
        assertTrue(s.saveSimple("weekly-report", "d", "i").any { "already exists" in it })
        // Edit keeps extra files and the switch; rename moves it.
        File(s.root, "weekly-report/template.md").writeText("T")
        s.setEnabled("weekly-report", true)
        assertEquals(emptyList<String>(), s.saveSimple("friday-report", "New text.", "2. Write.", editing = "weekly-report"))
        assertNull(s.get("weekly-report"))
        val f = s.get("friday-report")!!
        assertTrue(f.enabled)
        assertEquals("New text.", f.description)
        assertEquals("2. Write.", s.body("friday-report"))
        assertTrue(f.files.any { it.path == "template.md" })
        assertEquals(emptyList<String>(), s.saveSimple("friday-report", "Same name.", "3.", editing = "friday-report"))
        assertEquals("3.", s.body("friday-report"))
    }

    @Test fun exportZipReimports() {
        val s = store()
        s.install(candidate("share-me", "references/r.md" to "ref"), "x")
        val out = ByteArrayOutputStream()
        assertTrue(s.exportZip("share-me", out))
        val zip = tmp.newFile("share-me.zip").apply { writeBytes(out.toByteArray()) }
        val c = SkillArchive.scan(zip).candidates.single()
        assertEquals("share-me", c.name)
        assertEquals(setOf("SKILL.md", "references/r.md"), c.files.keys)
        assertFalse(s.exportZip("missing", ByteArrayOutputStream()))
    }

    @Test fun githubManagedSkillCannotBeEditedOrDeletedManually() {
        val s = store()
        s.install(candidate("managed"), "GitHub")
        val file = File(s.root, SkillStore.STATE)
        val state = org.json.JSONObject(file.readText())
        state.getJSONObject("skills").getJSONObject("managed").put("syncSource", "acme-skills")
        file.writeText(state.toString())
        assertEquals(listOf("Managed by GitHub source acme-skills; remove the source to delete it"),
            s.saveSimple("managed", "new description", "new body", editing = "managed"))
        assertFalse(s.delete("managed"))
        assertEquals("Steps for managed.", s.body("managed"))
    }

    @Test fun retiredExamplesAreNotSeededEvenWhenRestored() {
        val s = store()
        assertTrue(BuiltinSkills.all.isEmpty())
        assertEquals(0, s.seedExamples(force = false))
        assertEquals(0, s.seedExamples(force = true))
        assertTrue(s.list().isEmpty())
    }

    @Test fun builtinExamplesAreValid() {
        BuiltinSkills.all.forEach { (name, files) ->
            val p = SkillParser.parse(files.getValue("SKILL.md"), name)
            assertTrue("$name ${p.errors}", p.ok)
            assertEquals(name, p.manifest!!.name)
            assertTrue("$name ${p.warnings}", p.warnings.isEmpty())
        }
    }
}
