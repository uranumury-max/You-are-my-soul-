package com.karam.youaremysoul

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class OwnerMasterState(
    val unlocked: Boolean = false,
    val grantedScenes: Set<Int> = emptySet()
)

/** Calls only SECURITY DEFINER RPCs; the owner code is never persisted by Android. */
internal class OwnerMasterAccessRepository {
    private val config = SupabaseRuntimeConfig.fromBuildConfig()

    suspend fun configure(
        session: AuthenticatedSupabaseUser,
        worldId: UUID,
        code: String
    ): Boolean = rpcBoolean(
        "configure_owner_master_code",
        session,
        JSONObject().put("p_world_id", worldId.toString()).put("p_code", code)
    )

    suspend fun verify(
        session: AuthenticatedSupabaseUser,
        worldId: UUID,
        code: String
    ): Boolean = rpcBoolean(
        "verify_owner_master_code",
        session,
        JSONObject().put("p_world_id", worldId.toString()).put("p_code", code)
    )

    suspend fun grantScene(
        session: AuthenticatedSupabaseUser,
        worldId: UUID,
        targetUserId: UUID,
        sceneNumber: Int
    ): Boolean {
        require(sceneNumber in 1..21)
        return rpcBoolean(
            "grant_owner_scene",
            session,
            JSONObject()
                .put("p_world_id", worldId.toString())
                .put("p_target_user_id", targetUserId.toString())
                .put("p_scene_number", sceneNumber)
        )
    }

    suspend fun loadState(
        session: AuthenticatedSupabaseUser,
        worldId: UUID
    ): OwnerMasterState = withContext(Dispatchers.IO) {
        val raw = request(
            "get_owner_master_state",
            session,
            JSONObject().put("p_world_id", worldId.toString())
        )
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw SupabaseAuthException("owner_master_invalid_response")
        }
        val scenes = json.optJSONArray("granted_scenes") ?: JSONArray()
        val granted = buildSet {
            for (index in 0 until scenes.length()) {
                val number = scenes.optInt(index, -1)
                if (number in 1..21) add(number)
            }
        }
        OwnerMasterState(json.optBoolean("unlocked"), granted)
    }

    private suspend fun rpcBoolean(
        function: String,
        session: AuthenticatedSupabaseUser,
        body: JSONObject
    ): Boolean = withContext(Dispatchers.IO) {
        request(function, session, body).trim().toBooleanStrictOrNull()
            ?: throw SupabaseAuthException("owner_master_invalid_response")
    }

    private fun request(
        function: String,
        session: AuthenticatedSupabaseUser,
        body: JSONObject
    ): String {
        require(config.isConfigured) { "owner_master_not_configured" }
        require(session.userId.isNotBlank() && session.accessToken.isNotBlank())
        val endpoint = URL(
            config.projectUrl.trimEnd('/') + "/rest/v1/rpc/" + function
        )
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("apikey", config.publishableKey)
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { readBounded(it) }.orEmpty()
            if (status !in 200..299) {
                throw SupabaseAuthException(
                    when {
                        status == 401 || status == 403 -> "owner_master_authorization_failed"
                        status == 429 -> "owner_master_rate_limited"
                        status >= 500 -> "owner_master_service_unavailable"
                        else -> "owner_master_request_rejected"
                    }
                )
            }
            return response
        } catch (error: SupabaseAuthException) {
            throw error
        } catch (_: IOException) {
            throw SupabaseAuthException("owner_master_network_unavailable")
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(2 * 1024)
        var total = 0
        try {
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) {
                    throw SupabaseAuthException("owner_master_response_too_large")
                }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            buffer.fill(0)
            output.reset()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 16 * 1024
    }
}
