package com.karam.youaremysoul

import java.time.LocalDate

/**
 * Bounds and shape checks for decrypted events. Encryption authenticates the sender's
 * world key, but every event is still treated as untrusted input at the reducer boundary.
 */
internal object WorldEventValidation {
    fun memory(value: String): Boolean =
        value.isNotBlank() && value.length <= 400

    fun message(id: String, senderName: String, text: String, createdAtEpochMs: Long): Boolean =
        id.length in 1..64 &&
            senderName.isNotBlank() && senderName.length <= 40 &&
            text.isNotBlank() && text.length <= 1000 &&
            createdAtEpochMs > 0L

    fun capsule(
        id: String,
        message: String,
        createdAtEpochMs: Long,
        unlockAtEpochMs: Long
    ): Boolean =
        id.length in 1..64 &&
            message.isNotBlank() && message.length <= 800 &&
            createdAtEpochMs > 0L &&
            unlockAtEpochMs > createdAtEpochMs

    fun drawing(id: String, widthFraction: Float, points: List<WorldPoint>): Boolean =
        id.length in 1..64 &&
            widthFraction.isFinite() && widthFraction in 0.001f..0.03f &&
            points.size in 1..150 &&
            points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }

    fun dailyAnswer(date: String, answer: String): Boolean =
        answer.length <= 1000 &&
            runCatching { LocalDate.parse(date) }.isSuccess

    fun settings(worldName: String?, relationshipStartDate: String?): Boolean {
        if (worldName != null && (worldName.isBlank() || worldName.length > 40)) return false
        if (relationshipStartDate != null && relationshipStartDate.isNotBlank()) {
            if (runCatching { LocalDate.parse(relationshipStartDate) }.isFailure) return false
        }
        return true
    }

    fun media(
        worldId: String,
        id: String,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        createdAtEpochMs: Long,
        objectPath: String,
        keyVersion: Int
    ): Boolean {
        val canonicalWorld = runCatching { java.util.UUID.fromString(worldId).toString() }.getOrNull()
            ?: return false
        val canonicalMedia = runCatching { java.util.UUID.fromString(id).toString() }.getOrNull()
            ?: return false
        val allowedMime = mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/heic")
        return displayName.isNotBlank() && displayName.length <= 80 &&
            allowedMime &&
            sizeBytes in 1L..(20L * 1024L * 1024L) &&
            createdAtEpochMs > 0L &&
            objectPath == "$canonicalWorld/$canonicalMedia.wmd" &&
            keyVersion in 1..5
    }

    fun garden(delta: Int, lastWateredAt: Long): Boolean =
        delta in -10_000..10_000 && lastWateredAt >= 0L
}
