package com.karam.youaremysoul

import org.junit.Assert.assertEquals
import org.junit.Test

class DailySurpriseSelectorTest {
    @Test
    fun surpriseIsStableForTheSameUtcDayAndAdvances() {
        assertEquals(3, DailySurpriseSelector.indexForEpochDay(3L, 7))
        assertEquals(3, DailySurpriseSelector.indexForEpochDay(3L, 7))
        assertEquals(4, DailySurpriseSelector.indexForEpochDay(4L, 7))
    }

    @Test
    fun negativeEpochDaysRemainWithinTheSurpriseList() {
        assertEquals(6, DailySurpriseSelector.indexForEpochDay(-1L, 7))
        assertEquals(0, DailySurpriseSelector.indexForEpochDay(0L, 7))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnEmptySurpriseBank() {
        DailySurpriseSelector.indexForEpochDay(0L, 0)
    }
}
