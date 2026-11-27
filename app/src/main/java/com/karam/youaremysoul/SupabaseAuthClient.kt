package com.karam.youaremysoul

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers

/**
 * Minimal Supabase Auth REST client.
 *
 * It creates an anonymous Auth user only on first launch, then refreshes and
 * persists the rotating refresh token. Tokens are never logged or included in errors.
 */
class SupabaseAuthClient(context: Context) {
    private val config = SupabaseRuntimeConfig.fromBuildConfig()
    private val sessionStore = AuthSessionStore(context)
    private val sessionMutex = Mutex()

    suspend fun ensureAuthenticatedSession(): AuthenticatedSupabaseUser =
        sessionMutex.withLock {
            val validationError = config.validationError()
            if (validationError != null) {
                throw SupabaseAuthException(validationError)
            }

            val stored = withContext(Dispatchers.IO) { sessionStore.load() }
            if (stored == null) {
                return@withLock withContext(Dispatchers.IO) {
                    createAnonymousSession()
                }
            }

            val now = System.currentTimeMillis() / 1000L
            if (stored.expiresAtEpochSeconds > now + REFRESH_SKEW_SECONDS) {
                return@withLock AuthenticatedSupabaseUser(stored.userId, stored.accessToken)
            }

            // A failed refresh must not silently replace this identity with a new anonymous user.
            withContext(Dispatchers.IO) { refreshSession(stored.refreshToken) }
        }

    private fun createAnonymousSession(): AuthenticatedSupabaseUser {
        val body = JSONObject().put("data", JSONObject().put("app", "you_are_my_soul"))
        val response = request(
            path = "/auth/v1/signup",
            method = "POST",
            body = body,
            bearerToken = null
        )
        return persistResponse(response)
    }

    private fun refreshSession(refreshToken: String): AuthenticatedSupabaseUser {
        val response = request(
            path = "/auth/v1/token?grant_type=refresh_token",
            method = "POST",
            body = JSONObject().put("refresh_token", refreshToken),
            bearerToken = null
        )
        return persistResponse(response)
    }

    private fun persistResponse(response: JSONObject): AuthenticatedSupabaseUser {
        val user = response.optJSONObject("user")
            ?: throw SupabaseAuthException("auth_response_missing_user")
        if (user.has("is_anonymous") && !user.optBoolean("is_anonymous")) {
            throw SupabaseAuthException("auth_response_not_anonymous")
        }
        val rawUserId = user.optString("id").takeIf { it.isNotBlank() }
            ?: throw SupabaseAuthException("auth_response_missing_user_id")
        val userId = try {
            java.util.UUID.fromString(rawUserId).toString()
        } catch (_: IllegalArgumentException) {
            throw SupabaseAuthException("auth_response_invalid_user_id")
        }
        val accessToken = response.optString("access_token")
            .takeIf { it.isNotBlank() }
            ?: throw SupabaseAuthException("auth_response_missing_access_token")
        val refreshToken = response.optString("refresh_token")
            .takeIf { it.isNotBlank() }
            ?: throw SupabaseAuthException("auth_response_missing_refresh_token")
        val expiresIn = response.optLong("expires_in", DEFAULT_TOKEN_LIFETIME_SECONDS)
            .coerceIn(60L, MAX_TOKEN_LIFETIME_SECONDS)
        val expiresAt = response.optLong(
            "expires_at",
            System.currentTimeMillis() / 1000L + expiresIn
        ).takeIf { it > 0L } ?: (System.currentTimeMillis() / 1000L + expiresIn)

        sessionStore.save(
            StoredAuthSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                userId = userId,
                expiresAtEpochSeconds = expiresAt
            )
        )
        return AuthenticatedSupabaseUser(userId, accessToken)
    }

    private fun readBoundedResponse(stream: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) {
                throw IOException("Supabase Auth response exceeded the client safety limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun request(
        path: String,
        method: String,
        body: JSONObject,
        bearerToken: String?
    ): JSONObject {
        val endpoint = URL(config.projectUrl.trimEnd('/') + path)
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("apikey", config.publishableKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (bearerToken != null) setRequestProperty("Authorization", "Bearer $bearerToken")
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            // Auth payloads are small; bound parsing to protect memory from malformed responses.
            val raw = stream?.use { readBoundedResponse(it) }.orEmpty()
            if (status !in 200..299) {
                val code = runCatching {
                    JSONObject(raw).optString("code").ifBlank {
                        JSONObject(raw).optString("error_code")
                    }
                }.getOrDefault("")
                throw SupabaseAuthException(
                    when {
                        status == 429 -> "auth_rate_limited"
                        status == 401 || status == 403 -> "auth_rejected"
                        status >= 500 -> "auth_service_unavailable"
                        code.isNotBlank() -> "auth_request_rejected"
                        else -> "auth_http_$status"
                    }
                )
            }
            return try {
                JSONObject(raw)
            } catch (_: Exception) {
                throw SupabaseAuthException("auth_invalid_response")
            }
        } catch (error: SupabaseAuthException) {
            throw error
        } catch (_: IOException) {
            throw SupabaseAuthException("auth_network_unavailable")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val REFRESH_SKEW_SECONDS = 90L
        private const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3600L
        private const val MAX_TOKEN_LIFETIME_SECONDS = 86_400L
    }
}

data class AuthenticatedSupabaseUser(
    val userId: String,
    val accessToken: String
)

class SupabaseAuthException(val safeCode: String) : Exception(safeCode)
