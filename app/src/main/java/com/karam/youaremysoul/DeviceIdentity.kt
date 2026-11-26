package com.karam.youaremysoul

import android.content.Context
import java.security.SecureRandom

/** Stable per-install 9-digit identity. This is an app code, never a phone number. */
class DeviceIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
    val code: String
        get() {
            val saved = prefs.getString("code", null)
            if (saved != null) return saved
            val random = SecureRandom()
            var generated: String
            do {
                generated = (100_000_000 + random.nextInt(900_000_000)).toString()
            } while (generated == "000000000")
            prefs.edit().putString("code", generated).apply()
            return generated
        }
}
