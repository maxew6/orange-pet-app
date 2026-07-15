package com.orangepet.app.behavior

/**
 * Weighted selection for the "RANDOM" priority track. Deterministic given
 * an explicit roll value, so it's fully unit-testable without relying on
 * actual randomness (the caller supplies `Random.nextInt(totalWeight)` in
 * production, fixed values in tests).
 */
object BehaviorWeights {

    /** Sums to 100. WALKING kept dominant so the pet reads as clearly
     * active; IDLE trimmed down (and its own breathing animation made a
     * touch more visible — see PetBehaviorController.runIdle) so a
     * quiet moment doesn't read as "frozen". Two v3 behaviors (ball,
     * skipping) added at modest weight. */
    val BASE_WEIGHTS: List<Pair<PetBehavior, Int>> = listOf(
        PetBehavior.IDLE to 18,
        PetBehavior.WALKING to 32,
        PetBehavior.BLINKING to 12,
        PetBehavior.LOOKING_AROUND to 9,
        PetBehavior.HOPPING to 8,
        PetBehavior.SHOWING_HEART to 5,
        PetBehavior.SLEEPING to 3,
        PetBehavior.PLAYING_BALL to 8,
        PetBehavior.SKIPPING to 5
    )

    fun totalWeight(weights: List<Pair<PetBehavior, Int>> = BASE_WEIGHTS): Int =
        weights.sumOf { it.second }

    /** Selects the behavior whose cumulative weight range contains [roll]. */
    fun pick(roll: Int, weights: List<Pair<PetBehavior, Int>> = BASE_WEIGHTS): PetBehavior {
        var remaining = roll
        for ((behavior, weight) in weights) {
            if (remaining < weight) return behavior
            remaining -= weight
        }
        return weights.first().first
    }

    /**
     * Picks using [roll], and if the result is a repeat of [avoidSpecial]
     * (and is itself a "special" behavior — see [isSpecial]), re-rolls once
     * using [rerollValue] instead. Two roll values are taken explicitly
     * (rather than calling `Random` internally) so every branch is
     * reachable from a plain unit test.
     */
    fun pickNext(
        roll: Int,
        rerollValue: Int,
        avoidSpecial: PetBehavior?,
        weights: List<Pair<PetBehavior, Int>> = BASE_WEIGHTS
    ): PetBehavior {
        val first = pick(roll, weights)
        val shouldReroll = first.isSpecial() && first == avoidSpecial
        return if (shouldReroll) pick(rerollValue, weights) else first
    }

    /**
     * Applies clamped contextual multipliers (see `AdaptationMath`) to the
     * base weights. Each multiplier is re-clamped to [0.5, 1.5] here too,
     * defense-in-depth against a bad value slipping in from storage, and
     * every resulting weight is floored at 1 so a behavior is never fully
     * eliminated by learned preferences — only ever nudged.
     */
    fun applyMultipliers(
        weights: List<Pair<PetBehavior, Int>>,
        multipliers: Map<PetBehavior, Float>
    ): List<Pair<PetBehavior, Int>> = weights.map { (behavior, weight) ->
        val multiplier = (multipliers[behavior] ?: 1f).coerceIn(0.5f, 1.5f)
        behavior to maxOf(1, (weight * multiplier).toInt())
    }
}
