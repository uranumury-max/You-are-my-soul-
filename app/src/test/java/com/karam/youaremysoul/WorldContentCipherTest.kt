package com.karam.youaremysoul

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldContentCipherTest {
    private val worldId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val eventId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val key = ByteArray(32) { (it * 3 + 11).toByte() }

    @Test
    fun encryptedContentRoundTrips() {
        val plaintext = "ذكرى خاصة لا يجب أن يقرأها الخادم".toByteArray(Charsets.UTF_8)
        val envelope = WorldContentCipher.encrypt(
            worldId, eventId, SyncEventType.MEMORY_CREATE, 1, key, plaintext
        )
        val decrypted = WorldContentCipher.decrypt(
            worldId, eventId, SyncEventType.MEMORY_CREATE, 1, key, envelope
        )
        assertArrayEquals(plaintext, decrypted)
        assertTrue(envelope.startsWith("wce:v1:1:"))
    }

    @Test
    fun generatesDifferentNoncesForRepeatedEncryption() {
        val first = WorldContentCipher.encrypt(
            worldId, eventId, SyncEventType.MEMORY_CREATE, 1, key, byteArrayOf(1, 2, 3)
        )
        val second = WorldContentCipher.encrypt(
            worldId, eventId, SyncEventType.MEMORY_CREATE, 1, key, byteArrayOf(1, 2, 3)
        )
        assertTrue(first != second)
    }

    @Test
    fun rejectsWrongWorldEventTypeVersionAndKey() {
        val envelope = WorldContentCipher.encrypt(
            worldId, eventId, SyncEventType.MESSAGE_CREATE, 2, key, byteArrayOf(1, 2, 3, 4)
        )

        assertTrue(runCatching {
            WorldContentCipher.decrypt(
                UUID.randomUUID(), eventId, SyncEventType.MESSAGE_CREATE, 2, key, envelope
            )
        }.isFailure)
        assertTrue(runCatching {
            WorldContentCipher.decrypt(
                worldId, UUID.randomUUID(), SyncEventType.MESSAGE_CREATE, 2, key, envelope
            )
        }.isFailure)
        assertTrue(runCatching {
            WorldContentCipher.decrypt(
                worldId, eventId, SyncEventType.MEMORY_CREATE, 2, key, envelope
            )
        }.isFailure)
        assertTrue(runCatching {
            WorldContentCipher.decrypt(
                worldId, eventId, SyncEventType.MESSAGE_CREATE, 1, key, envelope
            )
        }.isFailure)
        assertTrue(runCatching {
            WorldContentCipher.decrypt(
                worldId, eventId, SyncEventType.MESSAGE_CREATE, 2, ByteArray(32), envelope
            )
        }.isFailure)
    }

    @Test
    fun rejectsPaddedAlternativeEncoding() {
        val envelope = WorldContentCipher.encrypt(
            worldId, eventId, SyncEventType.MEMORY_CREATE, 1, key, byteArrayOf(1, 2, 3)
        )
        assertTrue(runCatching {
            WorldContentCipher.decrypt(
                worldId, eventId, SyncEventType.MEMORY_CREATE, 1, key, "$envelope="
            )
        }.isFailure)
    }

    @Test
    fun rejectsTamperedCiphertextAndInvalidKeyLength() {
        val envelope = WorldContentCipher.encrypt(
            worldId, eventId, SyncEventType.MEMORY_CREATE, 1, key, byteArrayOf(9, 8, 7)
        )
        val prefix = "wce:v1:1:"
        val tampered = envelope.replaceRange(
            prefix.length,
            prefix.length + 1,
            if (envelope[prefix.length] == 'A') "B" else "A"
        )
        assertFalse(runCatching {
            WorldContentCipher.decrypt(
                worldId, eventId, SyncEventType.MEMORY_CREATE, 1, key, tampered
            )
        }.isSuccess)
        assertTrue(runCatching {
            WorldContentCipher.encrypt(
                worldId, eventId, SyncEventType.MEMORY_CREATE, 1, ByteArray(16), byteArrayOf(1)
            )
        }.isFailure)
    }
}
