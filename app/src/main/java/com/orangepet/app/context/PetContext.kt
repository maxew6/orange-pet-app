package com.orangepet.app.context

/** Coarse time-of-day bucket. Deliberately coarse — this is a weighting signal, not a clock. */
enum class TimeBucket { MORNING, AFTERNOON, EVENING, NIGHT }

/**
 * Everything the adaptive-weighting system is allowed to know about "the
 * moment". Notably absent: foreground app, screen contents, notification
 * text, location. Per the v3 privacy-safe-adaptation design, only signals
 * the user has implicitly or explicitly exposed are included here.
 */
data class PetContext(
    val timeBucket: TimeBucket,
    val isWeekend: Boolean,
    val batteryPercent: Int,
    val isCharging: Boolean
)
