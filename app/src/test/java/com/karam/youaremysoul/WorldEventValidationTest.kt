package com.karam.youaremysoul

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldEventValidationTest {
    @Test
    fun acceptsValidMessagesAndRejectsOversizedOrIncompleteMessages() {
        assertTrue(WorldEventValidation.message("message-1", "مشارك", "مرحباً", 1L))
        assertFalse(WorldEventValidation.message("", "مشارك", "مرحباً", 1L))
        assertFalse(WorldEventValidation.message("id", "", "مرحباً", 1L))
        assertFalse(WorldEventValidation.message("id", "مشارك", "ن".repeat(1001), 1L))
        assertFalse(WorldEventValidation.message("id", "مشارك", "مرحباً", 0L))
    }

    @Test
    fun validatesCapsuleIdentityContentAndUnlockOrder() {
        assertTrue(WorldEventValidation.capsule("capsule-1", "رسالة", 100L, 200L))
        assertFalse(WorldEventValidation.capsule("capsule-1", "", 100L, 200L))
        assertFalse(WorldEventValidation.capsule("capsule-1", "رسالة", 200L, 200L))
        assertFalse(WorldEventValidation.capsule("capsule-1", "ر".repeat(801), 100L, 200L))
    }

    @Test
    fun rejectsMalformedDrawingCoordinatesAndWidths() {
        assertTrue(WorldEventValidation.drawing("stroke-1", 0.006f, listOf(WorldPoint(0.2f, 0.8f))))
        assertFalse(WorldEventValidation.drawing("stroke-1", 0.5f, listOf(WorldPoint(0.2f, 0.8f))))
        assertFalse(WorldEventValidation.drawing("stroke-1", 0.006f, listOf(WorldPoint(1.1f, 0.8f))))
        assertFalse(WorldEventValidation.drawing("stroke-1", 0.006f, emptyList()))
        assertFalse(
            WorldEventValidation.drawing(
                "stroke-1", 0.006f, List(151) { WorldPoint(0.5f, 0.5f) }
            )
        )
        assertFalse(
            WorldEventValidation.drawing("stroke-1", Float.NaN, listOf(WorldPoint(0.5f, 0.5f)))
        )
        assertFalse(
            WorldEventValidation.drawing("stroke-1", 0.006f, listOf(WorldPoint(Float.POSITIVE_INFINITY, 0.5f)))
        )
    }

    @Test
    fun acceptsOnlySupportedSharedCanvasPaletteValues() {
        assertTrue(WorldEventValidation.drawingColor(androidx.compose.ui.graphics.Color(0xFFFF9CCB).value.toLong()))
        assertTrue(WorldEventValidation.drawingColor(androidx.compose.ui.graphics.Color(0xFF8FE7FF).value.toLong()))
        assertFalse(WorldEventValidation.drawingColor(Long.MIN_VALUE))
        assertFalse(WorldEventValidation.drawingColor(123456789L))
    }

    @Test
    fun validatesDrawingVersionIdentityTitleAndBoundedStrokes() {
        val stroke = DrawingStroke(
            "stroke-1",
            androidx.compose.ui.graphics.Color(0xFFFF9CCB).value.toLong(),
            0.006f,
            listOf(WorldPoint(0.2f, 0.8f))
        )
        val version = DrawingVersion("version-1", "نسخة النجوم", 100L, listOf(stroke))
        assertTrue(WorldEventValidation.drawingVersion(version))
        assertFalse(WorldEventValidation.drawingVersion(version.copy(title = "")))
        assertFalse(WorldEventValidation.drawingVersion(version.copy(title = "ن".repeat(41))))
        assertFalse(WorldEventValidation.drawingVersion(version.copy(createdAtEpochMs = 0L)))
        assertFalse(WorldEventValidation.drawingVersion(version.copy(strokes = emptyList())))
        assertFalse(
            WorldEventValidation.drawingVersion(
                version.copy(strokes = List(DrawingHistory.MAX_STROKES + 1) { stroke.copy(id = "s-$it") })
            )
        )
        assertFalse(
            WorldEventValidation.drawingVersion(
                version.copy(strokes = listOf(stroke.copy(colorArgb = Long.MIN_VALUE)))
            )
        )
    }

    @Test
    fun validatesDailyAnswerDateAndTextBounds() {
        assertTrue(WorldEventValidation.dailyAnswer("2026-11-27", "إجابة اليوم"))
        assertFalse(WorldEventValidation.dailyAnswer("27-11-2026", "إجابة"))
        assertFalse(WorldEventValidation.dailyAnswer("2026-11-27", "ج".repeat(1001)))
    }

    @Test
    fun validatesDailyAnswersPerParticipant() {
        assertTrue(
            WorldEventValidation.dailyAnswerEntry(
                "2026-11-27", "123456789", "إجابة مستقلة", 100L
            )
        )
        assertFalse(
            WorldEventValidation.dailyAnswerEntry(
                "2026-11-27", "12345", "إجابة", 100L
            )
        )
        assertFalse(
            WorldEventValidation.dailyAnswerEntry(
                "2026-11-27", "123456789", "", 100L
            )
        )
        assertFalse(
            WorldEventValidation.dailyAnswerEntry(
                "not-a-date", "123456789", "إجابة", 100L
            )
        )
        assertFalse(
            WorldEventValidation.dailyAnswerEntry(
                "2026-11-27", "123456789", "إجابة", 0L
            )
        )
    }

    @Test
    fun validatesSharedMediaMetadataAndStoragePath() {
        val worldId = "2b71d49b-bef3-4e8a-9f43-79ad53d3e73d"
        val mediaId = "8cbf47a0-92c2-40ef-9b8a-99ccf9427f5b"
        val path = "$worldId/$mediaId.wmd"
        assertTrue(
            WorldEventValidation.media(
                worldId, mediaId, "ذكرى.png", "image/png", 2048L, 100L, path, 1
            )
        )
        assertFalse(
            WorldEventValidation.media(
                worldId, mediaId, "ذكرى.png", "image/png", 0L, 100L, path, 1
            )
        )
        assertFalse(
            WorldEventValidation.media(
                worldId, mediaId, "ملف", "application/pdf", 2048L, 100L, path, 1
            )
        )
        assertFalse(
            WorldEventValidation.media(
                worldId, mediaId, "ذكرى.png", "image/png", 2048L, 100L, "$worldId/other.wmd", 1
            )
        )
        assertFalse(
            WorldEventValidation.media(
                worldId, mediaId, "ذكرى.png", "image/png", 21L * 1024L * 1024L, 100L, path, 1
            )
        )
        // Key rotation is server-authoritative and is not capped at five generations.
        assertTrue(
            WorldEventValidation.media(
                worldId, mediaId, "ذكرى.png", "image/png", 2048L, 100L, path, 6
            )
        )
        assertFalse(
            WorldEventValidation.media(
                worldId, mediaId, "ذكرى.png", "image/png", 2048L, 100L, path, 0
            )
        )
    }

    @Test
    fun validatesSettingsAndGardenRanges() {
        assertTrue(WorldEventValidation.settings("عالمنا", "2026-11-27"))
        assertTrue(WorldEventValidation.settings(null, null))
        assertFalse(WorldEventValidation.settings("ا".repeat(41), null))
        assertFalse(WorldEventValidation.settings(null, "not-a-date"))
        assertTrue(WorldEventValidation.garden(1, 10L))
        assertFalse(WorldEventValidation.garden(10001, 10L))
        assertFalse(WorldEventValidation.garden(1, -1L))
    }
}
