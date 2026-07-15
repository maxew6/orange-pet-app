package com.orangepet.app.notification

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lightweight in-process signal from [PetNotificationListenerService] to
 * the service layer. Both components run in the app's default process, so
 * a shared object is sufficient and avoids the complexity of binding or
 * broadcasting for a purely internal, best-effort UI hint.
 *
 * This bus intentionally carries no notification content — only the fact
 * that a message-like notification arrived — since the pet's reaction is a
 * fixed, canned message rather than a display of the user's private text.
 */
object NotificationReactionBus {

    private val _events = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1
    )

    val events: SharedFlow<Unit> = _events.asSharedFlow()

    /** Called by the listener service when a qualifying notification arrives. */
    fun notifyMessageReceived() {
        _events.tryEmit(Unit)
    }
}
