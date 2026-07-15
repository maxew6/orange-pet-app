package com.orangepet.app.settings

import android.content.Context
import com.orangepet.app.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime

/**
 * Stores the user's name, feature toggles, and schedule times, and exposes
 * them through a [StateFlow] — satisfying "exposes changes through Flow"
 * from the v3 spec without adding a DataStore dependency, which wasn't
 * genuinely required for this data's shape or volume. The API key itself
 * is deliberately **not** stored here; it goes through [ApiKeyStore] only,
 * so it's never present in this class's in-memory state or logs.
 */
class UserPreferencesRepository(
    private val context: Context,
    private val apiKeyStore: ApiKeyStore
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow(loadProfile())
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private fun loadProfile(): UserProfile = UserProfile(
        displayName = prefs.getString(KEY_NAME, "").orEmpty(),
        hasApiKey = apiKeyStore.hasApiKey(),
        aiConsentGiven = prefs.getBoolean(KEY_AI_CONSENT, false),
        notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true),
        contextAwareEnabled = prefs.getBoolean(KEY_CONTEXT_AWARE, false),
        morningTime = LocalTime.of(prefs.getInt(KEY_MORNING_H, 7), prefs.getInt(KEY_MORNING_M, 0)),
        lunchTime = LocalTime.of(prefs.getInt(KEY_LUNCH_H, 13), prefs.getInt(KEY_LUNCH_M, 0)),
        nightTime = LocalTime.of(prefs.getInt(KEY_NIGHT_H, 22), prefs.getInt(KEY_NIGHT_M, 0)),
        onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
    )

    private fun refresh() {
        _profile.value = loadProfile()
    }

    fun setDisplayName(name: String) {
        prefs.edit().putString(KEY_NAME, name.trim()).apply()
        refresh()
    }

    fun setAiConsentGiven(consent: Boolean) {
        prefs.edit().putBoolean(KEY_AI_CONSENT, consent).apply()
        refresh()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
        refresh()
    }

    fun setContextAwareEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTEXT_AWARE, enabled).apply()
        refresh()
    }

    fun setMorningTime(time: LocalTime) {
        prefs.edit().putInt(KEY_MORNING_H, time.hour).putInt(KEY_MORNING_M, time.minute).apply()
        refresh()
    }

    fun setLunchTime(time: LocalTime) {
        prefs.edit().putInt(KEY_LUNCH_H, time.hour).putInt(KEY_LUNCH_M, time.minute).apply()
        refresh()
    }

    fun setNightTime(time: LocalTime) {
        prefs.edit().putInt(KEY_NIGHT_H, time.hour).putInt(KEY_NIGHT_M, time.minute).apply()
        refresh()
    }

    fun markOnboardingCompleted() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
        refresh()
    }

    /** Delegates to [ApiKeyStore]; the key value itself never touches this class's own storage. */
    fun setApiKey(value: String) {
        apiKeyStore.saveApiKey(value)
        refresh()
    }

    fun clearApiKey() {
        apiKeyStore.clearApiKey()
        refresh()
    }

    companion object {
        private const val PREFS_FILE = "orange_pet_user_prefs"
        private const val KEY_NAME = "display_name"
        private const val KEY_AI_CONSENT = "ai_consent_given"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_CONTEXT_AWARE = "context_aware_enabled"
        private const val KEY_MORNING_H = "morning_hour"
        private const val KEY_MORNING_M = "morning_minute"
        private const val KEY_LUNCH_H = "lunch_hour"
        private const val KEY_LUNCH_M = "lunch_minute"
        private const val KEY_NIGHT_H = "night_hour"
        private const val KEY_NIGHT_M = "night_minute"
        private const val KEY_ONBOARDING_DONE = "onboarding_completed"
    }
}
