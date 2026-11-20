package com.embeddablechatbot.android.feature.mail

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.embeddablechatbot.android.core.designsystem.ChatbotTheme
import com.embeddablechatbot.android.core.domain.mail.*
import com.embeddablechatbot.android.core.domain.model.*
import com.embeddablechatbot.android.core.domain.testing.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectOnboardingScreenTest {
    @get:Rule val compose=createComposeRule()
    @Test fun disconnectedUserCanExploreWithoutOpeningGoogleOrApprovingMail() {
        val mail=FakeMailRepository().apply{statusResult=Outcome.Ok(mailStatus(connected=false))}
        val vm=MailViewModel(mail,ConnectMailboxUseCase(mail,FakePendingConnectStore(),FixedClock()),FakeAuthRepository(),FakeConfigRepository(),ConnectCallbackBus())
        var finished=false
        compose.setContent{ChatbotTheme{ConnectOnboardingScreen({finished=true},vm)}}
        compose.onNodeWithText("Connect your Gmail").assertIsDisplayed()
        compose.onNodeWithText("Connect Gmail").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Explore AI tools first").performScrollTo().performClick()
        assertTrue(finished);assertTrue(mail.challenges.isEmpty());assertTrue(mail.approvals.isEmpty())
    }
}
