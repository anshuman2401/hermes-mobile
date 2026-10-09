package com.hermes.bot.feature.chat.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Original capacity gauge; the parent button supplies the accessible label. */
@Composable
internal fun ContextUsageIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        drawArc(tint, startAngle = 150f, sweepAngle = 240f, useCenter = false,
            topLeft = Offset(size.width * .1f, size.height * .1f),
            size = Size(size.width * .8f, size.height * .8f),
            style = Stroke(stroke, cap = StrokeCap.Round))
        val center = Offset(size.width * .5f, size.height * .5f)
        drawLine(tint, center, Offset(size.width * .72f, size.height * .28f),
            strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(tint, radius = stroke, center = center)
    }
}
