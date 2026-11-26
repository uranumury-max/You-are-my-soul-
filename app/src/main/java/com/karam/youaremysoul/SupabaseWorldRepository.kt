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

/**
 * Idempotently provisions the cloud world row for a locally-created world.
 * The database trigger creates the owner membership in the same transaction.
 */
class SupabaseWorldRepository {
    private val config = SupabaseRuntimeConfig.fromBuildConfig()

    suspend fun ensureWorld(
        session: AuthenticatedSupabaseUser,
        worldId: String,
        displayName: String
    ) = withContext(Dispatchers.IO) {
        require(session.userId.isNotBlank() && session.accessToken.isNotBlank())
        val canonicalId = UUID.fromString(worldId).toString()
        val safeName = displayName.trim().ifBlank { "عالمنا الخاص" }.take(80)

        val createBody = JSONObject()
            .put("id", canonicalId)
            .put("owner_id", session.userId)
            .put("display_name", safeName)

        val createResponse = request(
            path = "/rest/v1/worlds?on_conflict=id",
            method = "POST",
            accessToken = session.accessToken,
            body = createBody,
            prefer = "resolution=ignore-duplicates,return=representation"
        )
        if (createResponse.isNotEmpty()) {
            val createdRows = JSONArray(createResponse)
            if (createdRows.length() > 0) {
                val row = createdRows.getJSONObject(0)
                verifyOwnerRow(row, canonicalId, session.userId)
                return@withContext Unit
            }
        }

        // A retry after an earlier successful insert returns no representation.
        // Verify that the existing row belongs to this authenticated account.
        val existing = request(
            path = "/rest/v1/worlds?id=eq.$canonicalId&select=id,owner_id,display_name",
            method = "GET",
            accessToken = session.accessToken,
            body = null
        )
        val rows = JSONArray(existing)
        if (rows.length() != 1) {
            throw SupabaseAuthException("cloud_world_not_visible_after_create")
        }
        val existingRow = rows.getJSONObject(0)
        verifyOwnerRow(existingRow, canonicalId, session.userId)
        if (existingRow.optString("display_name") != safeName) {
            request(
                path = "/rest/v1/worlds?id=eq.$canonicalId",
                method = "PATCH",
                accessToken = session.accessToken,
                body = JSONObject().put("display_name", safeName),
                prefer = "return=minimal"
            )
        }
        Unit
    }

    private fun verifyOwnerRow(row: JSONObject, expectedId: String, expectedOwner: String) {
        if (row.optString("id") != expectedId || row.optString("owner_id") != expectedOwner) {
            throw SupabaseAuthException("cloud_world_owner_mismatch")
        }
    }

    private fun request(
        path: String,
        method: String,
        accessToken: String,
        body: JSONObject?,
        prefer: String? = null
    ): String {
        val connection = (URL(config.projectUrl.trimEnd('/') + path)
            .openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = body != null
            setRequestProperty("apikey", config.publishableKey)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            if (body != null) setRequestProperty("Content-Type", "application/json")
            if (prefer != null) setRequestProperty("Prefer", prefer)
        }
        try {
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
                        status == 401 || status == 403 -> "cloud_world_authorization_failed"
                        status == 409 -> "cloud_world_conflict"
                        status == 429 -> "cloud_world_rate_limited"
                        status >= 500 -> "cloud_world_service_unavailable"
                        else -> "cloud_world_request_rejected"
                    }
                )
            }
            return response
        } catch (error: SupabaseAuthException) {
            throw error
        } catch (_: IOException) {
            throw SupabaseAuthException("cloud_world_network_unavailable")
        } finally {
            connection.disconnect()
        }
    }
}
