package io.github.anshuman2401.hermesmobile.feature.chat.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anshuman2401.hermesmobile.design.HermesShapeTokens
import io.github.anshuman2401.hermesmobile.design.HermesTheme
import io.github.anshuman2401.hermesmobile.feature.chat.BoundedPayloadMapper
import io.github.anshuman2401.hermesmobile.feature.chat.ToolActivity
import io.github.anshuman2401.hermesmobile.feature.chat.ToolStatus
import java.util.Locale

/**
 * BLD-006 S8 (SEC-005): user-initiated tool-detail copy writes sensitive content
 * metadata where the Android API supports it (ClipDescription.EXTRA_IS_SENSITIVE,
 * API 33+; silently absent below), per ASSUMPTION-007.
 */
internal fun copySensitiveText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, text)
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}

/**
 * Tool activity card per ARCH-001 (chat-rendering.md § Tool/activity cards).
 * Features:
 * - Collapsed by default
 * - Keyed to toolId
 * - Shows status: PREPARING (tool.generating), RUNNING (tool.start/progress), COMPLETED (tool.complete)
 * - Bounded, redacted arguments/results (BLD-006 S8 / D-09: budgets enforced
 *   during traversal upstream; collapsed rows show name/type, status, and the
 *   short approved summary only)
 * - Expanded details are inert text (SEC-002: raw payload maps never reach
 *   Compose; no payload-derived buttons/links/RPCs/intents)
 * - User-initiated copy carries the sensitivity warning and sensitive clipboard
 *   metadata (SEC-005)
 * - Minimum 48dp touch targets
 */
@Composable
fun ToolActivityCard(
    activity: ToolActivity,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isDetailsCopied by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HermesShapeTokens.cardCornerSmall))
            .border(1.dp, HermesTheme.extendedColors.glassOutline, RoundedCornerShape(HermesShapeTokens.cardCornerSmall)),
        color = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(HermesShapeTokens.cardCornerSmall)
    ) {
        Column {
            // Header Row: Status icon + Name + Duration + Expand/Collapse Button
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
                    when (activity.status) {
                        ToolStatus.PREPARING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = "Preparing ${activity.name}...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        ToolStatus.RUNNING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                // CHG-008 repair round 1 (P1-A): the RUNNING
                                // indicator is a graphical object on the
                                // #1E202B card header. The primary fill token
                                // #5352ED is 2.93:1 there, below the 3:1 floor.
                                color = HermesTheme.extendedColors.primaryText
                            )
                            Column {
                                Text(
                                    text = activity.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!activity.context.isNullOrBlank()) {
                                    Text(
                                        text = activity.context,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        ToolStatus.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Tool completed",
                                // CHG-008 repair round 1 (P1-A): graphical object
                                // on the #1E202B card header.
                                tint = HermesTheme.extendedColors.primaryText,
                                modifier = Modifier.size(16.dp)
                            )
                            Column {
                                Text(
                                    text = activity.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!activity.context.isNullOrBlank()) {
                                    Text(
                                        text = activity.context,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (activity.durationSeconds != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = String.format(Locale.ROOT, "%.1fs", activity.durationSeconds),
                                        style = MaterialTheme.typography.labelSmall,
                                        // CHG-008 repair round 2 (P2): the chip's
                                        // background is `surfaceContainerHighest`
                                        // (wired product token since PR #74).
                                        // The refreshed dark onSurfaceVariant
                                        // #8E92A4 is 3.97:1 there (pre-PR
                                        // #B7C2D0 was
                                        // 6.80:1). Sibling text at :172 already uses
                                        // onSurface; this matches it: 12.27:1 dark /
                                        // 12.69:1 light.
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics {
                            contentDescription = if (activity.isExpanded) {
                                "Collapse details for tool ${activity.name}"
                            } else {
                                "Expand details for tool ${activity.name}"
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

            // Expanded Detail View — inert bounded text only; no payload-derived
            // actions of any kind (REQ-008 / CON-007).
            AnimatedVisibility(visible = activity.isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // SEC-004 note: the gateway wire carries no typed failure
                    // field on `tool.complete` (contract:
                    // specs/architecture/contracts/tui-gateway-client-contract.md
                    // — `tool.complete`: tool_id, name, args, optional
                    // result/summary/duration). No payload-influenced failure
                    // styling exists; results render as inert bounded text
                    // regardless of content.

                    if (!activity.argsSummary.isNullOrBlank()) {
                        Text(
                            text = "Arguments",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = activity.argsSummary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (!activity.resultSummary.isNullOrBlank()) {
                        Text(
                            text = "Result",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = activity.resultSummary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // SEC-005: sensitivity warning is shown before/with the copy
                    // action; the write itself is user-initiated only.
                    Text(
                        text = "Details can contain sensitive values. Copied content is marked as sensitive on supported devices.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Copy action for expanded details
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                val details = buildString {
                                    appendLine("Tool: ${activity.name}")
                                    if (activity.durationSeconds != null) appendLine("Duration: ${activity.durationSeconds}s")
                                    if (!activity.argsSummary.isNullOrBlank()) appendLine("Args: ${activity.argsSummary}")
                                    if (!activity.resultSummary.isNullOrBlank()) appendLine("Result: ${activity.resultSummary}")
                                }
                                copySensitiveText(context, "Hermes tool details", details)
                                isDetailsCopied = true
                            },
                            // CHG-008 repair round 1 (P1-A): TextButton defaults to
                            // the primary fill token for its label, which is
                            // 2.93:1 on this #1E202B card. Content colour is
                            // overridden to the foreground role.
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = HermesTheme.extendedColors.primaryText
                            ),
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Text(
                                text = if (isDetailsCopied) "Details Copied" else "Copy Details",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}
