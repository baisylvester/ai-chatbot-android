package com.embeddablechatbot.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeKnowledgeUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun assistantShowsItsLinkedKnowledgeAndSourceCitations() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyNativeKnowledge")=="true")
        compose.waitUntil(60_000){compose.onAllNodesWithText("AI Assistant").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("AI Assistant").performClick()
        compose.waitUntil(60_000){compose.onAllNodesWithText("Knowledge base: Demo Assistant").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Knowledge base: Demo Assistant").assertIsDisplayed()
        compose.onNodeWithText("1 document ready · Uses the latest processed knowledge").assertIsDisplayed()
        compose.waitUntil(30_000){compose.onAllNodesWithText("Gani Macatoman public record",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("Gani Macatoman public record",substring=true).onLast().performScrollTo().assertIsDisplayed()
        println("NATIVE_KNOWLEDGE_UI_VERIFIED sourceVisible=true readyStatus=true citationVisible=true")
    }
}
