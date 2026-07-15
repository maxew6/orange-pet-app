package com.orangepet.app.behavior

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Pure `java.time` math for the morning/lunch/night schedule. `java.time`
 * is used directly (no desugaring library needed) because minSdk is 26,
 * where `java.time` already ships natively.
 *
 * Kept free of `android.*` so it can be compiled and unit-tested on a
 * plain JVM.
 */
object ScheduleMath {

    /**
     * The next future occurrence of [targetTime] at or after [now]. If
     * [targetTime] hasn't happened yet today, returns today's occurrence;
     * otherwise returns tomorrow's.
     */
    fun nextOccurrence(now: LocalDateTime, targetTime: LocalTime): LocalDateTime {
        val todayAtTarget = now.toLocalDate().atTime(targetTime)
        return if (!todayAtTarget.isAfter(now)) todayAtTarget.plusDays(1) else todayAtTarget
    }

    /** Non-negative delay in milliseconds from [now] until [target]. */
    fun delayMillisUntil(now: LocalDateTime, target: LocalDateTime): Long =
        Duration.between(now, target).toMillis().coerceAtLeast(0L)

    /**
     * True if [now] falls within the overnight window from [nightStart]
     * (e.g. 22:00) through [morningEnd] (e.g. 07:00) the *next* day.
     * Handles the midnight wraparound explicitly: the window is either
     * "now >= nightStart" (still today, before midnight) or
     * "now < morningEnd" (already past midnight, before wake time).
     *
     * If [nightStart] and [morningEnd] are equal, there is no window
     * (returns false) rather than treating it as "always night".
     */
    fun isWithinNightWindow(now: LocalTime, nightStart: LocalTime, morningEnd: LocalTime): Boolean {
        if (nightStart == morningEnd) return false
        return if (nightStart.isAfter(morningEnd)) {
            // Wraps midnight, e.g. 22:00 -> 07:00.
            !now.isBefore(nightStart) || now.isBefore(morningEnd)
        } else {
            // Doesn't wrap (unusual config, e.g. nightStart before morningEnd same day).
            !now.isBefore(nightStart) && now.isBefore(morningEnd)
        }
    }

    /** True if a once-per-day action recorded on [lastFiredDate] still needs to fire on [today]. */
    fun shouldFireForDate(lastFiredDate: LocalDate?, today: LocalDate): Boolean =
        lastFiredDate == null || lastFiredDate.isBefore(today)
}
