package com.eggman.backstage.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SkillSourcesTest {
    private fun bad(input: String): String = try { SkillSources.resolve(input); fail("accepted $input"); "" } catch (e: SkillSources.BadSource) { e.message!! }

    @Test fun repositoryLinks() {
        val repo = SkillSources.resolve("https://github.com/acme/agent-skills")
        assertEquals("https://api.github.com/repos/acme/agent-skills/zipball", repo.archiveUrl)
        assertNull(repo.subPath)
        assertTrue(repo.github)
        assertEquals("https://github.com/acme/agent-skills", repo.source)
        assertEquals(repo.archiveUrl, SkillSources.resolve("github.com/acme/agent-skills.git").archiveUrl)
        assertEquals(repo.archiveUrl, SkillSources.resolve("https://www.github.com/acme/agent-skills/").archiveUrl)
    }

    @Test fun folderAndFileLinks() {
        val tree = SkillSources.resolve("https://github.com/acme/agent-skills/tree/main/skills/pdf-notes")
        assertEquals("https://api.github.com/repos/acme/agent-skills/zipball/main", tree.archiveUrl)
        assertEquals("skills/pdf-notes", tree.subPath)
        val branchOnly = SkillSources.resolve("https://github.com/acme/agent-skills/tree/dev")
        assertEquals("https://api.github.com/repos/acme/agent-skills/zipball/dev", branchOnly.archiveUrl)
        assertNull(branchOnly.subPath)
        val blob = SkillSources.resolve("https://github.com/acme/agent-skills/blob/v1.2/skills/pdf-notes/SKILL.md")
        assertEquals("https://api.github.com/repos/acme/agent-skills/zipball/v1.2", blob.archiveUrl)
        assertEquals("skills/pdf-notes", blob.subPath)
        val raw = SkillSources.resolve("https://raw.githubusercontent.com/acme/agent-skills/main/skills/pdf-notes/SKILL.md")
        assertEquals("https://api.github.com/repos/acme/agent-skills/zipball/main", raw.archiveUrl)
        assertEquals("skills/pdf-notes", raw.subPath)
        val encoded = SkillSources.resolve("https://github.com/acme/agent-skills/tree/main/my%20skills/x")
        assertEquals("my skills/x", encoded.subPath)
    }

    @Test fun directArchivesPassThrough() {
        val zip = SkillSources.resolve("https://example.org/downloads/pack.zip")
        assertEquals("https://example.org/downloads/pack.zip", zip.archiveUrl)
        assertFalse(zip.github)
        val gh = SkillSources.resolve("https://github.com/acme/agent-skills/archive/refs/heads/main.zip")
        assertEquals("https://github.com/acme/agent-skills/archive/refs/heads/main.zip", gh.archiveUrl)
        assertNull(gh.subPath)
        assertEquals("https://github.com/acme/r/releases/download/v1/x.skill",
            SkillSources.resolve("https://github.com/acme/r/releases/download/v1/x.skill").archiveUrl)
    }

    @Test fun rejectedLinks() {
        assertTrue(bad("").contains("Enter"))
        assertTrue(bad("http://example.org/a.zip").contains("https"))
        assertTrue(bad("file:///sdcard/a.zip").contains("not a web link"))
        assertTrue(bad("https://github.com/acme").contains("owner and a repository"))
        assertTrue(bad("https://github.com/acme/r/pulls").contains("/tree/"))
        assertTrue(bad("https://github.com/acme/r/tree/main/../../etc").contains("not a valid path"))
        assertTrue(bad("https://raw.githubusercontent.com/acme/r/main").contains("does not name a file"))
    }
}
