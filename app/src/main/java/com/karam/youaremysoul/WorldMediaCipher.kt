package com.karam.youaremysoul

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption for binary media objects.
 * Object names and MIME metadata are not embedded in plaintext storage paths.
 */
internal object WorldMediaCipher {
    private val MAGIC = byteArrayOf(0x57, 0x4d, 0x44, 0x31) // WMD1
    private const val FORMAT_VERSION = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    const val MAX_PLAINTEXT_BYTES = 20 * 1024 * 1024
    private const val HEADER_BYTES = 4 + 4 + NONCE_BYTES

    fun encrypt(
        worldId: UUID,
        mediaId: UUID,
        keyVersion: Int,
        worldKey: ByteArray,
        plaintext: ByteArray
    ): ByteArray {
        require(keyVersion > 0)
        require(worldKey.size == 32)
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(worldKey, "AES"))
        cipher.updateAAD(aad(worldId, mediaId, keyVersion))
        val encrypted = cipher.doFinal(plaintext)
        val nonce = cipher.iv
        check(nonce.size == NONCE_BYTES)
        return ByteBuffer.allocate(HEADER_BYTES + encrypted.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .putInt(keyVersion)
            .put(nonce)
            .put(encrypted)
            .array()
    }

    fun decrypt(
        worldId: UUID,
        mediaId: UUID,
        keyVersion: Int,
        worldKey: ByteArray,
        encrypted: ByteArray
    ): ByteArray {
        require(worldKey.size == 32)
        require(encrypted.size in (HEADER_BYTES + TAG_BYTES)..(HEADER_BYTES + MAX_PLAINTEXT_BYTES + TAG_BYTES))
        val buffer = ByteBuffer.wrap(encrypted).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Unsupported media envelope" }
        val storedVersion = buffer.int
        require(storedVersion == keyVersion && storedVersion > 0) { "Media key version mismatch" }
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(worldKey, "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.updateAAD(aad(worldId, mediaId, keyVersion))
        return cipher.doFinal(ciphertext)
    }

    private fun aad(worldId: UUID, mediaId: UUID, keyVersion: Int): ByteArray =
        "com.karam.youaremysoul|media|v1|$worldId|$mediaId|$keyVersion"
            .toByteArray(Charsets.UTF_8)
}
