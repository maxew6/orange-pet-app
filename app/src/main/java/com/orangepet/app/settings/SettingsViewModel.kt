package com.orangepet.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.orangepet.app.model.UserProfile
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime

class SettingsViewModel(private val repository: UserPreferencesRepository) : ViewModel() {

    val profile: StateFlow<UserProfile> = repository.profile

    fun updateDisplayName(name: String) = repository.setDisplayName(name)
    fun updateAiConsent(consent: Boolean) = repository.setAiConsentGiven(consent)
    fun updateNotificationsEnabled(enabled: Boolean) = repository.setNotificationsEnabled(enabled)
    fun updateContextAwareEnabled(enabled: Boolean) = repository.setContextAwareEnabled(enabled)
    fun updateMorningTime(time: LocalTime) = repository.setMorningTime(time)
    fun updateLunchTime(time: LocalTime) = repository.setLunchTime(time)
    fun updateNightTime(time: LocalTime) = repository.setNightTime(time)
    fun completeOnboarding() = repository.markOnboardingCompleted()

    /** [rawKey] is validated/trimmed inside the repository/store; blank input is simply ignored. */
    fun saveApiKey(rawKey: String) = repository.setApiKey(rawKey)
    fun removeApiKey() = repository.clearApiKey()

    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val apiKeyStore = PrivatePrefsApiKeyStore(appContext)
            val repository = UserPreferencesRepository(appContext, apiKeyStore)
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository) as T
        }
    }
}
