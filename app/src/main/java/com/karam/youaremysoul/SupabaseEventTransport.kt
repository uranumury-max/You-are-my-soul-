package com.karam.youaremysoul

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Supabase transport for encrypted append-only events.
 * Event writes go only through append_world_event; the actor is derived by SQL auth.uid().
 */
class SupabaseEventTransport {
    private val config = SupabaseRuntimeConfig.fromBuildConfig()

    suspend fun append(
        session: AuthenticatedSupabaseUser,
        event: WorldSyncEvent
    ): Long = withContext(Dispatchers.IO) {
        require(event.actorUserId.toString() == session.userId) {
            "Event actor does not match authenticated account"
        }
        val invalid = SyncProtocolRules.validate(event)
        require(invalid == null) { "Invalid encrypted event: $invalid" }

        val body = JSONObject()
            .put("p_world_id", event.worldId.toString())
            .put("p_event_id", event.eventId.toString())
            .put("p_event_type", event.type.wireValue)
            .put("p_ciphertext", event.ciphertextBase64)
            .put("p_client_created_at", Instant.ofEpochMilli(event.clientCreatedAtEpochMs).toString())

        val response = request(
            path = "/rest/v1/rpc/append_world_event",
            method = "POST",
            session = session,
            body = body
        ).trim().removeSurrounding("\"")
        val sequence = response.toLongOrNull()
            ?: throw SupabaseAuthException("event_append_invalid_sequence")
        if (sequence == -429L) throw SupabaseAuthException("event_rate_limited")
        sequence.takeIf { it > 0L }
            ?: throw SupabaseAuthException("event_append_invalid_sequence")
    }

    suspend fun fetchPage(
        session: AuthenticatedSupabaseUser,
        worldId: UUID,
        after: SyncCursor,
        limit: Int = SyncProtocolRules.MAX_EVENTS_PER_PAGE
    ): SyncPage = withContext(Dispatchers.IO) {
        require(limit in 1..SyncProtocolRules.MAX_EVENTS_PER_PAGE)
        val response = request(
            path = "/rest/v1/world_events?select=server_seq,world_id,event_id,actor_id,event_type,ciphertext,client_created_at" +
                "&world_id=eq.$worldId&server_seq=gt.${after.serverSequence}" +
                "&order=server_seq.asc&limit=$limit",
            method = "GET",
            session = session,
            body = null
        )
        val rows = JSONArray(response)
        val events = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val eventType = SyncEventType.fromWire(row.getString("event_type"))
                ?: throw SupabaseAuthException("event_download_unknown_type")
            val eventWorldId = UUID.fromString(row.getString("world_id"))
            if (eventWorldId != worldId) {
                throw SupabaseAuthException("event_download_world_mismatch")
            }
            val clientCreatedAt = try {
                OffsetDateTime.parse(row.getString("client_created_at")).toInstant().toEpochMilli()
            } catch (_: Exception) {
                throw SupabaseAuthException("event_download_invalid_timestamp")
            }
            SequencedWorldEvent(
                serverSequence = row.getLong("server_seq"),
                event = WorldSyncEvent(
                    eventId = UUID.fromString(row.getString("event_id")),
                    worldId = eventWorldId,
                    actorUserId = UUID.fromString(row.getString("actor_id")),
                    type = eventType,
                    ciphertextBase64 = row.getString("ciphertext"),
                    clientCreatedAtEpochMs = clientCreatedAt
                )
            )
        }
        val nextCursor = events.lastOrNull()?.serverSequence?.let(::SyncCursor) ?: after
        SyncPage(
            events = events,
            nextCursor = nextCursor,
            hasMore = events.size == limit
        ).also { page ->
            val invalid = SyncProtocolRules.validatePage(page, after)
            if (invalid != null) throw SupabaseAuthException("event_download_$invalid")
        }
    }

    private fun readBoundedResponse(stream: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) {
                throw IOException("Supabase response exceeded the client safety limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
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
            readTimeout = 20_000
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
            // Never let an unexpectedly large HTTP response exhaust device memory.
            val response = stream?.use { readBoundedResponse(it) }.orEmpty()
            if (status !in 200..299) {
                throw SupabaseAuthException(
                    when {
                        status == 401 || status == 403 -> "event_authorization_failed"
                        status == 429 -> "event_rate_limited"
                        status >= 500 -> "event_service_unavailable"
                        else -> "event_request_rejected"
                    }
                )
            }
            response
        } catch (error: SupabaseAuthException) {
            throw error
        } catch (_: IOException) {
            throw SupabaseAuthException("event_network_unavailable")
        } finally {
            connection.disconnect()
        }
    }
    private companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
    }
}
