package com.embeddablechatbot.android.core.domain.chat

import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.ErrorReason
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PendingSend
import com.embeddablechatbot.android.core.domain.model.PendingState
import com.embeddablechatbot.android.core.domain.model.Role
import com.embeddablechatbot.android.core.domain.model.TurnStatus
import com.embeddablechatbot.android.core.domain.testing.FakeConversationRepository
import com.embeddablechatbot.android.core.domain.testing.FixedClock
import com.embeddablechatbot.android.core.domain.testing.SequentialIds
import com.embeddablechatbot.android.core.domain.testing.conversation
import com.embeddablechatbot.android.core.domain.testing.message
import com.embeddablechatbot.android.core.domain.testing.networkUncertain
import com.embeddablechatbot.android.core.domain.testing.turn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendCoordinatorTest {
    private val repo = FakeConversationRepository()
    private val ids = SequentialIds("req")
    private val clock = FixedClock()

    private fun TestScope.workScope() = CoroutineScope(StandardTestDispatcher(testScheduler))
    private fun TestScope.coordinator() = SendCoordinator(repo, ids, clock, workScope())

    @Test
    fun `successful send persists one stable request id with trimmed text, then clears it`() = runTest {
        val sut = coordinator()
        val gate = CompletableDeferred<Unit>().also { repo.submitGate = it }

        val start = sut.send("c1", "  hello  ", emailManager = true, stream = false)
        advanceUntilIdle()

        assertEquals(SendStart.Started("req-1"), start)
        val stored = repo.pending.value.getValue("req-1")
        assertEquals("hello", stored.text)
        assertEquals(PendingState.Submitting, stored.state)
        assertTrue(stored.emailManager)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(repo.pending.value.isEmpty())
        assertEquals(listOf(Triple("c1", "req-1", "hello")), repo.submissions)
    }

    @Test
    fun `empty and over-limit messages are rejected before any request id is created`() = runTest {
        val sut = coordinator()
        assertEquals(SendStart.Empty, sut.send("c1", "   ", false, false))
        assertEquals(SendStart.TooLong(4001), sut.send("c1", "x".repeat(4001), false, false))
        assertEquals(SendStart.Started("req-1"), sut.send("c1", "x".repeat(4000), false, false))
        assertEquals(1, ids.count)
    }

    @Test
    fun `a second send is blocked while an earlier one is unresolved`() = runTest {
        val sut = coordinator()
        repo.submitGate = CompletableDeferred()
        sut.send("c1", "first", false, false)
        val second = sut.send("c1", "second", false, false)
        assertTrue(second is SendStart.Blocked)
        assertEquals(1, ids.count)
    }

    @Test
    fun `uncertain result is reconciled from server state and adopted when the turn committed`() = runTest {
        val sut = coordinator()
        repo.submitResults += networkUncertain
        repo.syncResult = { Outcome.Ok(conversation("c1", lastTurn = turn("req-1", TurnStatus.Completed))) }

        sut.send("c1", "archive my newsletters", emailManager = true, stream = true)
        advanceUntilIdle()

        assertTrue("resolved from server state, not resent", repo.pending.value.isEmpty())
        assertEquals(1, repo.submissions.size)
    }

    @Test
    fun `uncertain result with no server trace is offered for retry with the SAME request id`() = runTest {
        val sut = coordinator()
        repo.submitResults += networkUncertain
        repo.syncResult = { Outcome.Ok(conversation("c1", lastTurn = turn("older", TurnStatus.Completed))) }

        sut.send("c1", "draft a reply", emailManager = true, stream = false)
        advanceUntilIdle()

        val pending = repo.pending.value.getValue("req-1")
        assertEquals(PendingState.NotAccepted, pending.state)
        assertEquals("never auto-resent", 1, repo.submissions.size)

        assertTrue(sut.retry("req-1", stream = false))
        advanceUntilIdle()
        assertEquals(listOf("req-1", "req-1"), repo.submissions.map { it.second })
        assertEquals(listOf("draft a reply", "draft a reply"), repo.submissions.map { it.third })
        assertEquals("no new id was generated", 1, ids.count)
    }

    @Test
    fun `a committed message carrying the request id resolves an uncertain send even if a later turn ran`() = runTest {
        val sut = coordinator()
        repo.submitResults += networkUncertain
        repo.messages.value = mapOf("c1" to listOf(message("m1", requestId = "req-1"), message("m2", role = Role.Assistant, sequence = "2", requestId = "req-1")))
        repo.syncResult = { Outcome.Ok(conversation("c1", lastTurn = turn("other-device", TurnStatus.Completed))) }

        sut.send("c1", "hi", false, false)
        advanceUntilIdle()
        assertTrue(repo.pending.value.isEmpty())
    }

    @Test
    fun `a running turn keeps the send pending as Running until a terminal state is read`() = runTest {
        val sut = coordinator()
        repo.submitResults += Outcome.Err(AppError.Conflict(ErrorReason.RequestRunning, "still running"))
        repo.syncResult = { Outcome.Ok(conversation("c1", lastTurn = turn("req-1", TurnStatus.Running))) }
        sut.send("c1", "hi", false, false)
        advanceUntilIdle()
        assertEquals(PendingState.Running, repo.pending.value.getValue("req-1").state)

        repo.syncResult = { Outcome.Ok(conversation("c1", lastTurn = turn("req-1", TurnStatus.Failed, error = "model failed"))) }
        sut.reconcile("c1")
        assertTrue("a failed turn is still a resolved request", repo.pending.value.isEmpty())
    }

    @Test
    fun `busy conversation and missing mailbox are definite refusals that keep the request id`() = runTest {
        for (reason in listOf(ErrorReason.ConversationBusy, ErrorReason.MailboxNotConnected)) {
            val repo = FakeConversationRepository()
            val sut = SendCoordinator(repo, SequentialIds(reason.wire), clock, workScope())
            repo.submitResults += Outcome.Err(AppError.Conflict(reason, "refused"))
            sut.send("c1", "hi", true, false)
            advanceUntilIdle()
            val pending = repo.pending.value.values.single()
            assertEquals(PendingState.NotAccepted, pending.state)
            assertEquals("refused", pending.errorMessage)
            assertEquals("not reconciled: the server refused before executing", 0, repo.syncCount)
        }
    }

    @Test
    fun `after process death a Submitting send is reconciled, never resubmitted`() = runTest {
        repo.pending.value = mapOf("req-x" to PendingSend("req-x", "c1", "hi", true, clock.now(), PendingState.Submitting))
        repo.syncResult = { Outcome.Ok(conversation("c1", lastTurn = null)) }
        val sut = coordinator()

        sut.reconcile("c1")

        assertEquals(PendingState.NotAccepted, repo.pending.value.getValue("req-x").state)
        assertTrue(repo.submissions.isEmpty())
    }

    @Test
    fun `offline reconcile leaves the send uncertain`() = runTest {
        repo.pending.value = mapOf("req-x" to PendingSend("req-x", "c1", "hi", false, clock.now(), PendingState.Submitting))
        repo.syncResult = { Outcome.Err(AppError.Network(uncertain = false)) }
        coordinator().reconcile("c1")
        assertEquals(PendingState.Uncertain, repo.pending.value.getValue("req-x").state)
    }

    @Test
    fun `a deleted conversation drops its pending sends`() = runTest {
        repo.pending.value = mapOf("req-x" to PendingSend("req-x", "c1", "hi", false, clock.now(), PendingState.Uncertain))
        repo.syncResult = { Outcome.Err(AppError.NotFound("gone")) }
        coordinator().reconcile("c1")
        assertTrue(repo.pending.value.isEmpty())
    }

    @Test
    fun `discard only applies to refused sends and returns the text`() = runTest {
        repo.pending.value = mapOf(
            "a" to PendingSend("a", "c1", "refused text", false, clock.now(), PendingState.NotAccepted),
            "b" to PendingSend("b", "c2", "uncertain", false, clock.now(), PendingState.Uncertain),
        )
        val sut = coordinator()
        assertEquals("refused text", sut.discard("a"))
        assertNull(sut.discard("b"))
        assertEquals(setOf("b"), repo.pending.value.keys)
    }

    @Test
    fun `retry is refused for sends that might have executed`() = runTest {
        repo.pending.value = mapOf("u" to PendingSend("u", "c1", "x", true, clock.now(), PendingState.Uncertain))
        assertTrue(!coordinator().retry("u", false))
        assertTrue(repo.submissions.isEmpty())
    }

    @Test
    fun `stream events build provisional live reply that disappears on completion`() = runTest {
        val sut = coordinator()
        val gate = CompletableDeferred<Unit>().also { repo.submitGate = it }
        sut.send("c1", "hi", false, true)
        advanceUntilIdle()
        assertEquals(setOf("req-1"), sut.liveReplies.value.keys)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(sut.liveReplies.value.isEmpty())
    }

    @Test
    fun `cancelAll stops local work for the previous account`() = runTest {
        val sut = coordinator()
        repo.submitGate = CompletableDeferred()
        sut.send("c1", "hi", false, false)
        advanceUntilIdle()
        sut.cancelAll()
        advanceUntilIdle()
        assertTrue(sut.liveReplies.value.isEmpty())
    }

    @Test
    fun `server failure is treated as uncertain, not as a refusal`() = runTest {
        val sut = coordinator()
        repo.submitResults += Outcome.Err(AppError.Server(502, "interrupted"))
        repo.syncResult = { Outcome.Ok(conversation("c1", lastTurn = turn("req-1", TurnStatus.Interrupted))) }
        sut.send("c1", "hi", true, false)
        advanceUntilIdle()
        assertTrue("interrupted turn is terminal: resolved without resending", repo.pending.value.isEmpty())
        assertEquals(1, repo.submissions.size)
    }
}
