package com.karam.youaremysoul

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AnimationUnlockProgressTest {
    @Test fun firstRelationshipDayUnlocksOnlyFirstScene() {
        val day = LocalDate.parse("2026-11-27")
        assertEquals(1, calculateUnlockedAnimationCount(day, day))
    }

    @Test fun unlocksOneAdditionalSceneEachCalendarDay() {
        val start = LocalDate.parse("2026-11-27")
        assertEquals(2, calculateUnlockedAnimationCount(start, start.plusDays(1)))
        assertEquals(7, calculateUnlockedAnimationCount(start, start.plusDays(6)))
    }

    @Test fun unlockCountNeverExceedsTwentyOne() {
        val start = LocalDate.parse("2026-11-01")
        assertEquals(21, calculateUnlockedAnimationCount(start, start.plusDays(50)))
    }

    @Test fun futureStartDateKeepsFirstSceneAvailable() {
        val today = LocalDate.parse("2026-11-27")
        assertEquals(1, calculateUnlockedAnimationCount(today.plusDays(3), today))
    }
}
