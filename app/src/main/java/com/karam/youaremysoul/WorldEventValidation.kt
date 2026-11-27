package com.karam.youaremysoul

import java.time.LocalDate
import androidx.compose.ui.graphics.Color

/**
 * Bounds and shape checks for decrypted events. Encryption authenticates the sender's
 * world key, but every event is still treated as untrusted input at the reducer boundary.
 */
internal object WorldEventValidation {
    private val allowedDrawingColors = setOf(
        Color(0xFFFF9CCB).value.toLong(),
        Color(0xFFFFD68A).value.toLong(),
        Color(0xFF8FE7FF).value.toLong(),
        Color(0xFFC7A6FF).value.toLong(),
        Color(0xFFFFFFFF).value.toLong()
    )

    fun drawingColor(colorArgb: Long): Boolean = colorArgb in allowedDrawingColors

    fun memory(value: String): Boolean =
        value.isNotBlank() && value.length <= 400

    fun message(id: String, senderName: String, text: String, createdAtEpochMs: Long): Boolean =
        id.length in 1..64 && id.none(Char::isISOControl) &&
            senderName.isNotBlank() && senderName.length <= 40 &&
            senderName.none(Char::isISOControl) &&
            text.isNotBlank() && text.length <= 1000 &&
            text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } &&
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

    fun drawingVersion(version: DrawingVersion): Boolean =
        version.id.length in 1..64 &&
            version.title.isNotBlank() && version.title.length <= 40 &&
            version.createdAtEpochMs > 0L &&
            version.strokes.size in 1..DrawingHistory.MAX_STROKES &&
            version.strokes.all { drawingColor(it.colorArgb) && drawing(it.id, it.widthFraction, it.points) }

    fun dailyAnswer(date: String, answer: String): Boolean =
        answer.length <= 1000 &&
            runCatching { LocalDate.parse(date) }.isSuccess

    fun dailyAnswerEntry(
        date: String,
        authorCode: String,
        answer: String,
        createdAtEpochMs: Long
    ): Boolean =
        dailyAnswer(date, answer) &&
            authorCode.matches(Regex("^[0-9]{9}$")) &&
            answer.isNotBlank() &&
            createdAtEpochMs > 0L

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
        // UUID parsing accepts alternate casing; require canonical IDs to keep
        // duplicate checks and storage paths consistent across clients.
        if (worldId != canonicalWorld || id != canonicalMedia) return false
        val allowedMime = mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/heic")
        return displayName.isNotBlank() && displayName.length <= 80 &&
            allowedMime &&
            sizeBytes in 1L..(20L * 1024L * 1024L) &&
            createdAtEpochMs > 0L &&
            objectPath == "$canonicalWorld/$canonicalMedia.wmd" &&
            keyVersion > 0
    }

    fun garden(delta: Int, lastWateredAt: Long): Boolean =
        delta in -10_000..10_000 && lastWateredAt >= 0L
}
