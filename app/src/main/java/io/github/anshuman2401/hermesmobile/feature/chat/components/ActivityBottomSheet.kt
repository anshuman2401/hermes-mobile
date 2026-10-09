package io.github.anshuman2401.hermesmobile.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anshuman2401.hermesmobile.design.HermesShapeTokens
import io.github.anshuman2401.hermesmobile.design.HermesTheme
import io.github.anshuman2401.hermesmobile.feature.chat.GenericGatewayActivity
import io.github.anshuman2401.hermesmobile.feature.chat.ReasoningEntry
import io.github.anshuman2401.hermesmobile.feature.chat.ToolActivity
import io.github.anshuman2401.hermesmobile.feature.chat.ToolActivityBlock
import io.github.anshuman2401.hermesmobile.feature.chat.ToolBlockPolicy
import io.github.anshuman2401.hermesmobile.feature.chat.AgentNoteEntry
import io.github.anshuman2401.hermesmobile.feature.chat.ToolBlockStep
import io.github.anshuman2401.hermesmobile.feature.chat.ToolDetailPolicy
import io.github.anshuman2401.hermesmobile.feature.chat.ToolStatus
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Native draggable Activity Bottom Sheet per PRD v2 § 2 & § 3.
 * - Level 1: Full chronological virtualized list of steps with summary chips (total actions, total time).
 * - Level 2: Step detail (dual-box for tools, single-box for reasoning, 200-char preview, copy actions).
 * - Back arrow ← returns to Level 1 inside the sheet; close ✕ or drag-dismiss returns to chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityBottomSheet(
    block: ToolActivityBlock,
    selectedStepId: String?,
    onStepClick: (String) -> Unit,
    onBackToLevel1: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    var nowMillis by remember(block.id) { mutableLongStateOf(System.currentTimeMillis()) }
    if (block.isLive) {
        LaunchedEffect(block.id) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        if (selectedStepId == null) {
            ActivitySheetLevel1(
                block = block,
                onStepClick = onStepClick,
                onDismiss = onDismiss
            )
        } else {
            val step = block.steps.firstOrNull { it.id == selectedStepId }
            ActivitySheetLevel2(
                step = step,
                nowMillis = nowMillis,
                onBack = onBackToLevel1,
                onDismiss = onDismiss
            )
        }
    }
}

/**
 * Level 1: Virtualized chronological list with summary chips per PRD v2 § 2.
 */
@Composable
fun ActivitySheetLevel1(
    block: ToolActivityBlock,
    onStepClick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
    ) {
        // Header Row: Title + Close Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Activity",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close activity sheet",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Summary Chips: Total actions, total time
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = ToolBlockPolicy.formatActionCount(block.totalActionCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (block.aggregateTimeText != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = block.aggregateTimeText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // Full chronological virtualized list
        LazyColumn(
            modifier = Modifier.fillMaxWidth()
        ) {
            items(
                items = block.steps,
                key = { it.id }
            ) { step ->
                Level1StepRow(
                    step = step,
                    onClick = { onStepClick(step.id) }
                )
            }
        }
    }
}

@Composable
private fun Level1StepRow(
    step: ToolBlockStep,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
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
            modifier = Modifier.size(18.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = step.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (step.durationText != null) {
            Text(
                text = step.durationText!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        StatusDot(isRunning = step.isRunning)

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Level 2: Step Detail view per PRD v2 § 3.
 */
@Composable
fun ActivitySheetLevel2(
    step: ToolBlockStep?,
    nowMillis: Long,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
    ) {
        // Header Row: Back Arrow + Title + Close Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to activity list",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = step?.name ?: ToolDetailPolicy.DETAILS_UNAVAILABLE,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close activity sheet",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (step == null) {
            // Malformed/absent step payload fallback
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ToolDetailPolicy.DETAILS_UNAVAILABLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (step) {
                is ToolBlockStep.Tool -> {
                    ToolDetailView(
                        activity = step.activity,
                        isRunning = step.isRunning,
                        nowMillis = nowMillis
                    )
                }

                is ToolBlockStep.Reasoning -> {
                    ReasoningDetailView(
                        reasoning = step.reasoning
                    )
                }

                is ToolBlockStep.Generic -> {
                    GenericDetailView(
                        generic = step.generic
                    )
                }

                is ToolBlockStep.Note -> {
                    NoteDetailView(
                        note = step.note
                    )
                }
            }

            // Footer: Status chip + elapsed time + Copy all
            DetailFooter(
                step = step,
                nowMillis = nowMillis
            )
        }
    }
}

/**
 * Dual-box layout for tools with input + output per PRD v2 § 3.
 */
@Composable
private fun ToolDetailView(
    activity: ToolActivity,
    isRunning: Boolean,
    nowMillis: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cwd = ToolDetailPolicy.extractCwd(activity)
    val input = ToolDetailPolicy.formatInputContent(activity)
    val output = ToolDetailPolicy.formatOutputContent(activity)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Box 1: Input (args + context + cwd when present)
        if (input != null || cwd != null) {
            DetailSectionBox(
                title = "Input",
                onCopy = {
                    val fullInput = buildString {
                        if (cwd != null) appendLine("cwd: $cwd")
                        if (input != null) append(input)
                    }.trim()
                    copySensitiveText(context, "Hermes tool input", fullInput)
                }
            ) {
                if (cwd != null) {
                    Text(
                        text = "cwd: $cwd",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                if (input != null) {
                    Text(
                        text = input,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Box 2: Output (result text, scrollable, 200-char preview with Show more/less)
        if (isRunning) {
            DetailSectionBox(
                title = "Output",
                onCopy = null
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    StatusDot(isRunning = true)
                    Text(
                        text = "Running… output will fill on completion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (output != null) {
            var isOutputExpanded by remember { mutableStateOf(false) }
            val exceedsCap = ToolDetailPolicy.exceedsPreviewCap(output)
            val displayText = if (!isOutputExpanded && exceedsCap) {
                ToolDetailPolicy.previewText(output)
            } else {
                output
            }

            DetailSectionBox(
                title = "Output",
                onCopy = { copySensitiveText(context, "Hermes tool output", output) }
            ) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (exceedsCap) {
                    TextButton(
                        onClick = { isOutputExpanded = !isOutputExpanded },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = HermesTheme.extendedColors.primaryText
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            text = if (isOutputExpanded) "Show less" else "Show more",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single-box layout for reasoning / thinking per PRD v2 § 3.
 */
@Composable
private fun ReasoningDetailView(
    reasoning: ReasoningEntry,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    val wordCount = ToolDetailPolicy.wordCount(reasoning.text)
    val exceedsCap = ToolDetailPolicy.exceedsPreviewCap(reasoning.text)
    val displayText = if (!isExpanded && exceedsCap) {
        ToolDetailPolicy.previewText(reasoning.text)
    } else {
        reasoning.text
    }

    val title = "${if (reasoning.kind == io.github.anshuman2401.hermesmobile.feature.chat.ReasoningDisplayKind.THINKING) "Thinking" else "Reasoning"} ($wordCount words)"

    DetailSectionBox(
        title = title,
        onCopy = { copySensitiveText(context, "Hermes reasoning", reasoning.text) },
        modifier = modifier
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (exceedsCap) {
            TextButton(
                onClick = { isExpanded = !isExpanded },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = HermesTheme.extendedColors.primaryText
                ),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = if (isExpanded) "Show less" else "Show more",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun GenericDetailView(
    generic: GenericGatewayActivity,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    DetailSectionBox(
        title = generic.eventType,
        onCopy = { copySensitiveText(context, "Hermes activity", "${generic.summary}\n${generic.payloadPreview ?: ""}".trim()) },
        modifier = modifier
    ) {
        Text(
            text = generic.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!generic.payloadPreview.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = generic.payloadPreview,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * CHAT-018 PR-A (OQ-002): Level 2 detail for an agent note — the same dimmed
 * prose presentation as the card row (no monospace, no command styling). The
 * "Copy all" footer affordance covers the note text via [ToolDetailPolicy.formatCopyAll].
 */
@Composable
private fun NoteDetailView(
    note: AgentNoteEntry,
    modifier: Modifier = Modifier
) {
    Text(
        text = note.text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun DetailSectionBox(
    title: String,
    onCopy: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var isCopied by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HermesShapeTokens.cardCornerSmall))
            .border(1.dp, HermesTheme.extendedColors.glassOutline, RoundedCornerShape(HermesShapeTokens.cardCornerSmall)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(HermesShapeTokens.cardCornerSmall)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onCopy != null) {
                    // Owner directive 2026-09-27: copy controls are icon-only.
                    // 48dp touch target + TalkBack description preserved; the
                    // icon flips to a check for ~1.5s after a successful copy.
                    IconButton(
                        onClick = {
                            onCopy()
                            isCopied = true
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription =
                                    if (isCopied) "Copied to clipboard" else "Copy $title"
                            }
                    ) {
                        CopyGlyph(
                            copied = isCopied,
                            glyphSize = 20.dp,
                            tint = HermesTheme.extendedColors.primaryText
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun DetailFooter(
    step: ToolBlockStep,
    nowMillis: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isCopied by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status Chip
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusDot(isRunning = step.isRunning)
                    Text(
                        text = if (step.isRunning) "Running…" else "Completed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Elapsed time
            val elapsed = if (step.isRunning && step.entry.timestamp > 0L) {
                val sec = ((nowMillis - step.entry.timestamp).coerceAtLeast(0L)) / 1000L
                ToolBlockPolicy.formatDuration(sec * 1000L)
            } else if (step is ToolBlockStep.Tool && step.activity.durationSeconds != null) {
                String.format(Locale.ROOT, "%.1fs", step.activity.durationSeconds)
            } else null

            if (elapsed != null) {
                Text(
                    text = elapsed,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Copy all: icon-only per owner directive 2026-09-27. Same copy-all
        // behavior; 48dp target + TalkBack description preserved; icon flips
        // to a check after the copy.
        IconButton(
            onClick = {
                val fullText = ToolDetailPolicy.formatCopyAll(step)
                copySensitiveText(context, "Hermes activity detail", fullText)
                isCopied = true
            },
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription =
                        if (isCopied) "All activity details copied" else "Copy all activity details"
                }
        ) {
            CopyGlyph(
                copied = isCopied,
                glyphSize = 20.dp,
                tint = HermesTheme.extendedColors.primaryText
            )
        }
    }
}
