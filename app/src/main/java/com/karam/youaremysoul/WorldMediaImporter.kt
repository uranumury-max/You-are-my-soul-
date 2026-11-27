package com.karam.youaremysoul

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Reads a user-selected image with a hard byte limit, encrypts and uploads it,
 * then returns metadata only after the private object has been stored.
 */
class WorldMediaImporter(
    private val context: Context,
    private val storage: SupabaseWorldMediaStorage,
    private val keyVault: WorldKeyVault
) {
    suspend fun import(
        uri: Uri,
        session: AuthenticatedSupabaseUser,
        worldId: UUID
    ): WorldMediaItem {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri)?.lowercase()?.substringBefore(';')
            ?: throw IllegalArgumentException("media_type_unknown")
        require(mimeType in ALLOWED_MIME_TYPES) { "media_type_not_supported" }

        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.takeIf { it.isNotBlank() }
            ?.take(80)
            ?: "صورة"

        val plaintext = resolver.openInputStream(uri)?.use { input ->
            val output = WipingByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "media_too_large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } finally {
                buffer.fill(0)
            }
        } ?: throw IllegalArgumentException("media_unreadable")

        try {
            require(plaintext.isNotEmpty()) { "media_empty" }
            validateImageBytes(plaintext)
            val mediaId = UUID.randomUUID()
            val keyVersion = keyVault.activeKeyVersion(worldId)
            val path = storage.upload(session, worldId, mediaId, plaintext, keyVault, keyVersion)
            return WorldMediaItem(
                id = mediaId.toString(),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = plaintext.size.toLong(),
                createdAtEpochMs = System.currentTimeMillis(),
                objectPath = path,
                keyVersion = keyVersion
            )
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024\n        private const val MAX_DIMENSION = 12_000\n        private const val MAX_PIXEL_COUNT = 64_000_000L\n        private const val PREVIEW_DECODE_DIMENSION = 1_600
        val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

/** Keeps the temporary stream's internal plaintext buffer from lingering after copy/failure. */
private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
    fun copyAndWipe(): ByteArray = toByteArray().also { wipe() }

    fun wipe() {
        buf.fill(0)
        reset()
    }
}
