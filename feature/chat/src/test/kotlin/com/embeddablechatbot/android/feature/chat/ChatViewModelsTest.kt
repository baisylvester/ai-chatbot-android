package com.embeddablechatbot.android.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.embeddablechatbot.android.core.domain.chat.SendCoordinator
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.ChatbotInfo
import com.embeddablechatbot.android.core.domain.model.KnowledgeInfo
import com.embeddablechatbot.android.core.domain.model.KnowledgeState
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PendingSend
import com.embeddablechatbot.android.core.domain.model.PendingState
import com.embeddablechatbot.android.core.domain.model.TurnStatus
import com.embeddablechatbot.android.core.domain.testing.FakeChatbotRepository
import com.embeddablechatbot.android.core.domain.testing.FakeConfigRepository
import com.embeddablechatbot.android.core.domain.testing.FakeConnectivity
import com.embeddablechatbot.android.core.domain.testing.FakeConversationRepository
import com.embeddablechatbot.android.core.domain.testing.FakeMailRepository
import com.embeddablechatbot.android.core.domain.testing.FakePreferencesRepository
import com.embeddablechatbot.android.core.domain.testing.FixedClock
import com.embeddablechatbot.android.core.domain.testing.SequentialIds
import com.embeddablechatbot.android.core.domain.testing.conversation
import com.embeddablechatbot.android.core.domain.testing.mailStatus
import com.embeddablechatbot.android.core.domain.testing.turn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModelTest {
    private val dispatcher = StandardTestDispatcher(TestCoroutineScheduler())
    private val repo = FakeConversationRepository()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle(), ids: SequentialIds = SequentialIds("conv")) =
        ConversationListViewModel(repo, FakeChatbotRepository(), FakeMailRepository(), FakeConfigRepository(), ids, FakeConnectivity(), saved)

    @Test
    fun `an uncertain create is retried with the same conversation id, even after process death`() = runTest(StandardTestDispatcher(TestCoroutineScheduler()), timeout = 10.seconds) {
        val saved = SavedStateHandle()
        val ids = SequentialIds("conv")
        repo.createResult = { Outcome.Err(AppError.Network(uncertain = true)) }
        viewModel(saved, ids).apply { newConversation() }
        dispatcher.scheduler.runCurrent()

        // Recreated from saved state (process death): still the same ID.
        repo.createResult = { Outcome.Ok(conversation(it)) }
        val vm = viewModel(saved, ids)
        vm.effect.test {
            vm.newConversation()
            dispatcher.scheduler.runCurrent()
            assertEquals(ListEffect.OpenConversation("conv-1"), awaitItem())
        }
        assertEquals(listOf("conv-1", "conv-1"), repo.created)
        assertEquals(1, ids.count)
    }

    @Test
    fun `a definite refusal forgets the id so the next tap starts fresh`() = runTest(StandardTestDispatcher(TestCoroutineScheduler()), timeout = 10.seconds) {
        val saved = SavedStateHandle()
        repo.createResult = { Outcome.Err(AppError.Forbidden(null, "inactive chatbot")) }
        val vm = viewModel(saved)
        vm.newConversation()
        dispatcher.scheduler.runCurrent()
        assertFalse(saved.contains("pendingNewConversationId"))
    }

    @Test
    fun `offline refresh marks the cached list as not current`() = runTest(StandardTestDispatcher(TestCoroutineScheduler()), timeout = 10.seconds) {
        repo.refreshResult = Outcome.Err(AppError.Network(uncertain = false))
        val vm = viewModel()
        val job = vm.state.launchIn(CoroutineScope(dispatcher))
        dispatcher.scheduler.runCurrent()
        assertEquals(Freshness.Offline, vm.state.value.freshness)
        job.cancel()
    }
    @Test
    fun `server unavailable does not mark a working network as offline`() = runTest(StandardTestDispatcher(TestCoroutineScheduler()), timeout = 10.seconds) {
        repo.refreshResult = Outcome.Err(AppError.NotConfigured("Native API is not deployed"))
        val vm = viewModel()
        val job = vm.state.launchIn(CoroutineScope(dispatcher))
        dispatcher.scheduler.runCurrent()
        assertEquals(Freshness.Unavailable, vm.state.value.freshness)
        job.cancel()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher(TestCoroutineScheduler())
    private val repo = FakeConversationRepository()
    private val mail = FakeMailRepository()
    private val prefs = FakePreferencesRepository()
    private val chatbots = FakeChatbotRepository()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(saved: SavedStateHandle = SavedStateHandle(mapOf("conversationId" to "c1"))): Pair<ChatViewModel, SendCoordinator> {
        val coordinator = SendCoordinator(repo, SequentialIds("req"), FixedClock(), CoroutineScope(dispatcher))
        return ChatViewModel(repo, coordinator, chatbots, mail, prefs, FakeConfigRepository(), FakeConnectivity(), saved) to coordinator
    }

    @Test
    fun `refresh picks up changed knowledge metadata without recreating the conversation`() = runTest {
        val knowledge=KnowledgeInfo(KnowledgeState.Ready,"cb_shared","System knowledge",1,0,12,null,true)
        chatbots.result=Outcome.Ok(ChatbotInfo("cb_personal","Assistant","",emptyList(),knowledge))
        val (vm,_)=viewModel(); val job=vm.state.launchIn(CoroutineScope(dispatcher))
        dispatcher.scheduler.runCurrent()
        assertEquals(1,vm.state.value.chatbot?.knowledge?.readyDocuments)
        vm.onDraft("Keep my draft")
        chatbots.result=Outcome.Ok(ChatbotInfo("cb_personal","Assistant","",emptyList(),knowledge.copy(readyDocuments=2,chunks=16)))
        vm.refresh();dispatcher.scheduler.runCurrent()
        assertEquals(2,vm.state.value.chatbot?.knowledge?.readyDocuments)
        assertEquals("Keep my draft",vm.state.value.draft);assertTrue(repo.created.isEmpty());job.cancel()
    }

    @Test
    fun `failed knowledge refresh clears stale source metadata`() = runTest {
        val (vm,_)=viewModel();val job=vm.state.launchIn(CoroutineScope(dispatcher))
        dispatcher.scheduler.runCurrent()
        chatbots.result=Outcome.Err(AppError.Network(false))
        vm.refresh();dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.chatbot);assertTrue(vm.state.value.knowledgeUnavailable);job.cancel()
    }

    @Test
    fun `sending clears the draft, shows the pending bubble and blocks a second send`() = runTest(StandardTestDispatcher(TestCoroutineScheduler()), timeout = 10.seconds) {
        val (vm, _) = viewModel()
        val job = vm.state.launchIn(CoroutineScope(dispatcher))
        repo.submitGate = CompletableDeferred()
        dispatcher.scheduler.runCurrent()
        vm.onDraft("Summarize unread mail")
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.canSend)
        vm.send()
        dispatcher.scheduler.runCurrent()
        val s = vm.state.value
        assertEquals("", s.draft)
        assertTrue(s.blockedByPending)
        assertFalse(s.canSend)
        assertTrue(s.items.filterIsInstance<ChatItem.Live>().size == 1)
        assertEquals(true, s.items.filterIsInstance<ChatItem.Pending>().single().pending.emailManager)
        job.cancel()
    }

    @Test
    fun `mail tools are only sent when eligible and connected`() = runTest(StandardTestDispatcher(TestCoroutineScheduler()), timeout = 10.seconds) {
        mail.statusResult = Outcome.Ok(mailStatus(connected = true, eligible = false))
        val (vm, _) = viewModel()
        val job = vm.state.launchIn(CoroutineScope(dispatcher))
        dispatcher.scheduler.runCurrent()
        assertFalse(vm.state.value.mailToolsAvailable)
        job.cancel()
    }

    @Test
    fun `unified assistant enables connected Gmail without a legacy mode switch`() = runTest {
        prefs.update { it.copy(mailToolsEnabled = false) }
        val (vm, _) = viewModel()
        val job = vm.state.launchIn(CoroutineScope(dispatcher))
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.mailToolsAvailable)
        job.cancel()
    }

    @Test
    fun `disconnected Gmail still allows a general chat without mailbox tools`() = runTest {
        mail.statusResult = Outcome.Ok(mailStatus(connected = false))
        val (vm, _) = viewModel()
        val job = vm.state.launchIn(CoroutineScope(dispatcher))
        repo.submitGate = CompletableDeferred()
        dispatcher.scheduler.runCurrent()
        vm.onDraft("Explain how rain forms")
        dispatcher.scheduler.runCurrent()
        vm.send()
        dispatcher.scheduler.runCurrent()
        assertFalse(repo.pending.value.values.single().emailManager)
        assertFalse(vm.state.value.mailToolsAvailable)
        job.cancel()
    }

    @Test
    fun `failed Gmail refresh clears stale access without losing the conversation draft`() = runTest {
        val (vm, _) = viewModel()
        val job = vm.state.launchIn(CoroutineScope(dispatcher))
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.mailToolsAvailable)
        vm.onDraft("Keep this draft")
        mail.statusResult = Outcome.Err(AppError.Network(uncertain = false))
        vm.refresh()
        dispatcher.scheduler.runCurrent()
        assertFalse(vm.state.value.mailToolsAvailable)
        assertNotNull(vm.state.value.mailStatusError)
        assertEquals("Keep this draft", vm.state.value.draft)
        job.cancel()
    }

    @Test
    fun `writing tool handoff preserves existing text and is consumed once across recreation`() = runTest {
        val saved = SavedStateHandle(mapOf("conversationId" to "c1"))
        val vm = viewModel(saved).first
        vm.onDraft("My instructions")
        vm.acceptAssistantInput("handoff-1", "Generated email")
        assertEquals("My instructions\n\nGenerated email", saved.get<String>("draft"))
        viewModel(saved).first.acceptAssistantInput("handoff-1", "Generated email")
        assertEquals("My instructions\n\nGenerated email", saved.get<String>("draft"))
        assertTrue(repo.submissions.isEmpty())
    }

    @Test
    fun `the draft survives process death through saved state`() = runTest(StandardTestDispatcher(TestCoroutineScheduler()), timeout = 10.seconds) {
        val saved = SavedStateHandle(mapOf("conversationId" to "c1"))
        viewModel(saved).first.onDraft("half-written")
        assertEquals("half-written", viewModel(saved).first.state.value.draft)
    }

    @Test
    fun `a failed turn without a reply is shown, and an uncertain send offers status check not resend`() = runTest(StandardTestDispatcher(TestCoroutineScheduler()), timeout = 10.seconds) {
        repo.syncResult = { Outcome.Ok(conversation("c1", lastTurn = turn("r0", TurnStatus.Failed, error = "model failed"))) }
        repo.pending.value = mapOf("r1" to PendingSend("r1", "c1", "hi", true, FixedClock().now(), PendingState.Uncertain))
        val (vm, _) = viewModel()
        val job = vm.state.launchIn(CoroutineScope(dispatcher))
        dispatcher.scheduler.runCurrent()
        val items = vm.state.value.items
        assertTrue(items.any { it is ChatItem.TurnNotice })
        // Reconcile found no trace, so the send became a safe same-ID retry; nothing was resubmitted.
        assertEquals(PendingState.NotAccepted, repo.pending.value.getValue("r1").state)
        assertTrue(repo.submissions.isEmpty())
        job.cancel()
    }

    @Test
    fun `a deleted conversation navigates away once`() = runTest(StandardTestDispatcher(TestCoroutineScheduler()), timeout = 10.seconds) {
        repo.syncResult = { Outcome.Err(AppError.NotFound("gone")) }
        val (vm, _) = viewModel()
        vm.effect.test {
            val job = vm.state.launchIn(CoroutineScope(dispatcher))
            dispatcher.scheduler.runCurrent()
            assertEquals(ChatEffect.ConversationGone, awaitItem())
            dispatcher.scheduler.advanceTimeBy(1_000)
            job.cancel()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
