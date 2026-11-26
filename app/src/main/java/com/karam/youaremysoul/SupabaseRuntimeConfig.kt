package com.karam.youaremysoul

import java.net.URI
import java.util.Base64

/**
 * Public Supabase client configuration supplied by the build environment.
 *
 * A publishable/anon key is designed to be present in a client application;
 * authorization must still be enforced by RLS and server-side RPCs.
 * A service-role or secret key is never valid here.
 */
data class SupabaseRuntimeConfig(
    val projectUrl: String,
    val publishableKey: String
) {
    fun validationError(): String? {
        val parsed = try {
            URI(projectUrl.trim())
        } catch (_: Exception) {
            return "invalid_project_url"
        }

        if (parsed.scheme != "https" || parsed.host.isNullOrBlank() ||
            parsed.userInfo != null || parsed.fragment != null
        ) return "https_project_url_required"

        val key = publishableKey.trim()
        if (key.isBlank() || key.any { it.isWhitespace() }) return "publishable_key_missing"

        if (key.startsWith("sb_secret_", ignoreCase = true) || isLegacyServiceRoleJwt(key)) {
            return "service_role_key_forbidden"
        }
        return null
    }

    val isConfigured: Boolean
        get() = validationError() == null

    companion object {
        fun fromBuildConfig(): SupabaseRuntimeConfig =
            SupabaseRuntimeConfig(
                projectUrl = BuildConfig.SUPABASE_URL,
                publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
            )

        private fun isLegacyServiceRoleJwt(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 3) return false
            val payload = try {
                String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return false
            }
            return Regex("\"role\"\\s*:\\s*\"service_role\"", RegexOption.IGNORE_CASE)
                .containsMatchIn(payload)
        }
    }
}
