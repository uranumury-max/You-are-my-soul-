package com.karam.youaremysoul

import android.content.Context
import java.security.SecureRandom

/** Stable per-install 9-digit identity. This is an app code, never a phone number. */
class DeviceIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)

    /** Stable random installation UUID; never derived from hardware identifiers. */
    val installationId: String
        get() {
            val saved = prefs.getString("installation_id", null)
            if (saved != null) return saved
            val generated = java.util.UUID.randomUUID().toString()
            val committed = prefs.edit().putString("installation_id", generated).commit()
            check(committed) { "Unable to persist installation identity" }
            return generated
        }

    val code: String
        get() {
            val saved = prefs.getString("code", null)
            if (saved != null) return saved
            val random = SecureRandom()
            var generated: String
            do {
                generated = (100_000_000 + random.nextInt(900_000_000)).toString()
            } while (generated == "000000000")
            val committed = prefs.edit().putString("code", generated).commit()
            check(committed) { "Unable to persist device code" }
            return generated
        }
}
