package com.embeddablechatbot.android.core.data.auth

import com.embeddablechatbot.android.core.data.network.ApiJson
import com.embeddablechatbot.android.core.data.network.AuthErrorDto
import com.embeddablechatbot.android.core.data.network.AuthTokenDto
import com.embeddablechatbot.android.core.data.network.HttpErrors
import com.embeddablechatbot.android.core.data.network.PasswordGrantBody
import com.embeddablechatbot.android.core.data.network.RefreshGrantBody
import com.embeddablechatbot.android.core.data.network.await
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.Clock
import com.embeddablechatbot.android.core.domain.model.Outcome
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Credentials of one Supabase session. Stored only through [SecretStore]. */
@Serializable
data class StoredSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val supabaseUrl: String,
)

sealed interface AuthCallResult {
    data class Success(val session: StoredSession) : AuthCallResult

    /** Credentials or refresh token rejected: the user must sign in again. */
    data class Rejected(val message: String) : AuthCallResult
    data class Failed(val error: AppError) : AuthCallResult
}

/** Supabase Auth REST endpoints, called with the public publishable key only. */
@Singleton
class SupabaseAuthApi @Inject constructor(private val client: OkHttpClient, private val clock: Clock) : AuthApi, RegistrationApi {
    private val jsonType = "application/json".toMediaType()

    override suspend fun signUp(url: String, key: String, email: String, password: String, redirect: String): Outcome<StoredSession?> {
        val target = "${url.trimEnd('/')}/auth/v1/signup".toHttpUrl().newBuilder().addQueryParameter("redirect_to",redirect).build()
        val request = Request.Builder().url(target).header("apikey",key).post(ApiJson.encodeToString(PasswordGrantBody.serializer(),PasswordGrantBody(email,password)).toRequestBody(jsonType)).build()
        return try {
            client.newCall(request).await().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) Outcome.Err(registrationError(response.code,text,response.header("Retry-After")))
                else {
                    val body = ApiJson.parseToJsonElement(text).jsonObject
                    if (body["access_token"]?.jsonPrimitive?.contentOrNull != null) {
                        val dto = ApiJson.decodeFromString(AuthTokenDto.serializer(),text)
                        Outcome.Ok(StoredSession(dto.user.id,dto.user.email.orEmpty(),dto.accessToken,dto.refreshToken,dto.expiresAt ?: (clock.now().epochSecond+dto.expiresIn),url))
                    } else if (body["id"] != null || body["user"]?.jsonObject?.get("id") != null) Outcome.Ok(null)
                    else Outcome.Err(AppError.Protocol("Account creation returned an invalid response. Try signing in."))
                }
            }
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { Outcome.Err(HttpErrors.fromException(e,idempotent=true)) }
    }

    override suspend fun resend(url: String, key: String, email: String, redirect: String): Outcome<Unit> {
        val body = ApiJson.encodeToString(ResendBody.serializer(),ResendBody(email=email))
        val target = "${url.trimEnd('/')}/auth/v1/resend".toHttpUrl().newBuilder().addQueryParameter("redirect_to",redirect).build()
        return try {
            client.newCall(Request.Builder().url(target).header("apikey",key).post(body.toRequestBody(jsonType)).build()).await().use { response ->
                if (response.isSuccessful) Outcome.Ok(Unit) else Outcome.Err(registrationError(response.code,response.body.string(),response.header("Retry-After")))
            }
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { Outcome.Err(HttpErrors.fromException(e,idempotent=true)) }
    }

    @Serializable private data class ResendBody(val type: String = "signup",val email: String)
    private fun registrationError(code: Int, body: String, retryAfter: String?): AppError {
        if (code !in listOf(400,401,403,422)) return HttpErrors.fromResponse(code,null,retryAfter)
        val dto = runCatching { ApiJson.decodeFromString(AuthErrorDto.serializer(),body) }.getOrNull()
        return AppError.Invalid(when(dto?.errorCode) {
            "user_already_exists", "email_exists" -> "An account may already exist. Try signing in."
            "signup_disabled" -> "Registration is currently unavailable. Contact support."
            else -> dto?.msg ?: dto?.message ?: "Could not create the account. Check your email and password, then try again."
        })
    }

    override suspend fun signInWithPassword(supabaseUrl: String, key: String, email: String, password: String): AuthCallResult =
        tokenRequest(supabaseUrl, key, "password", ApiJson.encodeToString(PasswordGrantBody.serializer(), PasswordGrantBody(email, password)))

    override suspend fun refresh(supabaseUrl: String, key: String, refreshToken: String): AuthCallResult =
        tokenRequest(supabaseUrl, key, "refresh_token", ApiJson.encodeToString(RefreshGrantBody.serializer(), RefreshGrantBody(refreshToken)))

    /** Ends this device's server session. Failures are ignored: local credentials are deleted regardless. */
    override suspend fun signOut(supabaseUrl: String, key: String, accessToken: String) {
        val request = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/auth/v1/logout?scope=local")
            .header("apikey", key)
            .header("Authorization", "Bearer $accessToken")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        try {
            client.newCall(request).await().close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private suspend fun tokenRequest(supabaseUrl: String, key: String, grant: String, body: String): AuthCallResult {
        val request = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/auth/v1/token?grant_type=$grant")
            .header("apikey", key)
            .post(body.toRequestBody(jsonType))
            .build()
        return try {
            client.newCall(request).await().use { response ->
                val text = response.body.string()
                when {
                    response.isSuccessful -> {
                        val dto = ApiJson.decodeFromString(AuthTokenDto.serializer(), text)
                        val expiresAt = dto.expiresAt ?: (clock.now().epochSecond + dto.expiresIn)
                        AuthCallResult.Success(
                            StoredSession(dto.user.id, dto.user.email.orEmpty(), dto.accessToken, dto.refreshToken, expiresAt, supabaseUrl),
                        )
                    }
                    response.code in 400..401 || response.code == 403 -> AuthCallResult.Rejected(rejectionMessage(text, grant))
                    else -> AuthCallResult.Failed(HttpErrors.fromResponse(response.code, null, response.header("Retry-After")))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthCallResult.Failed(HttpErrors.fromException(e, idempotent = true))
        }
    }

    private fun rejectionMessage(body: String, grant: String): String {
        if (grant == "refresh_token") return "Your session expired. Sign in again."
        val dto = runCatching { ApiJson.decodeFromString(AuthErrorDto.serializer(), body) }.getOrNull()
        return when (dto?.errorCode) {
            "email_not_confirmed" -> "Confirm your email address, then sign in."
            "invalid_credentials", null -> "Email or password is incorrect."
            else -> dto.msg ?: dto.errorDescription ?: "Sign-in failed."
        }
    }

    companion object {
        fun isExpiring(session: StoredSession, now: Instant, marginSeconds: Long = 60): Boolean =
            session.expiresAtEpochSeconds - marginSeconds <= now.epochSecond
    }
}
