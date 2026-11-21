package com.eggman.backstage.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillFormatTest {
    private fun md(front: String, body: String = "Do the thing.") = "---\n${front.trimIndent()}\n---\n\n$body\n"

    @Test fun minimalSkill() {
        val p = SkillParser.parse(md("name: tidy-desk\ndescription: Tidies the desktop."))
        assertTrue(p.errors.toString(), p.ok)
        assertEquals("tidy-desk", p.manifest!!.name)
        assertEquals("Tidies the desktop.", p.manifest!!.description)
        assertEquals("Do the thing.", p.body)
        assertTrue(p.warnings.isEmpty())
    }

    @Test fun optionalFieldsAndMetadata() {
        val p = SkillParser.parse(md("""
            name: pdf-notes
            description: "Reads PDFs: notes, forms and tables. Use when PDFs come up."
            license: Apache-2.0
            compatibility: Needs network access
            allowed-tools: Bash(git:*) Read
            metadata:
              author: someone
              version: "1.0"
        """))
        assertTrue(p.errors.toString(), p.ok)
        val m = p.manifest!!
        assertEquals("Reads PDFs: notes, forms and tables. Use when PDFs come up.", m.description)
        assertEquals("Apache-2.0", m.license)
        assertEquals("Needs network access", m.compatibility)
        assertEquals(listOf("Bash(git:*)", "Read"), m.allowedTools)
        assertEquals(mapOf("author" to "someone", "version" to "1.0"), m.metadata)
        assertTrue(p.warnings.any { "allowed-tools" in it })
    }

    @Test fun blockScalarsAndQuoting() {
        val literal = SkillParser.parse(md("name: a\ndescription: |\n  Line one\n  Line two\n"))
        assertEquals("Line one\nLine two", literal.manifest!!.description)
        val folded = SkillParser.parse(md("name: a\ndescription: >-\n  Folded one\n  folded two\n\n  next para\n"))
        assertEquals("Folded one folded two\nnext para", folded.manifest!!.description)
        val single = SkillParser.parse(md("name: 'a'\ndescription: 'It''s quoted # not a comment'"))
        assertEquals("It's quoted # not a comment", single.manifest!!.description)
        val escapes = SkillParser.parse(md("name: a\ndescription: \"Tab\\there \\u00e9\""))
        assertEquals("Tab\there é", escapes.manifest!!.description)
        val plainContinued = SkillParser.parse(md("name: a\ndescription: Starts here\n  and continues here # comment\nlicense: MIT"))
        assertEquals("Starts here and continues here", plainContinued.manifest!!.description)
        assertEquals("MIT", plainContinued.manifest!!.license)
    }

    @Test fun listsFlowStylesCommentsBomAndCrlf() {
        val text = "\uFEFF---\r\n# a comment\r\nname: a-b\r\ndescription: Desc # trailing\r\nallowed-tools:\r\n  - Read\r\n  - Write\r\nmetadata: {owner: me, tags: x}\r\n---\r\nBody\r\n"
        val p = SkillParser.parse(text)
        assertTrue(p.errors.toString(), p.ok)
        assertEquals("Desc", p.manifest!!.description)
        assertEquals(listOf("Read", "Write"), p.manifest!!.allowedTools)
        assertEquals(mapOf("owner" to "me", "tags" to "x"), p.manifest!!.metadata)
        assertEquals("Body", p.body)
        val flow = SkillParser.parse(md("name: a\ndescription: d\nallowed-tools: [Read, \"Bash(ls)\"]"))
        assertEquals(listOf("Read", "Bash(ls)"), flow.manifest!!.allowedTools)
    }

    @Test fun errors() {
        assertFalse(SkillParser.parse("# no frontmatter\nname: x").ok)
        assertTrue(SkillParser.parse("---\nname: a\ndescription: d\n").errors.single().contains("not closed"))
        assertTrue(SkillParser.parse(md("description: d")).errors.any { "name" in it })
        assertTrue(SkillParser.parse(md("name: a")).errors.any { "description" in it })
        assertTrue(SkillParser.parse(md("name: a\ndescription: " + "x".repeat(1025))).errors.any { "1024" in it })
        assertTrue(SkillParser.parse(md("name: a\n\tdescription: d")).errors.single().contains("tabs"))
        assertTrue(SkillParser.parse(md("name: a\ndescription: \"open")).errors.single().contains("not closed"))
        assertTrue(SkillParser.parse(md("name: [a]\ndescription: d")).errors.any { "list" in it })
        assertTrue(SkillParser.parse(md("name: a\ndescription: *alias")).errors.single().contains("not supported"))
        assertTrue(SkillParser.parse(md("just text")).errors.single().contains("key: value"))
    }

    @Test fun nameRules() {
        listOf("pdf", "pdf-processing", "a1-b2", "x".repeat(64), "über-skill").forEach { assertTrue(it, SkillRules.validName(it)) }
        listOf("", "PDF", "-pdf", "pdf-", "pdf--x", "pdf_x", "pdf x", "x".repeat(65), "../x", "a/b", "a.b").forEach {
            assertFalse(it, SkillRules.validName(it))
        }
        assertEquals("my-skill-2", SkillRules.suggestName("  My Skill #2! "))
    }

    @Test fun warnings() {
        val p = SkillParser.parse(md("name: a\ndescription: d\nversion: 2\nname: a", body = ""), folderName = "other")
        assertTrue(p.ok)
        assertTrue(p.warnings.any { "unknown field \"version\"" in it })
        assertTrue(p.warnings.any { "appears twice" in it })
        assertTrue(p.warnings.any { "folder \"other\"" in it })
        assertTrue(p.warnings.any { "no instructions" in it })
    }

    @Test fun renderRoundTrips() {
        val m = SkillManifest("round-trip", "Says: \"hi\" # and more", license = "MIT", metadata = mapOf("k" to "v: w"))
        val p = SkillParser.parse(SkillParser.render(m, "# Title\n\nStep 1."))
        assertTrue(p.errors.toString(), p.ok)
        assertEquals(m, p.manifest)
        assertEquals("# Title\n\nStep 1.", p.body)
        assertNull(SkillParser.parse("---\n---\n").manifest)
    }
}
