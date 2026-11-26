package com.karam.youaremysoul

import android.content.Context
import java.security.SecureRandom

/** Stable per-install 9-digit identity. This is an app code, never a phone number. */
class DeviceIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)

    /** Stable random installation UUID; never derived from hardware identifiers. */
    val installationId: String
        get() = synchronized(IDENTITY_LOCK) {
            val saved = prefs.getString("installation_id", null)
            if (saved != null) return@synchronized saved
            val generated = java.util.UUID.randomUUID().toString()
            val committed = prefs.edit().putString("installation_id", generated).commit()
            check(committed) { "Unable to persist installation identity" }
            generated
        }

    val code: String
        get() = synchronized(IDENTITY_LOCK) {
            val saved = prefs.getString("code", null)
            if (saved != null) return@synchronized saved
            generateAndPersistCode()
        }

    /** Replaces a colliding public discovery code while preserving installationId. */
    fun regenerateCode(): String = synchronized(IDENTITY_LOCK) { generateAndPersistCode() }

    private fun generateAndPersistCode(): String {
        val random = SecureRandom()
        val generated = (100_000_000 + random.nextInt(900_000_000)).toString()
        val committed = prefs.edit().putString("code", generated).commit()
        check(committed) { "Unable to persist device code" }
        return generated
    }

    private companion object {
        val IDENTITY_LOCK = Any()
    }
}
