package com.embeddablechatbot.android.feature.mail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.embeddablechatbot.android.core.domain.mail.ReviewMailActionUseCase
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.testing.FakeMailRepository
import com.embeddablechatbot.android.core.domain.testing.sendDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApprovalScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(mail: FakeMailRepository) {
        val vm = ApprovalViewModel(mail, ReviewMailActionUseCase(mail), SavedStateHandle(mapOf("actionId" to "a1")))
        compose.setContent { ApprovalScreen(onBack = {}, viewModel = vm) }
    }

    @Test
    fun reviewShowsAccountRecipientsSubjectAndFullBody_andApprovalNeedsConfirmation() {
        val mail = FakeMailRepository().apply { detailResult = Outcome.Ok(sendDetail(version = "v9", body = "Hello Sam,\nThe full body.\nBest")) }
        show(mail)

        compose.onNodeWithText("me@example.test").assertIsDisplayed()
        compose.onNodeWithText("x@example.test").assertIsDisplayed()
        compose.onNodeWithText("cc@example.test").assertIsDisplayed()
        compose.onNodeWithText("Hello Sam,\nThe full body.\nBest").assertIsDisplayed()

        compose.onNodeWithText("Send this email").performScrollTo().performClick()
        assertTrue("tapping the button alone must not approve", mail.approvals.isEmpty())

        compose.onNodeWithText("Send this email to x@example.test now? This can't be undone.").assertIsDisplayed()
        compose.onAllNodesWithTextCompat("Send this email").last().performClick()
        compose.waitUntil(5_000) { mail.approvals.isNotEmpty() }
        assertEquals(listOf("a1" to "v9"), mail.approvals)
    }

    @Test
    fun missingDraftCannotBeApproved() {
        show(FakeMailRepository().apply { detailResult = Outcome.Ok(sendDetail(version = null)) })
        compose.onNodeWithText("Send this email").performScrollTo().assertIsNotEnabled()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCompat(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().indices.map { onAllNodes(androidx.compose.ui.test.hasText(text))[it] }
}
