package com.embeddablechatbot.android.feature.mail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.embeddablechatbot.android.core.domain.mail.ConnectCallback
import com.embeddablechatbot.android.core.domain.mail.ConnectMailboxUseCase
import com.embeddablechatbot.android.core.domain.mail.ReviewMailActionUseCase
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.ErrorReason
import com.embeddablechatbot.android.core.domain.model.MailActionStatus
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.testing.FakeAuthRepository
import com.embeddablechatbot.android.core.domain.testing.FakeConfigRepository
import com.embeddablechatbot.android.core.domain.testing.FakeMailRepository
import com.embeddablechatbot.android.core.domain.testing.FakePendingConnectStore
import com.embeddablechatbot.android.core.domain.testing.FixedClock
import com.embeddablechatbot.android.core.domain.testing.mailStatus
import com.embeddablechatbot.android.core.domain.testing.sendDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val mail = FakeMailRepository()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ApprovalViewModel(mail, ReviewMailActionUseCase(mail), SavedStateHandle(mapOf("actionId" to "a1")))

    @Test
    fun `shows the complete draft and approves exactly the reviewed version`() = runTest(dispatcher) {
        val body = "Line\n".repeat(3000)
        mail.detailResult = Outcome.Ok(sendDetail(version = "v7", body = body))
        val vm = viewModel()
        runCurrent()
        assertEquals(body, vm.state.value.detail!!.send!!.body)
        vm.approve()
        vm.approve() // double tap
        runCurrent()
        assertEquals(listOf("a1" to "v7"), mail.approvals)
        assertEquals(ApprovalNotice.Done, vm.state.value.notice)
    }

    @Test
    fun `a stale approval reloads the current content and requires review again`() = runTest(dispatcher) {
        mail.detailResult = Outcome.Ok(sendDetail(version = "v1"))
        val vm = viewModel()
        runCurrent()
        mail.approveResults += Outcome.Err(AppError.Conflict(ErrorReason.StaleApproval, "changed"))
        mail.detailResult = Outcome.Ok(sendDetail(version = "v2", body = "Edited in Gmail"))
        vm.approve()
        runCurrent()
        assertEquals(ApprovalNotice.Stale, vm.state.value.notice)
        assertEquals("v2", vm.state.value.detail!!.version)
        assertEquals("Edited in Gmail", vm.state.value.detail!!.send!!.body)
        assertEquals(1, mail.approvals.size)
    }

    @Test
    fun `an uncertain provider outcome is reported, never retried`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        mail.approveResults += Outcome.Ok(com.embeddablechatbot.android.core.domain.testing.mailAction("a1", MailActionStatus.Uncertain))
        vm.approve()
        runCurrent()
        assertEquals(ApprovalNotice.Uncertain, vm.state.value.notice)
        assertEquals(1, mail.approvals.size)
    }

    @Test
    fun `a missing draft cannot be approved`() = runTest(dispatcher) {
        mail.detailResult = Outcome.Ok(sendDetail(version = null))
        val vm = viewModel()
        runCurrent()
        vm.approve()
        runCurrent()
        assertTrue(mail.approvals.isEmpty())
    }

    @Test
    fun `scheduling approves the reviewed version for a later time and sends nothing now`() = runTest(dispatcher) {
        mail.detailResult = Outcome.Ok(sendDetail(version = "v3"))
        val vm = viewModel()
        runCurrent()
        val at = java.time.Instant.parse("2026-11-26T00:00:00Z")
        vm.schedule(at)
        vm.schedule(at) // double tap
        runCurrent()
        assertEquals(listOf("a1" to "v3"), mail.approvals)
        assertEquals(listOf<java.time.Instant?>(at), mail.scheduledAt)
        assertEquals(ApprovalNotice.Scheduled, vm.state.value.notice)
    }

    @Test
    fun `a scheduled email can be returned to review or declined`() = runTest(dispatcher) {
        val scheduled = sendDetail(version = "v3").let { it.copy(action = it.action.copy(status = MailActionStatus.Scheduled)) }
        mail.detailResult = Outcome.Ok(scheduled)
        val vm = viewModel()
        runCurrent()
        vm.approve() // a scheduled email is not open for a second approval
        runCurrent()
        assertTrue(mail.approvals.isEmpty())
        vm.unschedule()
        runCurrent()
        assertEquals(listOf("a1"), mail.unschedules)
        assertEquals(ApprovalNotice.Unscheduled, vm.state.value.notice)
        vm.reject()
        runCurrent()
        assertEquals(listOf("a1"), mail.rejections)
    }
}

class FormattingTest {
    @Test
    fun `marks wrap selections and prefix every selected line`() {
        assertEquals("a **bc** d" to (4 to 6), applyMark("a bc d", 2, 4, TextStyleMark.Bold))
        assertEquals("- one\n- two" to (11 to 11), applyMark("one\ntwo", 0, 7, TextStyleMark.Bullets))
        assertEquals("1. one\n2. two", applyMark("- one\n- two", 0, 11, TextStyleMark.Numbers).first)
        assertEquals("see [docs](https://)", applyMark("see docs", 4, 8, TextStyleMark.Link).first)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val mail = FakeMailRepository()
    private val store = FakePendingConnectStore()
    private val auth = FakeAuthRepository()
    private val bus = ConnectCallbackBus()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = MailViewModel(mail, ConnectMailboxUseCase(mail, store, FixedClock()), auth, FakeConfigRepository(), bus)

    @Test
    fun `connect opens the system browser with the server's authorization url`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.effect.test {
            vm.startConnect()
            runCurrent()
            assertEquals(MailEffect.OpenBrowser("https://accounts.google.com/o/oauth2/v2/auth?x"), awaitItem())
        }
        assertEquals("tx-1", store.value!!.transactionId)
    }

    @Test
    fun `coming back without a return link says the sign-in did not finish`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.effect.test { vm.startConnect(); runCurrent(); awaitItem() }
        vm.refresh(); advanceTimeBy(2_500); runCurrent()
        assertEquals(MailNotice.Unfinished, vm.state.value.notice)
    }

    @Test
    fun `a return link that arrives right after resuming is not reported as unfinished`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.effect.test { vm.startConnect(); runCurrent(); awaitItem() }
        vm.refresh()
        bus.post(ConnectCallback("tx-1", "k".repeat(43), null)); runCurrent()
        advanceTimeBy(2_500); runCurrent()
        assertEquals(MailNotice.Connected, vm.state.value.notice)
    }

    @Test
    fun `a return link completes once, then is consumed`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startConnect()
        runCurrent()
        val code = "k".repeat(43)
        bus.post(ConnectCallback("tx-1", code, null))
        runCurrent()
        assertEquals(MailNotice.Connected, vm.state.value.notice)
        assertEquals(1, mail.completions.size)
        // A second screen instance (or recomposition) sees no callback to replay.
        viewModel()
        runCurrent()
        assertEquals(1, mail.completions.size)
    }

    @Test
    fun `members are told mailbox tools need an owner or admin`() = runTest(dispatcher) {
        mail.statusResult = Outcome.Ok(mailStatus(connected = false, eligible = false))
        val vm = viewModel()
        vm.refresh()
        runCurrent()
        assertEquals(false, vm.state.value.status!!.eligible)
    }

    @Test
    fun `signed-out users cannot start a connection`() = runTest(dispatcher) {
        auth.state.value = AuthState.SignedOut()
        val vm = viewModel()
        vm.startConnect()
        runCurrent()
        assertTrue(mail.challenges.isEmpty())
    }
}
