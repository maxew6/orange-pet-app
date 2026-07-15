package com.orangepet.app.context

import android.content.Context
import com.orangepet.app.behavior.PetBehavior

/**
 * Local-only engagement counters. Deliberately simple: three integers per
 * behavior (shown / positive / dismissed), never anything about *why* —
 * no foreground app, no screen content, no notification text. This is
 * "contextual preference learning" per the v3 design, not reinforcement
 * learning: [AdaptationMath] turns these counters into a clamped weight
 * multiplier, it doesn't run an online policy update.
 */
class InteractionLearningRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun recordShown(behavior: PetBehavior) = increment(shownKey(behavior))
    fun recordPositive(behavior: PetBehavior) = increment(positiveKey(behavior))
    fun recordDismissed(behavior: PetBehavior) = increment(dismissedKey(behavior))

    /** Clamped [0.5, 1.5] multiplier per behavior, ready to feed into `BehaviorWeights.applyMultipliers`. */
    fun currentMultipliers(behaviors: List<PetBehavior>): Map<PetBehavior, Float> =
        behaviors.associateWith { behavior ->
            AdaptationMath.multiplierFor(
                shown = prefs.getInt(shownKey(behavior), 0),
                positive = prefs.getInt(positiveKey(behavior), 0),
                dismissed = prefs.getInt(dismissedKey(behavior), 0)
            )
        }

    private fun increment(key: String) {
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    private fun shownKey(b: PetBehavior) = "shown_${b.name}"
    private fun positiveKey(b: PetBehavior) = "positive_${b.name}"
    private fun dismissedKey(b: PetBehavior) = "dismissed_${b.name}"

    companion object {
        private const val PREFS_FILE = "orange_pet_interaction_stats"
    }
}
