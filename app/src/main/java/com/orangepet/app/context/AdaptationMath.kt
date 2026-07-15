package com.orangepet.app.context

/**
 * Turns simple local counters into a bounded weight multiplier. This is
 * "contextual preference learning", not reinforcement learning: there's no
 * reward signal, no online policy update, and no data ever leaves the
 * device or gets read from other apps' screens — only counts of how often
 * a behavior was shown vs. positively/negatively acknowledged.
 */
object AdaptationMath {

    private const val MIN_MULTIPLIER = 0.5f
    private const val MAX_MULTIPLIER = 1.5f
    private const val STEP_PER_NET_SIGNAL = 0.08f

    /**
     * [shown] how many times the behavior has played, [positive] explicit
     * or implicit positive signals (e.g. a scheduled-greeting notification
     * was tapped), [dismissed] negative signals (e.g. swiped away). Always
     * clamped to [MIN_MULTIPLIER, MAX_MULTIPLIER] so learned preferences
     * can only ever nudge weights, never zero a behavior out or let it
     * dominate completely.
     */
    fun multiplierFor(shown: Int, positive: Int, dismissed: Int): Float {
        if (shown <= 0) return 1f
        val net = positive - dismissed
        val raw = 1f + net * STEP_PER_NET_SIGNAL
        return raw.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
    }
}
