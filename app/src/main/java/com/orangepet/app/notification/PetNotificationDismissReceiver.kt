package com.orangepet.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.orangepet.app.behavior.PetBehavior
import com.orangepet.app.context.InteractionLearningRepository

/**
 * Fired only by our own [PetNotificationManager]-created `deleteIntent`
 * (never exported to other apps) when the user swipes away a scheduled
 * greeting notification without tapping it — recorded as a "dismissed"
 * signal for that behavior's local engagement counters.
 */
class PetNotificationDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val behaviorName = intent.getStringExtra(EXTRA_BEHAVIOR_NAME) ?: return
        val behavior = runCatching { PetBehavior.valueOf(behaviorName) }.getOrNull() ?: return
        InteractionLearningRepository(context.applicationContext).recordDismissed(behavior)
    }

    companion object {
        const val EXTRA_BEHAVIOR_NAME = "extra_behavior_name"
    }
}
