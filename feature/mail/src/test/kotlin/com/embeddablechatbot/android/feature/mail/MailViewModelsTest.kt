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
