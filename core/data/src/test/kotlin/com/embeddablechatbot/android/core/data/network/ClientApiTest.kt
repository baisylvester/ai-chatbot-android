package com.embeddablechatbot.android.core.data.network

import com.embeddablechatbot.android.core.data.FakeTokens
import com.embeddablechatbot.android.core.data.conversationJson
import com.embeddablechatbot.android.core.data.messageJson
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.ErrorReason
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PlatformConfig
import com.embeddablechatbot.android.core.domain.model.SendEvent
import com.embeddablechatbot.android.core.domain.model.TurnStatus
import com.embeddablechatbot.android.core.domain.testing.FakeConfigRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClientApiTest {
    private val server = MockWebServer()
    private val tokens = FakeTokens()
    private lateinit var api: ClientApi

    @Before
    fun setUp() {
        server.start()
        val config = FakeConfigRepository(
            PlatformConfig(server.url("/").toString().trimEnd('/'), "https://auth.test", "sb_publishable_x", "cb_testbot"),
            allowCleartext = true,
        )
        api = ClientApi(OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(2, TimeUnit.SECONDS).build(), tokens, config)
    }

    @After
    fun tearDown() = server.close()

    private fun ok(body: String, user: String = "user-1") =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").addHeader("X-Authenticated-User", user).body(body).build()

    @Test
    fun `requests carry the bearer token, never an Origin or cookie, and keep decimal strings intact`() = runBlocking {
        server.enqueue(ok("""{"conversation":${conversationJson(revision = "9007199254740993")},"messages":[${messageJson("m1", "9007199254740993")}],"nextCursor":"9007199254740993","hasMore":false}"""))
        val page = (api.messages("c1", "0") as Outcome.Ok).value.value.toDomain()
        val request = server.takeRequest()
        assertEquals("Bearer token-1", request.headers["Authorization"])
        assertNull(request.headers["Origin"])
        assertNull(request.headers["Cookie"])
        assertEquals("/api/v1/client/conversations/c1/messages?after=0", request.target)
        assertEquals("9007199254740993", page.nextCursor)
        assertEquals("9007199254740993", page.messages.single().sequence)
        assertEquals("9007199254740993", page.conversation.revision)
    }

    @Test
    fun `message bodies use text and the exact request id, with emailManager only when enabled`() = runBlocking {
        server.enqueue(ok("""{"turn":{"requestId":"r1","status":"completed","text":"done","citations":[],"error":null}}"""))
        server.enqueue(ok("""{"turn":{"requestId":"r2","status":"completed","text":"done","citations":[],"error":null}}"""))
        api.sendJson("c1", "r1", "hello", emailManager = true)
        api.sendJson("c1", "r2", "hello", emailManager = false)
        assertEquals("""{"requestId":"r1","message":"hello","stream":false,"emailManager":true}""", server.takeRequest().body?.utf8())
        assertEquals("""{"requestId":"r2","message":"hello","stream":false}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun `a 200 response can carry a failed turn, which is not an HTTP error`() = runBlocking {
        server.enqueue(ok("""{"turn":{"requestId":"r1","status":"failed","text":"","citations":[],"error":"The reply could not finish."}}"""))
        val turn = (api.sendJson("c1", "r1", "hi", false) as Outcome.Ok).value.value
        assertEquals(TurnStatus.Failed, turn.status)
        assertEquals("The reply could not finish.", turn.error)
    }

    @Test
    fun `401 refreshes once and retries the identical request`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"unauthorized","message":"expired"}}""").build())
        server.enqueue(ok("""{"turn":{"requestId":"r1","status":"completed","text":"ok","citations":[],"error":null}}"""))
        assertTrue(api.sendJson("c1", "r1", "hi", false) is Outcome.Ok)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("Bearer token-1", first.headers["Authorization"])
        assertEquals("Bearer token-2", second.headers["Authorization"])
        assertEquals(first.body?.utf8(), second.body?.utf8())
        assertEquals(1, tokens.refreshes)
    }

    @Test
    fun `a response for another account or an older session epoch is discarded`() = runBlocking {
        server.enqueue(ok(conversationJson(), user = "someone-else"))
        assertEquals(AppError.Unauthorized, (api.conversation("c1") as Outcome.Err).error)

        // Token obtained under epoch 1, but the user signed out/in (epoch 2) before the response arrived.
        tokens.epoch = 2
        val stale = object : com.embeddablechatbot.android.core.data.auth.AccessTokenProvider by tokens {
            override suspend fun token() = com.embeddablechatbot.android.core.data.auth.TokenResult.Token("token-1", "user-1", 1)
        }
        val staleApi = ClientApi(OkHttpClient(), stale, FakeConfigRepository(PlatformConfig(server.url("/").toString().trimEnd('/'), "https://a.test", "k", "cb_testbot"), allowCleartext = true))
        server.enqueue(ok(conversationJson()))
        assertEquals(AppError.Unauthorized, (staleApi.conversation("c1") as Outcome.Err).error)
    }

    @Test
    fun `409 reasons are preserved`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(409).body("""{"error":{"code":"conflict","message":"Another reply is running","reason":"conversation_busy"}}""").build())
        val error = (api.sendJson("c1", "r1", "hi", false) as Outcome.Err).error
        assertEquals(AppError.Conflict(ErrorReason.ConversationBusy, "Another reply is running"), error)
    }

    @Test
    fun `GET recovers when a pooled connection closes before response headers`() = runBlocking {
        server.enqueue(ok(conversationJson()))
        assertTrue(api.conversation("c1") is Outcome.Ok)
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(ok(conversationJson()))
        assertTrue(api.conversation("c1") is Outcome.Ok)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `approval is not replayed when the server closes before returning its receipt`() = runBlocking {
        server.enqueue(ok(conversationJson()))
        assertTrue(api.conversation("c1") is Outcome.Ok)
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(ok("{}"))
        assertEquals(AppError.Network(uncertain = true), (api.approve("a1", "version") as Outcome.Err).error)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a read timeout on an idempotent GET is a certain network failure`() = runBlocking {
        server.enqueue(ok("""{"turn":{}}""").newBuilder().headersDelay(5, TimeUnit.SECONDS).build())
        val shortApi = ClientApi(
            OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).callTimeout(500, TimeUnit.MILLISECONDS).build(),
            tokens,
            FakeConfigRepository(PlatformConfig(server.url("/").toString().trimEnd('/'), "https://a.test", "k", "cb_testbot"), allowCleartext = true),
        )
        // Non-idempotent timeouts are uncertain (HttpErrorsTest); the long send client itself uses a 270 s read timeout.
        val result = shortApi.conversation("c1")
        assertEquals(AppError.Network(uncertain = false), (result as Outcome.Err).error)
    }

    @Test
    fun `stream delivers provisional events and a validated terminal turn`() = runBlocking {
        val body = "event: accepted\ndata: {\"type\":\"accepted\",\"requestId\":\"r1\"}\n\n" +
            "event: tool\ndata: {\"type\":\"tool\",\"name\":\"email_manager\",\"phase\":\"start\"}\n\n" +
            "event: token\ndata: {\"type\":\"token\",\"text\":\"Hel\"}\n\n" +
            "event: token\ndata: {\"type\":\"token\",\"text\":\"lo\"}\n\n" +
            "event: done\ndata: {\"type\":\"done\",\"turn\":{\"requestId\":\"r1\",\"status\":\"completed\",\"text\":\"Hello\",\"citations\":[],\"error\":null}}\n\n"
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").addHeader("X-Authenticated-User", "user-1").chunkedBody(body, 7).build())
        val events = mutableListOf<SendEvent>()
        val result = api.sendStream("c1", "r1", "hi", true) { events += it }
        assertEquals("Hello", (result as Outcome.Ok).value.value.text)
        assertEquals("Hello", events.filterIsInstance<SendEvent.Token>().joinToString("") { it.text })
        assertTrue(events.any { it is SendEvent.Tool })
        assertTrue(events.last() is SendEvent.Done)
    }

    @Test
    fun `stream EOF without done, or an error event, is an uncertain interruption`() = runBlocking {
        val partial = "event: accepted\ndata: {\"type\":\"accepted\",\"requestId\":\"r1\"}\n\nevent: token\ndata: {\"type\":\"token\",\"text\":\"Hel\"}\n\n"
        server.enqueue(MockResponse.Builder().code(200).addHeader("X-Authenticated-User", "user-1").body(partial).build())
        assertEquals(AppError.Network(uncertain = true), (api.sendStream("c1", "r1", "hi", true) {} as Outcome.Err).error)

        server.enqueue(MockResponse.Builder().code(200).addHeader("X-Authenticated-User", "user-1")
            .body(partial + "event: error\ndata: {\"code\":\"interrupted\",\"message\":\"x\"}\n\n").build())
        assertEquals(AppError.Network(uncertain = true), (api.sendStream("c1", "r1", "hi", true) {} as Outcome.Err).error)
    }

    @Test
    fun `a done event for a different request is a protocol error`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("X-Authenticated-User", "user-1")
            .body("event: done\ndata: {\"type\":\"done\",\"turn\":{\"requestId\":\"other\",\"status\":\"completed\",\"text\":\"x\",\"citations\":[],\"error\":null}}\n\n").build())
        assertTrue((api.sendStream("c1", "r1", "hi", false) {} as Outcome.Err).error is AppError.Protocol)
    }

    @Test
    fun `approval posts the reviewed version`() = runBlocking {
        server.enqueue(ok("""{"action":{"id":"a1","kind":"send","summary":"s","status":"done","createdAt":"2026-11-17T00:00:00.000Z"}}"""))
        api.approve("a1", "abc123")
        val request = server.takeRequest()
        assertEquals("/api/v1/client/mail/actions/a1/approve", request.target)
        assertEquals("""{"version":"abc123"}""", request.body?.utf8())
    }

    @Test
    fun `cleartext platform URLs are refused when cleartext is not allowed`() = runBlocking {
        val strict = ClientApi(OkHttpClient(), tokens, FakeConfigRepository(PlatformConfig("http://insecure.test", "https://a.test", "k", "cb_testbot"), allowCleartext = false))
        assertTrue((strict.conversation("c1") as Outcome.Err).error is AppError.NotConfigured)
        assertEquals(0, server.requestCount)
    }
}
