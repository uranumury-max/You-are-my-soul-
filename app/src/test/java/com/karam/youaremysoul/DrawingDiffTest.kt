package com.karam.youaremysoul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingDiffTest {
    private fun stroke(id: String) = DrawingStroke(
        id = id, colorArgb = 1L, widthFraction = 0.006f,
        points = listOf(WorldPoint(0.1f, 0.2f))
    )

    @Test
    fun undoTargetsOnlyTheLatestStrokeWithoutClearingOtherDeviceDrawings() {
        val old = listOf(stroke("one"), stroke("two"), stroke("remote"))
        val delta = DrawingDiff.between(old, old.dropLast(1))
        assertFalse(delta.clearBoard)
        assertEquals("remote", delta.removedStrokeId)
        assertTrue(delta.addedStrokes.isEmpty())
    }

    @Test
    fun explicitClearIsBoardWide() {
        val delta = DrawingDiff.between(listOf(stroke("one"), stroke("two")), emptyList())
        assertTrue(delta.clearBoard)
        assertEquals(null, delta.removedStrokeId)
        assertTrue(delta.addedStrokes.isEmpty())
    }

    @Test
    fun nonUndoRemovalRebuildsBoardDeterministically() {
        val old = listOf(stroke("one"), stroke("middle"), stroke("last"))
        val next = listOf(stroke("one"), stroke("last"))
        val delta = DrawingDiff.between(old, next)
        assertTrue(delta.clearBoard)
        assertEquals(listOf("one", "last"), delta.addedStrokes.map { it.id })
    }

    @Test
    fun addingAStrokeDoesNotClearExistingBoard() {
        val old = listOf(stroke("one"))
        val delta = DrawingDiff.between(old, old + stroke("two"))
        assertFalse(delta.clearBoard)
        assertEquals(listOf("two"), delta.addedStrokes.map { it.id })
    }
}
