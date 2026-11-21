package com.eggman.backstage.surfaces

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.Settings
import com.eggman.backstage.ui.settings.CoreSettingsStore
import com.eggman.backstage.ui.settings.SkillScriptsSettingsSection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkillScriptsSettingsRobolectricTest {
    @get:Rule val compose = createComposeRule()
    @Test fun offAskAutoControlTheTwoIndependentGates() {
        val settings = Settings(MemoryStore())
        compose.setContent { SkillScriptsSettingsSection(CoreSettingsStore(settings)) }
        compose.onNodeWithText("Off").assertIsSelected()
        compose.onNodeWithText("Ask").performClick()
        compose.runOnIdle { assertTrue(settings.skillScriptsEnabled); assertFalse(settings.skillScriptsAutoApprove) }
        compose.onNodeWithText("Auto").performClick()
        compose.runOnIdle { assertTrue(settings.skillScriptsEnabled); assertTrue(settings.skillScriptsAutoApprove) }
        compose.onNodeWithText("Off").performClick()
        compose.runOnIdle { assertFalse(settings.skillScriptsEnabled) }
    }
}
