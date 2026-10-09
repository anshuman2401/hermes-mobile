package com.hermes.bot.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Brand provenance record for Relay Orbit icon.
 * Required by BLD-004 deliverable 4 and specs/architecture/design/theme-and-icon-brief.md.
 */
object RelayOrbitProvenance {
    const val ICON_NAME = "Relay Orbit"
    const val CREATOR = "Hermes Bot Android Team (BLD-004)"
    const val CONCEPT = "Two unequal rounded message nodes joined by an orbital relay path"
    const val PROVENANCE_NOTE =
        "Original geometric vector/Compose asset created for Hermes Bot Android MVP. " +
        "Contains 0% copied Hermes Go, third-party mascot, or external brand assets."
}

/**
 * Original project-owned Relay Orbit icon rendered in Compose.
 * Displays two unequal rounded message nodes joined by a soft orbital path.
 */
@Composable
fun RelayOrbitIcon(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    contentDescriptionText: String? = "Relay Orbit product icon",
    primaryNodeColor: Color = MaterialTheme.colorScheme.primary,
    secondaryNodeColor: Color = MaterialTheme.colorScheme.secondary,
    orbitTrackColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
) {
    Canvas(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescriptionText != null) {
                    Modifier.semantics { contentDescription = contentDescriptionText }
                } else Modifier
            )
    ) {
        val w = this.size.width
        val h = this.size.height

        // Outer subtle orbital track
        val outerTrackPath = Path().apply {
            moveTo(w * 0.30f, h * 0.63f)
            cubicTo(
                w * 0.22f, h * 0.48f,
                w * 0.35f, h * 0.30f,
                w * 0.54f, h * 0.26f
            )
            cubicTo(
                w * 0.70f, h * 0.22f,
                w * 0.81f, h * 0.37f,
                w * 0.76f, h * 0.54f
            )
            cubicTo(
                w * 0.70f, h * 0.70f,
                w * 0.46f, h * 0.78f,
                w * 0.31f, h * 0.69f
            )
        }
        drawPath(
            path = outerTrackPath,
            color = orbitTrackColor,
            style = Stroke(width = w * 0.035f, cap = StrokeCap.Round)
        )

        // Active relay stream connecting the two nodes
        val relayStreamPath = Path().apply {
            moveTo(w * 0.38f, h * 0.58f)
            cubicTo(
                w * 0.44f, h * 0.43f,
                w * 0.56f, h * 0.35f,
                w * 0.62f, h * 0.39f
            )
            cubicTo(
                w * 0.69f, h * 0.43f,
                w * 0.67f, h * 0.52f,
                w * 0.56f, h * 0.60f
            )
            cubicTo(
                w * 0.44f, h * 0.69f,
                w * 0.35f, h * 0.65f,
                w * 0.38f, h * 0.58f
            )
        }
        drawPath(
            path = relayStreamPath,
            color = primaryNodeColor,
            style = Stroke(width = w * 0.05f, cap = StrokeCap.Round)
        )

        // Larger host message node (top-right)
        val hostCenter = Offset(w * 0.61f, h * 0.40f)
        val hostRadius = w * 0.15f
        drawCircle(
            color = primaryNodeColor,
            radius = hostRadius,
            center = hostCenter
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = hostRadius * 0.45f,
            center = hostCenter
        )

        // Smaller client message node (bottom-left)
        val clientCenter = Offset(w * 0.38f, h * 0.58f)
        val clientRadius = w * 0.10f
        drawCircle(
            color = secondaryNodeColor,
            radius = clientRadius,
            center = clientCenter
        )
        drawCircle(
            color = Color.White,
            radius = clientRadius * 0.35f,
            center = clientCenter
        )
    }
}
