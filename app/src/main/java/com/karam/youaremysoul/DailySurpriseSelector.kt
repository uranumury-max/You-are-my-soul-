package com.karam.youaremysoul

/** Stable UTC epoch-day selection for the shared daily surprise. */
internal object DailySurpriseSelector {
    fun indexForEpochDay(epochDay: Long, surpriseCount: Int): Int {
        require(surpriseCount > 0) { "Surprise list must not be empty" }
        return Math.floorMod(epochDay, surpriseCount.toLong()).toInt()
    }
}
