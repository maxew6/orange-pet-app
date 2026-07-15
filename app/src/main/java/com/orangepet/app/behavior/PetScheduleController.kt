package com.orangepet.app.behavior

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.orangepet.app.ai.OfflinePetBrain
import com.orangepet.app.ai.PetBrain
import com.orangepet.app.model.UserProfile
import com.orangepet.app.notification.PetNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Owns the morning/lunch/night schedule. Fetches greeting text (local or
 * Gemini, via [petBrainProvider]) and hands it to [onEvent] together with
 * the corresponding [PetEvent.Scheduled] — this is the **only** place in
 * the app that calls a [PetBrain], which is what guarantees Gemini is
 * never called from `PetBehaviorController`'s per-frame walking/animation
 * loops.
 */
class PetScheduleController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val userProfileProvider: () -> UserProfile,
    private val petBrainProvider: () -> PetBrain,
    private val notificationManager: PetNotificationManager,
    private val onEvent: (PetEvent, speechText: String?) -> Unit,
    private val onNightWindowChanged: (Boolean) -> Unit
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private var schedulerJob: Job? = null
    private var timeChangeReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    fun start() {
        registerTimeChangeReceiver()
        restartSchedulerJob()
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
        unregisterTimeChangeReceiver()
    }

    private fun restartSchedulerJob() {
        schedulerJob?.cancel()
        schedulerJob = scope.launch { schedulerLoop() }
    }

    private suspend fun schedulerLoop() {
        while (isActive) {
            val profile = userProfileProvider()
            val now = LocalDateTime.now()
            val today = now.toLocalDate()

            onNightWindowChanged(
                ScheduleMath.isWithinNightWindow(now.toLocalTime(), profile.nightTime, profile.morningTime)
            )

            maybeFire(GreetingPeriod.MORNING, profile.morningTime, KEY_LAST_MORNING, now, today, profile)
            maybeFire(GreetingPeriod.LUNCH, profile.lunchTime, KEY_LAST_LUNCH, now, today, profile)
            maybeFire(GreetingPeriod.NIGHT, profile.nightTime, KEY_LAST_NIGHT, now, today, profile)

            val nextBoundary = listOf(
                ScheduleMath.nextOccurrence(now, profile.morningTime),
                ScheduleMath.nextOccurrence(now, profile.lunchTime),
                ScheduleMath.nextOccurrence(now, profile.nightTime)
            ).min()

            // Capped so the loop periodically re-checks the night window even
            // between named boundaries, and stays responsive to cancellation.
            val delayMs = ScheduleMath.delayMillisUntil(now, nextBoundary).coerceAtMost(MAX_SLEEP_CHUNK_MS)
            delay(delayMs.coerceAtLeast(MIN_SLEEP_MS))
        }
    }

    private fun maybeFire(
        period: GreetingPeriod,
        targetTime: java.time.LocalTime,
        prefsKey: String,
        now: LocalDateTime,
        today: LocalDate,
        profile: UserProfile
    ) {
        val alreadyFiredToday = !ScheduleMath.shouldFireForDate(getLastFiredDate(prefsKey), today)
        if (alreadyFiredToday) return
        if (now.toLocalTime().isBefore(targetTime)) return
        setLastFiredDate(prefsKey, today)
        fireGreeting(period, profile)
    }

    private fun fireGreeting(period: GreetingPeriod, profile: UserProfile) {
        scope.launch {
            val text = try {
                val brain = if (profile.aiConsentGiven) petBrainProvider() else OfflinePetBrain()
                brain.createGreeting(profile.displayName, period)
            } catch (t: Throwable) {
                // Should already be unreachable (PetBrain implementations catch
                // their own failures), but never let a scheduled greeting crash
                // the service.
                OfflinePetBrain().createGreeting(profile.displayName, period)
            }
            onEvent(PetEvent.Scheduled(period), text)
            if (profile.notificationsEnabled) {
                notificationManager.postGreeting(period, text)
            }
        }
    }

    private fun getLastFiredDate(key: String): LocalDate? =
        runCatching { prefs.getString(key, null)?.let(LocalDate::parse) }.getOrNull()

    private fun setLastFiredDate(key: String, date: LocalDate) {
        prefs.edit().putString(key, date.toString()).apply()
    }

    private fun registerTimeChangeReceiver() {
        if (isReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                // Clock, timezone, or date changed underneath us: throw away
                // whatever the loop was waiting on and recompute from scratch.
                restartSchedulerJob()
            }
        }
        timeChangeReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        isReceiverRegistered = true
    }

    private fun unregisterTimeChangeReceiver() {
        if (isReceiverRegistered) {
            timeChangeReceiver?.let { context.unregisterReceiver(it) }
            timeChangeReceiver = null
            isReceiverRegistered = false
        }
    }

    companion object {
        private const val PREFS_FILE = "orange_pet_schedule"
        private const val KEY_LAST_MORNING = "last_morning_greeting_date"
        private const val KEY_LAST_LUNCH = "last_lunch_action_date"
        private const val KEY_LAST_NIGHT = "last_good_night_date"
        private const val MAX_SLEEP_CHUNK_MS = 15 * 60 * 1000L // re-check at least every 15 minutes
        private const val MIN_SLEEP_MS = 1_000L
    }
}
