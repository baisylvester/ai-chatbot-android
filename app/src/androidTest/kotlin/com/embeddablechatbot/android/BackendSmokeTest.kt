package com.embeddablechatbot.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.embeddablechatbot.android.core.domain.mail.MailFolder
import com.embeddablechatbot.android.core.domain.mail.WritingRequest
import com.embeddablechatbot.android.core.domain.mail.WritingTask
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.ConnectionStatus
import com.embeddablechatbot.android.core.domain.model.Outcome
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Explicitly opted-in real backend check: mailbox reads and synthetic AI text only. */
@RunWith(AndroidJUnit4::class)
class BackendSmokeTest {
    @get:Rule val wakeLock=DeviceVerificationWakeLock()
    @Test fun signedInAccountCanPrepareReadAndGenerate() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verifyCloudBackend") == "true")
        val expectedEmail = requireNotNull(args.getString("expectedEmail"))
        val expectedPlatform = requireNotNull(args.getString("expectedPlatformUrl"))
        withTimeout(180_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val app = EntryPointAccessors.fromApplication(context, BackendVerificationEntryPoint::class.java)
            app.startup().awaitReady()
            assertEquals("Unexpected backend configuration", expectedPlatform, app.config().config.value.platformUrl)
            val account = app.auth().authState.value as? AuthState.SignedIn
            assertTrue("The expected account must already be signed in", account?.account?.email?.equals(expectedEmail, ignoreCase = true) == true)

            assertTrue("Private assistant provisioning failed", app.onboarding().prepare() is Outcome.Ok)
            val statusResult = app.mail().status(app.config().config.value.chatbotId)
            assertTrue("Mailbox status failed (${(statusResult as? Outcome.Err)?.error?.javaClass?.simpleName ?: "unexpected response"})", statusResult is Outcome.Ok)
            val status = (statusResult as Outcome.Ok).value
            assertTrue("Mail server configuration is incomplete", status.serverReady)
            assertTrue("Personal assistant mailbox access was refused", status.eligible)
            assertEquals(ConnectionStatus.Active, status.connection?.status)
            assertTrue("A different mailbox is connected", status.connection?.email?.equals(expectedEmail, ignoreCase = true) == true)

            val inbox = app.inbox().list(MailFolder.Inbox, "")
            assertTrue("Real inbox read failed", inbox is Outcome.Ok)
            val writing = app.inbox().write(WritingRequest(WritingTask.Write, "Write a short friendly email thanking a colleague for helping with a presentation.", length = "Short"))
            assertTrue("Synthetic AI writing failed", writing is Outcome.Ok)
            assertTrue("Synthetic AI output was empty", (writing as Outcome.Ok).value.trim().length >= 30)
            println("CLOUD_BACKEND_VERIFIED provisioning=true mailbox=true inbox=true aiWriting=true gmailMutation=false")
        }
    }
}
