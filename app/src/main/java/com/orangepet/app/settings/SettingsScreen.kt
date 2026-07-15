package com.orangepet.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalTime

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onDone: () -> Unit) {
    val profile by viewModel.profile.collectAsState()

    var nameInput by remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var apiKeyInput by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var apiKeyStatusMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(text = "OrangePet Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(20.dp))

        Text(text = "Your name", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = nameInput,
            onValueChange = {
                nameInput = it
                viewModel.updateDisplayName(it)
            },
            placeholder = { Text("Skip to just be called \"friend\"") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Divider()

        Text(text = "AI-generated messages (optional)", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Gemini generates short greeting text only. All movement and " +
                "timing stays fully local and deterministic — the AI never controls " +
                "the pet's behavior.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it; apiKeyStatusMessage = null },
            label = { Text(if (profile.hasApiKey) "Replace Gemini API key" else "Gemini API key") },
            placeholder = { Text(if (profile.hasApiKey) "•••• already saved" else "Paste your key") },
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            leadingIcon = { Text("\uD83D\uDDDD\uFE0F") }, // 🗝️ — see README for why this is used instead of a Material icon
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                Text(if (apiKeyVisible) "Hide" else "Show")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    viewModel.removeApiKey()
                    apiKeyInput = ""
                    apiKeyStatusMessage = "API key removed."
                },
                enabled = profile.hasApiKey
            ) { Text("Remove") }
            TextButton(
                onClick = {
                    viewModel.saveApiKey(apiKeyInput)
                    apiKeyInput = ""
                    apiKeyVisible = false
                    apiKeyStatusMessage = "API key saved."
                },
                enabled = ApiKeyValidation.isValid(apiKeyInput)
            ) { Text("Save API Key") }
        }
        apiKeyStatusMessage?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = "Stored only on this device's private app storage, never shown again after " +
                "saving, and never logged. See README for the exact security scope of this storage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Divider()

        ToggleRow(
            title = "Let OrangePet speak using AI",
            subtitle = "Uses your Gemini key when available and consented to; otherwise OrangePet uses local phrases.",
            checked = profile.aiConsentGiven,
            onCheckedChange = viewModel::updateAiConsent
        )
        ToggleRow(
            title = "Notifications",
            subtitle = "Morning / lunch / good-night messages as system notifications.",
            checked = profile.notificationsEnabled,
            onCheckedChange = viewModel::updateNotificationsEnabled
        )
        ToggleRow(
            title = "Context-aware behavior",
            subtitle = "Nudges which animations play based on time of day and whether you " +
                "engaged with past messages. Off by default. Never reads other apps or your screen.",
            checked = profile.contextAwareEnabled,
            onCheckedChange = viewModel::updateContextAwareEnabled
        )

        Divider()

        Text(text = "Schedule", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        TimeStepperRow("Morning greeting", profile.morningTime, viewModel::updateMorningTime)
        TimeStepperRow("Lunch reminder", profile.lunchTime, viewModel::updateLunchTime)
        TimeStepperRow("Good night / sleep", profile.nightTime, viewModel::updateNightTime)

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                viewModel.completeOnboarding()
                onDone()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Done") }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TimeStepperRow(label: String, time: LocalTime, onChange: (LocalTime) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange(time.minusHours(1)) }) { Text("−1h") }
        Text(
            text = "%02d:%02d".format(time.hour, time.minute),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        TextButton(onClick = { onChange(time.plusHours(1)) }) { Text("+1h") }
        Spacer(modifier = Modifier.width(4.dp))
        TextButton(onClick = { onChange(time.minusMinutes(15)) }) { Text("−15m") }
        TextButton(onClick = { onChange(time.plusMinutes(15)) }) { Text("+15m") }
    }
}

/** Minimal full-width rule; avoids depending on a specific Material3 Divider/HorizontalDivider API surface. */
@Composable
private fun Divider() {
    Spacer(modifier = Modifier.height(20.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
    Spacer(modifier = Modifier.height(20.dp))
}
