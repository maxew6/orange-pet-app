package com.orangepet.app.behavior

/** Which scheduled moment a [PetEvent.Scheduled] event represents. */
enum class GreetingPeriod { MORNING, LUNCH, NIGHT }

/**
 * Discrete, one-shot events that can interrupt whatever the pet is
 * currently doing. Continuous state (battery percent, charging, current
 * time-of-day) is *not* modeled as an event — it's read directly by
 * [PetPriority.resolveTrack] — because a "faint" or "night window"
 * condition doesn't fire once, it's simply true or false at any moment.
 */
sealed interface PetEvent {
    data class Scheduled(val period: GreetingPeriod) : PetEvent
    data object MessageNotification : PetEvent

    /**
     * Reserved for future tap/drag interaction. The overlay is currently
     * `FLAG_NOT_TOUCHABLE` (unchanged from v1/v2), so nothing produces this
     * event yet — it exists so [PetPriority] already has a slot for it
     * without a later priority-order change.
     */
    data class UserRequestedAction(val behavior: PetBehavior) : PetEvent
}
