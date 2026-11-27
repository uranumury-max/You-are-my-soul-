package com.karam.youaremysoul

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class WorldMediaCipherTest {
    private val worldId = UUID.fromString("2b71d49b-bef3-4e8a-9f43-79ad53d3e73d")
    private val mediaId = UUID.fromString("8cbf47a0-92c2-40ef-9b8a-99ccf9427f5b")
    private val key = ByteArray(32) { (it * 7 + 11).toByte() }

    @Test
    fun encryptedMediaRoundTripsAndDoesNotContainPlaintext() {
        val plaintext = ByteArray(8192) { (it % 251).toByte() }
        val encrypted = WorldMediaCipher.encrypt(worldId, mediaId, 1, key, plaintext)

        assertFalse(encrypted.toList().windowed(plaintext.size.coerceAtMost(64))
            .any { it.toByteArrayOrEmpty().contentEquals(plaintext.take(64).toByteArray()) })
        assertArrayEquals(plaintext, WorldMediaCipher.decrypt(worldId, mediaId, 1, key, encrypted))
    }

    @Test
    fun encryptionUsesFreshNonce() {
        val plaintext = "same image bytes".toByteArray()
        val first = WorldMediaCipher.encrypt(worldId, mediaId, 1, key, plaintext)
        val second = WorldMediaCipher.encrypt(worldId, mediaId, 1, key, plaintext)
        assertFalse(first.contentEquals(second))
        assertNotEquals(first.toList().subList(8, 20), second.toList().subList(8, 20))
    }

    @Test
    fun associatedDataPreventsCrossWorldOrMediaSubstitution() {
        val encrypted = WorldMediaCipher.encrypt(worldId, mediaId, 1, key, "private".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            WorldMediaCipher.decrypt(UUID.randomUUID(), mediaId, 1, key, encrypted)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldMediaCipher.decrypt(worldId, UUID.randomUUID(), 1, key, encrypted)
        }
    }

    @Test
    fun tamperingAndVersionMismatchAreRejected() {
        val encrypted = WorldMediaCipher.encrypt(worldId, mediaId, 1, key, "secret".toByteArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            WorldMediaCipher.decrypt(worldId, mediaId, 1, key, encrypted)
        }
        val fresh = WorldMediaCipher.encrypt(worldId, mediaId, 1, key, "secret".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            WorldMediaCipher.decrypt(worldId, mediaId, 2, key, fresh)
        }
    }

    @Test
    fun emptyAndOversizedPlaintextAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WorldMediaCipher.encrypt(worldId, mediaId, 1, key, byteArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldMediaCipher.encrypt(
                worldId, mediaId, 1, key,
                ByteArray(WorldMediaCipher.MAX_PLAINTEXT_BYTES + 1)
            )
        }
    }
}
