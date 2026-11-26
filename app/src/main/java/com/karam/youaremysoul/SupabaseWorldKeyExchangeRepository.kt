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
            val envelope = worldKeyVault.createDeviceEnvelope(
                id, keyVersion, device.publicKeyBase64, deviceKeyVault
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
