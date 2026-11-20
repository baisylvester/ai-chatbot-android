package com.embeddablechatbot.android.core.data.auth

import com.embeddablechatbot.android.core.data.network.ApiJson
import com.embeddablechatbot.android.core.domain.model.Account
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.Clock
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.SignOutReason
import com.embeddablechatbot.android.core.domain.repository.AuthRepository
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import com.embeddablechatbot.android.core.domain.repository.RegistrationRepository
import com.embeddablechatbot.android.core.domain.repository.RegistrationStatus
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AuthApi {
    suspend fun signInWithPassword(supabaseUrl: String, key: String, email: String, password: String): AuthCallResult
    suspend fun refresh(supabaseUrl: String, key: String, refreshToken: String): AuthCallResult
    suspend fun signOut(supabaseUrl: String, key: String, accessToken: String)
}

sealed interface TokenResult {
    data class Token(val accessToken: String, val userId: String, val epoch: Long) : TokenResult
    data object SignedOut : TokenResult
    data class Unavailable(val error: AppError) : TokenResult
}

/** Supplies access tokens to the API client and identifies which account a response belongs to. */
interface AccessTokenProvider {
    suspend fun token(): TokenResult

    /** After a 401 with [staleToken]: refresh once, unless another caller already did. */
    suspend fun refreshAfterUnauthorized(staleToken: String): TokenResult

    /** Increments on every sign-in, sign-out and account change; responses from an older epoch are discarded. */
    val epoch: Long
    fun currentUserId(): String?
}

/**
 * Owns the Supabase session. All refreshes are serialized by one mutex, so concurrent requests that
 * find an expired token trigger a single refresh and Supabase's rotating refresh token is never reused.
 */
@Singleton
class SessionManager @Inject constructor(
    private val api: AuthApi,
    private val secrets: SecretStore,
    private val config: ConfigRepository,
    private val clock: Clock,
    private val registration: RegistrationApi,
) : AuthRepository, AccessTokenProvider, RegistrationRepository {
    private val mutex = Mutex()
    private var session: StoredSession? = null
    private val epochCounter = AtomicLong(0)
    private val _authState = MutableStateFlow<AuthState>(AuthState.Restoring)

    override val authState: StateFlow<AuthState> = _authState.asStateFlow()
    override val epoch: Long get() = epochCounter.get()
    override fun currentUserId(): String? = (authState.value as? AuthState.SignedIn)?.account?.userId

    override suspend fun register(email: String, password: String): Outcome<RegistrationStatus> {
        if (authState.value is AuthState.SignedIn) return Outcome.Err(AppError.Invalid("Sign out before creating another account."))
        val initialEpoch = epoch
        val cfg = config.config.value
        return when (val result = registration.signUp(cfg.supabaseUrl,cfg.supabasePublishableKey,email.trim(),password,cfg.platformUrl.trimEnd('/')+"/auth/email-confirmed")) {
            is Outcome.Err -> result
            is Outcome.Ok -> mutex.withLock {
                if (epoch != initialEpoch || config.config.value.supabaseUrl != cfg.supabaseUrl) return@withLock Outcome.Err(AppError.Unauthorized)
                val created = result.value
                if (created == null) Outcome.Ok(RegistrationStatus.ConfirmEmail)
                else {
                    secrets.write(KEY,ApiJson.encodeToString(StoredSession.serializer(),created))
                    session = created; epochCounter.incrementAndGet()
                    _authState.value = AuthState.SignedIn(Account(created.userId,created.email))
                    Outcome.Ok(RegistrationStatus.SignedIn)
                }
            }
        }
    }

    override suspend fun resendConfirmation(email: String): Outcome<Unit> {
        val cfg = config.config.value
        return registration.resend(cfg.supabaseUrl,cfg.supabasePublishableKey,email.trim(),cfg.platformUrl.trimEnd('/')+"/auth/email-confirmed")
    }

    override suspend fun restore() {
        mutex.withLock {
            if (_authState.value != AuthState.Restoring) return
            val stored = secrets.read(KEY)?.let { runCatching { ApiJson.decodeFromString(StoredSession.serializer(), it) }.getOrNull() }
            if (stored == null || stored.supabaseUrl != config.config.value.supabaseUrl) {
                if (stored != null) secrets.write(KEY, null)
                _authState.value = AuthState.SignedOut()
                return
            }
            session = stored
            epochCounter.incrementAndGet()
            // Signed in from stored credentials so cached history is usable offline; an expired token is
            // refreshed on first use, and a rejected refresh signs the user out.
            _authState.value = AuthState.SignedIn(Account(stored.userId, stored.email))
        }
    }

    override suspend fun signIn(email: String, password: String): Outcome<Account> {
        val cfg = config.config.value
        if (cfg.supabaseUrl.isBlank() || cfg.supabasePublishableKey.isBlank()) return Outcome.Err(AppError.NotConfigured("Configure the platform first."))
        return when (val result = api.signInWithPassword(cfg.supabaseUrl, cfg.supabasePublishableKey, email.trim(), password)) {
            is AuthCallResult.Success -> mutex.withLock {
                secrets.write(KEY, ApiJson.encodeToString(StoredSession.serializer(), result.session))
                session = result.session
                epochCounter.incrementAndGet()
                val account = Account(result.session.userId, result.session.email)
                _authState.value = AuthState.SignedIn(account)
                Outcome.Ok(account)
            }
            is AuthCallResult.Rejected -> Outcome.Err(AppError.Invalid(result.message))
            is AuthCallResult.Failed -> Outcome.Err(result.error)
        }
    }

    override suspend fun signOut() {
        val ended = mutex.withLock { endLocked(SignOutReason.UserRequested) }
        if (ended != null) {
            val cfg = config.config.value
            api.signOut(ended.supabaseUrl, cfg.supabasePublishableKey, ended.accessToken)
        }
    }

    override suspend fun token(): TokenResult = mutex.withLock {
        val current = session ?: return TokenResult.SignedOut
        if (!SupabaseAuthApi.isExpiring(current, clock.now())) return TokenResult.Token(current.accessToken, current.userId, epoch)
        refreshLocked(current)
    }

    override suspend fun refreshAfterUnauthorized(staleToken: String): TokenResult = mutex.withLock {
        val current = session ?: return TokenResult.SignedOut
        if (current.accessToken != staleToken) return TokenResult.Token(current.accessToken, current.userId, epoch)
        refreshLocked(current)
    }

    private suspend fun refreshLocked(current: StoredSession): TokenResult {
        val cfg = config.config.value
        return when (val result = api.refresh(current.supabaseUrl, cfg.supabasePublishableKey, current.refreshToken)) {
            is AuthCallResult.Success -> {
                if (result.session.userId != current.userId) {
                    endLocked(SignOutReason.SessionExpired)
                    return TokenResult.SignedOut
                }
                session = result.session
                secrets.write(KEY, ApiJson.encodeToString(StoredSession.serializer(), result.session))
                TokenResult.Token(result.session.accessToken, result.session.userId, epoch)
            }
            is AuthCallResult.Rejected -> {
                endLocked(SignOutReason.SessionExpired)
                TokenResult.SignedOut
            }
            is AuthCallResult.Failed -> TokenResult.Unavailable(result.error)
        }
    }

    private suspend fun endLocked(reason: SignOutReason): StoredSession? {
        val ended = session
        session = null
        secrets.write(KEY, null)
        epochCounter.incrementAndGet()
        _authState.value = AuthState.SignedOut(reason)
        return ended
    }

    private companion object {
        const val KEY = "supabase_session"
    }
}
