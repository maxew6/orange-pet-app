package com.orangepet.app.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.orangepet.app.MainActivity
import com.orangepet.app.behavior.GreetingPeriod
import com.orangepet.app.behavior.PetBehavior

/**
 * Posts a normal (non-foreground) notification for scheduled greetings —
 * separate from `FloatingPetService`'s own always-on foreground-service
 * notification. Tapping the notification opens [MainActivity] and records
 * a "positive" engagement signal; swiping it away records a "dismissed"
 * one — both feed `InteractionLearningRepository`'s local-only counters.
 *
 * Respects the caller's "notifications enabled" toggle (checked by the
 * caller before invoking [postGreeting]) and Android 13+'s
 * `POST_NOTIFICATIONS` runtime permission (checked here; silently skips
 * posting if not granted, exactly like the spec's "respect notification
 * permission... before posting non-foreground notifications").
 */
class PetNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun postGreeting(period: GreetingPeriod, text: String) {
        ensureChannel()
        if (!hasPostNotificationsPermission()) return

        val behavior = behaviorFor(period)
        val notificationId = NOTIFICATION_ID_BASE + period.ordinal

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_ENGAGED_BEHAVIOR, behavior.name)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val deleteIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, PetNotificationDismissReceiver::class.java).apply {
                putExtra(PetNotificationDismissReceiver.EXTRA_BEHAVIOR_NAME, behavior.name)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(com.orangepet.app.R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "OrangePet messages",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Morning, lunch, and good-night messages from OrangePet"
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun hasPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val CHANNEL_ID = "orangepet_messages_channel"
        private const val NOTIFICATION_ID_BASE = 2000

        fun behaviorFor(period: GreetingPeriod): PetBehavior = when (period) {
            GreetingPeriod.MORNING -> PetBehavior.GREETING
            GreetingPeriod.LUNCH -> PetBehavior.EATING
            GreetingPeriod.NIGHT -> PetBehavior.NIGHT_SLEEPING
        }
    }
}
