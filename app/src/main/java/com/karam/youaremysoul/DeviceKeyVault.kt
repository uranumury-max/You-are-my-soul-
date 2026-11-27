package com.karam.youaremysoul

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Per-install RSA key pair. The private key never leaves Android Keystore.
 * Only the X.509 public key is registered with the authenticated device profile.
 */
class DeviceKeyVault {
    fun publicKeyBase64(): String =
        Base64.encodeToString(loadOrCreateKeyPair().public.encoded, Base64.NO_WRAP)

    fun wrapWorldKey(publicKeyBase64: String, worldKey: ByteArray): String {
        require(worldKey.size == WORLD_KEY_BYTES)
        val publicKeyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        require(publicKeyBytes.size in MIN_PUBLIC_KEY_BYTES..MAX_PUBLIC_KEY_BYTES)
        val publicKey = java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyBytes))
        require(publicKey.algorithm.equals("RSA", ignoreCase = true))
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_PARAMETERS)
        return ENVELOPE_PREFIX + Base64.encodeToString(cipher.doFinal(worldKey), Base64.NO_WRAP)
    }

    fun unwrapWorldKey(envelope: String): ByteArray {
        require(envelope.startsWith(ENVELOPE_PREFIX))
        require(envelope.length <= MAX_ENVELOPE_CHARS)
        val encrypted = Base64.decode(envelope.removePrefix(ENVELOPE_PREFIX), Base64.NO_WRAP)
        require(encrypted.size == RSA_BYTES)
        val privateKey = loadOrCreateKeyPair().private
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_PARAMETERS)
        return cipher.doFinal(encrypted).also {
            check(it.size == WORLD_KEY_BYTES) { "Invalid world key envelope" }
        }
    }

    private fun loadOrCreateKeyPair(): KeyPair = synchronized(KEYSTORE_LOCK) {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = store.getCertificate(KEY_ALIAS)
        if (existing != null) {
            val privateKey = store.getKey(KEY_ALIAS, null) as? java.security.PrivateKey
                ?: throw IllegalStateException("Device private key is unavailable")
            return@synchronized KeyPair(existing.publicKey, privateKey)
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKeyPair()
    }

    private companion object {
        val KEYSTORE_LOCK = Any()
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "you_are_my_soul_device_exchange_v1"
        const val RSA_TRANSFORMATION = "RSA/ECB/OAEPPadding"
        const val WORLD_KEY_BYTES = 32
        const val RSA_BYTES = 256
        const val MIN_PUBLIC_KEY_BYTES = 256
        const val MAX_PUBLIC_KEY_BYTES = 1024
        const val MAX_ENVELOPE_CHARS = 1024
        const val ENVELOPE_PREFIX = "wke:rsa-oaep-sha256:v1:"
        val OAEP_PARAMETERS = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA1,
            PSource.PSpecified.DEFAULT
        )
    }
}
