package com.embeddablechatbot.android.feature.mail

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.embeddablechatbot.android.core.designsystem.ChatbotTheme
import com.embeddablechatbot.android.core.domain.mail.*
import com.embeddablechatbot.android.core.domain.model.*
import com.embeddablechatbot.android.core.domain.testing.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MailHubScreenTest {
    @get:Rule val compose = createComposeRule()
    private val repository=ScreenInbox()
    private val assistantRequests=mutableListOf<String?>()
    private fun show(status: Outcome<MailStatus> = Outcome.Ok(mailStatus()), writingToolsOnly: Boolean = false) {
        val mail=FakeMailRepository().apply {statusResult=status}
        val vm=MailHubViewModel(repository,mail,FakeConfigRepository(),FakePreferencesRepository(),SequentialIds(),SavedStateHandle())
        compose.setContent { ChatbotTheme { MailHubScreen({ assistantRequests += it }, {}, {}, {}, vm, writingToolsOnly = writingToolsOnly) } }
    }
    @Test fun mailboxFailureRemainsAnErrorAndAssistantEntryIsAvailable() {
        show(Outcome.Err(AppError.NotConfigured("The mail service is not available on this server yet.")))
        compose.onNodeWithText("The mail service is not available on this server yet.").assertIsDisplayed()
        compose.onNodeWithText("You’re all caught up").assertDoesNotExist()
        compose.onNodeWithText("AI mail").assertDoesNotExist()
        compose.onNodeWithText("AI Assistant").performClick()
        assertEquals(listOf<String?>(null),assistantRequests)
        assertTrue(repository.writes.isEmpty()); assertTrue(repository.drafts.isEmpty())
    }
    @Test fun standaloneWriterStillWorksWithoutMailboxAndCanReturnToAssistant() {
        show(Outcome.Err(AppError.NotConfigured("The mail service is not available on this server yet.")), writingToolsOnly = true)
        compose.onNode(hasSetTextAction()).performTextInput("Thank Ana for the update.")
        compose.onNodeWithText("Generate").performScrollTo().performClick()
        compose.onNodeWithText("Thanks, Ana, for the update.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continue in AI Assistant").performScrollTo().performClick()
        assertEquals(listOf("Thanks, Ana, for the update."),assistantRequests)
        assertEquals(1,repository.writes.size); assertTrue(repository.drafts.isEmpty())
    }
    @Test fun promptOpensAssistantDraftWithoutSendingOrCreatingGmailDrafts() {
        show()
        compose.onNodeWithContentDescription("Refresh").assertIsDisplayed()
        compose.onNodeWithText("Prompts").performClick()
        compose.onNodeWithText("Arrange a meeting").performScrollTo().performClick()
        assertTrue(assistantRequests.single()!!.contains("Write a professional meeting invitation."))
        assertTrue(repository.writes.isEmpty()); assertTrue(repository.drafts.isEmpty())
    }
    @Test fun messageReadFailureCanRetryWithoutLeavingTheReader() {
        repository.showMessage=true
        show()
        compose.onNodeWithText("Test message").performClick()
        compose.onNodeWithText("Could not connect. Check your connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Refresh").performClick()
        compose.onNodeWithText("Recovered message body").assertIsDisplayed()
    }
    private class ScreenInbox : InboxRepository {
        val writes=mutableListOf<WritingRequest>(); val drafts=mutableListOf<ComposeDraft>()
        var showMessage=false; private var reads=0
        private val message=InboxMessage("m1","t1","Ana","Test message","","A test preview",true,false)
        override suspend fun list(folder:MailFolder,query:String,cursor:String?,labelId:String?)=Outcome.Ok(InboxPage(if(showMessage) listOf(message) else emptyList(),null))
        override suspend fun labels():Outcome<List<MailLabel>> = Outcome.Ok(emptyList())
        override suspend fun createLabel(name:String):Outcome<MailLabel> = Outcome.Ok(MailLabel("Label_1",name))
        override suspend fun relabel(ids:List<String>,add:List<String>,remove:List<String>):Outcome<Unit> = Outcome.Ok(Unit)
        override suspend fun attachment(messageId:String,partId:String):Outcome<DownloadedFile> = Outcome.Err(AppError.NotFound("Missing"))
        override suspend fun attachmentText(messageId:String,partId:String):Outcome<AttachmentText> = Outcome.Err(AppError.NotFound("Missing"))
        override suspend fun read(id:String):Outcome<OpenMessage> = if(reads++==0) Outcome.Err(AppError.Network(false)) else Outcome.Ok(OpenMessage(message,"me@example.test","Recovered message body",false))
        override suspend fun change(id:String,change:MailChange,requestId:String)=Outcome.Ok(MailChangeReceipt(null,false))
        override suspend fun saveDraft(draft:ComposeDraft):Outcome<DraftReceipt> {drafts+=draft;return Outcome.Ok(DraftReceipt("d","a",draft.to,draft.subject))}
        override suspend fun write(request:WritingRequest):Outcome<String> {writes+=request;return Outcome.Ok("Thanks, Ana, for the update.")}
    }
}
