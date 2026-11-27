package com.karam.youaremysoul

/** Reveal gate for the shared daily-answer moment. */
internal object DailyAnswerReveal {
    fun isReady(authorCodes: List<String>): Boolean =
        authorCodes
            .filter { it.matches(Regex("^[0-9]{9}$")) }
            .distinct()
            .size >= 2
}
