package com.eggman.backstage.surfaces

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.eggman.backstage.skills.SkillStore
import com.eggman.backstage.skills.skillMd
import com.eggman.backstage.skills.zipOf
import com.eggman.backstage.ui.settings.SettingsServices
import com.eggman.backstage.ui.settings.SkillsScreen
import com.eggman.backstage.ui.settings.SkillsSection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Skills as rendered Compose UI: imported fixtures, switches, details, editor and delete. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SkillsSettingsRobolectricTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val skills0 = SettingsServices.skills
    private val download0 = SettingsServices.downloadSkill
    private lateinit var store: SkillStore

    @Before fun setUp() {
        store = SkillStore(tmp.newFolder("skills")).also { it.seedExamples(force = false) }
        SettingsServices.skills = { store }
    }

    @After fun tearDown() {
        SettingsServices.skills = skills0
        SettingsServices.downloadSkill = download0
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun settingsEntryShowsTheSummary() {
        var opened = false
        compose.setContent { SkillsSection { opened = true } }
        waitForText("No skills installed.")
        compose.onNodeWithText("Manage skills").performClick()
        assertTrue(opened)
    }

    @Test fun installedSkillsCanBeEnabledInspectedAndDeleted() {
        for (name in listOf("alpha", "beta")) {
            val md = skillMd(name)
            store.install(com.eggman.backstage.skills.SkillCandidate("", com.eggman.backstage.skills.SkillParser.parse(md),
                mapOf("SKILL.md" to md.toByteArray(), "references/notes.md" to "notes".toByteArray())), "test fixture")
        }
        compose.setContent { SkillsScreen(onBack = {}) }
        waitForText("alpha")
        compose.onNodeWithText("Scripts inside skills run real code", substring = true).assertExists()
        // The GitHub sync switch sits above the list; the skill rows (alpha, beta) are the last two toggles.
        compose.onAllNodes(isToggleable()).let { it[it.fetchSemanticsNodes().size - 2] }.performScrollTo().performClick()
        compose.waitUntil(5_000) { store.enabled().map { it.name } == listOf("alpha") }

        compose.onAllNodesWithText("Details")[1].performScrollTo().performClick()
        waitForText("references/notes.md")
        compose.onNodeWithText("Source: test fixture").assertExists()
        compose.onNodeWithText("Delete").performClick()
        compose.onAllNodesWithText("Delete")[1].performClick()
        compose.waitUntil(5_000) { store.get("beta") == null }
        waitForText("Deleted beta.")
    }

    @Test fun aSkillCanBeCreatedAndEdited() {
        compose.setContent { SkillsScreen(onBack = {}) }
        waitForText("New skill")
        compose.onNodeWithText("New skill").performClick()
        compose.onNodeWithText("Name").performTextInput("Weekly Report")
        compose.onNodeWithText("Use \"weekly-report\"").performClick()
        compose.onNodeWithText("Description").performTextInput("Writes the weekly report. Use on Fridays.")
        compose.onNodeWithText("Instructions (Markdown)").performTextInput("1. Collect the notes.")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { store.get("weekly-report") != null }
        val s = store.get("weekly-report")!!
        assertFalse(s.enabled)
        assertEquals("1. Collect the notes.", store.body("weekly-report"))
        waitForText("Created weekly-report")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Edit").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.onNodeWithText("Instructions (Markdown)").performTextReplacement("1. Collect the notes.\n2. Send them.")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { store.body("weekly-report") == "1. Collect the notes.\n2. Send them." }
    }

    @Test fun importFromALinkShowsAPreviewAndRecordsTheSource() {
        var asked: String? = null
        SettingsServices.downloadSkill = { d, file, _ ->
            asked = d.archiveUrl
            zipOf(file, "acme-skills-1/skills/pdf-notes/SKILL.md" to skillMd("pdf-notes"),
                "acme-skills-1/skills/pdf-notes/scripts/x.py" to "print(1)",
                "acme-skills-1/skills/other/SKILL.md" to skillMd("other"))
        }
        compose.setContent { SkillsScreen(onBack = {}) }
        waitForText("Import from link")
        compose.onNodeWithText("Import from link").performClick()
        compose.onNodeWithText("Link").performTextInput("https://github.com/acme/skills/tree/main/skills/pdf-notes")
        compose.onNodeWithText("Download").performClick()
        waitForText("pdf-notes — 2 file(s)")
        assertEquals("https://api.github.com/repos/acme/skills/zipball/main", asked)
        assertTrue(compose.onAllNodes(hasText("other —", substring = true)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Install").performClick()
        compose.waitUntil(5_000) { store.get("pdf-notes") != null }
        val s = store.get("pdf-notes")!!
        assertEquals("https://github.com/acme/skills/tree/main/skills/pdf-notes", s.source)
        assertFalse(s.enabled)
        assertTrue(s.hasScripts)
        assertNull(store.get("other"))
        waitForText("Installed 1")
    }

    @Test fun badLinksAreExplained() {
        compose.setContent { SkillsScreen(onBack = {}) }
        waitForText("Import from link")
        compose.onNodeWithText("Import from link").performClick()
        compose.onNodeWithText("Link").performTextInput("http://example.org/x.zip")
        compose.onNodeWithText("Download").performClick()
        waitForText("Only https links are accepted.")
    }
}
