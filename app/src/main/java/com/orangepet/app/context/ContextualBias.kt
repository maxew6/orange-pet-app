package com.orangepet.app.context

import com.orangepet.app.behavior.PetBehavior

/**
 * A small, deterministic time-of-day nudge, independent from
 * [AdaptationMath]'s learned engagement multiplier. It exists because the
 * overlay stays `FLAG_NOT_TOUCHABLE` in this version (unchanged from
 * v1/v2 — see README "v3 scope notes"), so there's no in-overlay tap
 * feedback to learn from for most behaviors; this gives the
 * "context-aware behavior" toggle a real, always-available effect rather
 * than being functionally inert until a future version adds touch
 * feedback. Every value here is a mild nudge, not a hard rule — final
 * weights are still clamped to [0.5, 1.5] by `BehaviorWeights.applyMultipliers`.
 */
object ContextualBias {

    fun multipliersFor(context: PetContext): Map<PetBehavior, Float> = when (context.timeBucket) {
        TimeBucket.MORNING -> mapOf(
            PetBehavior.HOPPING to 1.2f,
            PetBehavior.BLINKING to 1.15f
        )
        TimeBucket.AFTERNOON -> mapOf(
            PetBehavior.PLAYING_BALL to 1.2f,
            PetBehavior.SKIPPING to 1.2f,
            PetBehavior.SLEEPING to 0.8f
        )
        TimeBucket.EVENING -> mapOf(
            PetBehavior.SHOWING_HEART to 1.2f,
            PetBehavior.LOOKING_AROUND to 1.1f
        )
        TimeBucket.NIGHT -> mapOf(
            PetBehavior.SLEEPING to 1.3f,
            PetBehavior.PLAYING_BALL to 0.7f,
            PetBehavior.SKIPPING to 0.7f
        )
    }

    /** Combines the learned engagement multiplier with the time-of-day bias, one multiply, still to be clamped by the caller. */
    fun combine(engagement: Map<PetBehavior, Float>, bias: Map<PetBehavior, Float>): Map<PetBehavior, Float> {
        val allBehaviors = engagement.keys + bias.keys
        return allBehaviors.associateWith { behavior ->
            (engagement[behavior] ?: 1f) * (bias[behavior] ?: 1f)
        }
    }
}
