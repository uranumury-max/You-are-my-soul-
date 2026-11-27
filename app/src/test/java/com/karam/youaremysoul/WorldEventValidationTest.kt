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
    }

    @Test
    fun validatesDailyAnswerDateAndTextBounds() {
        assertTrue(WorldEventValidation.dailyAnswer("2026-11-27", "إجابة اليوم"))
        assertFalse(WorldEventValidation.dailyAnswer("27-11-2026", "إجابة"))
        assertFalse(WorldEventValidation.dailyAnswer("2026-11-27", "ج".repeat(1001)))
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
