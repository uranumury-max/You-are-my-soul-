package com.karam.youaremysoul

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyQuestionSelectorTest {
    @Test
    fun selectionIsStableAndAdvancesWithTheUtcDay() {
        assertEquals(17, DailyQuestionSelector.indexForEpochDay(17L, 50))
        assertEquals(17, DailyQuestionSelector.indexForEpochDay(17L, 50))
        assertEquals(18, DailyQuestionSelector.indexForEpochDay(18L, 50))
    }

    @Test
    fun negativeEpochDaysStillProduceAValidIndex() {
        assertEquals(49, DailyQuestionSelector.indexForEpochDay(-1L, 50))
        assertEquals(0, DailyQuestionSelector.indexForEpochDay(0L, 50))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnEmptyQuestionBank() {
        DailyQuestionSelector.indexForEpochDay(0L, 0)
    }
}
