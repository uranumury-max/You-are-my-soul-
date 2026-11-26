package com.karam.youaremysoul

import java.util.Base64
import java.util.UUID

/**
 * Transport-neutral synchronization contract.
 *
 * User content must be encrypted before it is placed in ciphertextBase64.
 * This model deliberately contains no plaintext body or device-code credential.
 */
enum class SyncEventType(val wireValue: String) {
    WORLD_UPDATE("world_update"),
    MEMORY_CREATE("memory_create"),
    MEMORY_DELETE("memory_delete"),
    MESSAGE_CREATE("message_create"),
    GARDEN_UPDATE("garden_update"),
    DAILY_ANSWER("daily_answer"),
    DRAWING_UPDATE("drawing_update"),
    CAPSULE_UPDATE("capsule_update"),
    MEDIA_ADD("media_add"),
    SETTINGS_UPDATE("settings_update")
}

data class WorldSyncEvent(
    val eventId: UUID,
    val worldId: UUID,
    val actorUserId: UUID,
    val type: SyncEventType,
    val ciphertextBase64: String,
    val clientCreatedAtEpochMs: Long
)

data class SequencedWorldEvent(
    val serverSequence: Long,
    val event: WorldSyncEvent
)

@JvmInline
value class SyncCursor(val serverSequence: Long) {
    init {
        require(serverSequence >= 0L) { "Sync cursor cannot be negative" }
    }
}

data class SyncPage(
    val events: List<SequencedWorldEvent>,
    val nextCursor: SyncCursor,
    val hasMore: Boolean
)

data class PendingSyncEvent(
    val event: WorldSyncEvent,
    val queuedAtEpochMs: Long,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMs: Long = queuedAtEpochMs
)

/** Shared protocol limits. Server-side limits remain authoritative. */
object SyncProtocolRules {
    const val MAX_EVENTS_PER_PAGE = 100
    const val MAX_CIPHERTEXT_BYTES = 1_048_576
    const val MIN_ENCODED_CIPHERTEXT_BYTES = 32
    const val MIN_DECODED_CIPHERTEXT_BYTES = 28
    const val MAX_PENDING_ATTEMPTS = 20

    fun validate(event: WorldSyncEvent): String? {
        if (event.clientCreatedAtEpochMs <= 0L) return "invalid_client_timestamp"
        if (event.ciphertextBase64.isBlank()) return "missing_ciphertext"
        // The server limits the UTF-8 Base64 column to one MiB; Base64 is ASCII,
        // so its character count is also its encoded byte count.
        if (event.ciphertextBase64.length > MAX_CIPHERTEXT_BYTES) {
            return "ciphertext_size_out_of_range"
        }
        val decoded = try {
            Base64.getDecoder().decode(event.ciphertextBase64)
        } catch (_: IllegalArgumentException) {
            return "invalid_ciphertext_encoding"
        }
        if (event.ciphertextBase64.length < MIN_ENCODED_CIPHERTEXT_BYTES ||
            decoded.size < MIN_DECODED_CIPHERTEXT_BYTES
        ) return "ciphertext_size_out_of_range"
        return null
    }

    fun validatePage(page: SyncPage, requestedAfter: SyncCursor): String? {
        if (page.events.size > MAX_EVENTS_PER_PAGE) return "page_too_large"
        var previous = requestedAfter.serverSequence
        for (item in page.events) {
            if (item.serverSequence <= previous) return "non_monotonic_server_sequence"
            if (item.serverSequence <= 0L) return "invalid_server_sequence"
            if (validate(item.event) != null) return "invalid_event_payload"
            previous = item.serverSequence
        }
        if (page.events.isNotEmpty() && page.nextCursor.serverSequence != previous) {
            return "cursor_does_not_match_last_event"
        }
        if (page.events.isEmpty() && page.nextCursor.serverSequence != requestedAfter.serverSequence) {
            return "empty_page_changed_cursor"
        }
        return null
    }

    fun validatePending(record: PendingSyncEvent): String? {
        if (record.queuedAtEpochMs <= 0L || record.nextAttemptAtEpochMs <= 0L) {
            return "invalid_queue_timestamp"
        }
        if (record.attemptCount !in 0..MAX_PENDING_ATTEMPTS) return "attempt_limit_exceeded"
        return validate(record.event)
    }
}
