package com.orangepet.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.orangepet.app.MainActivity
import com.orangepet.app.R
import com.orangepet.app.ai.GeminiPetBrain
import com.orangepet.app.ai.OfflinePetBrain
import com.orangepet.app.ai.PetBrain
import com.orangepet.app.behavior.PetBehaviorController
import com.orangepet.app.behavior.PetContent
import com.orangepet.app.behavior.PetEvent
import com.orangepet.app.behavior.PetScheduleController
import com.orangepet.app.context.InteractionLearningRepository
import com.orangepet.app.notification.NotificationReactionBus
import com.orangepet.app.notification.PetNotificationManager
import com.orangepet.app.settings.PrivatePrefsApiKeyStore
import com.orangepet.app.settings.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the OrangePet floating overlay.
 *
 * Per the v3 layered architecture, this class is intentionally thin: it
 * owns the Android-specific plumbing every version has needed
 * (foreground-service lifecycle, notification, `LifecycleOwner`/
 * `SavedStateRegistryOwner`/`ViewModelStoreOwner` for the window-attached
 * `ComposeView`) and wires together [OverlayWindowController],
 * [BatteryMonitor], [PetBehaviorController], and [PetScheduleController].
 * It does **not** contain behavior logic, scheduling math, or AI prompts —
 * those live in their own classes.
 */
class FloatingPetService :
    Service(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var isOverlayInitialized = false

    private lateinit var overlay: OverlayWindowController
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var behaviorController: PetBehaviorController
    private lateinit var scheduleController: PetScheduleController
    private lateinit var interactionRepo: InteractionLearningRepository
    private lateinit var apiKeyStore: PrivatePrefsApiKeyStore
    private lateinit var userPreferencesRepository: UserPreferencesRepository

    override fun onCreate() {
        super.onCreate()

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        interactionRepo = InteractionLearningRepository(applicationContext)
        apiKeyStore = PrivatePrefsApiKeyStore(applicationContext)
        userPreferencesRepository = UserPreferencesRepository(applicationContext, apiKeyStore)

        overlay = OverlayWindowController(
            context = this,
            lifecycleOwner = this,
            savedStateRegistryOwner = this,
            viewModelStoreOwner = this
        )
        batteryMonitor = BatteryMonitor(applicationContext)

        behaviorController = PetBehaviorController(
            overlay = overlay,
            scope = serviceScope,
            densityProvider = { resources.displayMetrics.density },
            interactionRepo = interactionRepo,
            contextAwareEnabledProvider = { userPreferencesRepository.profile.value.contextAwareEnabled }
        )

        scheduleController = PetScheduleController(
            context = applicationContext,
            scope = serviceScope,
            userProfileProvider = { userPreferencesRepository.profile.value },
            petBrainProvider = { buildPetBrain() },
            notificationManager = PetNotificationManager(applicationContext),
            onEvent = { event, speech -> behaviorController.postEvent(event, speech) },
            onNightWindowChanged = { inWindow -> behaviorController.updateNightWindow(inWindow) }
        )

        // Battery/charging -> behavior controller.
        serviceScope.launch {
            batteryMonitor.status.collect { status ->
                behaviorController.updateBattery(status.percent, status.isCharging)
            }
        }

        // Message-notification signal -> behavior controller. This is a
        // real-time overlay reaction (not a posted system notification),
        // so it's independent of the "Notifications" toggle, which governs
        // PetNotificationManager's scheduled greeting posts only.
        serviceScope.launch {
            NotificationReactionBus.events.collect {
                behaviorController.postEvent(PetEvent.MessageNotification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Idempotent startup: only attach the overlay / start the
        // controllers once, no matter how many times onStartCommand fires.
        if (!isOverlayInitialized) {
            isOverlayInitialized = true
            initializeOverlay()
            batteryMonitor.start()
            scheduleController.start()
            behaviorController.start()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initializeOverlay() {
        // IMPORTANT: the lifecycle must reach STARTED *before* the
        // ComposeView is attached to WindowManager, not after. Compose's
        // lifecycle-aware Recomposer only actively recomposes once the
        // associated Lifecycle is at least STARTED; attaching the view
        // while still CREATED and moving to STARTED afterward risks the
        // very first frame rendering (from the default PetUiState) and then
        // never updating again, since nothing is actually recomposing. That
        // symptom looks exactly like "the pet renders once, then freezes."
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        overlay.attach(
            widthDp = OVERLAY_WIDTH_DP,
            heightDp = OVERLAY_HEIGHT_DP,
            bottomMarginDp = BOTTOM_MARGIN_DP
        ) {
            val state by behaviorController.uiState.collectAsState()
            MaterialTheme {
                PetContent(state = state)
            }
        }
    }

    /**
     * Built fresh on every scheduled greeting rather than cached at
     * startup, so saving a new API key in Settings takes effect on the
     * next greeting without needing to restart the service.
     */
    private fun buildPetBrain(): PetBrain {
        val key = apiKeyStore.getApiKey()
        return if (key.isNotBlank()) GeminiPetBrain(apiKey = key) else OfflinePetBrain()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        behaviorController.reclampToScreen()
    }

    // ---------------------------------------------------------------------
    // Foreground-service notification (unchanged responsibility from v1/v2)
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = getString(R.string.notification_channel_description)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(contentIntent)
            .build()
    }

    // ---------------------------------------------------------------------
    // Teardown
    // ---------------------------------------------------------------------

    override fun onDestroy() {
        // Cancel all behavior/schedule work first.
        behaviorController.shutdown()
        scheduleController.stop()
        batteryMonitor.stop()

        // Remove the overlay only if it was attached.
        overlay.detach()

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        serviceScope.cancel()

        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "orangepet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val OVERLAY_WIDTH_DP = 128
        private const val OVERLAY_HEIGHT_DP = 180
        private const val BOTTOM_MARGIN_DP = 24
    }
}
