package com.hermes.bot.feature.chat.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.hermes.bot.design.DarkWarning
import com.hermes.bot.design.HermesTheme
import com.hermes.bot.design.LightWarning

/** The halo follows the bolt itself; there is no enclosing badge or circle. */
@Composable
internal fun YoloToggle(mode: Boolean?, enabled: Boolean, onToggle: () -> Unit, pending: Boolean = false) {
    val tint = if (mode == true) {
        if (HermesTheme.extendedColors.isDark) DarkWarning else LightWarning
    } else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(
        onClick = onToggle,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = "Toggle YOLO for this chat"
            stateDescription = if (pending) "YOLO ${if (mode == true) "ON" else if (mode == false) "OFF" else "unknown"}. Unconfirmed; checking host" else when (mode) {
                true -> "YOLO ON. Approval prompts bypassed"
                false -> "YOLO OFF"
                null -> "YOLO status unavailable"
            }
        }
    ) {
        Canvas(Modifier.size(24.dp)) {
            val bolt = Path().apply {
                moveTo(size.width * .65f, size.height * .08f)
                lineTo(size.width * .20f, size.height * .55f)
                lineTo(size.width * .45f, size.height * .55f)
                lineTo(size.width * .35f, size.height * .92f)
                lineTo(size.width * .80f, size.height * .40f)
                lineTo(size.width * .55f, size.height * .40f)
                close()
            }
            if (mode == true) {
                for (width in listOf(8f, 5f, 2f)) {
                    drawPath(bolt, tint.copy(alpha = .07f), style = Stroke(width.dp.toPx()))
                }
            }
            drawPath(bolt, tint.copy(alpha = if (mode == null) .38f else 1f))
        }
    }
}
