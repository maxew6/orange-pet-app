package com.orangepet.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.orangepet.app.behavior.PetBehavior
import com.orangepet.app.context.InteractionLearningRepository
import com.orangepet.app.service.FloatingPetService
import com.orangepet.app.settings.SettingsActivity

/**
 * Entry point for OrangePet.
 *
 * Responsibilities:
 *  - Verify SYSTEM_ALERT_WINDOW ("draw over other apps") permission using
 *    [Settings.canDrawOverlays] as the single source of truth, because the
 *    overlay-permission settings screen does not reliably return a usable
 *    Activity result on all OEM builds.
 *  - Request Android 13+ POST_NOTIFICATIONS permission, which is required
 *    for the foreground service's persistent notification to be visible.
 *  - Start [FloatingPetService] only once overlay permission is confirmed.
 *  - Leave the overlay running when this Activity is closed: the Activity
 *    only launches/observes the service, it does not own the overlay.
 */
class MainActivity : ComponentActivity() {

    // Tracks UI + permission state for the Compose screen.
    private var overlayPermissionGranted by mutableStateOf(false)
    private var serviceStartAttempted by mutableStateOf(false)
    private var serviceStarted by mutableStateOf(false)

    // Optional: whether the user has granted "Notification access" so the
    // pet can react to incoming message-style notifications. This is never
    // required to use the pet; it only gates the notification-reaction bubble.
    private var notificationAccessGranted by mutableStateOf(false)

    // Guards against spamming the overlay-permission settings screen if the
    // user rapidly taps the "grant" button or backgrounds/foregrounds the app.
    private var overlaySettingsLaunchInFlight = false

    private lateinit var overlaySettingsLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var notificationAccessSettingsLauncher: androidx.activity.result.ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If this launch came from tapping a scheduled-greeting notification
        // (see PetNotificationManager), record it as positive engagement for
        // that behavior's local adaptation counters.
        intent?.getStringExtra(EXTRA_ENGAGED_BEHAVIOR)?.let { behaviorName ->
            runCatching { PetBehavior.valueOf(behaviorName) }.getOrNull()?.let { behavior ->
                InteractionLearningRepository(applicationContext).recordPositive(behavior)
            }
        }

        // Registered once, before STARTED, per ActivityResult API contract.
        overlaySettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            // Deliberately ignore the returned result code: many OEMs do not
            // report an accurate RESULT_OK/RESULT_CANCELED for this screen.
            overlaySettingsLaunchInFlight = false
            refreshOverlayPermissionState()
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            // Notification permission is best-effort: the service still runs
            // (and still calls startForeground) even if this is denied.
            maybeStartService()
        }

        notificationAccessSettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            // Same pattern as overlay permission: re-check the real state
            // rather than trusting the result code.
            refreshNotificationAccessState()
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OrangePetScreen(
                        overlayPermissionGranted = overlayPermissionGranted,
                        serviceStarted = serviceStarted,
                        serviceStartAttempted = serviceStartAttempted,
                        notificationAccessGranted = notificationAccessGranted,
                        onGrantPermissionClick = ::requestOverlayPermission,
                        onStartClick = ::onStartRequested,
                        onEnableNotificationAccessClick = ::requestNotificationAccess,
                        onOpenSettingsClick = { startActivity(Intent(this, SettingsActivity::class.java)) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Settings.canDrawOverlays is rechecked every time the Activity
        // resumes, since it is the source of truth for permission state.
        refreshOverlayPermissionState()
        refreshNotificationAccessState()
    }

    private fun refreshOverlayPermissionState() {
        overlayPermissionGranted = Settings.canDrawOverlays(this)
    }

    private fun refreshNotificationAccessState() {
        notificationAccessGranted = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)
    }

    /**
     * Opens the system "Notification access" settings screen so the user
     * can optionally allow OrangePet to react to incoming messages. This
     * is entirely optional and never blocks the core pet experience: if
     * the settings screen is unavailable on a given device/OEM, this fails
     * quietly rather than crashing.
     */
    private fun requestNotificationAccess() {
        if (notificationAccessGranted) return
        try {
            notificationAccessSettingsLauncher.launch(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            )
        } catch (e: ActivityNotFoundException) {
            // Gracefully do nothing: message reactions simply stay off.
        }
    }

    private fun onStartRequested() {
        if (!overlayPermissionGranted) {
            requestOverlayPermission()
            return
        }
        ensureNotificationPermissionThenStart()
    }

    private fun requestOverlayPermission() {
        if (overlayPermissionGranted || overlaySettingsLaunchInFlight) {
            // Already granted, or a launch is already in flight: do nothing,
            // preventing repeated/uncontrolled settings launches.
            return
        }
        overlaySettingsLaunchInFlight = true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlaySettingsLauncher.launch(intent)
    }

    private fun ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        maybeStartService()
    }

    /**
     * Starts [FloatingPetService] only when overlay permission is currently
     * granted. Safe to call multiple times: FloatingPetService's own
     * startup logic is idempotent (it does not attach a second overlay if
     * one is already attached), and repeated calls to startForegroundService
     * with the same component are a no-op from the OS's perspective once the
     * service is already running.
     */
    private fun maybeStartService() {
        serviceStartAttempted = true
        if (!Settings.canDrawOverlays(this)) {
            serviceStarted = false
            return
        }
        val intent = Intent(this, FloatingPetService::class.java)
        ContextCompat.startForegroundService(this, intent)
        serviceStarted = true
    }

    companion object {
        /** Set by PetNotificationManager's tap PendingIntent; names a PetBehavior for engagement tracking. */
        const val EXTRA_ENGAGED_BEHAVIOR = "extra_engaged_behavior"
    }
}

@Composable
private fun OrangePetScreen(
    overlayPermissionGranted: Boolean,
    serviceStarted: Boolean,
    serviceStartAttempted: Boolean,
    notificationAccessGranted: Boolean,
    onGrantPermissionClick: () -> Unit,
    onStartClick: () -> Unit,
    onEnableNotificationAccessClick: () -> Unit,
    onOpenSettingsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val permissionRequiredText = stringResource(R.string.status_permission_required)
        val waitingText = stringResource(R.string.status_waiting_for_permission)
        val startedText = stringResource(R.string.status_service_started)
        val missingText = stringResource(R.string.status_permission_missing)

        val statusText = remember(overlayPermissionGranted, serviceStarted, serviceStartAttempted) {
            when {
                serviceStarted && overlayPermissionGranted -> startedText
                !overlayPermissionGranted -> permissionRequiredText
                serviceStartAttempted && !overlayPermissionGranted -> missingText
                else -> waitingText
            }
        }

        Text(text = "OrangePet", style = MaterialTheme.typography.headlineMedium)
        Text(text = statusText, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))

        if (!overlayPermissionGranted) {
            Button(onClick = onGrantPermissionClick) {
                Text(text = stringResource(R.string.button_grant_permission))
            }
        } else {
            Button(onClick = onStartClick) {
                Text(text = stringResource(R.string.button_start_pet))
            }
        }

        if (overlayPermissionGranted && serviceStarted) {
            val notificationStatusText = if (notificationAccessGranted) {
                stringResource(R.string.status_notification_access_granted)
            } else {
                stringResource(R.string.status_notification_access_missing)
            }
            Text(
                text = notificationStatusText,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
            )
            if (!notificationAccessGranted) {
                TextButton(onClick = onEnableNotificationAccessClick) {
                    Text(text = stringResource(R.string.button_enable_notification_access))
                }
            }
        }

        TextButton(onClick = onOpenSettingsClick, modifier = Modifier.padding(top = 12.dp)) {
            Text(text = stringResource(R.string.button_open_settings))
        }
    }
}
