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

/**
 * Private Supabase Storage transport for end-to-end encrypted media.
 * Only opaque ciphertext is uploaded; bucket policies enforce active world membership.
 */
class SupabaseWorldMediaStorage {
    private val config = SupabaseRuntimeConfig.fromBuildConfig()

    suspend fun upload(
        session: AuthenticatedSupabaseUser,
        worldId: UUID,
        mediaId: UUID,
        plaintext: ByteArray,
        worldKeyVault: WorldKeyVault,
        keyVersion: Int = 1
    ): String = withContext(Dispatchers.IO) {
        require(config.isConfigured) { "media_storage_not_configured" }
        require(plaintext.isNotEmpty() && plaintext.size <= WorldMediaCipher.MAX_PLAINTEXT_BYTES)
        val path = objectPath(worldId, mediaId)
        val encrypted = worldKeyVault.encryptMedia(worldId, mediaId, plaintext, keyVersion)
        try {
            request(
                path = "/storage/v1/object/world-media/$path",
                method = "POST",
                session = session,
                body = encrypted,
                maxResponseBytes = 16 * 1024
            )
            path
        } finally {
            encrypted.fill(0)
        }
    }

    suspend fun download(
        session: AuthenticatedSupabaseUser,
        worldId: UUID,
        mediaId: UUID,
        worldKeyVault: WorldKeyVault,
        keyVersion: Int = 1
    ): ByteArray = withContext(Dispatchers.IO) {
        require(config.isConfigured) { "media_storage_not_configured" }
        val path = objectPath(worldId, mediaId)
        val encrypted = request(
            path = "/storage/v1/object/authenticated/world-media/$path",
            method = "GET",
            session = session,
            body = null,
            maxResponseBytes = MAX_ENCRYPTED_BYTES
        )
        try {
            worldKeyVault.decryptMedia(worldId, mediaId, encrypted, keyVersion)
        } finally {
            encrypted.fill(0)
        }
    }

    suspend fun delete(
        session: AuthenticatedSupabaseUser,
        worldId: UUID,
        mediaId: UUID
    ) = withContext(Dispatchers.IO) {
        require(config.isConfigured) { "media_storage_not_configured" }
        val path = objectPath(worldId, mediaId)
        val body = JSONObject().put("prefixes", JSONArray().put(path))
            .toString().toByteArray(Charsets.UTF_8)
        request(
            path = "/storage/v1/object/world-media",
            method = "DELETE",
            session = session,
            body = body,
            maxResponseBytes = 16 * 1024,
            contentType = "application/json"
        )
    }

    private fun request(
        path: String,
        method: String,
        session: AuthenticatedSupabaseUser,
        body: ByteArray?,
        maxResponseBytes: Int,
        contentType: String = "application/octet-stream"
    ): ByteArray {
        val connection = (URL(config.projectUrl.trimEnd('/') + path)
            .openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = body != null
            setRequestProperty("apikey", config.publishableKey)
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("x-upsert", "false")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.close()
                throw SupabaseAuthException(
                    when {
                        status == 401 || status == 403 -> "media_authorization_failed"
                        status == 404 -> "media_not_found"
                        status == 413 -> "media_too_large"
                        status == 409 -> "media_already_exists"
                        status == 429 -> "media_rate_limited"
                        status >= 500 -> "media_service_unavailable"
                        else -> "media_request_rejected"
                    }
                )
            }
            val declared = connection.contentLengthLong
            if (declared > maxResponseBytes || declared < -1L) {
                throw SupabaseAuthException("media_response_size_invalid")
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(maxResponseBytes, declared.coerceAtLeast(0L).toInt()))
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxResponseBytes) {
                        throw SupabaseAuthException("media_response_too_large")
                    }
                    output.write(buffer, 0, count)
                }
                buffer.fill(0)
                output.toByteArray()
            }
        } catch (error: SupabaseAuthException) {
            throw error
        } catch (_: IOException) {
            throw SupabaseAuthException("media_network_unavailable")
        } finally {
            connection.disconnect()
        }
    }

    private fun objectPath(worldId: UUID, mediaId: UUID): String =
        "$worldId/$mediaId.wmd"

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 45_000
        const val MAX_ENCRYPTED_BYTES = WorldMediaCipher.MAX_PLAINTEXT_BYTES + 64
    }
}
