package com.karam.youaremysoul

/** Bounded immutable version list operations for the shared canvas. */
internal object DrawingVersionHistory {
    fun append(existing: List<DrawingVersion>, version: DrawingVersion): List<DrawingVersion> =
        (existing + version).takeLast(WorldLimits.MAX_DRAWING_VERSIONS)

    fun restore(version: DrawingVersion): List<DrawingStroke> = version.strokes.toList()
}
