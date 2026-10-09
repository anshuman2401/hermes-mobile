package com.hermes.bot.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.bot.design.HermesShapeTokens
import com.hermes.bot.design.HermesTheme
import com.hermes.bot.feature.chat.ReasoningDisplayKind
import com.hermes.bot.feature.chat.ReasoningEntry

/**
 * F007-S2: collapsed-by-default dim block for live reasoning text between
 * assistant segments. Mirrors the GenericGatewayCard collapsed-card pattern.
 * Invariants:
 * - The text is inert, already-redacted/bounded upstream ([ReasoningPolicy]
 *   32 KiB accumulation; adapter-side redaction) — plain Text only, no
 *   WebView/HTML interpretation, no links or actions from payload (CON-007).
 * - The expand affordance keeps a >=48dp target and an accessible
 *   content description, mirroring the existing collapsed-card pattern.
 * - Dim presentation is opacity-based (non-color-only semantics: the label
 *   text and content description stay readable by screen readers).
 *
 * CHG-008 follow-up (theme-refresh dark regressions): the blanket
 * `Modifier.alpha(0.7f)` on this Column was removed. Every child here carries
 * meaning (the info icon even carries a contentDescription), and alpha dilutes
 * ALL of it: onSurfaceVariant over surfaceContainer composites to 3.40:1 dark
 * / 3.38:1 light under 0.7 alpha. The BINDING failure is the 4.5:1 TEXT floor
 * (the label and expanded body text sit under the alpha): 3.40 dark and 3.38
 * light both fail it in BOTH themes. Diluted light would still clear the 3:1
 * icon floor (3.38) — it is the dark ICON that fails that floor via its
 * original `outline` tint (1.97:1 on the card, below 3:1), which is why the
 * icon shares the onSurfaceVariant role of the text beside it. With the alpha
 * removed the card passes on its existing roles (onSurfaceVariant: 5.50:1
 * dark / 6.78:1 light on the card). The "dim" read is carried by the
 * collapsed presentation and the muted onSurfaceVariant roles, not by opacity.
 */
@Composable
fun ReasoningCard(
    entry: ReasoningEntry,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = when (entry.kind) {
        ReasoningDisplayKind.REASONING -> "Reasoning"
        ReasoningDisplayKind.THINKING -> "Thinking"
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HermesShapeTokens.cardCornerSmall))
            .border(1.dp, HermesTheme.extendedColors.glassOutline, RoundedCornerShape(HermesShapeTokens.cardCornerSmall)),
        color = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(HermesShapeTokens.cardCornerSmall)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Live reasoning from Hermes",
                        // CHG-008 follow-up: `outline` is the decorative dark
                        // hairline #454B60 (1.97:1 on card — below the 3:1
                        // graphical-object floor, D7 ruling). Icons are
                        // meaningful content, so they share the onSurfaceVariant
                        // role of the text beside them (same fix as
                        // GenericGatewayCard, repair round 1).
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics {
                            contentDescription = if (entry.isExpanded) {
                                "Collapse $label"
                            } else {
                                "Expand $label"
                            }
                        }
                ) {
                    Icon(
                        imageVector = if (entry.isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = entry.isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
