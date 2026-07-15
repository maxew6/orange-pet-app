package com.orangepet.app.behavior

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orangepet.app.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private const val PET_IMAGE_SIZE_DP = 128

/**
 * Renders one immutable [PetUiState] frame. Kept as a single composable
 * function per the doc's "Compose renders the current immutable state" —
 * this file has no behavior/timing logic of its own, only presentation.
 */
@Composable
fun PetContent(state: PetUiState) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Bed layer, behind the pet, only during scheduled night sleep.
        if (state.behavior == PetBehavior.NIGHT_SLEEPING) {
            Image(
                painter = painterResource(id = R.drawable.pet_bed),
                contentDescription = null,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }

        // Skipping rope, only while actively skipping.
        if (state.behavior == PetBehavior.SKIPPING) {
            Image(
                painter = painterResource(id = R.drawable.pet_skipping_rope),
                contentDescription = null,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // Food bowl, only while eating.
        if (state.foodVisible) {
            Image(
                painter = painterResource(id = R.drawable.pet_food_bowl),
                contentDescription = null,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }

        // Ball, animated in a small bouncing arc while playing.
        if (state.ballVisible) {
            val density = LocalDensity.current.density
            val sweepPx = 34f * density
            val ballOffsetX = (state.ballProgress - 0.5f) * sweepPx
            val ballOffsetY = -abs(sin(state.ballProgress * PI.toFloat() * 3f)) * 18f * density
            Image(
                painter = painterResource(id = R.drawable.pet_ball),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .graphicsLayer {
                        translationX = ballOffsetX
                        translationY = ballOffsetY
                    }
            )
        }

        // Speech bubble (notification reaction OR AI/offline greeting) — mutually
        // exclusive in practice since both occupy TopCenter, notification reaction wins if both somehow overlap.
        if (state.chatBubbleVisible) {
            SpeechBubble(text = stringResource(R.string.notification_reaction_bubble), bubbleAlpha = state.chatBubbleAlpha)
        } else if (!state.speechText.isNullOrBlank()) {
            SpeechBubble(text = state.speechText, bubbleAlpha = state.speechAlpha)
        } else if (state.heartVisible) {
            Text(
                text = "\u2665",
                color = Color(0xFFFF4D6D),
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = state.heartAlpha
                        translationY = state.heartOffsetY
                    }
            )
        } else if (state.sleepText != null) {
            Text(
                text = state.sleepText,
                color = Color(0xFF5B4636),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = state.sleepTextAlpha }
            )
        }

        val drawableRes = when {
            state.isLowBattery && !state.isCharging -> R.drawable.orange_pet_faint
            state.isCharging -> R.drawable.orange_pet_happy
            state.behavior == PetBehavior.NIGHT_SLEEPING -> R.drawable.orange_pet_sleep
            state.behavior == PetBehavior.BLINKING -> R.drawable.orange_pet_blink
            else -> R.drawable.orange_pet
        }

        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(PET_IMAGE_SIZE_DP.dp)
                .graphicsLayer {
                    scaleX = (if (state.facingRight) 1f else -1f) * state.scaleX
                    scaleY = state.scaleY
                    translationY = state.translationY
                    rotationZ = state.rotationZ
                }
        )
    }
}

@Composable
private fun SpeechBubble(text: String, bubbleAlpha: Float) {
    Surface(
        color = Color(0xFFFFFDF6),
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 3.dp,
        modifier = Modifier
            .graphicsLayer { alpha = bubbleAlpha }
    ) {
        Text(
            text = text,
            color = Color(0xFF4A3B2A),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}
