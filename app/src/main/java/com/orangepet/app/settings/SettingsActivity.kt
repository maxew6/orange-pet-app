package com.orangepet.app.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider

/**
 * Settings/onboarding screen. Kept as its own Activity rather than a
 * destination inside a `NavHost`, since there's currently exactly one
 * settings screen — adding Navigation-Compose for a single destination
 * would be a new dependency with no functional benefit yet.
 *
 * Uses `ViewModelProvider` directly (rather than the `by viewModels()`
 * Kotlin delegate) since that's guaranteed available from the already-
 * pinned `androidx.lifecycle:lifecycle-viewmodel-ktx` dependency, with no
 * reliance on exactly what `androidx.activity:activity-compose` pulls in
 * transitively.
 */
class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by lazy {
        ViewModelProvider(this, SettingsViewModel.Factory(applicationContext)).get(SettingsViewModel::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(viewModel = viewModel, onDone = { finish() })
                }
            }
        }
    }
}
