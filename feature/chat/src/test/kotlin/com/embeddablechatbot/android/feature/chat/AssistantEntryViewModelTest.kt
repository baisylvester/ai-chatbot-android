package com.embeddablechatbot.android.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.testing.FakeConversationRepository
import com.embeddablechatbot.android.core.domain.testing.SequentialIds
import com.embeddablechatbot.android.core.domain.testing.conversation
import com.embeddablechatbot.android.core.domain.testing.turn
import com.embeddablechatbot.android.core.domain.model.TurnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantEntryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeConversationRepository()
    private val ids = SequentialIds("assistant")
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()
    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = AssistantEntryViewModel(repo, ids, saved)

    @Test fun `assistant resumes most recently updated private conversation`() = runTest {
        repo.conversations.value = listOf(conversation("older"), conversation("latest").let { it.copy(updatedAt = it.updatedAt.plusSeconds(50)) })
        val vm = vm()
        vm.effect.test {
            vm.open(); dispatcher.scheduler.runCurrent()
            assertEquals("latest", awaitItem())
        }
        assertTrue(repo.created.isEmpty())
    }

    @Test fun `first assistant entry creates one conversation despite repeated taps`() = runTest {
        val vm = vm()
        vm.effect.test {
            vm.open(); vm.open(); dispatcher.scheduler.runCurrent()
            assertEquals("assistant-1", awaitItem())
        }
        assertEquals(listOf("assistant-1"), repo.created)
    }

    @Test fun `prompt reuses an unused conversation instead of adding an empty one`() = runTest {
        repo.conversations.value = listOf(conversation("used", lastTurn = turn("r1", TurnStatus.Completed)), conversation("empty"))
        val vm = vm(SavedStateHandle(mapOf("draft" to "Write a meeting invitation")))
        vm.effect.test {
            vm.open(); dispatcher.scheduler.runCurrent()
            assertEquals("empty", awaitItem())
        }
        assertEquals(2, repo.conversations.value.size)
        assertTrue(repo.submissions.isEmpty())
    }

    @Test fun `prompt opens a fresh thread without submitting it or replacing history`() = runTest {
        repo.conversations.value = listOf(conversation("existing", lastTurn = turn("r1", TurnStatus.Completed)))
        val vm = vm(SavedStateHandle(mapOf("draft" to "Write a meeting invitation")))
        vm.effect.test {
            vm.open(); dispatcher.scheduler.runCurrent()
            assertEquals("assistant-1", awaitItem())
        }
        assertEquals(2, repo.conversations.value.size)
        assertTrue(repo.submissions.isEmpty())
    }

    @Test fun `uncertain creation retries the retained id after recreation`() = runTest {
        val saved = SavedStateHandle()
        repo.createResult = { Outcome.Err(AppError.Network(uncertain = true)) }
        vm(saved).open(); dispatcher.scheduler.runCurrent()
        repo.createResult = { Outcome.Ok(conversation(it)) }
        val restored = vm(saved)
        restored.effect.test {
            restored.open(); dispatcher.scheduler.runCurrent()
            assertEquals("assistant-1", awaitItem())
        }
        assertEquals(listOf("assistant-1", "assistant-1"), repo.created)
        assertEquals(1, ids.count)
    }

    @Test fun `offline entry uses cached history and never creates a duplicate`() = runTest {
        repo.refreshResult = Outcome.Err(AppError.Network(uncertain = false))
        repo.conversations.value = listOf(conversation("cached"))
        val vm = vm()
        vm.effect.test {
            vm.open(); dispatcher.scheduler.runCurrent()
            assertEquals("cached", awaitItem())
        }
        assertTrue(repo.created.isEmpty())
    }

    @Test fun `offline without history offers retry without creating a thread`() = runTest {
        repo.refreshResult = Outcome.Err(AppError.Network(uncertain = false))
        val vm = vm()
        vm.open(); dispatcher.scheduler.runCurrent()
        assertFalse(vm.state.value.opening)
        assertNotNull(vm.state.value.error)
        assertTrue(repo.created.isEmpty())
    }
}
