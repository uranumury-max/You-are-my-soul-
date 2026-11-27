package com.karam.youaremysoul

import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Fetches trusted UTC time from the configured Supabase database. */
internal class SupabaseServerClock {
    private val config = SupabaseRuntimeConfig.fromBuildConfig()

    fun nowEpochMillis(session: AuthenticatedSupabaseUser): Long {
        val validation = config.validationError()
        if (validation != null) throw SupabaseAuthException(validation)
        val endpoint = URL(config.projectUrl.trimEnd('/') + "/rest/v1/rpc/get_server_time")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("apikey", config.publishableKey)
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { readBounded(it, 8 * 1024) }.orEmpty()
            if (status !in 200..299) {
                throw SupabaseAuthException(
                    when {
                        status == 401 || status == 403 -> "server_clock_authorization_failed"
                        status == 429 -> "server_clock_rate_limited"
                        status >= 500 -> "server_clock_unavailable"
                        else -> "server_clock_rejected"
                    }
                )
            }
            val value = JSONTokener(raw).nextValue()
            val timestamp = value as? String
                ?: throw SupabaseAuthException("server_clock_invalid_response")
            return Instant.parse(timestamp).toEpochMilli()
        } catch (error: SupabaseAuthException) {
            throw error
        } catch (_: IOException) {
            throw SupabaseAuthException("server_clock_network_unavailable")
        } catch (_: Exception) {
            throw SupabaseAuthException("server_clock_invalid_response")
        } finally {
            connection.disconnect()
        }
    }
    private fun readBounded(stream: java.io.InputStream, maximumBytes: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximumBytes) throw IOException("Response exceeded safety limit")
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }


}
