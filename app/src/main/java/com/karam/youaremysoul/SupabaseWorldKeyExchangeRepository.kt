package com.karam.youaremysoul

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

data class WorldMemberDeviceKey(
    val userId: String,
    val deviceId: String,
    val publicKeyBase64: String
)

data class DownloadedWorldKeyEnvelope(
    val worldId: String,
    val keyVersion: Int,
    val envelope: String
)

/** Authenticated transport for owner-published, recipient-encrypted world keys. */
class SupabaseWorldKeyExchangeRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("world_key_exchange_cache", Context.MODE_PRIVATE)
    private val config = SupabaseRuntimeConfig.fromBuildConfig()
    private val identity = DeviceIdentity(context.applicationContext)

    suspend fun activeMemberDevices(
        session: AuthenticatedSupabaseUser,
        worldId: String
    ): List<WorldMemberDeviceKey> = withContext(Dispatchers.IO) {
        val id = UUID.fromString(worldId).toString()
        val rows = JSONArray(
            request(
                "/rest/v1/rpc/list_world_member_devices",
                "POST",
                session,
                JSONObject().put("p_world_id", id)
            )
        )
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            WorldMemberDeviceKey(
                userId = UUID.fromString(row.getString("member_user_id")).toString(),
                deviceId = UUID.fromString(row.getString("member_device_id")).toString(),
                publicKeyBase64 = row.getString("member_public_key").also {
                    require(it.length in 300..2048)
                }
            )
        }
    }

    suspend fun publishEnvelope(
        session: AuthenticatedSupabaseUser,
        worldId: String,
        recipient: WorldMemberDeviceKey,
        keyVersion: Int,
        envelope: String
    ): Boolean = withContext(Dispatchers.IO) {
        val id = UUID.fromString(worldId).toString()
        val userId = UUID.fromString(recipient.userId).toString()
        val deviceId = UUID.fromString(recipient.deviceId).toString()
        require(keyVersion > 0)
        require(envelope.startsWith("wke:rsa-oaep-sha256:v1:") && envelope.length in 350..1024)
        request(
            "/rest/v1/rpc/publish_world_key_envelope",
            "POST",
            session,
            JSONObject()
                .put("p_world_id", id)
                .put("p_user_id", userId)
                .put("p_device_id", deviceId)
                .put("p_key_version", keyVersion)
                .put("p_envelope", envelope)
        ).trim().equals("true", ignoreCase = true)
    }


    suspend fun currentWorldKeyVersion(
        session: AuthenticatedSupabaseUser,
        worldId: String
    ): Int = withContext(Dispatchers.IO) {
        val response = request(
            "/rest/v1/rpc/get_world_key_version",
            "POST",
            session,
            JSONObject().put("p_world_id", UUID.fromString(worldId).toString())
        ).trim()
        val value = runCatching { JSONArray(response).optInt(0, -1) }.getOrDefault(-1)
            .takeIf { it > 0 } ?: response.removeSurrounding("\"", "\"").toIntOrNull()
        requireNotNull(value?.takeIf { it > 0 }) { "Invalid active world key version response" }
    }

    /** Fetches/imports the server's active key while retaining all older local keys. */
    suspend fun synchronizeOwnActiveKey(
        session: AuthenticatedSupabaseUser,
        worldId: String,
        worldKeyVault: WorldKeyVault,
        deviceKeyVault: DeviceKeyVault
    ): Int = withContext(Dispatchers.IO) {
        val id = UUID.fromString(worldId)
        val activeVersion = currentWorldKeyVersion(session, worldId)
        if (!worldKeyVault.hasWorldKey(id, activeVersion)) {
            val envelope = fetchOwnEnvelope(session, worldId, activeVersion)
                ?: throw SupabaseAuthException("world_key_envelope_not_available")
            worldKeyVault.importDeviceEnvelope(id, activeVersion, envelope.envelope, deviceKeyVault)
        }
        worldKeyVault.setActiveKeyVersion(id, activeVersion)
        activeVersion
    }

    /** Owner-only rotation: create once, persist stable recipient envelopes, publish all, then activate server-side. */
    suspend fun rotateOwnerWorldKey(
        session: AuthenticatedSupabaseUser,
        worldId: String,
        worldKeyVault: WorldKeyVault,
        deviceKeyVault: DeviceKeyVault
    ): Int = withContext(Dispatchers.IO) {
        val id = UUID.fromString(worldId)
        val current = currentWorldKeyVersion(session, worldId)
        val next = Math.addExact(current, 1)
        if (!worldKeyVault.hasWorldKey(id, next)) worldKeyVault.createWorldKey(id, next)
        val devices = activeMemberDevices(session, worldId)
        require(devices.isNotEmpty()) { "No registered member devices are available" }
        for (device in devices) {
            val envelope = stableEnvelope(id, next, device, worldKeyVault, deviceKeyVault)
            check(publishEnvelope(session, worldId, device, next, envelope)) {
                "World key envelope publication was not confirmed"
            }
        }
        check(activateKeyVersion(session, worldId, current, next)) {
            "Server did not confirm key version activation"
        }
        worldKeyVault.setActiveKeyVersion(id, next)
        next
    }

    private suspend fun activateKeyVersion(
        session: AuthenticatedSupabaseUser,
        worldId: String,
        expectedVersion: Int,
        newVersion: Int
    ): Boolean = withContext(Dispatchers.IO) {
        request(
            "/rest/v1/rpc/activate_world_key_version",
            "POST",
            session,
            JSONObject()
                .put("p_world_id", UUID.fromString(worldId).toString())
                .put("p_expected_version", expectedVersion)
                .put("p_new_version", newVersion)
        ).trim().equals("true", ignoreCase = true)
    }

    private fun stableEnvelope(
        worldId: UUID,
        keyVersion: Int,
        device: WorldMemberDeviceKey,
        worldKeyVault: WorldKeyVault,
        deviceKeyVault: DeviceKeyVault
    ): String {
        val cacheKey = "envelope_${worldId}_v${keyVersion}_${device.userId}_${device.deviceId}"
        prefs.getString(cacheKey, null)?.let { return it }
        val generated = worldKeyVault.createDeviceEnvelope(
            worldId, keyVersion, device.publicKeyBase64, deviceKeyVault
        )
        synchronized(ENVELOPE_CACHE_LOCK) {
            prefs.getString(cacheKey, null)?.let { return it }
            check(prefs.edit().putString(cacheKey, generated).commit()) {
                "Unable to persist stable recipient envelope"
            }
            return generated
        }
    }

    /** Wraps the current world key separately for every active member device. */
    suspend fun distributeOwnerWorldKey(
        session: AuthenticatedSupabaseUser,
        worldId: String,
        keyVersion: Int,
        worldKeyVault: WorldKeyVault,
        deviceKeyVault: DeviceKeyVault
    ): Int = withContext(Dispatchers.IO) {
        val id = UUID.fromString(worldId)
        require(keyVersion > 0 && worldKeyVault.hasWorldKey(id, keyVersion))
        val devices = activeMemberDevices(session, worldId)
        require(devices.isNotEmpty()) { "No registered member devices are available" }
        var published = 0
        for (device in devices) {
            val envelope = stableEnvelope(
                id, keyVersion, device, worldKeyVault, deviceKeyVault
            )
            check(publishEnvelope(session, worldId, device, keyVersion, envelope)) {
                "World key envelope publication was not confirmed"
            }
            published += 1
        }
        published
    }

    suspend fun fetchOwnEnvelope(
        session: AuthenticatedSupabaseUser,
        worldId: String,
        keyVersion: Int
    ): DownloadedWorldKeyEnvelope? = withContext(Dispatchers.IO) {
        val id = UUID.fromString(worldId).toString()
        require(keyVersion > 0)
        val response = request(
            "/rest/v1/world_member_key_envelopes?world_id=eq.$id&user_id=eq.${session.userId}&device_id=eq.${identity.installationId}&key_version=eq.$keyVersion&select=world_id,key_version,envelope",
            "GET",
            session,
            null
        )
        val rows = JSONArray(response)
        if (rows.length() == 0) return@withContext null
        val row = rows.getJSONObject(0)
        DownloadedWorldKeyEnvelope(
            worldId = UUID.fromString(row.getString("world_id")).toString(),
            keyVersion = row.getInt("key_version"),
            envelope = row.getString("envelope")
        ).also {
            require(it.envelope.startsWith("wke:rsa-oaep-sha256:v1:"))
            require(it.envelope.length in 350..1024)
        }
    }

    private companion object { val ENVELOPE_CACHE_LOCK = Any() }

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
                        status == 401 || status == 403 -> "world_key_exchange_authorization_failed"
                        status == 429 -> "world_key_exchange_rate_limited"
                        status >= 500 -> "world_key_exchange_service_unavailable"
                        else -> "world_key_exchange_rejected"
                    }
                )
            }
            response
        } catch (error: SupabaseAuthException) {
            throw error
        } catch (_: IOException) {
            throw SupabaseAuthException("world_key_exchange_network_unavailable")
        } finally {
            connection.disconnect()
        }
    }
}
