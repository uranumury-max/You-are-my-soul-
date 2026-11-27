package com.karam.youaremysoul

/** Pure bounded history operations for the shared canvas. */
internal object DrawingHistory {
    const val MAX_STROKES = 40

    fun append(existing: List<DrawingStroke>, stroke: DrawingStroke): List<DrawingStroke> =
        (existing + stroke).takeLast(MAX_STROKES)

    fun undo(existing: List<DrawingStroke>): List<DrawingStroke> =
        if (existing.isEmpty()) existing else existing.dropLast(1)

    fun clear(): List<DrawingStroke> = emptyList()
}
