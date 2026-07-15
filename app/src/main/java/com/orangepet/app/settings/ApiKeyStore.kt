package com.orangepet.app.settings

/**
 * Storage-agnostic access to the user-supplied Gemini API key. The initial
 * implementation ([SharedPreferencesHelper]-backed, in this file's
 * companion class `PrivatePrefsApiKeyStore`) uses private `SharedPreferences`,
 * which is **app-private storage, not a full security boundary** — see the
 * security note in `SharedPreferencesHelper`. The interface exists so a
 * Keystore-backed implementation can be swapped in later without touching
 * any caller.
 */
interface ApiKeyStore {
    fun getApiKey(): String
    fun saveApiKey(value: String)
    fun clearApiKey()
    fun hasApiKey(): Boolean
}

/** Trims and rejects blank input — pure validation, shared by the store and the settings UI. */
object ApiKeyValidation {
    fun normalize(rawInput: String): String = rawInput.trim()
    fun isValid(rawInput: String): Boolean = normalize(rawInput).isNotEmpty()
}
