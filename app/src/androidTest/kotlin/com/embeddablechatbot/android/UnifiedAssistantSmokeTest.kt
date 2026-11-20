package com.embeddablechatbot.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.SendEvent
import com.embeddablechatbot.android.core.domain.model.Turn
import com.embeddablechatbot.android.core.domain.model.TurnStatus
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Creates one private QA conversation. Mailbox operation is a synthetic, read-only search. */
@RunWith(AndroidJUnit4::class)
class UnifiedAssistantSmokeTest {
    @get:Rule val wakeLock=DeviceVerificationWakeLock()
    @Test fun generalChatDoesNotRequireMailboxCapability() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verifyUnifiedAssistant") == "true")
        withTimeout(90_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val app = EntryPointAccessors.fromApplication(context, BackendVerificationEntryPoint::class.java)
            app.startup().awaitReady()
            val original = app.config().config.value
            val preview = args.getString("previewPlatformUrl")
            if (preview != null) {
                require(preview == "https://unified-assistant-review-ai-chatbot.ai-edu-web.workers.dev")
                app.config().update(original.copy(platformUrl = preview))
            }
            try {
            assertEquals(args.getString("expectedPlatformUrl"), app.config().config.value.platformUrl)
            val account = app.auth().authState.value as? AuthState.SignedIn
            assertTrue(account?.account?.email?.equals(args.getString("expectedEmail"), ignoreCase = true) == true)
            val id = UUID.randomUUID().toString()
            val repository = app.conversations()
            assertTrue(repository.createConversation(id) is Outcome.Ok)
            val tools = mutableListOf<SendEvent.Tool>()
            val response = repository.submitMessage(id, UUID.randomUUID().toString(), "Briefly explain how a rainbow forms. This is a general question; do not access email.", emailManager = false, stream = true) {
                if (it is SendEvent.Tool) tools += it
            }
            assertTrue("General chat request failed", response is Outcome.Ok)
            val turn = (response as Outcome.Ok).value
            assertEquals("General chat must work without mailbox capability: ${turn.error}", TurnStatus.Completed, turn.status)
            assertTrue(turn.text.length > 30)
            assertTrue(tools.isEmpty())
            println("GENERAL_ASSISTANT_VERIFIED mailboxCapability=false reply=true")
            } finally {
                if (preview != null) app.config().update(original)
            }
        }
    }

    @Test fun generalChatMailboxSearchAndFollowupUseOneConversation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verifyUnifiedAssistant") == "true")
        withTimeout(240_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val app = EntryPointAccessors.fromApplication(context, BackendVerificationEntryPoint::class.java)
            app.startup().awaitReady()
            assertEquals(args.getString("expectedPlatformUrl"), app.config().config.value.platformUrl)
            val account = app.auth().authState.value as? AuthState.SignedIn
            assertTrue(account?.account?.email?.equals(args.getString("expectedEmail"), ignoreCase = true) == true)
            assertTrue(app.onboarding().prepare() is Outcome.Ok)
            val status = app.mail().status(app.config().config.value.chatbotId)
            assertTrue(status is Outcome.Ok && status.value.canUseMailTools)
            val repository = app.conversations()
            val id = UUID.randomUUID().toString()
            assertTrue(repository.createConversation(id) is Outcome.Ok)

            suspend fun ask(text: String): Pair<Turn, List<SendEvent.Tool>> {
                val tools = mutableListOf<SendEvent.Tool>()
                val result = repository.submitMessage(id, UUID.randomUUID().toString(), text, emailManager = true, stream = true) {
                    if (it is SendEvent.Tool) tools += it
                }
                assertTrue("Conversation request failed", result is Outcome.Ok)
                val turn = (result as Outcome.Ok).value
                assertEquals("Assistant did not complete the turn", TurnStatus.Completed, turn.status)
                assertTrue("Empty assistant answer", turn.text.isNotBlank())
                return turn to tools
            }

            val general = ask("For a synthetic app test, remember the code word LANTERN. Briefly explain how a rainbow forms. Do not access or modify email for this request.")
            assertTrue("General answer was too short", general.first.text.length >= 30)
            assertTrue("General question unexpectedly accessed mail", general.second.none { it.name == "email_manager" })

            val mailbox = ask("Use email_manager to search my Inbox for subject:NativeUnifiedReadOnlyQA_20261120. Report only whether a match exists. Do not create drafts, send, trash, organize or mark any messages.")
            assertTrue("Mailbox tool was not called", mailbox.second.any { it.name == "email_manager" && it.phase == "end" && !it.failed && it.steps.any { step -> step.contains("Searched", ignoreCase = true) } })
            assertTrue("Unexpected queued mailbox approval", mailbox.second.all { (it.waiting ?: 0) == 0 })

            val followup = ask("Without accessing email, what was the code word I asked you to remember earlier? Answer with that code word only.")
            assertTrue("Shared conversation context was lost", followup.first.text.contains("LANTERN", ignoreCase = true))
            assertTrue(repository.syncMessages(id) is Outcome.Ok)
            assertEquals("Expected three saved user/assistant pairs", 6, repository.observeMessages(id).first().size)
            println("UNIFIED_ASSISTANT_VERIFIED generalChat=true mailboxSearch=true sharedContext=true savedMessages=6 gmailMutation=false")
        }
    }
}
