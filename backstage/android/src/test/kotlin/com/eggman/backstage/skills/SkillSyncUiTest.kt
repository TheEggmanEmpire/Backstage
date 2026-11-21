package com.eggman.backstage.skills

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.eggman.backstage.ui.settings.SettingsServices
import com.eggman.backstage.ui.settings.SkillsScreen
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SkillSyncUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val skills0 = SettingsServices.skills
    private lateinit var context: Context
    private lateinit var store: SkillStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(SkillSyncPreferences.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        store = SkillStore(tmp.newFolder("skills"))
        SettingsServices.skills = { store }
    }
    @After fun cleanup() {
        SettingsServices.skills = skills0
        context.getSharedPreferences(SkillSyncPreferences.FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun waitFor(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun githubSectionShowsSourceAndStatus() {
        val source = SkillSyncSource("acme-skills", "acme", "skills")
        SkillSyncPreferences(context).apply {
            save(SkillSyncConfig(sources = listOf(source)))
            saveStatus(source.id, SkillSyncStatus(1000, "partial", counts = SkillSyncCounts(1, 2, 3, 4)))
        }
        compose.setContent { SkillsScreen({}) }
        waitFor("acme-skills")
        compose.onNodeWithText("GitHub sync").performScrollTo().assertExists()
        compose.onNodeWithText("acme-skills").performScrollTo().assertExists()
        compose.onNodeWithText("+1 added, 2 updated, 3 deleted, 4 skipped", substring = true).assertExists()
        compose.onNodeWithText("Next sync: automatic sync is off.").assertExists()
        compose.onNodeWithText("Remove").performScrollTo().performClick()
        waitFor("Remove acme-skills?")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, SkillSyncPreferences(context).config().sources.size)
    }

    @Test fun invalidIntervalIsRejectedInTheUi() {
        compose.setContent { SkillsScreen({}) }
        waitFor("GitHub sync")
        compose.onNodeWithText("Interval").performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("Save interval").performScrollTo().performClick()
        waitFor("positive number")
        assertEquals(60, SkillSyncPreferences(context).config().intervalMinutes)
    }

    @Test fun managedSkillBlocksEditAndDelete() {
        val candidate = SkillArchive.fromSkillMd(skillMd("managed")).candidates.single()
        store.mirror("acme-skills", listOf(candidate), "https://github.com/acme/skills")
        compose.setContent { SkillsScreen({}) }
        waitFor("GitHub source acme-skills")
        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        waitFor("Managed by GitHub source acme-skills; remove the source to delete it")
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        assertEquals("Steps for managed.", store.body("managed"))
        assertTrue(compose.onAllNodesWithText("Delete managed?").fetchSemanticsNodes().isEmpty())
    }
}
