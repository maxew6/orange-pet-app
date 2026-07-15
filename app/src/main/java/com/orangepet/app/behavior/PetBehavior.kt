package com.orangepet.app.behavior

/**
 * All behaviors the pet can be in. Exactly one is active at a time, owned
 * by [PetBehaviorController]'s single behavior [kotlinx.coroutines.Job].
 *
 * v3 keeps every v2 behavior and adds five new ones (PLAYING_BALL,
 * SKIPPING, EATING, GREETING, NIGHT_SLEEPING). SLEEPING (short random nap)
 * and NIGHT_SLEEPING (scheduled 10pm-7am rest) are intentionally distinct,
 * per the v3 spec.
 */
enum class PetBehavior {
    // v2 — normal weighted-random scheduler
    IDLE,
    WALKING,
    BLINKING,
    HOPPING,
    LOOKING_AROUND,
    SHOWING_HEART,
    SLEEPING,

    // v3 — normal weighted-random scheduler additions
    PLAYING_BALL,
    SKIPPING,

    // v3 — scheduled / event-driven (never chosen by the random scheduler)
    EATING,
    GREETING,
    NIGHT_SLEEPING,

    // v2 — priority interrupts (never chosen by the random scheduler)
    CHARGING,
    NOTIFICATION_REACTION,
    FAINTED
}

/** True for behaviors the random scheduler is allowed to pick. */
fun PetBehavior.isSchedulable(): Boolean = when (this) {
    PetBehavior.IDLE, PetBehavior.WALKING, PetBehavior.BLINKING, PetBehavior.HOPPING,
    PetBehavior.LOOKING_AROUND, PetBehavior.SHOWING_HEART, PetBehavior.SLEEPING,
    PetBehavior.PLAYING_BALL, PetBehavior.SKIPPING -> true
    else -> false
}

/** "Special" behaviors are eligible for the no-two-in-a-row rule (everything but idle/walking). */
fun PetBehavior.isSpecial(): Boolean = this != PetBehavior.IDLE && this != PetBehavior.WALKING
