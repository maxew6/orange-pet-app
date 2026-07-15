package com.orangepet.app.behavior

import com.orangepet.app.context.ContextProvider
import com.orangepet.app.context.ContextualBias
import com.orangepet.app.context.InteractionLearningRepository
import com.orangepet.app.service.OverlayMath
import com.orangepet.app.service.OverlayWindowController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Owns **exactly one** master behavior [Job] ([behaviorJob]) at a time —
 * never more, per the v3 safety rules. Every entry point that changes what
 * the pet is doing goes through [reconcile], which resolves the current
 * [PetPriorityTrack] via [PetPriority] and, only if the track actually
 * changed, cancels whatever's running and starts the right replacement via
 * [launchBehaviorJob]. That wrapper's `finally` block guarantees temporary
 * visual effects (bob, rotation, heart, speech, ball, food, sleep text)
 * are reset even if the job is cancelled mid-behavior — not just on a
 * function's normal return path.
 */
class PetBehaviorController(
    private val overlay: OverlayWindowController,
    private val scope: CoroutineScope,
    private val densityProvider: () -> Float,
    private val interactionRepo: InteractionLearningRepository,
    private val contextAwareEnabledProvider: () -> Boolean
) {
    private val _uiState = MutableStateFlow(PetUiState())
    val uiState: StateFlow<PetUiState> = _uiState.asStateFlow()

    private var isControllerActive = true
    private var isStarted = false
    private var behaviorJob: Job? = null
    private var lastTrack: PetPriorityTrack? = null
    private var lastRandomSpecial: PetBehavior? = null
    private var overlayXPx = 0

    // Continuous inputs, pushed in by the service.
    private var batteryPercent = 100
    private var isLowBattery = false
    private var isCharging = false
    private var isNightWindow = false

    // Discrete pending event (see PetEvent) — at most one at a time.
    private var pendingEvent: PetEvent? = null
    private var pendingSpeechText: String? = null

    // ------------------------------------------------------------------
    // External inputs
    // ------------------------------------------------------------------

    fun updateBattery(percent: Int, charging: Boolean) {
        batteryPercent = percent
        isLowBattery = percent <= LOW_BATTERY_THRESHOLD
        isCharging = charging
        updateState { it.copy(batteryPercent = percent, isLowBattery = isLowBattery, isCharging = charging) }
        if (isStarted) reconcile()
    }

    fun updateNightWindow(inWindow: Boolean) {
        isNightWindow = inWindow
        if (isStarted) reconcile()
    }

    /** Posts a one-shot interrupt. [speechText] is already-sanitized display text, or null for none. */
    fun postEvent(event: PetEvent, speechText: String? = null) {
        pendingEvent = event
        pendingSpeechText = speechText
        if (isStarted) reconcile()
    }

    /**
     * Marks the controller live and runs the first priority resolution.
     * Nothing above (updateBattery/updateNightWindow/postEvent) is allowed
     * to launch a behavior job before this runs — without that guard, a
     * StateFlow collector delivering its initial value during service
     * `onCreate()` (before the overlay is even attached) could start a
     * behavior job that immediately gets thrown away, wasting a frame and
     * risking a visible glitch on first appearance.
     */
    fun start() {
        isStarted = true
        reconcile(force = true)
    }

    /**
     * Forces the overlay's x position back within bounds immediately after
     * an orientation or display-size change, regardless of what behavior
     * is currently running. Without this, a stale x from before the
     * resize would only self-correct the next time WALKING happens to
     * run (its own per-frame clamp would catch it) — this makes the fix
     * immediate instead of "eventually", matching v1/v2's explicit
     * `onConfigurationChanged` handling.
     */
    fun reclampToScreen() {
        if (!overlay.isAttached) return
        val screenWidth = overlay.currentScreenWidthPx()
        overlayXPx = OverlayMath.clampX(overlayXPx, screenWidth, overlay.overlayWidthPx)
        overlay.moveTo(overlayXPx)
    }

    fun shutdown() {
        isControllerActive = false
        isStarted = false
        behaviorJob?.cancel()
        behaviorJob = null
    }

    // ------------------------------------------------------------------
    // Priority resolution
    // ------------------------------------------------------------------

    private fun reconcile(force: Boolean = false) {
        if (!isControllerActive || !isStarted) return
        val track = PetPriority.resolveTrack(
            isLowBattery = isLowBattery,
            isCharging = isCharging,
            isNightWindow = isNightWindow,
            hasPendingEvent = pendingEvent != null
        )
        if (!force && track == lastTrack) return
        lastTrack = track
        when (track) {
            PetPriorityTrack.FAINTED -> switchToFainted()
            PetPriorityTrack.NIGHT_SLEEP -> launchBehaviorJob { nightSleepLoop() }
            PetPriorityTrack.CHARGING -> launchBehaviorJob { chargingLoop() }
            PetPriorityTrack.EVENT -> switchToEvent()
            PetPriorityTrack.RANDOM -> launchBehaviorJob { randomLoop() }
        }
    }

    /**
     * Cancels any current job, runs [block], and always resets transient
     * visuals afterward — even on cancellation. Also defends against the
     * pet silently freezing forever if a behavior throws an unexpected
     * exception: real cancellation is rethrown and left alone, but any
     * other exception is caught, and — after a short delay so a
     * deterministic bug can't spin in a tight crash loop — triggers a
     * fresh priority resolution, so *something* always resumes rather than
     * the coroutine just quietly dying with nothing left to restart it.
     */
    private fun launchBehaviorJob(block: suspend () -> Unit) {
        behaviorJob?.cancel()
        behaviorJob = scope.launch {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Exception) {
                delay(1000L)
                lastTrack = null
                reconcile(force = true)
            } finally {
                resetTransientVisualEffects()
            }
        }
    }

    private fun switchToFainted() {
        behaviorJob?.cancel()
        behaviorJob = null
        resetTransientVisualEffects()
        updateState { it.copy(behavior = PetBehavior.FAINTED) }
    }

    private fun switchToEvent() {
        val event = pendingEvent
        if (event == null) {
            // Shouldn't happen (reconcile only picks EVENT when pendingEvent != null), but stay safe.
            launchBehaviorJob { randomLoop() }
            return
        }
        val speechText = pendingSpeechText
        launchBehaviorJob {
            runEvent(event, speechText)
            pendingEvent = null
            pendingSpeechText = null
            lastTrack = null // force a fresh reconcile once the event finishes
            reconcile(force = true)
        }
    }

    // ------------------------------------------------------------------
    // Event dispatch
    // ------------------------------------------------------------------

    private suspend fun runEvent(event: PetEvent, speechText: String?) {
        when (event) {
            is PetEvent.Scheduled -> when (event.period) {
                GreetingPeriod.MORNING -> {
                    interactionRepo.recordShown(PetBehavior.GREETING)
                    runGreeting(speechText)
                }
                GreetingPeriod.LUNCH -> {
                    interactionRepo.recordShown(PetBehavior.EATING)
                    runEating(speechText)
                }
                GreetingPeriod.NIGHT -> {
                    interactionRepo.recordShown(PetBehavior.NIGHT_SLEEPING)
                    runGoodnightMessage(speechText)
                }
            }
            PetEvent.MessageNotification -> runNotificationReaction()
            is PetEvent.UserRequestedAction -> {
                // Reserved: the overlay is FLAG_NOT_TOUCHABLE (unchanged from v1/v2),
                // so nothing currently produces this event. No-op by design.
            }
        }
    }

    // ------------------------------------------------------------------
    // Random weighted scheduler (v2 behaviors + v3's ball/skipping)
    // ------------------------------------------------------------------

    private suspend fun randomLoop() {
        while (coroutineContext.isActive) {
            val weights = computeWeights()
            val total = BehaviorWeights.totalWeight(weights)
            val roll = Random.nextInt(total)
            val reroll = Random.nextInt(total)
            val next = BehaviorWeights.pickNext(roll, reroll, lastRandomSpecial, weights)
            lastRandomSpecial = if (next.isSpecial()) next else null
            interactionRepo.recordShown(next)

            when (next) {
                PetBehavior.IDLE -> runIdle(randomMs(1500, 3500))
                PetBehavior.WALKING -> runWalking(randomMs(3000, 8000))
                PetBehavior.BLINKING -> runBlinking()
                PetBehavior.LOOKING_AROUND -> runLookingAround()
                PetBehavior.HOPPING -> runHopping()
                PetBehavior.SHOWING_HEART -> runShowingHeart()
                PetBehavior.SLEEPING -> runSleeping(randomMs(5000, 10000))
                PetBehavior.PLAYING_BALL -> runPlayingBall()
                PetBehavior.SKIPPING -> runSkipping()
                else -> delay(500L) // defensively unreachable: these aren't in BehaviorWeights
            }
        }
    }

    private fun computeWeights(): List<Pair<PetBehavior, Int>> {
        if (!contextAwareEnabledProvider()) return BehaviorWeights.BASE_WEIGHTS
        val context = ContextProvider.buildContext(LocalDateTime.now(), batteryPercent, isCharging)
        val engagement = interactionRepo.currentMultipliers(BehaviorWeights.BASE_WEIGHTS.map { it.first })
        val bias = ContextualBias.multipliersFor(context)
        val combined = ContextualBias.combine(engagement, bias)
        return BehaviorWeights.applyMultipliers(BehaviorWeights.BASE_WEIGHTS, combined)
    }

    private fun randomMs(minMs: Long, maxMs: Long): Long = Random.nextLong(minMs, maxMs)

    // ------------------------------------------------------------------
    // v2 behaviors (unchanged logic, ported onto OverlayWindowController)
    // ------------------------------------------------------------------

    private suspend fun runIdle(durationMs: Long) {
        updateState { it.copy(behavior = PetBehavior.IDLE, translationY = 0f, rotationZ = 0f) }
        val start = System.currentTimeMillis()
        while (coroutineContext.isActive && System.currentTimeMillis() - start < durationMs) {
            val elapsed = System.currentTimeMillis() - start
            val phase = (elapsed % 1800L) / 1800f * (2 * PI).toFloat()
            // 4% scale oscillation (up from a barely-visible 2%) so idle
            // reads as "breathing" rather than "frozen" on a small sprite.
            val breathe = 1f + 0.04f * ((sin(phase) + 1f) / 2f)
            updateState { it.copy(scaleX = 1f, scaleY = breathe) }
            delay(FRAME_DELAY_MS)
        }
    }

    private suspend fun runWalking(durationMs: Long) {
        updateState { it.copy(behavior = PetBehavior.WALKING) }
        val density = densityProvider()
        val maxSpeedPxPerSec = WALK_MAX_SPEED_DP_PER_SEC * density
        var velocityPxPerSec = 0f
        var direction = if (uiState.value.facingRight) 1f else -1f

        val start = System.currentTimeMillis()
        var lastFrame = start
        var bobPhase = 0f

        while (coroutineContext.isActive && System.currentTimeMillis() - start < durationMs) {
            val now = System.currentTimeMillis()
            val dtMs = (now - lastFrame).coerceAtLeast(1L)
            lastFrame = now
            val dt = dtMs / 1000f

            if (overlay.isAttached) {
                val screenWidth = overlay.currentScreenWidthPx()
                val maximumX = OverlayMath.maximumX(screenWidth, overlay.overlayWidthPx)

                val targetVelocity = direction * maxSpeedPxPerSec
                velocityPxPerSec += (targetVelocity - velocityPxPerSec) * 0.15f
                var nextX = overlayXPx + (velocityPxPerSec * dt).toInt()

                if (nextX >= maximumX) {
                    overlayXPx = maximumX
                    overlay.moveTo(overlayXPx)
                    playEdgeReaction()
                    lastFrame = System.currentTimeMillis()
                    direction = -1f
                    velocityPxPerSec = 0f
                } else if (nextX <= 0) {
                    overlayXPx = 0
                    overlay.moveTo(overlayXPx)
                    playEdgeReaction()
                    lastFrame = System.currentTimeMillis()
                    direction = 1f
                    velocityPxPerSec = 0f
                } else {
                    overlayXPx = nextX
                    overlay.moveTo(overlayXPx)
                }

                bobPhase += dt * 6f
                val bobPx = sin(bobPhase) * 4f * density
                val rot = sin(bobPhase * 0.5f) * 2f
                updateState {
                    it.copy(
                        facingRight = direction > 0,
                        translationY = bobPx,
                        rotationZ = rot,
                        scaleX = 1f,
                        scaleY = 1f
                    )
                }
            }
            delay(FRAME_DELAY_MS)
        }
    }

    private suspend fun playEdgeReaction() {
        updateState { it.copy(translationY = 0f, rotationZ = 0f) }
        delay(160)
        updateState { it.copy(scaleY = 0.9f) }
        delay(90)
        updateState { it.copy(scaleY = 1f) }
        delay(90)
    }

    private suspend fun runBlinking() {
        updateState { it.copy(behavior = PetBehavior.BLINKING) }
        delay(Random.nextLong(100L, 180L))
        updateState { it.copy(behavior = PetBehavior.IDLE) }
        if (Random.nextInt(100) < 30) {
            delay(140)
            updateState { it.copy(behavior = PetBehavior.BLINKING) }
            delay(Random.nextLong(100L, 180L))
            updateState { it.copy(behavior = PetBehavior.IDLE) }
        }
    }

    private suspend fun runHopping(hopCountOverride: Int? = null) {
        updateState { it.copy(behavior = PetBehavior.HOPPING) }
        val density = densityProvider()
        val hopCount = hopCountOverride ?: if (Random.nextBoolean()) 1 else 2
        repeat(hopCount) { hopIndex ->
            val hopHeightPx = Random.nextInt(20, 32) * density
            val hopDurationMs = Random.nextLong(500L, 900L)
            val steps = max(4, (hopDurationMs / FRAME_DELAY_MS).toInt())
            for (i in 0 until steps) {
                val t = i / steps.toFloat()
                val y = -hopHeightPx * sin((t * PI).toFloat())
                updateState { it.copy(translationY = y) }
                delay(FRAME_DELAY_MS)
            }
            updateState { it.copy(translationY = 0f) }
            if (hopIndex < hopCount - 1) delay(120)
        }
    }

    private suspend fun runLookingAround() {
        updateState { it.copy(behavior = PetBehavior.LOOKING_AROUND) }
        val original = uiState.value.facingRight
        updateState { it.copy(facingRight = false) }
        delay(Random.nextLong(400L, 700L))
        updateState { it.copy(facingRight = true) }
        delay(Random.nextLong(400L, 700L))
        updateState { it.copy(facingRight = original) }
    }

    private suspend fun runShowingHeart() {
        updateState {
            it.copy(behavior = PetBehavior.SHOWING_HEART, heartVisible = true, heartOffsetY = 0f, heartAlpha = 1f)
        }
        val density = densityProvider()
        val durationMs = Random.nextLong(1000L, 1500L)
        val steps = max(4, (durationMs / FRAME_DELAY_MS).toInt())
        val maxOffsetPx = Random.nextInt(20, 40) * density
        for (i in 0 until steps) {
            val t = i / steps.toFloat()
            updateState { it.copy(heartOffsetY = -maxOffsetPx * t, heartAlpha = 1f - t) }
            delay(FRAME_DELAY_MS)
        }
    }

    private suspend fun runSleeping(durationMs: Long) {
        val density = densityProvider()
        updateState { it.copy(behavior = PetBehavior.SLEEPING, rotationZ = 6f, translationY = 4f * density) }
        val frames = listOf("Z", "Zz", "Zzz")
        val start = System.currentTimeMillis()
        var index = 0
        while (coroutineContext.isActive && System.currentTimeMillis() - start < durationMs) {
            val text = frames[index % frames.size]
            index++
            updateState { it.copy(sleepText = text, sleepTextAlpha = 0f) }
            fadeAlpha(1f, 200) { a -> updateState { it.copy(sleepTextAlpha = a) } }
            delay(500)
            fadeAlpha(0f, 200) { a -> updateState { it.copy(sleepTextAlpha = a) } }
            delay(150)
        }
    }

    private suspend fun chargingLoop() {
        updateState { it.copy(behavior = PetBehavior.CHARGING) }
        val density = densityProvider()
        val hopHeightPx = 10f * density
        val steps = 20
        while (coroutineContext.isActive) {
            for (i in 0 until steps) {
                val t = i / steps.toFloat()
                updateState { it.copy(translationY = -hopHeightPx * sin((t * PI).toFloat())) }
                delay(FRAME_DELAY_MS)
            }
            updateState { it.copy(translationY = 0f) }
            delay(500)
        }
    }

    private suspend fun runNotificationReaction() {
        updateState { it.copy(behavior = PetBehavior.NOTIFICATION_REACTION) }
        val density = densityProvider()
        val hopHeightPx = 18f * density
        val hopSteps = 14
        for (i in 0 until hopSteps) {
            val t = i / hopSteps.toFloat()
            updateState { it.copy(translationY = -hopHeightPx * sin((t * PI).toFloat())) }
            delay(FRAME_DELAY_MS)
        }
        updateState { it.copy(translationY = 0f) }

        updateState { it.copy(chatBubbleVisible = true, chatBubbleAlpha = 0f) }
        fadeAlpha(1f, 180) { a -> updateState { it.copy(chatBubbleAlpha = a) } }
        delay(Random.nextLong(4000L, 6000L))
        fadeAlpha(0f, 220) { a -> updateState { it.copy(chatBubbleAlpha = a) } }
    }

    // ------------------------------------------------------------------
    // v3 behaviors
    // ------------------------------------------------------------------

    private suspend fun runPlayingBall() {
        updateState { it.copy(behavior = PetBehavior.PLAYING_BALL, ballVisible = true, ballProgress = 0f) }
        val durationMs = Random.nextLong(2000L, 4000L)
        val steps = max(8, (durationMs / FRAME_DELAY_MS).toInt())
        for (i in 0 until steps) {
            val t = i / steps.toFloat()
            updateState { it.copy(ballProgress = t) }
            delay(FRAME_DELAY_MS)
        }
    }

    private suspend fun runSkipping() {
        updateState { it.copy(behavior = PetBehavior.SKIPPING) }
        val density = densityProvider()
        val hopCount = Random.nextInt(2, 5) // 2..4 small hops
        repeat(hopCount) {
            val hopHeightPx = 14f * density
            val steps = 12
            for (i in 0 until steps) {
                val t = i / steps.toFloat()
                val y = -hopHeightPx * sin((t * PI).toFloat())
                val rot = if (t < 0.5f) -6f else 6f
                updateState { it.copy(translationY = y, rotationZ = rot) }
                delay(FRAME_DELAY_MS)
            }
            delay(60)
        }
    }

    private suspend fun runEating(speechText: String?) {
        updateState { it.copy(behavior = PetBehavior.EATING, foodVisible = true, foodProgress = 0f) }
        val density = densityProvider()
        val chewDurationMs = Random.nextLong(9000L, 16000L)
        val start = System.currentTimeMillis()
        while (coroutineContext.isActive && System.currentTimeMillis() - start < chewDurationMs) {
            val elapsed = System.currentTimeMillis() - start
            val phase = (elapsed % 600L) / 600f * (2 * PI).toFloat()
            val bob = sin(phase) * 3f * density
            val chewScale = 1f + 0.03f * ((sin(phase) + 1f) / 2f)
            updateState {
                it.copy(
                    translationY = bob,
                    scaleY = chewScale,
                    foodProgress = (elapsed.toFloat() / chewDurationMs).coerceIn(0f, 1f)
                )
            }
            delay(FRAME_DELAY_MS)
        }
        updateState { it.copy(translationY = 0f, scaleY = 1f) }
        if (!speechText.isNullOrBlank()) {
            showSpeech(speechText, holdMs = 3000L)
        }
    }

    private suspend fun runGreeting(speechText: String?) {
        updateState { it.copy(behavior = PetBehavior.GREETING) }
        runHopping(hopCountOverride = 1)
        updateState { it.copy(behavior = PetBehavior.GREETING) }
        if (!speechText.isNullOrBlank()) {
            showSpeech(speechText, holdMs = 3500L)
        }
    }

    private suspend fun runGoodnightMessage(speechText: String?) {
        updateState { it.copy(behavior = PetBehavior.GREETING) }
        if (!speechText.isNullOrBlank()) {
            showSpeech(speechText, holdMs = 3000L)
        } else {
            delay(200)
        }
        // NIGHT_SLEEPING visuals take over automatically: isNightWindow is
        // already true by the time this fires, so the reconcile() call in
        // switchToEvent() will resolve straight to the NIGHT_SLEEP track.
    }

    /** Continuous track — runs for as long as [isNightWindow] stays true; cancelled by reconcile() otherwise. */
    private suspend fun nightSleepLoop() {
        val density = densityProvider()
        updateState { it.copy(behavior = PetBehavior.NIGHT_SLEEPING, rotationZ = 8f, translationY = 6f * density) }
        val frames = listOf("Z", "Zz", "Zzz")
        var index = 0
        while (coroutineContext.isActive) {
            val text = frames[index % frames.size]
            index++
            updateState { it.copy(sleepText = text, sleepTextAlpha = 0f) }
            fadeAlpha(1f, 250) { a -> updateState { it.copy(sleepTextAlpha = a) } }
            delay(700)
            fadeAlpha(0f, 250) { a -> updateState { it.copy(sleepTextAlpha = a) } }
            delay(300)
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private suspend fun showSpeech(text: String, holdMs: Long) {
        updateState { it.copy(speechText = text, speechAlpha = 0f) }
        fadeAlpha(1f, 200) { a -> updateState { it.copy(speechAlpha = a) } }
        delay(holdMs)
        fadeAlpha(0f, 200) { a -> updateState { it.copy(speechAlpha = a) } }
        updateState { it.copy(speechText = null, speechAlpha = 0f) }
    }

    private suspend fun fadeAlpha(target: Float, durationMs: Long, apply: (Float) -> Unit) {
        val steps = max(1, (durationMs / FRAME_DELAY_MS).toInt())
        val start = if (target == 1f) 0f else 1f
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            apply(start + (target - start) * t)
            delay(FRAME_DELAY_MS)
        }
    }

    private fun resetTransientVisualEffects() {
        updateState {
            it.copy(
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                rotationZ = 0f,
                heartVisible = false,
                heartOffsetY = 0f,
                heartAlpha = 0f,
                sleepText = null,
                sleepTextAlpha = 0f,
                chatBubbleVisible = false,
                chatBubbleAlpha = 0f,
                speechText = null,
                speechAlpha = 0f,
                ballVisible = false,
                ballProgress = 0f,
                foodVisible = false,
                foodProgress = 0f
            )
        }
    }

    private fun updateState(transform: (PetUiState) -> PetUiState) {
        if (!isControllerActive) return
        _uiState.update(transform)
    }

    companion object {
        private const val LOW_BATTERY_THRESHOLD = 30
        private const val FRAME_DELAY_MS = 16L
        private const val WALK_MAX_SPEED_DP_PER_SEC = 70f
    }
}
