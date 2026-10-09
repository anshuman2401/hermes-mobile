package io.github.anshuman2401.hermesmobile.feature.chat.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.anshuman2401.hermesmobile.design.HermesShapeTokens
import io.github.anshuman2401.hermesmobile.design.HermesTheme
import io.github.anshuman2401.hermesmobile.feature.chat.ToolActivityBlock
import io.github.anshuman2401.hermesmobile.feature.chat.ToolBlockPolicy
import io.github.anshuman2401.hermesmobile.feature.chat.ToolBlockStep
import kotlinx.coroutines.delay

/**
 * Collapsed Tool Activity Block Card in chat per PRD v2 § 1.
 * - Header: action count ("15 actions") + client-measured aggregate run time ("12m 32s").
 * - One preview row, preferring the latest tool step; the full chronological list remains in the sheet.
 * - Tapping the header opens the Activity Bottom Sheet at Level 1.
 * - Tapping the preview opens the sheet directly at that row's Level 2 detail.
 */
@Composable
fun ToolActivityBlockCard(
    block: ToolActivityBlock,
    onHeaderClick: () -> Unit,
    onStepClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isLive = ToolBlockPolicy.hasLiveStep(block)
    // UI-side 1 Hz ticking clock for live running turns
    var nowMillis by remember(block.id) { mutableLongStateOf(System.currentTimeMillis()) }
    if (isLive) {
        LaunchedEffect(block.id, isLive) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    // CHAT-018 PR-A (OQ-001): notes-only cards read "Agent notes"; cards with
    // tool/reasoning/generic steps keep the frozen "N actions" copy.
    val actionCountText = ToolBlockPolicy.headerTitle(block)
    val aggregateTime = ToolBlockPolicy.headerElapsedText(block, nowMillis)

    val headerSemantics = buildString {
        append("Tool activity block: ")
        append(actionCountText)
        if (aggregateTime != null) {
            append(", elapsed ")
            append(aggregateTime)
        }
        append(". View all activity details")
    }

    val visibleSteps = ToolBlockPolicy.visibleCollapsedSteps(block.steps)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HermesShapeTokens.cardCornerSmall))
            .border(1.dp, HermesTheme.extendedColors.glassOutline, RoundedCornerShape(HermesShapeTokens.cardCornerSmall)),
        color = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(HermesShapeTokens.cardCornerSmall)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header Row: Action count + aggregate time + tap affordance
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onHeaderClick)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics { contentDescription = headerSemantics },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = HermesTheme.extendedColors.primaryText,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = actionCountText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (aggregateTime != null) {
                        Text(
                            text = "• $aggregateTime",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "View all",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (block.steps.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Single selected preview; the header opens every chronological step.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    visibleSteps.forEach { step ->
                        StepRow(
                            step = step,
                            onClick = { onStepClick(step.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    step: ToolBlockStep,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when (step) {
            is ToolBlockStep.Tool -> Icons.Default.Build
            is ToolBlockStep.Reasoning -> Icons.Default.Info
            is ToolBlockStep.Generic -> Icons.Default.Info
            is ToolBlockStep.Note -> Icons.Default.Info
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        if (step is ToolBlockStep.Note) {
            // CHAT-018 PR-A (OQ-002): agent notes read as quiet commentary —
            // an "Agent note" prefix over dimmed prose, visually distinct from
            // command-in/command-out tool rows (no monospace, no status chip).
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Agent note",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = step.note.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Text(
                text = step.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        if (step.durationText != null) {
            Text(
                text = step.durationText!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        StatusDot(isRunning = step.isRunning)
    }
}
