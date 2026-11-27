package com.karam.youaremysoul

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

data class PendingWorldJoinRequest(
    val requestId: String,
    val displayName: String,
    val createdAt: String,
    val expiresAt: String
)

data class WorldJoinRequestStatus(
    val requestId: String,
    val worldId: String,
    val status: String,
    val expiresAt: String
)

data class ActiveWorldMember(
    val userId: String,
    val displayName: String,
    val joinedAt: String
)

/** Authenticated client for device-code discovery and owner-controlled admission. */
class SupabasePairingRepository {
    private val config = SupabaseRuntimeConfig.fromBuildConfig()

    suspend fun requestJoin(
        session: AuthenticatedSupabaseUser,
        deviceCode: String,
        displayName: String
    ): String? = withContext(Dispatchers.IO) {
        require(PairingSecurity.isValidDeviceCode(deviceCode))
        val safeName = displayName.trim()
        require(safeName.isNotBlank() && safeName.length <= 24 && safeName.none(Char::isISOControl))
        val response = request(
            path = "/rest/v1/rpc/request_join_by_device_code",
            method = "POST",
            session = session,
            body = JSONObject()
                .put("p_device_code", deviceCode)
                .put("p_display_name", safeName)
        ).trim()
        if (response.isBlank() || response == "null") return@withContext null
        val requestId = response.removeSurrounding("\"").trim()
        UUID.fromString(requestId).toString()
    }

    suspend fun pendingRequests(
        session: AuthenticatedSupabaseUser,
        worldId: String
    ): List<PendingWorldJoinRequest> = withContext(Dispatchers.IO) {
        val id = UUID.fromString(worldId).toString()
        val response = request(
            path = "/rest/v1/join_requests?world_id=eq.$id&status=eq.pending&select=id,display_name,created_at,expires_at&order=created_at.asc",
            method = "GET",
            session = session,
            body = null
        )
        val rows = JSONArray(response)
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            PendingWorldJoinRequest(
                requestId = UUID.fromString(row.getString("id")).toString(),
                displayName = row.optString("display_name", "مشارك"),
                createdAt = row.optString("created_at"),
                expiresAt = row.optString("expires_at")
            )
        }
    }

    suspend fun decideRequest(
        session: AuthenticatedSupabaseUser,
        requestId: String,
        approve: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val id = UUID.fromString(requestId).toString()
        request(
            path = "/rest/v1/rpc/decide_join_request",
            method = "POST",
            session = session,
            body = JSONObject()
                .put("p_request_id", id)
                .put("p_approve", approve)
        ).trim().equals("true", ignoreCase = true)
    }

    suspend fun requestStatus(
        session: AuthenticatedSupabaseUser,
        requestId: String
    ): WorldJoinRequestStatus? = withContext(Dispatchers.IO) {
        val id = UUID.fromString(requestId).toString()
        val response = request(
            path = "/rest/v1/join_requests?id=eq.$id&select=id,world_id,status,expires_at",
            method = "GET",
            session = session,
            body = null
        )
        val rows = JSONArray(response)
        if (rows.length() == 0) return@withContext null
        val row = rows.getJSONObject(0)
        WorldJoinRequestStatus(
            requestId = UUID.fromString(row.getString("id")).toString(),
            worldId = UUID.fromString(row.getString("world_id")).toString(),
            status = row.getString("status"),
            expiresAt = row.optString("expires_at")
        )
    }

    suspend fun activeMembers(
        session: AuthenticatedSupabaseUser,
        worldId: String
    ): List<ActiveWorldMember> = withContext(Dispatchers.IO) {
        val id = UUID.fromString(worldId).toString()
        val rows = JSONArray(
            request(
                path = "/rest/v1/rpc/list_world_active_members",
                method = "POST",
                session = session,
                body = JSONObject().put("p_world_id", id)
            )
        )
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            ActiveWorldMember(
                userId = UUID.fromString(row.getString("member_user_id")).toString(),
                displayName = row.optString("member_display_name", "مشارك").take(24),
                joinedAt = row.optString("member_joined_at")
            )
        }
    }

    suspend fun revokeMember(
        session: AuthenticatedSupabaseUser,
        worldId: String,
        memberUserId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val world = UUID.fromString(worldId).toString()
        val member = UUID.fromString(memberUserId).toString()
        request(
            path = "/rest/v1/rpc/revoke_world_member",
            method = "POST",
            session = session,
            body = JSONObject()
                .put("p_world_id", world)
                .put("p_member_id", member)
        ).trim().equals("true", ignoreCase = true)
    }

    private fun request(
        path: String,
        method: String,
        session: AuthenticatedSupabaseUser,
        body: JSONObject?
    ): String {
        val connection = (URL(config.projectUrl.trimEnd('/') + path)
            .openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = body != null
            setRequestProperty("apikey", config.publishableKey)
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "return=representation")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw SupabaseAuthException(
                    when {
                        status == 401 || status == 403 -> "pairing_authorization_failed"
                        status == 429 -> "pairing_rate_limited"
                        status >= 500 -> "pairing_service_unavailable"
                        else -> "pairing_request_rejected"
                    }
                )
            }
            response
        } catch (error: SupabaseAuthException) {
            throw error
        } catch (_: IOException) {
            throw SupabaseAuthException("pairing_network_unavailable")
        } finally {
            connection.disconnect()
        }
    }
}
