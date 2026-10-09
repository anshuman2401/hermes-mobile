package com.hermes.bot.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
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
import com.hermes.bot.feature.chat.GenericGatewayActivity

/**
 * Safe fallback card for unknown future gateway events per ARCH-001 (chat-rendering.md).
 * Invariants:
 * - Treats unknown event data as inert bounded diagnostic text (BLD-006 S8 /
 *   D-09: budgets enforced during traversal upstream; collapsed row shows the
 *   event type and the short approved summary only).
 * - No client execution surfaces and no payload-derived actions (CON-007).
 */

@Composable
fun GenericGatewayCard(
    activity: GenericGatewayActivity,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                        contentDescription = "Hermes activity notice",
                        // CHG-008 repair round 1 (P1-B): `colorScheme.outline` is
                        // now the DECORATIVE hairline #454B60 (1.97:1 on this
                        // card). An icon is a graphical object and needs >= 3:1,
                        // so the tint matches the onSurfaceVariant text it sits
                        // beside (5.50:1 dark / 6.78:1 light).
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Hermes activity: ${activity.eventType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics {
                            contentDescription = if (activity.isExpanded) {
                                "Collapse activity details for ${activity.eventType}"
                            } else {
                                "Expand activity details for ${activity.eventType}"
                            }
                        }
                ) {
                    Icon(
                        imageVector = if (activity.isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = activity.isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Summary: ${activity.summary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!activity.payloadPreview.isNullOrBlank()) {
                        Text(
                            text = activity.payloadPreview,
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
}
