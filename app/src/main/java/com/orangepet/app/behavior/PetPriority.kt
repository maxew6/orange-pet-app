package com.orangepet.app.behavior

/**
 * Which family of behavior should currently be driving the pet. Ordered
 * highest-priority first; [resolveTrack] always returns the
 * highest-priority track whose condition holds.
 *
 * Service destruction isn't a track here — it's handled directly in
 * `FloatingPetService.onDestroy()`, which cancels everything before this
 * resolver would ever run again.
 */
enum class PetPriorityTrack {
    FAINTED,
    NIGHT_SLEEP,
    CHARGING,
    EVENT,
    RANDOM
}

object PetPriority {

    /**
     * Battery ≤ 30% and not charging: absolute priority over everything
     * except charging itself (preserved unchanged from v2).
     */
    fun isFainted(isLowBattery: Boolean, isCharging: Boolean): Boolean =
        isLowBattery && !isCharging

    /**
     * Resolves the active track. Design notes (see README "v3 scope
     * notes" for the full rationale):
     *  - FAINTED beats everything, including night window — a dead-battery
     *    pet doesn't get a bedtime animation, it just faints, per spec.
     *  - NIGHT_SLEEP beats CHARGING: a phone charging overnight should
     *    still show the pet sleeping, not bouncing happily at 2am.
     *  - CHARGING beats pending EVENTs and RANDOM: while plugged in (and
     *    not fainted/night) the pet stays in its happy bounce rather than
     *    wandering into a scheduled or random behavior.
     *  - A pending [PetEvent] (greeting/lunch/goodnight/notification) beats
     *    RANDOM, so scheduled and reactive moments are never silently
     *    skipped by the random scheduler.
     */
    fun resolveTrack(
        isLowBattery: Boolean,
        isCharging: Boolean,
        isNightWindow: Boolean,
        hasPendingEvent: Boolean
    ): PetPriorityTrack = when {
        isFainted(isLowBattery, isCharging) -> PetPriorityTrack.FAINTED
        isNightWindow -> PetPriorityTrack.NIGHT_SLEEP
        isCharging -> PetPriorityTrack.CHARGING
        hasPendingEvent -> PetPriorityTrack.EVENT
        else -> PetPriorityTrack.RANDOM
    }
}
