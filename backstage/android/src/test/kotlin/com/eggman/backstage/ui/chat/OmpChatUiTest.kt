package com.eggman.backstage.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OmpChatUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thinkingExpandsWhileStreamingCollapsesForAnswerAndCanBeReopened() {
        val entry = mutableStateOf(Transcript.Entry(1, Transcript.Kind.RESULT, "", "t", thinking = "Check files", thinkingActive = true))
        compose.setContent { MaterialTheme { EntryRow(entry.value) } }
        compose.onNodeWithText("Check files").assertExists()
        compose.runOnIdle { entry.value = entry.value.copy(text = "Answer", thinkingActive = false) }
        compose.onNodeWithText("Answer").assertExists()
        compose.onNodeWithText("Check files").assertDoesNotExist()
        compose.onNodeWithText("Thinking").performClick()
        compose.onNodeWithText("Check files").assertExists()
    }

    @Test fun toolTailCanBeExpandedAndHiddenReasoningHasOnlyTheProviderLabel() {
        compose.setContent { MaterialTheme { Column {
            EntryRow(Transcript.Entry(1, Transcript.Kind.ACTION, "bash: ls", "t", detail = "result tail", step = 1))
            EntryRow(Transcript.Entry(2, Transcript.Kind.RESULT, "Answer", "t", thinking = "Thinking (hidden by provider)"))
        } } }
        compose.onNodeWithText("result tail").assertDoesNotExist()
        compose.onNodeWithText("1. bash: ls").performClick()
        compose.onNodeWithText("result tail").assertExists()
        compose.onNodeWithText("Thinking (hidden by provider)").assertExists()
    }
}
