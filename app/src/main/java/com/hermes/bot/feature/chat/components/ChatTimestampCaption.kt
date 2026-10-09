package com.hermes.bot.feature.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.bot.feature.chat.ChatDaySeparatorPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CHAT-009 (frozen plan v1, FZ-1/FZ-7): the per-bubble time caption ("h:mm a",
 * Locale.ENGLISH — HOME-S2 precedent), rendered as a dim line under the bubble
 * (wrap-content width so the bubble Column's End/Start alignment applies,
 * with a 2dp top gap — CHAT-T7 S-3). Pure formatting lives in
 * [ChatDaySeparatorPolicy.timeCaption].
 * An absent timestamp (the 0L sentinel from FZ-7) renders NOTHING — never a
 * fabricated time or divider. The zone is captured once per (timestamp)
 * composition key, per FZ-5.
 * When a real timestamp is displayed, renders a full-width 0.5.dp horizontal
 * divider beneath it before the next transcript item.
 */
@Composable
internal fun ChatTimestampCaption(
    timestampMillis: Long,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null
) {
    if (timestampMillis <= 0L) return
    val zone = remember(timestampMillis) { ZoneId.systemDefault() }
    val caption = remember(timestampMillis, zone) {
        ChatDaySeparatorPolicy.timeCaption(timestampMillis, zone)
    }
    if (caption != null) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingContent?.invoke()
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}
