package com.embeddablechatbot.android.feature.auth

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.embeddablechatbot.android.core.designsystem.ChatbotTheme
import com.embeddablechatbot.android.core.domain.model.*
import com.embeddablechatbot.android.core.domain.repository.*
import com.embeddablechatbot.android.core.domain.testing.*
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    @get:Rule val compose=createComposeRule()
    private class Registration:RegistrationRepository {
        var calls=0
        override suspend fun register(email:String,password:String):Outcome<RegistrationStatus>{calls++;return Outcome.Ok(RegistrationStatus.ConfirmEmail)}
        override suspend fun resendConfirmation(email:String)=Outcome.Ok(Unit)
    }
    @Test fun welcomeExplainsApprovalAndOffersAccountCreation() {
        var create=false
        compose.setContent{ChatbotTheme{WelcomeScreen({create=true},{})}}
        compose.onNodeWithText("Your inbox, with a little help").assertIsDisplayed()
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"onboarding-welcome.png")
        file.outputStream().use{compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText("Get started").performScrollTo().performClick();assertTrue(create)
    }
    @Test fun invalidRegistrationNeverSubmits() {
        val repo=Registration();val vm=RegistrationViewModel(repo,SavedStateHandle())
        compose.setContent{ChatbotTheme{RegistrationScreen({}, {},vm)}}
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("invalid")
        compose.onAllNodesWithText("Create account").onLast().performScrollTo().performClick()
        compose.onNodeWithText("Enter a valid email address.").performScrollTo().assertIsDisplayed();assertEquals(0,repo.calls)
    }
    @Test fun signupShowsConfirmationAndReturnsTheEmailToSignIn() {
        val repo=Registration();val saved=SavedStateHandle();val vm=RegistrationViewModel(repo,saved);var email=""
        compose.setContent{ChatbotTheme{RegistrationScreen({email=it},{},vm)}}
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("new@example.test")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("a-long-test-password")
        compose.onAllNodes(hasSetTextAction())[2].performTextInput("a-long-test-password")
        compose.onAllNodesWithText("Create account").onLast().performScrollTo().performClick()
        compose.onNodeWithText("Check your email").assertIsDisplayed()
        assertEquals("",vm.state.value.password);assertTrue(saved.keys().none{it.contains("password",true)})
        compose.onNodeWithText("Continue to sign in").performClick();assertEquals("new@example.test",email);assertEquals(1,repo.calls)
    }
    @Test fun setupErrorCanRetryWithoutSigningOutOrShowingAnEmptyInbox() {
        var ready=false;var failed=true
        val repo=object:OnboardingRepository{override suspend fun prepare():Outcome<Unit> = if(failed)Outcome.Err(AppError.Unavailable(null,"Account setup unavailable")) else Outcome.Ok(Unit)}
        val mail=FakeMailRepository().apply{statusResult=Outcome.Ok(mailStatus(connected=false))}
        val vm=OnboardingViewModel(repo,mail,FakeConfigRepository(),FakeAuthRepository(),FakePreferencesRepository())
        compose.setContent{ChatbotTheme{BootstrapScreen({_,_->ready=true},vm)}}
        compose.onNodeWithText("Account setup unavailable").assertIsDisplayed();failed=false
        compose.onNodeWithText("Try again").performClick();compose.waitUntil(5000){ready};assertTrue(ready)
    }
}
