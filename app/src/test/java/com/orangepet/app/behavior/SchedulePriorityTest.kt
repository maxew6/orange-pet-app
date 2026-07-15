package com.orangepet.app.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * These cases were first validated by actually *running* them (not just
 * compiling them) against a real Kotlin 2.0.21 runtime in the sandbox this
 * project was built in — see the README's "v3 environment verification"
 * section. This file is the idiomatic JUnit form of that same logic, for
 * `./gradlew test` once the project is in a normal Android build
 * environment.
 */
class SchedulePriorityTest {

    private val night = LocalTime.of(22, 0)
    private val morning = LocalTime.of(7, 0)

    // -- ScheduleMath.isWithinNightWindow: the midnight-wraparound case --

    @Test
    fun nightWindow_justAfterNightStart_isNight() {
        assertTrue(ScheduleMath.isWithinNightWindow(LocalTime.of(23, 0), night, morning))
    }

    @Test
    fun nightWindow_exactlyAtNightStart_isNight() {
        assertTrue(ScheduleMath.isWithinNightWindow(LocalTime.of(22, 0), night, morning))
    }

    @Test
    fun nightWindow_pastMidnightBeforeWake_isNight() {
        assertTrue(ScheduleMath.isWithinNightWindow(LocalTime.of(3, 0), night, morning))
        assertTrue(ScheduleMath.isWithinNightWindow(LocalTime.of(6, 59), night, morning))
    }

    @Test
    fun nightWindow_exactlyAtWakeTime_isNotNight() {
        assertEquals(false, ScheduleMath.isWithinNightWindow(LocalTime.of(7, 0), night, morning))
    }

    @Test
    fun nightWindow_midday_isNotNight() {
        assertEquals(false, ScheduleMath.isWithinNightWindow(LocalTime.of(12, 0), night, morning))
    }

    @Test
    fun nightWindow_justBeforeNightStart_isNotNight() {
        assertEquals(false, ScheduleMath.isWithinNightWindow(LocalTime.of(21, 59), night, morning))
    }

    @Test
    fun nightWindow_equalStartAndEnd_isNeverNight() {
        val t = LocalTime.of(9, 0)
        assertEquals(false, ScheduleMath.isWithinNightWindow(LocalTime.of(10, 0), t, t))
    }

    // -- ScheduleMath.nextOccurrence / shouldFireForDate --

    @Test
    fun nextOccurrence_targetLaterToday_returnsToday() {
        val now = LocalDateTime.of(2026, 7, 13, 6, 0)
        assertEquals(LocalDateTime.of(2026, 7, 13, 7, 0), ScheduleMath.nextOccurrence(now, LocalTime.of(7, 0)))
    }

    @Test
    fun nextOccurrence_targetAlreadyPassedToday_returnsTomorrow() {
        val now = LocalDateTime.of(2026, 7, 13, 8, 0)
        assertEquals(LocalDateTime.of(2026, 7, 14, 7, 0), ScheduleMath.nextOccurrence(now, LocalTime.of(7, 0)))
    }

    @Test
    fun shouldFireForDate_neverFired_fires() {
        assertTrue(ScheduleMath.shouldFireForDate(null, LocalDate.of(2026, 7, 13)))
    }

    @Test
    fun shouldFireForDate_firedToday_doesNotFireAgain() {
        val today = LocalDate.of(2026, 7, 13)
        assertEquals(false, ScheduleMath.shouldFireForDate(today, today))
    }

    @Test
    fun shouldFireForDate_firedYesterday_firesAgain() {
        val today = LocalDate.of(2026, 7, 13)
        assertTrue(ScheduleMath.shouldFireForDate(today.minusDays(1), today))
    }

    // -- PetPriority: the cross-cutting rules from the spec --

    @Test
    fun priority_lowBatteryNotCharging_isFaintedEvenAtNight() {
        assertEquals(
            PetPriorityTrack.FAINTED,
            PetPriority.resolveTrack(isLowBattery = true, isCharging = false, isNightWindow = true, hasPendingEvent = true)
        )
    }

    @Test
    fun priority_lowBatteryButCharging_atNight_isNightSleepNotFainted() {
        // Charging beats fainting, but night beats charging: a charging
        // phone at 30% battery overnight shows the sleeping pet, not the
        // faint sprite and not the happy bounce.
        assertEquals(
            PetPriorityTrack.NIGHT_SLEEP,
            PetPriority.resolveTrack(isLowBattery = true, isCharging = true, isNightWindow = true, hasPendingEvent = false)
        )
    }

    @Test
    fun priority_lowBatteryButCharging_daytime_isCharging() {
        assertEquals(
            PetPriorityTrack.CHARGING,
            PetPriority.resolveTrack(isLowBattery = true, isCharging = true, isNightWindow = false, hasPendingEvent = false)
        )
    }

    @Test
    fun priority_charging_beatsAPendingEvent() {
        assertEquals(
            PetPriorityTrack.CHARGING,
            PetPriority.resolveTrack(isLowBattery = false, isCharging = true, isNightWindow = false, hasPendingEvent = true)
        )
    }

    @Test
    fun priority_pendingEvent_beatsRandom() {
        assertEquals(
            PetPriorityTrack.EVENT,
            PetPriority.resolveTrack(isLowBattery = false, isCharging = false, isNightWindow = false, hasPendingEvent = true)
        )
    }

    @Test
    fun priority_nothingSpecial_isRandom() {
        assertEquals(
            PetPriorityTrack.RANDOM,
            PetPriority.resolveTrack(isLowBattery = false, isCharging = false, isNightWindow = false, hasPendingEvent = false)
        )
    }

    @Test
    fun isFainted_trueOnlyWhenLowAndNotCharging() {
        assertTrue(PetPriority.isFainted(isLowBattery = true, isCharging = false))
        assertEquals(false, PetPriority.isFainted(isLowBattery = true, isCharging = true))
        assertEquals(false, PetPriority.isFainted(isLowBattery = false, isCharging = false))
    }
}
