package com.embeddablechatbot.android.core.domain.auth

import com.embeddablechatbot.android.core.domain.chat.SendCoordinator
import com.embeddablechatbot.android.core.domain.model.Account
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.PendingConnect
import com.embeddablechatbot.android.core.domain.model.PlatformConfig
import com.embeddablechatbot.android.core.domain.model.DecimalStrings
import com.embeddablechatbot.android.core.domain.testing.FakeAuthRepository
import com.embeddablechatbot.android.core.domain.testing.FakeCleaner
import com.embeddablechatbot.android.core.domain.testing.FakeConversationRepository
import com.embeddablechatbot.android.core.domain.testing.FakePendingConnectStore
import com.embeddablechatbot.android.core.domain.testing.FixedClock
import com.embeddablechatbot.android.core.domain.testing.SequentialIds
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountLifecycleTest {
    @Test
    fun `restoring the same account keeps the cache, sign-out and account switch clear it`() = runTest {
        val auth = FakeAuthRepository(AuthState.Restoring)
        val cleaner = FakeCleaner()
        val connect = FakePendingConnectStore().apply { value = PendingConnect("tx", "v", Instant.EPOCH, "a") }
        val coordinator = SendCoordinator(FakeConversationRepository(), SequentialIds(), FixedClock(), backgroundScope)
        val lifecycle = AccountLifecycle(auth, cleaner, coordinator, connect)
        backgroundScope.launch { lifecycle.run() }
        runCurrent()

        auth.state.value = AuthState.SignedIn(Account("a", "a@example.test"))
        runCurrent()
        assertEquals(0, cleaner.clears)
        assertNotNull(connect.value)

        auth.state.value = AuthState.SignedOut()
        runCurrent()
        assertEquals(1, cleaner.clears)
        assertNull(connect.value)

        auth.state.value = AuthState.SignedIn(Account("b", "b@example.test"))
        runCurrent()
        assertEquals(2, cleaner.clears)
    }

    @Test
    fun `starting signed out clears leftovers from a killed session`() = runTest {
        val auth = FakeAuthRepository(AuthState.Restoring)
        val cleaner = FakeCleaner()
        val lifecycle = AccountLifecycle(auth, cleaner, SendCoordinator(FakeConversationRepository(), SequentialIds(), FixedClock(), backgroundScope), FakePendingConnectStore())
        backgroundScope.launch { lifecycle.run() }
        auth.state.value = AuthState.SignedOut()
        runCurrent()
        assertEquals(1, cleaner.clears)
    }
}

class RulesTest {
    @Test
    fun `decimal strings compare numerically without conversion`() {
        val values = listOf("10", "9", "100", "2", "18446744073709551616", "0")
        assertEquals(listOf("0", "2", "9", "10", "100", "18446744073709551616"), values.sortedWith(DecimalStrings::compare))
        assertTrue(DecimalStrings.isValid("12345678901234567890123"))
        assertTrue(!DecimalStrings.isValid("1.5") && !DecimalStrings.isValid("01") && !DecimalStrings.isValid("-1"))
    }

    @Test
    fun `release configuration requires https and permits server-assigned chatbot selection`() {
        val ok = PlatformConfig("https://p.test", "https://a.test", "sb_publishable_x", "cb_abcdefghijklmnop")
        assertNull(PlatformConfig.validate(ok, allowCleartext = false))
        assertNull(PlatformConfig.validate(ok.copy(chatbotId = ""), allowCleartext = false))
        assertNotNull(PlatformConfig.validate(ok.copy(platformUrl = "http://10.0.2.2:3000"), allowCleartext = false))
        assertNull(PlatformConfig.validate(ok.copy(platformUrl = "http://10.0.2.2:3000"), allowCleartext = true))
        assertNotNull(PlatformConfig.validate(ok.copy(chatbotId = "cbk_private"), allowCleartext = false))
    }
}
