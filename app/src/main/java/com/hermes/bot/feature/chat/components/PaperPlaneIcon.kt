package com.hermes.bot.feature.chat.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Original outlined glyph, matching the approved mockup without external assets. */
@Composable
internal fun PaperPlaneIcon(tint: Color, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * .13f, size.height * .42f)
            lineTo(size.width * .87f, size.height * .13f)
            lineTo(size.width * .58f, size.height * .87f)
            lineTo(size.width * .44f, size.height * .57f)
            close()
            moveTo(size.width * .44f, size.height * .57f)
            lineTo(size.width * .87f, size.height * .13f)
        }
        scale(scaleX = if (rtl) -1f else 1f, scaleY = 1f) {
            drawPath(path, tint, style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
