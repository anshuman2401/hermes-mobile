package io.github.anshuman2401.hermesmobile.feature.chat.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Status dot per PRD v2 § 1 & § 4:
 * Pulsing green dot while running, solid green dot when completed.
 * Explicitly NO red/failed dot in v1 (host protocol carries no success/failure flag).
 */
@Composable
fun StatusDot(
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp
) {
    val dotColor = Color(0xFF4CAF50)
    val description = if (isRunning) "Running" else "Completed"

    if (isRunning) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        )
        Box(
            modifier = modifier
                .size(size)
                .graphicsLayer { this.alpha = alpha }
                .clip(CircleShape)
                .background(dotColor)
                .semantics { contentDescription = description }
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(dotColor)
                .semantics { contentDescription = description }
        )
    }
}
