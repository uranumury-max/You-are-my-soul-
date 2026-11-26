package com.karam.youaremysoul

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end content envelope. The backend receives only this authenticated ciphertext.
 * A distinct random nonce is generated for every encryption operation.
 */
internal object WorldContentCipher {
    private const val PREFIX = "wce:v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val MAX_PLAINTEXT_BYTES = 700_000
    private val random = SecureRandom()

    fun encrypt(
        worldId: UUID,
        eventId: UUID,
        eventType: SyncEventType,
        keyVersion: Int,
        worldKey: ByteArray,
        plaintext: ByteArray
    ): String {
        require(keyVersion > 0)
        require(worldKey.size == 32) { "World key must be 256 bits" }
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Content exceeds the safe event size limit" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(worldKey, "AES"))
        cipher.updateAAD(associatedData(worldId, eventId, eventType, keyVersion))
        val encrypted = cipher.doFinal(plaintext)
        val envelope = cipher.iv + encrypted
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(envelope)
        val result = "$PREFIX$keyVersion:$encoded"
        require(result.toByteArray(Charsets.UTF_8).size <= SyncProtocolRules.MAX_CIPHERTEXT_BYTES) {
            "Encrypted content exceeds the server payload limit"
        }
        return result
    }

    fun decrypt(
        worldId: UUID,
        eventId: UUID,
        eventType: SyncEventType,
        keyVersion: Int,
        worldKey: ByteArray,
        envelope: String
    ): ByteArray {
        require(keyVersion > 0)
        require(worldKey.size == 32) { "World key must be 256 bits" }
        val expectedPrefix = "$PREFIX$keyVersion:"
        require(envelope.startsWith(expectedPrefix)) { "Unsupported world content envelope" }
        require(envelope.length <= SyncProtocolRules.MAX_CIPHERTEXT_BYTES)

        val payload = Base64.getDecoder().decode(envelope.removePrefix(expectedPrefix))
        require(payload.size in (NONCE_BYTES + TAG_BYTES)..(MAX_PLAINTEXT_BYTES + NONCE_BYTES + TAG_BYTES)) {
            "Invalid encrypted content size"
        }

        val nonce = payload.copyOfRange(0, NONCE_BYTES)
        val ciphertext = payload.copyOfRange(NONCE_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(worldKey, "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.updateAAD(associatedData(worldId, eventId, eventType, keyVersion))
        return cipher.doFinal(ciphertext)
    }

    private fun associatedData(
        worldId: UUID,
        eventId: UUID,
        eventType: SyncEventType,
        keyVersion: Int
    ): ByteArray =
        "com.karam.youaremysoul|world-content|v1|$worldId|$eventId|" +
            eventType.wireValue + "|$keyVersion"
            .toByteArray(Charsets.UTF_8)
}
