package com.orangepet.app.model

import java.time.LocalTime

/**
 * The user's onboarding/settings choices. Deliberately does **not** hold
 * the Gemini API key itself (see `settings.ApiKeyStore`) — only whether
 * one is configured — so this object is safe to log, put in a StateFlow
 * snapshot, or otherwise pass around without a risk of leaking the key.
 */
data class UserProfile(
    val displayName: String = "",
    val hasApiKey: Boolean = false,
    val aiConsentGiven: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val contextAwareEnabled: Boolean = false,
    val morningTime: LocalTime = LocalTime.of(7, 0),
    val lunchTime: LocalTime = LocalTime.of(13, 0),
    val nightTime: LocalTime = LocalTime.of(22, 0),
    val onboardingCompleted: Boolean = false
)
