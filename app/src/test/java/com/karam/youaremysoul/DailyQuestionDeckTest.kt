package com.karam.youaremysoul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyQuestionDeckTest {
    @Test
    fun bankContainsFiftyDistinctNonEmptyQuestions() {
        assertEquals(50, DailyQuestionDeck.entries.size)
        assertEquals(50, DailyQuestionDeck.entries.toSet().size)
        assertTrue(DailyQuestionDeck.entries.all { it.isNotBlank() })
    }
}
