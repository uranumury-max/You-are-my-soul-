package com.karam.youaremysoul

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Day 1 opens scene 1; unlocks are cumulative and capped at the gallery's 21 scenes. */
internal fun calculateUnlockedAnimationCount(startDate: LocalDate, currentDate: LocalDate): Int =
    (ChronoUnit.DAYS.between(startDate, currentDate) + 1L).coerceIn(1L, 21L).toInt()
