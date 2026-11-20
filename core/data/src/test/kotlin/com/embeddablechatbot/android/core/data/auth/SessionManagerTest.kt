package com.embeddablechatbot.android.core.data.auth

import com.embeddablechatbot.android.core.data.network.ApiJson
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.SignOutReason
import com.embeddablechatbot.android.core.domain.testing.FakeConfigRepository
import com.embeddablechatbot.android.core.domain.testing.FixedClock
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemorySecrets : SecretStore {
    val values = mutableMapOf<String, String>()
    override suspend fun read(name: String) = values[name]
    override suspend fun write(name: String, value: String?) { if (value == null) values.remove(name) else values[name] = value }
}

private class ScriptedAuthApi : AuthApi, RegistrationApi {
    var registrationCalls = 0
    var registrationGate: CompletableDeferred<Unit>? = null
    var registrationResult: com.embeddablechatbot.android.core.domain.model.Outcome<StoredSession?> = com.embeddablechatbot.android.core.domain.model.Outcome.Ok(null)
    override suspend fun signUp(url: String,key: String,email: String,password: String,redirect: String): com.embeddablechatbot.android.core.domain.model.Outcome<StoredSession?> {
        registrationCalls++; registrationGate?.await(); return registrationResult
    }
    override suspend fun resend(url: String,key: String,email: String,redirect: String) = com.embeddablechatbot.android.core.domain.model.Outcome.Ok(Unit)
    var refreshCalls = 0
    var signOuts = 0
    var gate: CompletableDeferred<Unit>? = null
    var refreshResult: (String) -> AuthCallResult = { token -> AuthCallResult.Success(session("user-1", "access-${refreshCalls}", "refresh-${refreshCalls}")) }
    override suspend fun signInWithPassword(supabaseUrl: String, key: String, email: String, password: String) =
        if (password == "right") AuthCallResult.Success(session("user-1", "access-0", "refresh-0")) else AuthCallResult.Rejected("Email or password is incorrect.")
    override suspend fun refresh(supabaseUrl: String, key: String, refreshToken: String): AuthCallResult {
        refreshCalls++
        gate?.await()
        return refreshResult(refreshToken)
    }
    override suspend fun signOut(supabaseUrl: String, key: String, accessToken: String) { signOuts++ }
}

private fun session(user: String, access: String, refresh: String, expiresAt: Long = Instant.parse("2026-11-18T01:00:00Z").epochSecond) =
    StoredSession(user, "$user@example.test", access, refresh, expiresAt, "https://auth.test")

class SessionManagerTest {
    private val clock = FixedClock(Instant.parse("2026-11-18T00:00:00Z"))
    private val secrets = MemorySecrets()
    private val api = ScriptedAuthApi()
    private val manager = SessionManager(api, secrets, FakeConfigRepository(), clock, api)

    @Test fun `confirmation-required registration never saves session credentials`() = runTest {
        manager.restore()
        val result = manager.register("new@example.test", "a-long-test-password")
        assertTrue(result is com.embeddablechatbot.android.core.domain.model.Outcome.Ok)
        assertTrue(manager.authState.value is AuthState.SignedOut)
        assertTrue(secrets.values.isEmpty())
        assertTrue(manager.token() is TokenResult.SignedOut)
    }
    @Test fun `immediate registration securely adopts its own session`() = runTest {
        manager.restore();api.registrationResult = com.embeddablechatbot.android.core.domain.model.Outcome.Ok(session("new-user", "new-access", "new-refresh"))
        manager.register("new@example.test", "a-long-test-password")
        assertEquals("new-user", manager.currentUserId())
        assertEquals("new-access", (manager.token() as TokenResult.Token).accessToken)
        assertTrue(secrets.values.isNotEmpty())
    }
    @Test fun `registration cannot replace an already signed-in account`() = runTest {
        manager.restore();manager.signIn("existing@example.test", "right")
        assertTrue(manager.register("new@example.test", "a-long-test-password") is com.embeddablechatbot.android.core.domain.model.Outcome.Err)
        assertEquals(0,api.registrationCalls);assertEquals("user-1",manager.currentUserId())
    }
    @Test fun `a late registration response cannot overwrite a newer sign-in`() = runTest {
        manager.restore();api.registrationGate=CompletableDeferred()
        api.registrationResult=com.embeddablechatbot.android.core.domain.model.Outcome.Ok(session("new-user","new-access","new-refresh"))
        val pending=async{manager.register("new@example.test","a-long-test-password")}
        advanceUntilIdle();manager.signIn("existing@example.test","right");api.registrationGate!!.complete(Unit)
        assertTrue(pending.await() is com.embeddablechatbot.android.core.domain.model.Outcome.Err)
        assertEquals("user-1",manager.currentUserId())
    }

    private suspend fun storeSession(expiresAt: Long) {
        secrets.write("supabase_session", ApiJson.encodeToString(StoredSession.serializer(), session("user-1", "old-access", "old-refresh", expiresAt)))
        manager.restore()
    }

    @Test
    fun `concurrent requests with an expired token trigger exactly one refresh`() = runTest {
        storeSession(expiresAt = clock.now().epochSecond - 10)
        api.gate = CompletableDeferred()
        val calls = List(5) { async { manager.token() } }
        advanceUntilIdle()
        api.gate!!.complete(Unit)
        val tokens = calls.awaitAll()
        assertEquals(1, api.refreshCalls)
        assertTrue(tokens.all { it is TokenResult.Token && it.accessToken == "access-1" })
    }

    @Test
    fun `a 401 refresh is skipped when another caller already rotated the token`() = runTest {
        storeSession(expiresAt = clock.now().epochSecond + 3600)
        val first = manager.refreshAfterUnauthorized("old-access") as TokenResult.Token
        val second = manager.refreshAfterUnauthorized("old-access") as TokenResult.Token
        assertEquals(1, api.refreshCalls)
        assertEquals(first.accessToken, second.accessToken)
    }

    @Test
    fun `a rejected refresh signs out as expired and deletes stored credentials`() = runTest {
        storeSession(expiresAt = clock.now().epochSecond - 10)
        val epoch = manager.epoch
        api.refreshResult = { AuthCallResult.Rejected("expired") }
        assertEquals(TokenResult.SignedOut, manager.token())
        assertEquals(AuthState.SignedOut(SignOutReason.SessionExpired), manager.authState.value)
        assertTrue(secrets.values.isEmpty())
        assertTrue(manager.epoch > epoch)
    }

    @Test
    fun `a network failure during refresh keeps the session`() = runTest {
        storeSession(expiresAt = clock.now().epochSecond - 10)
        api.refreshResult = { AuthCallResult.Failed(AppError.Network(false)) }
        assertTrue(manager.token() is TokenResult.Unavailable)
        assertTrue(manager.authState.value is AuthState.SignedIn)
    }

    @Test
    fun `a refresh that returns another user ends the session`() = runTest {
        storeSession(expiresAt = clock.now().epochSecond - 10)
        api.refreshResult = { AuthCallResult.Success(session("intruder", "a", "r")) }
        assertEquals(TokenResult.SignedOut, manager.token())
        assertNull(manager.currentUserId())
    }

    @Test
    fun `stored credentials for a different Supabase project are discarded`() = runTest {
        secrets.write("supabase_session", ApiJson.encodeToString(StoredSession.serializer(), session("u", "a", "r").copy(supabaseUrl = "https://other.test")))
        manager.restore()
        assertTrue(manager.authState.value is AuthState.SignedOut)
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun `sign-in, wrong password and sign-out`() = runTest {
        manager.restore()
        assertTrue(manager.signIn("u@example.test", "wrong") is com.embeddablechatbot.android.core.domain.model.Outcome.Err)
        assertTrue(manager.signIn("u@example.test", "right") is com.embeddablechatbot.android.core.domain.model.Outcome.Ok)
        val epoch = manager.epoch
        manager.signOut()
        assertEquals(AuthState.SignedOut(SignOutReason.UserRequested), manager.authState.value)
        assertEquals(1, api.signOuts)
        assertTrue(secrets.values.isEmpty())
        assertTrue(manager.epoch > epoch)
    }
}
