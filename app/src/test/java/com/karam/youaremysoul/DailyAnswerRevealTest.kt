package com.karam.youaremysoul

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyAnswerRevealTest {
    @Test
    fun requiresTwoDifferentValidParticipants() {
        assertFalse(DailyAnswerReveal.isReady(emptyList()))
        assertFalse(DailyAnswerReveal.isReady(listOf("123456789", "123456789")))
        assertFalse(DailyAnswerReveal.isReady(listOf("123456789", "invalid")))
        assertTrue(DailyAnswerReveal.isReady(listOf("123456789", "987654321")))
    }
}
