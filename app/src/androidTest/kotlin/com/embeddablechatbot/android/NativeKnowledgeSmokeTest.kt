package com.embeddablechatbot.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.embeddablechatbot.android.core.domain.mail.WritingRequest
import com.embeddablechatbot.android.core.domain.mail.WritingTask
import com.embeddablechatbot.android.core.domain.model.*
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Real server knowledge, citation and writing test. No mailbox operation is requested. */
@RunWith(AndroidJUnit4::class)
class NativeKnowledgeSmokeTest {
    @get:Rule val wakeLock=DeviceVerificationWakeLock()
    @Test fun nativeAssistantUsesTheSameLiveKnowledgeAsThePublishedChatbot() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verifyNativeKnowledge")=="true")
        withTimeout(240_000) {
            val app=EntryPointAccessors.fromApplication(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,BackendVerificationEntryPoint::class.java)
            app.startup().awaitReady()
            val account=app.auth().authState.value as? AuthState.SignedIn
            assertTrue(account?.account?.email?.equals(args.getString("expectedEmail"),ignoreCase=true)==true)
            val original=app.config().config.value
            val preview=args.getString("previewPlatformUrl")
            if(preview!=null){require(preview=="https://native-knowledge-review-ai-chatbot.ai-edu-web.workers.dev");app.config().update(original.copy(platformUrl=preview))}
            try {
                assertEquals(args.getString("expectedPlatformUrl"),app.config().config.value.platformUrl)
                assertTrue(app.onboarding().prepare() is Outcome.Ok)
                val ownId=app.config().config.value.chatbotId
                val sourceId=requireNotNull(args.getString("expectedKnowledgeChatbotId"))
                assertNotEquals("Private conversation identity must be preserved",sourceId,ownId)
                val nativeResult=app.chatbots().chatbot(ownId)
                val sourceResult=app.chatbots().chatbot(sourceId)
                assertTrue(nativeResult is Outcome.Ok && sourceResult is Outcome.Ok)
                val knowledge=(nativeResult as Outcome.Ok).value.knowledge
                val published=(sourceResult as Outcome.Ok).value.knowledge
                assertNotNull("Native knowledge metadata is absent",knowledge)
                assertEquals(KnowledgeState.Ready,knowledge!!.state)
                assertEquals(sourceId,knowledge.sourceChatbotId);assertTrue(knowledge.shared)
                assertTrue(knowledge.readyDocuments>0 && knowledge.chunks>0)
                assertEquals(published?.readyDocuments,knowledge.readyDocuments)
                assertEquals(published?.chunks,knowledge.chunks)

                val conversation=UUID.randomUUID().toString()
                assertTrue(app.conversations().createConversation(conversation) is Outcome.Ok)
                for(mailEnabled in listOf(false,true)) {
                    val tools=mutableListOf<SendEvent.Tool>()
                    val result=app.conversations().submitMessage(conversation,UUID.randomUUID().toString(),
                        "According to the knowledge base, which years did Gani Macatoman earn his Bachelor of Laws and MBA? Cite the knowledge source. Do not access email.",
                        emailManager=mailEnabled,stream=true) { if(it is SendEvent.Tool)tools+=it }
                    assertTrue("Grounded native answer failed",result is Outcome.Ok)
                    val turn=(result as Outcome.Ok).value
                    assertEquals(TurnStatus.Completed,turn.status)
                    assertTrue("Expected law degree year missing",turn.text.contains("1995"))
                    assertTrue("Expected MBA year missing",turn.text.contains("1999"))
                    assertTrue("Answer lacks a knowledge-base citation",turn.citations.any { it.title.contains("Gani Macatoman public record",ignoreCase=true) })
                    assertTrue("Knowledge question unexpectedly accessed Gmail",tools.none { it.name=="email_manager" })
                }
                val writing=app.inbox().write(WritingRequest(WritingTask.Write,"Write a short factual professional bio of Gani Macatoman. Include his law and MBA graduation years from the knowledge base.",length="Short"))
                assertTrue("Knowledge-grounded writing failed",writing is Outcome.Ok)
                val text=(writing as Outcome.Ok).value
                assertTrue("Writing does not reflect both knowledge-base dates",text.contains("1995") && text.contains("1999"))
                println("NATIVE_KNOWLEDGE_VERIFIED metadataMatchesWeb=true citedAnswer=true mailboxModes=2 groundedWriting=true gmailMutation=false")
            } finally { if(preview!=null)app.config().update(original) }
        }
    }
}
