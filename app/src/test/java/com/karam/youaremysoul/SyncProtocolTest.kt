package com.karam.youaremysoul

import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncProtocolTest {
    private fun event(payloadSize: Int = 48): WorldSyncEvent = WorldSyncEvent(
        eventId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
        worldId = UUID.fromString("22222222-2222-4222-8222-222222222222"),
        actorUserId = UUID.fromString("33333333-3333-4333-8333-333333333333"),
        type = SyncEventType.MEMORY_CREATE,
        ciphertextBase64 = "wce:v1:1:" + Base64.getEncoder().encodeToString(ByteArray(payloadSize) { 7 }),
        clientCreatedAtEpochMs = 1_700_000_000_000L
    )

    @Test
    fun acceptsWellFormedEncryptedEvent() {
        assertNull(SyncProtocolRules.validate(event()))
    }

    @Test
    fun rejectsMalformedOrOversizedCiphertext() {
        assertEquals("invalid_ciphertext_encoding", SyncProtocolRules.validate(event().copy(ciphertextBase64 = "not-base64!")))
        assertEquals("ciphertext_size_out_of_range", SyncProtocolRules.validate(event(payloadSize = 8)))
        assertEquals(
            "ciphertext_size_out_of_range",
            SyncProtocolRules.validate(event(payloadSize = SyncProtocolRules.MAX_CIPHERTEXT_BYTES + 1))
        )
    }


    @Test
    fun rejectsUnknownEnvelopeVersionAndInvalidKeyVersion() {
        assertEquals(
            "invalid_ciphertext_encoding",
            SyncProtocolRules.validate(event().copy(ciphertextBase64 = "wce:v2:1:AAAA"))
        )
        assertEquals(
            "invalid_ciphertext_encoding",
            SyncProtocolRules.validate(event().copy(ciphertextBase64 = "wce:v1:0:AAAA"))
        )
        assertEquals(
            "invalid_ciphertext_encoding",
            SyncProtocolRules.validate(event().copy(ciphertextBase64 = "wce:v1:1:not-base64!"))
        )
    }

    @Test
    fun validatesMonotonicServerCursor() {
        val first = SequencedWorldEvent(4L, event())
        val second = SequencedWorldEvent(8L, event().copy(eventId = UUID.randomUUID()))
        val valid = SyncPage(listOf(first, second), SyncCursor(8L), hasMore = false)
        assertNull(SyncProtocolRules.validatePage(valid, SyncCursor(3L)))

        val duplicateSequence = SyncPage(
            listOf(first, second.copy(serverSequence = 4L)),
            SyncCursor(4L),
            hasMore = false
        )
        assertEquals(
            "non_monotonic_server_sequence",
            SyncProtocolRules.validatePage(duplicateSequence, SyncCursor(3L))
        )
    }

    @Test
    fun rejectsCursorThatSkipsOrMisreportsEvents() {
        val page = SyncPage(
            listOf(SequencedWorldEvent(5L, event())),
            SyncCursor(6L),
            hasMore = false
        )
        assertEquals(
            "cursor_does_not_match_last_event",
            SyncProtocolRules.validatePage(page, SyncCursor(4L))
        )
    }

    @Test
    fun rejectsPendingEventAfterRetryLimit() {
        val pending = PendingSyncEvent(
            event = event(),
            queuedAtEpochMs = 1_700_000_000_000L,
            attemptCount = SyncProtocolRules.MAX_PENDING_ATTEMPTS + 1
        )
        assertEquals("attempt_limit_exceeded", SyncProtocolRules.validatePending(pending))
    }
}
