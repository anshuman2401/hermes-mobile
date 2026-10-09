package com.hermes.bot.feature.chat.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.hermes.bot.design.HermesShapeTokens
import com.hermes.bot.design.HermesTheme
import com.hermes.bot.feature.chat.CachedToolTraceEntry
import com.hermes.bot.feature.chat.CachedToolTraceStatus
import com.hermes.bot.feature.chat.ExecutionTracePolicy
import com.hermes.bot.feature.chat.TraceOverallState
import com.hermes.bot.feature.chat.TraceState
import kotlinx.coroutines.delay

/**
 * PR-4 (plan CACHE-PR4 §5.6/§6/ASSUMPTION-07): section title of the
 * phone-captured tool-trace card — plan-frozen literal, single copy, used by
 * [CachedToolTraceCard] and byte-exact pinned by ChatCachedToolTraceTest.
 */
internal const val CACHED_TOOL_TRACE_SECTION_TITLE = "Tool activity captured on this phone"

/**
 * TRD1 slice 1 (TASK-007): the turn-scoped ExecutionTrace header — exactly
 * three facts (REQ-002): overall state, action count, and the client-measured
 * elapsed value when derivable (fail-closed hide otherwise, REQ-005).
 *
 * Icons stay within `material-icons-core` (PAT-002); colors are
 * [HermesTheme.extendedColors] + Material3 roles only (GUD-002, no new
 * tokens); typography is Material3 only. The state label is a visible,
 * non-color-only text plus an accessible `contentDescription`.
 *
 * Live elapsed (RISK-004/ALT-004): while [isLive] is true the header runs a
 * UI-side 1 Hz ticker — the ViewModel stays clock-write-only, and the header
 * recomposition is O(1) per second (never an O(n) timeline pass). When not
 * live the frozen terminal elapsed renders unchanged.
 */
@Composable
fun ExecutionTraceHeader(
    trace: TraceState,
    actionCount: Int,
    elapsedText: String?,
    isLive: Boolean,
    modifier: Modifier = Modifier
) {
    // Live 1 Hz ticker, UI-side only. `nowMillis` recomposes ONLY this
    // composable; the ViewModel is never written from here.
    var nowMillis by remember(trace.turnId) { mutableLongStateOf(0L) }
    if (isLive) {
        LaunchedEffect(trace.turnId, trace.startedAtMillis) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    val stateLabel = when (trace.overallState) {
        TraceOverallState.RUNNING -> "Running"
        TraceOverallState.COMPLETE -> "Complete"
        TraceOverallState.INTERRUPTED -> "Interrupted"
        TraceOverallState.FAILED -> "Failed"
        TraceOverallState.UNKNOWN -> "Unknown"
    }
    val liveElapsed = if (isLive && elapsedText == null && trace.startedAtMillis > 0) {
        ExecutionTracePolicy.elapsedText(trace.startedAtMillis, null, nowMillis)
    } else {
        elapsedText
    }
    val description = buildString {
        append("Execution trace: ")
        append(stateLabel)
        append(", ")
        append(actionCount)
        append(if (actionCount == 1) " action" else " actions")
        if (liveElapsed != null) {
            append(", elapsed ")
            append(liveElapsed)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HermesShapeTokens.cardCornerSmall))
            .background(HermesTheme.extendedColors.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (trace.overallState == TraceOverallState.COMPLETE) {
                Icons.Default.CheckCircle
            } else {
                Icons.Default.Info
            },
            contentDescription = null,
            tint = if (trace.overallState == TraceOverallState.COMPLETE) {
                HermesTheme.extendedColors.primaryText
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = stateLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$actionCount ${if (actionCount == 1) "action" else "actions"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (liveElapsed != null) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = liveElapsed,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * TRD1 slice 1 (TASK-007): the bordered/filled surface container for the
 * turn-scoped ExecutionTrace (compact visual treatment via tokens only,
 * GUD-002). The header slot renders above the turn's existing activity rows;
 * the content slot renders those rows byte-for-byte unchanged (REQ-004).
 */
@Composable
fun ExecutionTraceSurface(
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HermesShapeTokens.cardCornerSmall))
            .background(HermesTheme.extendedColors.surfaceContainer)
    ) {
        header()
        content()
    }
}

/**
 * PR-4 (plan CACHE-PR4 §5.4): one bounded card for the PHONE-CAPTURED tool
 * trace — safe redacted rows (name/status/timing only) plus the provenance
 * caption passed in from the caller's copy constant (single source). The
 * card is presentation-only: it confirms nothing, authorizes nothing, and
 * claims nothing about server history (SEC-06, REQ-07).
 */
@Composable
fun CachedToolTraceCard(
    entries: List<CachedToolTraceEntry>,
    provenanceLabel: String,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HermesShapeTokens.cardCornerSmall))
            .background(HermesTheme.extendedColors.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = CACHED_TOOL_TRACE_SECTION_TITLE,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        entries.forEach { entry -> CachedToolTraceRow(entry) }
        Text(
            text = provenanceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = provenanceLabel }
        )
    }
}

@Composable
private fun CachedToolTraceRow(entry: CachedToolTraceEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (entry.status == CachedToolTraceStatus.COMPLETE) {
                Icons.Default.CheckCircle
            } else {
                Icons.Default.Info
            },
            contentDescription = null,
            tint = if (entry.status == CachedToolTraceStatus.COMPLETE) {
                HermesTheme.extendedColors.primaryText
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            // Status wording is policy-derived: RUNNING never claims a
            // completion (T-13 pin).
            text = ExecutionTracePolicy.cachedTraceStatusText(
                entry.status,
                entry.durationSeconds
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
