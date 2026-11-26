package com.karam.youaremysoul

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local protection for per-world content keys.
 *
 * The world key is wrapped by a non-exportable Android Keystore key before
 * persistence. It is exposed only briefly in memory during encrypt/decrypt.
 * This vault does not yet distribute keys to other members; a separately
 * authenticated public-key envelope exchange is required before sync is enabled.
 */
class WorldKeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("world_key_vault", Context.MODE_PRIVATE)

    @Synchronized
    fun createWorldKey(worldId: UUID, keyVersion: Int = 1) {
        require(keyVersion > 0)
        val storageKey = storageKey(worldId, keyVersion)
        check(prefs.getString(storageKey, null) == null) { "World key version already exists" }

        val rawKey = ByteArray(WORLD_KEY_BYTES).also(SecureRandom()::nextBytes)
        try {
            val wrapped = wrapWorldKey(worldId, keyVersion, rawKey)
            check(prefs.edit().putString(storageKey, wrapped).commit()) {
                "Unable to persist wrapped world key"
            }
        } finally {
            rawKey.fill(0)
        }
    }

    fun hasWorldKey(worldId: UUID, keyVersion: Int = 1): Boolean =
        prefs.contains(storageKey(worldId, keyVersion))

    fun encrypt(
        worldId: UUID,
        eventId: UUID,
        eventType: SyncEventType,
        plaintext: ByteArray,
        keyVersion: Int = 1
    ): String {
        val rawKey = loadWorldKey(worldId, keyVersion)
        return try {
            WorldContentCipher.encrypt(worldId, eventId, eventType, keyVersion, rawKey, plaintext)
        } finally {
            rawKey.fill(0)
        }
    }

    fun decrypt(
        worldId: UUID,
        eventId: UUID,
        eventType: SyncEventType,
        envelope: String,
        keyVersion: Int = 1
    ): ByteArray {
        val rawKey = loadWorldKey(worldId, keyVersion)
        return try {
            WorldContentCipher.decrypt(worldId, eventId, eventType, keyVersion, rawKey, envelope)
        } finally {
            rawKey.fill(0)
        }
    }

    @Synchronized
    private fun loadWorldKey(worldId: UUID, keyVersion: Int): ByteArray {
        require(keyVersion > 0)
        val wrapped = prefs.getString(storageKey(worldId, keyVersion), null)
            ?: throw IllegalStateException("World encryption key is unavailable")
        return unwrapWorldKey(worldId, keyVersion, wrapped)
    }

    private fun wrapWorldKey(worldId: UUID, keyVersion: Int, rawKey: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        cipher.updateAAD(wrappingAad(worldId, keyVersion))
        val payload = cipher.iv + cipher.doFinal(rawKey)
        return WRAPPED_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun unwrapWorldKey(worldId: UUID, keyVersion: Int, wrapped: String): ByteArray {
        require(wrapped.startsWith(WRAPPED_PREFIX)) { "Unsupported wrapped world key format" }
        val payload = Base64.decode(wrapped.removePrefix(WRAPPED_PREFIX), Base64.NO_WRAP)
        require(payload.size == NONCE_BYTES + WORLD_KEY_BYTES + TAG_BYTES) {
            "Invalid wrapped world key size"
        }

        val nonce = payload.copyOfRange(0, NONCE_BYTES)
        val ciphertext = payload.copyOfRange(NONCE_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateWrappingKey(),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.updateAAD(wrappingAad(worldId, keyVersion))
        return cipher.doFinal(ciphertext).also {
            check(it.size == WORLD_KEY_BYTES) { "Invalid world key material" }
        }
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(WRAPPING_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAPPING_KEY_ALIAS,
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

    private fun storageKey(worldId: UUID, keyVersion: Int): String =
        "world_" + worldId + "_key_v" + keyVersion

    private fun wrappingAad(worldId: UUID, keyVersion: Int): ByteArray =
        ("com.karam.youaremysoul|world-key-wrap|v1|" + worldId + "|" + keyVersion)
            .toByteArray(Charsets.UTF_8)

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val WRAPPING_KEY_ALIAS = "you_are_my_soul_world_key_wrap_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val WORLD_KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val WRAPPED_PREFIX = "wrapped:v1:"
    }
}
