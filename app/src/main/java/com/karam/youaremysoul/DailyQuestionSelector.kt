package com.karam.youaremysoul

/** Stable UTC epoch-day selection for the shared daily prompt. */
internal object DailyQuestionSelector {
    fun indexForEpochDay(epochDay: Long, questionCount: Int): Int {
        require(questionCount > 0) { "Question bank must not be empty" }
        return Math.floorMod(epochDay, questionCount.toLong()).toInt()
    }
}
