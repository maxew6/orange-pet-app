package com.orangepet.app.behavior

/**
 * Immutable snapshot of everything the pet composable needs to render a
 * frame. Deliberately contains only primitives, `String`, and enums — no
 * Compose objects, `Drawable`, `Context`, or animation instances, per the
 * v3 architecture notes.
 *
 * Field names are unchanged from v2 (`facingRight`, `translationY`, etc.)
 * rather than renamed to the v3 doc's suggested `direction`/
 * `verticalOffsetPx` — this is a deliberate choice to avoid touching
 * already-verified v2 rendering code. See README "v3 scope notes".
 */
data class PetUiState(
    val behavior: PetBehavior = PetBehavior.IDLE,
    val batteryPercent: Int = 100,
    val isLowBattery: Boolean = false,
    val isCharging: Boolean = false,
    val facingRight: Boolean = true,
    val translationY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationZ: Float = 0f,

    // v2 heart burst
    val heartVisible: Boolean = false,
    val heartOffsetY: Float = 0f,
    val heartAlpha: Float = 0f,

    // v2 short random nap
    val sleepText: String? = null,
    val sleepTextAlpha: Float = 0f,

    // v2 notification-reaction bubble (always the same fixed line)
    val chatBubbleVisible: Boolean = false,
    val chatBubbleAlpha: Float = 0f,

    // v3: general pet speech (greetings, lunch, goodnight — AI or offline)
    val speechText: String? = null,
    val speechAlpha: Float = 0f,

    // v3: ball play
    val ballVisible: Boolean = false,
    val ballProgress: Float = 0f,

    // v3: eating
    val foodVisible: Boolean = false,
    val foodProgress: Float = 0f
)
