package com.embeddablechatbot.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in signed-in device test; generation stays in the composer and is never submitted. */
@RunWith(AndroidJUnit4::class)
class UnifiedAssistantUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun assistantHistoryAndWritingToolsShareOneChat() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyUnifiedAssistant") == "true")
        compose.waitUntil(60_000) { compose.onAllNodesWithText("AI Assistant").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("AI mail").assertDoesNotExist()
        compose.onNodeWithText("AI Assistant").performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithContentDescription("Conversation history").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("AI Assistant").assertIsDisplayed()
        compose.onNodeWithText("Mailbox tools").assertDoesNotExist()
        compose.onNodeWithContentDescription("Conversation history").performClick()
        compose.onNodeWithText("Conversation history").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Writing tools").performClick()
        compose.onNodeWithText("Writing tools").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Write a short email thanking a colleague for a helpful presentation. Do not send it.")
        compose.onNodeWithText("Generate").performScrollTo().performClick()
        compose.waitUntil(90_000) { compose.onAllNodesWithText("Continue in AI Assistant").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Continue in AI Assistant").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Conversation history").fetchSemanticsNodes().isNotEmpty() }
        val draft = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertTrue("Generated writing was not handed back to the conversation", draft.length > 30)
        // Clear only our generated, unsent QA text; nothing was sent to the assistant or Gmail here.
        compose.onNode(hasSetTextAction()).performTextClearance()
        println("UNIFIED_ASSISTANT_UI_VERIFIED entry=true history=true writingTools=true draftHandoff=true sent=false")
    }
}
