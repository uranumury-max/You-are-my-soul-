package com.karam.youaremysoul

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores Supabase refresh credentials encrypted with a dedicated Android Keystore key.
 * The auth key and AAD are deliberately separate from the world snapshot cipher.
 */
internal class AuthSessionStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): StoredAuthSession? {
        val encoded = preferences.getString(SESSION_KEY, null) ?: return null
        val payload = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("Stored authentication session is unreadable")
        }
        require(payload.size in (IV_BYTES + TAG_BYTES)..MAX_PAYLOAD_BYTES) {
            "Stored authentication session has an invalid size"
        }
        val iv = payload.copyOfRange(0, IV_BYTES)
        val encrypted = payload.copyOfRange(IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_BYTES * 8, iv)
        )
        cipher.updateAAD(AAD)
        val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        return StoredAuthSession(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            userId = json.getString("user_id"),
            expiresAtEpochSeconds = json.getLong("expires_at")
        ).also { session ->
            require(session.accessToken.isNotBlank() && session.refreshToken.isNotBlank())
            require(session.userId.isNotBlank() && session.expiresAtEpochSeconds > 0L)
        }
    }

    @Synchronized
    fun save(session: StoredAuthSession) {
        require(session.accessToken.isNotBlank() && session.refreshToken.isNotBlank())
        require(session.userId.isNotBlank() && session.expiresAtEpochSeconds > 0L)
        val json = JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("user_id", session.userId)
            .put("expires_at", session.expiresAtEpochSeconds)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val payload = cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val persisted = preferences.edit()
            .putString(SESSION_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()
        check(persisted) { "Unable to persist authentication session" }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(SESSION_KEY).commit()) {
            "Unable to clear authentication session"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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
        const val PREFERENCES_NAME = "secure_auth_session"
        const val SESSION_KEY = "encrypted_session_v1"
        const val KEY_ALIAS = "you_are_my_soul_auth_session_v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val MAX_PAYLOAD_BYTES = 16 * 1024
        val AAD = "com.karam.youaremysoul/auth-session/v1".toByteArray(Charsets.UTF_8)
    }
}

internal data class StoredAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val expiresAtEpochSeconds: Long
)
