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
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    @Synchronized
    fun decrypt(encoded: String): String {
        require(encoded.startsWith(PREFIX)) { "Unsupported encrypted snapshot format" }
        val payload = Base64.decode(encoded.removePrefix(PREFIX), Base64.NO_WRAP)
        require(payload.size > IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) { "Invalid encrypted snapshot" }
        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val encrypted = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(tagLengthBits, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
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
        const val PREFIX = "enc:v1:"
        const val IV_LENGTH_BYTES = 12
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
