package com.orangepet.app.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Optional component: detects that a message-style notification has
 * arrived so the pet can show a short, fixed reaction bubble.
 *
 * Privacy note: this service deliberately never reads notification title,
 * text, sender, or any other content. It only checks the notification's
 * declared [Notification.category] (and, as a fallback, whether the
 * originating app looks like a messaging app) and forwards a contentless
 * signal through [NotificationReactionBus]. The pet's reaction is always
 * the same static line — never anything derived from the message itself.
 *
 * This service is inert until the user explicitly grants "Notification
 * access" for OrangePet in system settings (see MainActivity/Settings).
 * Without that grant, Android never binds this service and nothing
 * happens; the rest of the app functions normally either way.
 */
class PetNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isLikelyMessageNotification(sbn)) {
            NotificationReactionBus.notifyMessageReceived()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No action needed: the pet's reaction is time-based, not tied to
        // the notification's lifetime.
    }

    private fun isLikelyMessageNotification(sbn: StatusBarNotification): Boolean {
        // Ignore our own foreground-service / reminder notifications to
        // avoid feedback loops.
        if (sbn.packageName == packageName) return false

        val notification = sbn.notification ?: return false
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return false

        val category = notification.category
        if (category == Notification.CATEGORY_MESSAGE) return true

        val pkg = sbn.packageName.lowercase()
        val messagingHints = listOf(
            "whatsapp", "messenger", "telegram", "signal", "sms",
            "messaging", "mms", "chat", "gmail", "outlook", "slack",
            "discord", "hangouts", "line", "viber", "wechat"
        )
        return messagingHints.any { pkg.contains(it) }
    }
}
