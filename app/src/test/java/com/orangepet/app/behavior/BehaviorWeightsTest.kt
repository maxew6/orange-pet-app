package com.orangepet.app.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorWeightsTest {

    @Test
    fun totalWeight_isOneHundred() {
        assertEquals(100, BehaviorWeights.totalWeight())
    }

    @Test
    fun pick_rollZero_isFirstEntry() {
        assertEquals(PetBehavior.IDLE, BehaviorWeights.pick(0))
    }

    @Test
    fun pick_lastRoll_isLastEntry() {
        val total = BehaviorWeights.totalWeight()
        assertEquals(PetBehavior.SKIPPING, BehaviorWeights.pick(total - 1))
    }

    @Test
    fun pick_everyRollInRange_resolvesToASchedulableBehavior() {
        val total = BehaviorWeights.totalWeight()
        for (roll in 0 until total) {
            assertTrue("roll=$roll", BehaviorWeights.pick(roll).isSchedulable())
        }
    }

    @Test
    fun pickNext_rerollsWhenFirstPickRepeatsASpecialBehavior() {
        // Whatever specific behavior roll=70 maps to under the current
        // weight table, this test only relies on it being a "special" one
        // (anything but IDLE/WALKING) — which every entry from BLINKING
        // onward is.
        val first = BehaviorWeights.pick(70)
        val result = BehaviorWeights.pickNext(roll = 70, rerollValue = 0, avoidSpecial = first)
        assertEquals(PetBehavior.IDLE, result)
    }

    @Test
    fun pickNext_doesNotRerollWhenNotAvoided() {
        val result = BehaviorWeights.pickNext(roll = 0, rerollValue = 99, avoidSpecial = PetBehavior.SLEEPING)
        assertEquals(PetBehavior.IDLE, result)
    }

    @Test
    fun pickNext_neverRerollsIdleOrWalkingRepeats() {
        // IDLE/WALKING are not "special", so repeating them is fine and no reroll happens.
        val result = BehaviorWeights.pickNext(roll = 0, rerollValue = 99, avoidSpecial = PetBehavior.IDLE)
        assertEquals(PetBehavior.IDLE, result)
    }

    @Test
    fun applyMultipliers_clampsAboveRangeTo1_5x() {
        val result = BehaviorWeights.applyMultipliers(
            listOf(PetBehavior.HOPPING to 10),
            mapOf(PetBehavior.HOPPING to 5.0f)
        )
        assertEquals(15, result.first().second)
    }

    @Test
    fun applyMultipliers_clampsBelowRangeTo0_5x() {
        val result = BehaviorWeights.applyMultipliers(
            listOf(PetBehavior.HOPPING to 10),
            mapOf(PetBehavior.HOPPING to -5.0f)
        )
        assertEquals(5, result.first().second)
    }

    @Test
    fun applyMultipliers_neverFloorsBelowOne() {
        val result = BehaviorWeights.applyMultipliers(
            listOf(PetBehavior.SLEEPING to 1),
            mapOf(PetBehavior.SLEEPING to 0.5f)
        )
        assertEquals(1, result.first().second)
    }

    @Test
    fun isSpecial_idleAndWalkingAreNotSpecial() {
        assertEquals(false, PetBehavior.IDLE.isSpecial())
        assertEquals(false, PetBehavior.WALKING.isSpecial())
    }

    @Test
    fun isSchedulable_scheduledOnlyEventsAreNotRandomlyPicked() {
        assertEquals(false, PetBehavior.EATING.isSchedulable())
        assertEquals(false, PetBehavior.GREETING.isSchedulable())
        assertEquals(false, PetBehavior.NIGHT_SLEEPING.isSchedulable())
        assertEquals(false, PetBehavior.CHARGING.isSchedulable())
        assertEquals(false, PetBehavior.FAINTED.isSchedulable())
        assertEquals(false, PetBehavior.NOTIFICATION_REACTION.isSchedulable())
    }
}
