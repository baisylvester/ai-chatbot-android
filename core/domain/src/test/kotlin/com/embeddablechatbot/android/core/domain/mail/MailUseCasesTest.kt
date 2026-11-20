package com.embeddablechatbot.android.core.domain.mail

import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.ErrorReason
import com.embeddablechatbot.android.core.domain.model.MailActionStatus
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PendingConnect
import com.embeddablechatbot.android.core.domain.testing.FakeMailRepository
import com.embeddablechatbot.android.core.domain.testing.FakePendingConnectStore
import com.embeddablechatbot.android.core.domain.testing.FixedClock
import com.embeddablechatbot.android.core.domain.testing.sendDetail
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectMailboxUseCaseTest {
    private val repo = FakeMailRepository()
    private val store = FakePendingConnectStore()
    private val clock = FixedClock(Instant.parse("2026-11-18T00:00:00Z"))
    private val sut = ConnectMailboxUseCase(repo, store, clock)

    @Test
    fun `start sends only the S256 challenge and keeps the verifier on the device`() = runTest {
        val url = sut.start("cb_test", "user-1")
        assertTrue((url as Outcome.Ok).value.startsWith("https://accounts.google.com/"))
        val saved = assertNotNull(store.value).let { store.value!! }
        assertEquals(Pkce.challenge(saved.verifier), repo.challenges.single())
        assertTrue(saved.verifier != repo.challenges.single())
        assertEquals("user-1", saved.accountId)
    }

    @Test
    fun `completion requires the matching transaction and account and then clears the attempt`() = runTest {
        store.value = PendingConnect("tx-1", "verifier", Instant.parse("2026-11-18T00:10:00Z"), "user-1")
        val code = "c".repeat(43)

        assertEquals(ConnectResult.Ignored, sut.complete(ConnectCallback("tx-other", code, null), "user-1"))
        assertEquals(ConnectResult.Ignored, sut.complete(ConnectCallback("tx-1", code, null), "user-2"))
        assertTrue(repo.completions.isEmpty())

        val result = sut.complete(ConnectCallback("tx-1", code, null), "user-1")
        assertTrue(result is ConnectResult.Connected)
        assertEquals(Triple("tx-1", code, "verifier"), repo.completions.single())
        assertNull(store.value)
        assertEquals("a replayed link is ignored", ConnectResult.Ignored, sut.complete(ConnectCallback("tx-1", code, null), "user-1"))
    }

    @Test
    fun `expired attempts, provider errors and malformed codes never call the server`() = runTest {
        store.value = PendingConnect("tx-1", "v", Instant.parse("2026-11-17T23:59:00Z"), "user-1")
        assertTrue(sut.complete(ConnectCallback("tx-1", "c".repeat(43), null), "user-1") is ConnectResult.Declined)
        store.value = PendingConnect("tx-1", "v", Instant.parse("2026-11-18T00:10:00Z"), "user-1")
        assertTrue(sut.complete(ConnectCallback("tx-1", null, "denied"), "user-1") is ConnectResult.Declined)
        store.value = PendingConnect("tx-1", "v", Instant.parse("2026-11-18T00:10:00Z"), "user-1")
        assertTrue(sut.complete(ConnectCallback("tx-1", "short", null), "user-1") is ConnectResult.Declined)
        assertTrue(repo.completions.isEmpty())
    }

    @Test
    fun `a network failure keeps the attempt so it can complete before expiry`() = runTest {
        store.value = PendingConnect("tx-1", "v", Instant.parse("2026-11-18T00:10:00Z"), "user-1")
        repo.completeResult = Outcome.Err(AppError.Network(uncertain = true))
        assertTrue(sut.complete(ConnectCallback("tx-1", "c".repeat(43), null), "user-1") is ConnectResult.Failed)
        assertNotNull(store.value)
        repo.completeResult = Outcome.Err(AppError.Conflict(ErrorReason.VerificationFailed, "no"))
        sut.complete(ConnectCallback("tx-1", "c".repeat(43), null), "user-1")
        assertNull(store.value)
    }
}

class ReviewMailActionUseCaseTest {
    private val repo = FakeMailRepository()
    private val sut = ReviewMailActionUseCase(repo)

    @Test
    fun `approval sends the reviewed version`() = runTest {
        val result = sut.approve(sendDetail(version = "v42"))
        assertEquals(MailActionStatus.Done, (result as ApprovalResult.Finished).action.status)
        assertEquals("a1" to "v42", repo.approvals.single())
    }

    @Test
    fun `no version or a handled action cannot be approved`() = runTest {
        assertEquals(ApprovalResult.Stale, sut.approve(sendDetail(version = null)))
        assertEquals(ApprovalResult.Stale, sut.approve(sendDetail(status = MailActionStatus.Done)))
        assertTrue(repo.approvals.isEmpty())
    }

    @Test
    fun `stale and already-handled server answers are surfaced distinctly`() = runTest {
        repo.approveResults += Outcome.Err(AppError.Conflict(ErrorReason.StaleApproval, "changed"))
        repo.approveResults += Outcome.Err(AppError.Conflict(ErrorReason.AlreadyHandled, "handled"))
        assertEquals(ApprovalResult.Stale, sut.approve(sendDetail()))
        assertEquals(ApprovalResult.AlreadyHandled, sut.approve(sendDetail()))
    }

    @Test
    fun `a double tap sends a single approval request`() = runTest {
        repo.approveGate = CompletableDeferred()
        val first = async { sut.approve(sendDetail()) }
        val second = async { sut.approve(sendDetail()) }
        advanceUntilIdle()
        repo.approveGate!!.complete(Unit)
        assertEquals(ApprovalResult.AlreadyHandled, second.await())
        assertTrue(first.await() is ApprovalResult.Finished)
        assertEquals(1, repo.approvals.size)
    }

    @Test
    fun `reject only applies to pending actions`() = runTest {
        assertEquals(ApprovalResult.AlreadyHandled, sut.reject(sendDetail(status = MailActionStatus.Done)))
        assertTrue(sut.reject(sendDetail()) is ApprovalResult.Finished)
        assertEquals(listOf("a1"), repo.rejections)
    }
}
