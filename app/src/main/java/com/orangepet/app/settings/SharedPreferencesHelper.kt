package com.orangepet.app.settings

import android.content.Context

/**
 * **Security note (read before reusing this for anything sensitive):**
 * private `SharedPreferences` protects the key from *other apps* on a
 * normal, non-rooted device — it does **not** protect it from a
 * compromised or reverse-engineered client, a rooted device, or a device
 * backup that isn't excluded. For a personal, user-supplied key (this
 * feature's actual use case) that's a reasonable, disclosed trade-off; it
 * would **not** be reasonable for a shared production credential. The
 * long-term fix, if this ever needs to be hardened, is an
 * Android-Keystore-backed implementation behind the same [ApiKeyStore]
 * interface — every caller already goes through that interface, so the
 * swap wouldn't touch calling code.
 *
 * Also: this key is never logged, never placed in `BuildConfig`, and never
 * included in the notification, crash report, or analytics paths — grep
 * the project for `API_KEY` if you want to confirm that yourself.
 */
object SharedPreferencesHelper {
    private const val FILE_NAME = "orange_pet_private_preferences"
    private const val API_KEY = "gemini_api_key"

    fun saveApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(API_KEY, apiKey.trim())
            .apply()
    }

    fun getApiKey(context: Context): String =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getString(API_KEY, "")
            .orEmpty()

    fun clearApiKey(context: Context) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(API_KEY)
            .apply()
    }
}

/** [ApiKeyStore] backed by [SharedPreferencesHelper] — see the security note there. */
class PrivatePrefsApiKeyStore(private val context: Context) : ApiKeyStore {
    override fun getApiKey(): String = SharedPreferencesHelper.getApiKey(context)

    override fun saveApiKey(value: String) {
        val normalized = ApiKeyValidation.normalize(value)
        if (ApiKeyValidation.isValid(normalized)) {
            SharedPreferencesHelper.saveApiKey(context, normalized)
        }
    }

    override fun clearApiKey() = SharedPreferencesHelper.clearApiKey(context)

    override fun hasApiKey(): Boolean = getApiKey().isNotBlank()
}
