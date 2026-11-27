package com.karam.youaremysoul

internal data class DrawingDelta(
    val clearBoard: Boolean,
    val removedStrokeId: String?,
    val addedStrokes: List<DrawingStroke>
)

/**
 * Emits a targeted remove for the normal undo action. Treating every deletion
 * as a board-wide clear would erase a concurrently added stroke from the other
 * member's device during replay.
 */
internal object DrawingDiff {
    fun between(previous: List<DrawingStroke>, next: List<DrawingStroke>): DrawingDelta {
        val previousIds = previous.mapTo(mutableSetOf()) { it.id }
        val nextIds = next.mapTo(mutableSetOf()) { it.id }
        val removed = previous.filter { it.id !in nextIds }
        val latestUndo = removed.size == 1 &&
            previous.isNotEmpty() &&
            next.size == previous.size - 1 &&
            previous.dropLast(1).map { it.id } == next.map { it.id } &&
            next.all { it.id in previousIds }

        if (latestUndo) {
            return DrawingDelta(
                clearBoard = false,
                removedStrokeId = previous.last().id,
                addedStrokes = emptyList()
            )
        }

        val cleared = removed.isNotEmpty()
        return DrawingDelta(
            clearBoard = cleared,
            removedStrokeId = null,
            addedStrokes = next.filter { cleared || it.id !in previousIds }
        )
    }
}
