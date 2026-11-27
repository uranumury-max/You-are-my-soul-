package com.karam.youaremysoul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailySurpriseDeckTest {
    @Test
    fun deckContainsFiftyDistinctNonEmptyDailyActivities() {
        assertEquals(50, DailySurpriseDeck.entries.size)
        assertEquals(50, DailySurpriseDeck.entries.map { it.first }.toSet().size)
        assertTrue(DailySurpriseDeck.entries.all {
            it.first.isNotBlank() && it.second.isNotBlank()
        })
    }
}
