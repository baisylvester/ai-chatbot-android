package com.embeddablechatbot.android.core.data.repository

import com.embeddablechatbot.android.core.data.DirectTransactions
import com.embeddablechatbot.android.core.data.FakeChatDao
import com.embeddablechatbot.android.core.data.FakeTokens
import com.embeddablechatbot.android.core.data.conversationJson
import com.embeddablechatbot.android.core.data.messageJson
import com.embeddablechatbot.android.core.data.network.ClientApi
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PlatformConfig
import com.embeddablechatbot.android.core.domain.testing.FakeAuthRepository
import com.embeddablechatbot.android.core.domain.testing.FakeConfigRepository
import com.embeddablechatbot.android.core.domain.testing.FixedClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationRepositoryImplTest {
    private val server = MockWebServer()
    private val tokens = FakeTokens()
    private val dao = FakeChatDao()
    private lateinit var config: FakeConfigRepository
    private lateinit var repo: ConversationRepositoryImpl

    @Before
    fun setUp() {
        server.start()
        config = FakeConfigRepository(PlatformConfig(server.url("/").toString().trimEnd('/'), "https://a.test", "k", "cb_testbot"), allowCleartext = true)
        val keys = AccountKeys(tokens, config)
        repo = ConversationRepositoryImpl(ClientApi(OkHttpClient(), tokens, config), dao, DirectTransactions, keys, config, FixedClock(), FakeAuthRepository())
    }

    @After
    fun tearDown() = server.close()

    private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("X-Authenticated-User", "user-1").body(body).build()
    private fun page(messages: List<String>, next: String, more: Boolean, lastTurn: String = "null") =
        ok("""{"conversation":${conversationJson(lastTurn = lastTurn)},"messages":[${messages.joinToString(",")}],"nextCursor":"$next","hasMore":$more}""")

    @Test
    fun `sync follows the cursor until hasMore is false, merges by id and keeps numeric order`() = runBlocking {
        server.enqueue(page(listOf(messageJson("m1", "1"), messageJson("m2", "2")), next = "2", more = true))
        server.enqueue(page(listOf(messageJson("m10", "10", role = "assistant"), messageJson("m2", "2")), next = "10", more = false))

        assertTrue(repo.syncMessages("c1") is Outcome.Ok)

        assertEquals("/api/v1/client/conversations/c1/messages?after=0", server.takeRequest().target)
        assertEquals("/api/v1/client/conversations/c1/messages?after=2", server.takeRequest().target)
        assertEquals(listOf("m1", "m2", "m10"), repo.observeMessages("c1").first().map { it.id })
        assertEquals("10", dao.syncStates.values.single().cursor)

        // The next sync resumes from the retained cursor.
        server.enqueue(page(emptyList(), next = "10", more = false))
        repo.syncMessages("c1")
        assertEquals("/api/v1/client/conversations/c1/messages?after=10", server.takeRequest().target)
    }

    @Test
    fun `responses that arrive after an account change are not written`() = runBlocking {
        server.enqueue(page(listOf(messageJson("m9", "9")), next = "9", more = false))
        tokens.epoch = 2 // token issued under epoch 2...
        val api = ClientApi(OkHttpClient(), object : com.embeddablechatbot.android.core.data.auth.AccessTokenProvider by tokens {
            override suspend fun token() = com.embeddablechatbot.android.core.data.auth.TokenResult.Token("token-1", "user-1", 1) // ...but this request belongs to epoch 1
        }, config)
        val stale = ConversationRepositoryImpl(api, dao, DirectTransactions, AccountKeys(tokens, config), config, FixedClock(), FakeAuthRepository())
        assertEquals(AppError.Unauthorized, (stale.syncMessages("c1") as Outcome.Err).error)
        assertTrue(dao.messages.value.isEmpty())
    }

    @Test
    fun `cache rows are namespaced by account, platform and chatbot`() = runBlocking {
        server.enqueue(page(listOf(messageJson("m1", "1")), next = "1", more = false))
        repo.syncMessages("c1")
        tokens.userId = "user-2"
        assertTrue("another account sees nothing", repo.observeMessages("c1").first().isEmpty())
        tokens.userId = "user-1"
        config.update(config.config.value.copy(chatbotId = "cb_otherbot"))
        assertTrue("another chatbot sees nothing", repo.observeMessages("c1").first().isEmpty())
    }

    @Test
    fun `a 404 removes the conversation and its transcript locally`() = runBlocking {
        server.enqueue(page(listOf(messageJson("m1", "1")), next = "1", more = false))
        repo.syncMessages("c1")
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":{"code":"not_found","message":"Conversation not found"}}""").build())
        assertTrue((repo.syncMessages("c1") as Outcome.Err).error is AppError.NotFound)
        assertTrue(dao.messages.value.isEmpty())
        assertTrue(dao.conversations.value.isEmpty())
    }

    @Test
    fun `refresh removes conversations deleted on another device and keeps the opaque cursor`() = runBlocking {
        server.enqueue(ok("""{"conversations":[${conversationJson("a", createdAt = "2026-11-17T03:00:00.000Z")},${conversationJson("b", createdAt = "2026-11-17T02:00:00.000Z")}],"nextCursor":null}"""))
        repo.refreshConversations()
        assertEquals(listOf("a", "b"), repo.observeConversations().first().map { it.id })

        server.enqueue(ok("""{"conversations":[${conversationJson("a", createdAt = "2026-11-17T03:00:00.000Z")}],"nextCursor":"2026-11-17T03:00:00.000Z|a"}"""))
        assertEquals(Outcome.Ok(true), repo.refreshConversations())
        assertEquals(listOf("a", "b"), repo.observeConversations().first().map { it.id }.let { ids -> ids })
        server.takeRequest(); server.takeRequest()

        server.enqueue(ok("""{"conversations":[],"nextCursor":null}"""))
        repo.loadMoreConversations()
        assertEquals("/api/v1/client/conversations?chatbotId=cb_testbot&before=2026-11-17T03%3A00%3A00.000Z%7Ca", server.takeRequest().target)
    }

    @Test
    fun `an uncertain create keeps the same id for the retry`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).body("""{"error":{"code":"server_error","message":"x"}}""").build())
        assertTrue(repo.createConversation("fixed-id") is Outcome.Err)
        assertEquals(listOf("fixed-id"), repo.unconfirmedConversationIds())
        server.enqueue(ok(conversationJson("fixed-id")))
        assertTrue(repo.createConversation("fixed-id") is Outcome.Ok)
        assertTrue(repo.unconfirmedConversationIds().isEmpty())
        server.takeRequest()
        assertEquals("""{"id":"fixed-id","chatbotId":"cb_testbot"}""", server.takeRequest().body?.utf8())
    }
}
