package com.embeddablechatbot.android.core.data.auth

import com.embeddablechatbot.android.core.domain.model.*
import com.embeddablechatbot.android.core.domain.testing.FixedClock
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*

class RegistrationApiTest {
    private val server=MockWebServer()
    @Before fun setup(){server.start()}
    @After fun close(){server.close()}
    private val api=SupabaseAuthApi(OkHttpClient(),FixedClock())
    @Test fun `unconfirmed signup returns no session and uses only the public key`()=runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"new-user","email":"new@example.test"}""").build())
        val result=api.signUp(server.url("/").toString(),"public-key","new@example.test","a-long-test-password","https://platform.test/auth/email-confirmed")
        assertNull((result as Outcome.Ok).value)
        val request=server.takeRequest();assertEquals("public-key",request.headers["apikey"]);assertNull(request.headers["Authorization"])
        assertTrue(request.target!!.startsWith("/auth/v1/signup?redirect_to="));assertTrue(request.body!!.utf8().contains("new@example.test"))
    }
    @Test fun `immediate signup session is parsed without losing refresh credentials`()=runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"access_token":"access","refresh_token":"refresh","expires_in":3600,"user":{"id":"new-user","email":"new@example.test"}}""").build())
        val session=(api.signUp(server.url("/").toString(),"public-key","new@example.test","a-long-test-password","https://platform.test/auth/email-confirmed") as Outcome.Ok).value!!
        assertEquals("new-user",session.userId);assertEquals("refresh",session.refreshToken)
    }
    @Test fun `resend includes the signup type and provider throttling is preserved`()=runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        assertTrue(api.resend(server.url("/").toString(),"public-key","new@example.test","https://platform.test/auth/email-confirmed") is Outcome.Ok)
        val request=server.takeRequest();assertTrue(request.body!!.utf8().contains("\"type\":\"signup\""))
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After","60").body("{}").build())
        assertTrue(api.resend(server.url("/").toString(),"public-key","new@example.test","https://platform.test/auth/email-confirmed") is Outcome.Err)
    }
    @Test fun `malformed signup success never pretends to confirm an account`()=runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        assertTrue(api.signUp(server.url("/").toString(),"public-key","new@example.test","a-long-test-password","https://platform.test/auth/email-confirmed") is Outcome.Err)
    }
}
