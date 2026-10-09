package com.hermes.bot.design

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal object AtelierShapes {
    val panel = RoundedCornerShape(16.dp)
    val field = RoundedCornerShape(14.dp)
    val action = RoundedCornerShape(25.dp)
}

@Composable
internal fun AtelierAppChrome(title: String, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RelayOrbitIcon(size = 18.dp)
        Text(title, Modifier.weight(1f).padding(start = 7.dp, end = 8.dp),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** Profile controls share the flexible space; trailing actions stay anchored to the edge. */
@Composable
internal fun AtelierSessionChrome(
    title: String,
    isConnected: Boolean,
    trailingActions: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            RelayOrbitIcon(size = 18.dp)
            Text(title, Modifier.weight(1f, fill = false).padding(start = 7.dp, end = 8.dp),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            ConnectionStatusDot(isConnected)
            actions()
        }
        trailingActions()
    }
}

@Composable
internal fun AtelierSectionLabel(title: String, modifier: Modifier = Modifier, topPadding: Dp = 16.dp) {
    Text(title.uppercase(Locale.ROOT), modifier.padding(start = 4.dp, top = topPadding, bottom = 8.dp)
        .semantics { heading() },
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun AtelierDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** A segment of a lazy group: only the first/last rows own the outer corners. */
@Composable
internal fun AtelierGroupRow(first: Boolean, last: Boolean, modifier: Modifier = Modifier,
    content: @Composable () -> Unit) {
    val radius = 16.dp
    val border = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(topStart = if (first) radius else 0.dp,
        topEnd = if (first) radius else 0.dp, bottomStart = if (last) radius else 0.dp,
        bottomEnd = if (last) radius else 0.dp)
    Surface(modifier.fillMaxWidth().drawWithContent {
        drawContent()
        val r = radius.toPx()
        val stroke = 1.dp.toPx()
        val inset = stroke / 2
        val w = size.width - inset
        val h = size.height - inset
        drawLine(border, Offset(inset, if (first) r else 0f),
            Offset(inset, if (last) h - r else size.height), stroke)
        drawLine(border, Offset(w, if (first) r else 0f),
            Offset(w, if (last) h - r else size.height), stroke)
        if (first) {
            drawLine(border, Offset(r, inset), Offset(w - r, inset), stroke)
            drawArc(border, 180f, 90f, false, Offset(inset, inset), Size(r * 2, r * 2), style = Stroke(stroke))
            drawArc(border, 270f, 90f, false, Offset(w - r * 2, inset), Size(r * 2, r * 2), style = Stroke(stroke))
        }
        if (last) {
            drawLine(border, Offset(r, h), Offset(w - r, h), stroke)
            drawArc(border, 90f, 90f, false, Offset(inset, h - r * 2), Size(r * 2, r * 2), style = Stroke(stroke))
            drawArc(border, 0f, 90f, false, Offset(w - r * 2, h - r * 2), Size(r * 2, r * 2), style = Stroke(stroke))
        }
    }, shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) { content() }
}

@Composable
internal fun AtelierPrimaryAction(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = AtelierShapes.action,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        content = content)
}
