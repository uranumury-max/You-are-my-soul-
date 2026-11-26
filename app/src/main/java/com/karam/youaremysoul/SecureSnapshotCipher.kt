package com.karam.youaremysoul

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption for the local world snapshot.
 * The AES key is non-exportable and managed by Android Keystore.
 */
internal class SecureSnapshotCipher {
    private val keyAlias = "you_are_my_soul_world_v1"
    private val transformation = "AES/GCM/NoPadding"
    private val tagLengthBits = 128

    @Synchronized
    fun encrypt(plainText: String): String {
        val plainBytes = plainText.toByteArray(Charsets.UTF_8)
        require(plainBytes.size <= MAX_PLAINTEXT_BYTES) { "World snapshot exceeds the safe size limit" }

        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(V2_ASSOCIATED_DATA)
        val encrypted = cipher.doFinal(plainBytes)
        val payload = cipher.iv + encrypted
        return PREFIX_V2 + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    @Synchronized
    fun decrypt(encoded: String): String {
        val version = when {
            encoded.startsWith(PREFIX_V2) -> 2
            encoded.startsWith(PREFIX_V1) -> 1
            else -> throw IllegalArgumentException("Unsupported encrypted snapshot format")
        }
        require(encoded.length <= MAX_ENCODED_LENGTH) { "Encrypted snapshot exceeds the safe size limit" }

        val prefix = if (version == 2) PREFIX_V2 else PREFIX_V1
        val payload = Base64.decode(encoded.removePrefix(prefix), Base64.NO_WRAP)
        require(payload.size in MIN_PAYLOAD_BYTES..MAX_PAYLOAD_BYTES) { "Invalid encrypted snapshot" }

        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val encrypted = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(tagLengthBits, iv))
        if (version == 2) cipher.updateAAD(V2_ASSOCIATED_DATA)
        val plainBytes = cipher.doFinal(encrypted)
        require(plainBytes.size <= MAX_PLAINTEXT_BYTES) { "Decrypted snapshot exceeds the safe size limit" }
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFIX_V1 = "enc:v1:"
        const val PREFIX_V2 = "enc:v2:"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BYTES = 16
        const val MIN_PAYLOAD_BYTES = IV_LENGTH_BYTES + TAG_LENGTH_BYTES
        const val MAX_PLAINTEXT_BYTES = 2 * 1024 * 1024
        const val MAX_PAYLOAD_BYTES = MAX_PLAINTEXT_BYTES + MIN_PAYLOAD_BYTES
        const val MAX_ENCODED_LENGTH = 3_000_000
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        val V2_ASSOCIATED_DATA = "com.karam.youaremysoul/world-snapshot/v2".toByteArray(Charsets.UTF_8)
    }
}
