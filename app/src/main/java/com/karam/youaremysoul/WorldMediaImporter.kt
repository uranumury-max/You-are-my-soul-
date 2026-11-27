package com.karam.youaremysoul

import android.content.Context
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
            val output = ByteArrayOutputStream()
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
            val mediaId = UUID.randomUUID()
            val path = storage.upload(session, worldId, mediaId, plaintext, keyVault)
            return WorldMediaItem(
                id = mediaId.toString(),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = plaintext.size.toLong(),
                createdAtEpochMs = System.currentTimeMillis(),
                objectPath = path,
                keyVersion = 1
            )
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heic")
    }
}
