package com.karam.youaremysoul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SupabaseRuntimeConfigTest {
    @Test
    fun acceptsHttpsProjectAndPublishableKey() {
        val config = SupabaseRuntimeConfig(
            "https://example.supabase.co",
            "sb_publishable_example"
        )
        assertTrue(config.isConfigured)
        assertNull(config.validationError())
    }

    @Test
    fun rejectsHttpProjectUrl() {
        val config = SupabaseRuntimeConfig(
            "http://example.supabase.co",
            "sb_publishable_example"
        )
        assertFalse(config.isConfigured)
        assertEquals("https_project_url_required", config.validationError())
    }

    @Test
    fun rejectsMissingPublishableKey() {
        val config = SupabaseRuntimeConfig("https://example.supabase.co", " ")
        assertEquals("publishable_key_missing", config.validationError())
    }

    @Test
    fun rejectsNewSecretKeyFormat() {
        val config = SupabaseRuntimeConfig(
            "https://example.supabase.co",
            "sb_secret_do_not_embed"
        )
        assertEquals("service_role_key_forbidden", config.validationError())
    }

    @Test
    fun rejectsLegacyServiceRoleJwt() {
        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"HS256"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"role":"service_role"}""".toByteArray())
        val config = SupabaseRuntimeConfig(
            "https://example.supabase.co",
            "$header.$payload.signature"
        )
        assertEquals("service_role_key_forbidden", config.validationError())
    }
}
