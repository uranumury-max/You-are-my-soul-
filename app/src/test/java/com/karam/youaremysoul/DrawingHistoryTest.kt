package com.karam.youaremysoul

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawingHistoryTest {
    private fun stroke(id: Int) = DrawingStroke(
        id = id.toString(), colorArgb = 0xFFFF9CCB, widthFraction = 0.006f,
        points = listOf(WorldPoint(0.1f, 0.2f))
    )

    @Test
    fun appendKeepsNewestFortyStrokesInOrder() {
        val history = (1..DrawingHistory.MAX_STROKES).map(::stroke)
        val result = DrawingHistory.append(history, stroke(41))
        assertEquals(40, result.size)
        assertEquals("2", result.first().id)
        assertEquals("41", result.last().id)
    }

    @Test
    fun undoRemovesExactlyOneLatestStrokeAndEmptyHistoryIsSafe() {
        val history = listOf(stroke(1), stroke(2))
        assertEquals(listOf(stroke(1)), DrawingHistory.undo(history))
        assertEquals(emptyList<DrawingStroke>(), DrawingHistory.undo(emptyList()))
    }

    @Test
    fun clearReturnsEmptyHistory() {
        assertEquals(emptyList<DrawingStroke>(), DrawingHistory.clear())
    }
}
