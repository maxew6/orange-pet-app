package com.orangepet.app.context

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Builds a [PetContext] from plain inputs. Kept pure (no `android.*`) so
 * the bucketing logic — the part most likely to have an off-by-one at a
 * boundary hour — is unit-testable on a plain JVM. The caller (Android
 * side) is responsible for supplying the actual current time and battery
 * reading.
 */
object ContextProvider {

    fun buildContext(now: LocalDateTime, batteryPercent: Int, isCharging: Boolean): PetContext {
        val hour = now.hour
        val bucket = when (hour) {
            in 5..11 -> TimeBucket.MORNING
            in 12..16 -> TimeBucket.AFTERNOON
            in 17..21 -> TimeBucket.EVENING
            else -> TimeBucket.NIGHT // 22:00-4:59
        }
        val isWeekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
        return PetContext(
            timeBucket = bucket,
            isWeekend = isWeekend,
            batteryPercent = batteryPercent,
            isCharging = isCharging
        )
    }
}
