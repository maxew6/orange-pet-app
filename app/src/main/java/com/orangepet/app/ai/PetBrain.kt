package com.orangepet.app.ai

import com.orangepet.app.behavior.GreetingPeriod

/**
 * Produces short greeting/status text for the pet to speak. The rest of
 * the app depends only on this interface — never on `GeminiPetBrain`
 * directly — so Gemini-specific types stay out of the service and the
 * behavior controller, and so the app always has a working
 * [OfflinePetBrain] fallback.
 *
 * Implementations must be safe to call speculatively: on any failure
 * (network, timeout, invalid key, malformed response) they should return
 * a reasonable local fallback string rather than throwing, since a
 * greeting failing to generate should never crash the overlay or block
 * the pet's animation.
 */
interface PetBrain {
    suspend fun createGreeting(userName: String, period: GreetingPeriod): String
}
