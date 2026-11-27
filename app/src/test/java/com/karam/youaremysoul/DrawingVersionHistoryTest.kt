package com.karam.youaremysoul

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawingVersionHistoryTest {
    private fun version(index: Int) = DrawingVersion(
        id = "version-$index",
        title = "نسخة $index",
        createdAtEpochMs = index.toLong() + 1,
        strokes = listOf(
            DrawingStroke(
                id = "stroke-$index",
                colorArgb = androidx.compose.ui.graphics.Color(0xFFFF9CCB).value.toLong(),
                widthFraction = 0.006f,
                points = listOf(WorldPoint(0.25f, 0.75f))
            )
        )
    )

    @Test
    fun appendRetainsOnlyTheNewestEightVersions() {
        val history = (1..9).fold(emptyList<DrawingVersion>()) { current, index ->
            DrawingVersionHistory.append(current, version(index))
        }
        assertEquals(WorldLimits.MAX_DRAWING_VERSIONS, history.size)
        assertEquals("version-2", history.first().id)
        assertEquals("version-9", history.last().id)
    }

    @Test
    fun restoreReturnsTheSavedStrokeSetWithoutMutatingTheVersion() {
        val saved = version(3)
        val restored = DrawingVersionHistory.restore(saved)
        assertEquals(saved.strokes, restored)
        assertEquals(saved, version(3))
    }
}
