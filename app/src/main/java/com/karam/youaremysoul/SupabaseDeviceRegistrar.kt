package com.karam.youaremysoul

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Registers this installation against the authenticated Supabase identity.
 * The database function derives user_id from auth.uid(); the client never sends it.
 */
class SupabaseDeviceRegistrar(context: Context) {
    private val config = SupabaseRuntimeConfig.fromBuildConfig()
    private val identity = DeviceIdentity(context.applicationContext)
    private val deviceKeys = DeviceKeyVault()

    suspend fun register(accessToken: String) = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank()) { "Authenticated access token required" }
        repeat(MAX_REGISTRATION_ATTEMPTS) {
            if (registerOnce(accessToken)) {
                registerPublicKey(accessToken)
                return@withContext Unit
            }
            identity.regenerateCode()
        }
        throw SupabaseAuthException("device_code_collision")
    }

    /**
     * A false RPC result means only that another installation currently owns
     * this public code. Ownership conflicts for installationId are server errors.
     */
    private fun registerOnce(accessToken: String): Boolean {
        val endpoint = URL(config.projectUrl.trimEnd('/') + "/rest/v1/rpc/register_device_code")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("apikey", config.publishableKey)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val body = JSONObject()
                .put("p_device_id", identity.installationId)
                .put("p_device_code", identity.code)
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw SupabaseAuthException(
                    when {
                        status == 401 || status == 403 -> "device_registration_rejected"
                        status == 429 -> "device_registration_rate_limited"
                        status >= 500 -> "device_registration_unavailable"
                        else -> "device_registration_failed"
                    }
                )
            }
            return when (response.trim()) {
                "true" -> true
                "false" -> false
                else -> throw SupabaseAuthException("device_registration_not_confirmed")
            }
        } catch (error: SupabaseAuthException) {
            throw error
        } catch (_: IOException) {
            throw SupabaseAuthException("device_registration_network_unavailable")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_REGISTRATION_ATTEMPTS = 4
    }
}
