package com.eggman.backstage.ui.report

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// Robolectric #8460: an explicitly wider-than-default window can oscillate while measuring a text field
// inside a dialog. Keep the default 320dp width; real-device sizing remains a separate verification step.
// https://github.com/robolectric/robolectric/issues/8460
@Config(qualifiers = "w320dp-h891dp-xxhdpi")
// Exercise Android's native text measurement for these dialogs and their previews.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class AiReportUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val directory get() = app.noBackupFilesDir.resolve("ai-reports")

    @Before fun clearReports() { directory.deleteRecursively() }

    @Test fun longPressSavesInAppWithoutLaunchingExternalAppsThenSharesOnlyOnTap() {
        compose.setContent {
            MaterialTheme { ReportableAiResponse("Selected assistant text", "task-1") { Text("Selected assistant text", it) } }
        }
        compose.onNodeWithText("Selected assistant text").performTouchInput { longClick() }
        compose.onNodeWithText(if (PLAY_DISTRIBUTION) "Report this AI response" else "Report response").performClick()
        compose.onNodeWithText(ReportCategory.HARMFUL.label).performClick()
        compose.onNodeWithText("Save report").performClick()
        compose.waitUntil(10_000) { AiReportStore(directory).list().isNotEmpty() }
        compose.waitForIdle()
        val report = AiReportStore(directory).list().single()
        assertEquals(ReportCategory.HARMFUL, report.category)
        assertEquals("Selected assistant text", report.response)
        assertNull(shadowOf(app).nextStartedActivity)
        compose.onNodeWithText("Share report").performScrollTo().performClick()
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val outgoing = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, outgoing.action)
        assertEquals(report.shareText(), outgoing.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun cancelDoesNotSaveAndUserCanReopenAndDeleteALocalReport() {
        val existing = AiContentReport.create("older response", null, ReportCategory.OTHER, "")
        AiReportStore(directory).save(existing)
        compose.setContent {
            MaterialTheme { ReportableAiResponse("New response", null) { Text("New response", it) } }
        }
        // The direct screen-reader action bypasses the popup and must have the same save/cancel behaviour.
        compose.onNodeWithText("New response").performCustomAccessibilityActionWithLabel("Report response")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf(existing), AiReportStore(directory).list())
        compose.onNodeWithText("New response").performTouchInput { longClick() }
        compose.onNodeWithText("Saved reports").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Other concern", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Other concern", substring = true).performClick()
        compose.onNodeWithText("Delete local report").performScrollTo().performClick()
        compose.waitUntil(10_000) { AiReportStore(directory).list().isEmpty() }
    }

    @Test fun shareAndEmailIntentsContainExactlyThePreviewAndNoAutomaticProviderDestination() {
        val report = AiContentReport.create("response", null, ReportCategory.PRIVACY, "note")
        val share = AiReportIntents.create(report)
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("text/plain", share.type)
        assertEquals(report.shareText(), share.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(share.getStringArrayExtra(Intent.EXTRA_EMAIL))
        val email = AiReportIntents.create(report, "safety@example.org")
        assertEquals(Intent.ACTION_SENDTO, email.action)
        assertEquals("mailto", email.data!!.scheme)
        assertEquals("safety@example.org", email.data!!.schemeSpecificPart)
        assertEquals(report.shareText(), email.getStringExtra(Intent.EXTRA_TEXT))
        for (bad in listOf("x@example.org\nBcc:victim@example.org", "https://provider.example/api", "a@b.org?body=secret")) {
            assertThrows(IllegalArgumentException::class.java) { AiReportIntents.create(report, bad) }
        }
    }
}
