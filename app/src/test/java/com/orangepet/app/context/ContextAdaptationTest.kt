package com.orangepet.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ContextAdaptationTest {

    @Test
    fun buildContext_bucketsHoursCorrectly() {
        assertEquals(TimeBucket.MORNING, ContextProvider.buildContext(LocalDateTime.of(2026, 7, 13, 6, 0), 80, false).timeBucket)
        assertEquals(TimeBucket.AFTERNOON, ContextProvider.buildContext(LocalDateTime.of(2026, 7, 13, 13, 0), 80, false).timeBucket)
        assertEquals(TimeBucket.EVENING, ContextProvider.buildContext(LocalDateTime.of(2026, 7, 13, 19, 0), 80, false).timeBucket)
        assertEquals(TimeBucket.NIGHT, ContextProvider.buildContext(LocalDateTime.of(2026, 7, 13, 23, 0), 80, false).timeBucket)
        assertEquals(TimeBucket.NIGHT, ContextProvider.buildContext(LocalDateTime.of(2026, 7, 13, 2, 0), 80, false).timeBucket)
    }

    @Test
    fun buildContext_weekendDetection() {
        // 2026-07-13 is a Monday.
        assertEquals(false, ContextProvider.buildContext(LocalDateTime.of(2026, 7, 13, 10, 0), 80, false).isWeekend)
        // 2026-07-18 is a Saturday.
        assertTrue(ContextProvider.buildContext(LocalDateTime.of(2026, 7, 18, 10, 0), 80, false).isWeekend)
    }

    @Test
    fun buildContext_carriesBatteryThrough() {
        val ctx = ContextProvider.buildContext(LocalDateTime.of(2026, 7, 13, 10, 0), 42, true)
        assertEquals(42, ctx.batteryPercent)
        assertTrue(ctx.isCharging)
    }

    @Test
    fun adaptationMath_noSignals_isNeutral() {
        assertEquals(1.0f, AdaptationMath.multiplierFor(shown = 0, positive = 0, dismissed = 0), 0.001f)
    }

    @Test
    fun adaptationMath_allPositive_clampsAtUpperBound() {
        assertTrue(AdaptationMath.multiplierFor(shown = 20, positive = 20, dismissed = 0) <= 1.5f)
    }

    @Test
    fun adaptationMath_allNegative_clampsAtLowerBound() {
        assertTrue(AdaptationMath.multiplierFor(shown = 20, positive = 0, dismissed = 20) >= 0.5f)
    }

    @Test
    fun adaptationMath_neverExceedsBoundsForAnyInput() {
        for (shown in 0..30) {
            for (positive in 0..30) {
                for (dismissed in 0..30) {
                    val m = AdaptationMath.multiplierFor(shown, positive, dismissed)
                    assertTrue("shown=$shown positive=$positive dismissed=$dismissed -> $m", m in 0.5f..1.5f)
                }
            }
        }
    }
}
