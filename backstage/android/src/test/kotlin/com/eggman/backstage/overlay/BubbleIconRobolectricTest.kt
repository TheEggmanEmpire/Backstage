package com.eggman.backstage.overlay

import android.graphics.drawable.VectorDrawable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.surfaces.FakeAgent
import com.eggman.backstage.surfaces.memorySettings
import com.eggman.backstage.ui.settings.SettingsStores
import com.eggman.backstage.ui.theme.BackstageTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** the bubble shows the Backstage app icon, TalkBack calls it "Backstage"; the theme's default content colour. */
@RunWith(RobolectricTestRunner::class)
class BubbleIconRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        SettingsStores.set(memorySettings())
        AgentAccess.override = FakeAgent()
    }

    @After fun tearDown() {
        AgentAccess.override = null
        SettingsStores.set(null)
    }

    @Test fun theBubblesIconIsTheRoundBackstageIcon() {
        val drawable = RuntimeEnvironment.getApplication().getDrawable(BubbleModel.ICON)
        assertTrue(drawable is VectorDrawable)
        assertEquals("bs_ic_launcher_round", RuntimeEnvironment.getApplication().resources.getResourceEntryName(BubbleModel.ICON))
    }

    @Test fun talkBackCallsTheBubbleBackstage() {
        val service = Robolectric.buildService(BubbleService::class.java).create().get()
        compose.setContent { BubbleContent(service.controller) { false } }
        compose.onNodeWithContentDescription(BubbleModel.LABEL).assertExists()
    }

    @Test @Config(qualifiers = "night") fun iconsOutsideASurfaceUseTheThemesLightContentColourInTheDark() {
        var content = Color.Unspecified
        var onSurface = Color.Unspecified
        compose.setContent { BackstageTheme { content = LocalContentColor.current; onSurface = MaterialTheme.colorScheme.onSurface } }
        compose.waitForIdle()
        assertEquals(onSurface, content)
        // Light on the dark sheet (not Compose's default black).
        assertNotEquals(Color.Black, content)
        assertTrue(content.red > 0.7f && content.green > 0.7f && content.blue > 0.7f)
    }

    @Test fun inTheLightThemeTheContentColourIsTheThemesDarkOne() {
        var content = Color.Unspecified
        var onSurface = Color.Unspecified
        compose.setContent { BackstageTheme { content = LocalContentColor.current; onSurface = MaterialTheme.colorScheme.onSurface } }
        compose.waitForIdle()
        assertEquals(onSurface, content)
        assertTrue(content.red < 0.3f)
    }
}
