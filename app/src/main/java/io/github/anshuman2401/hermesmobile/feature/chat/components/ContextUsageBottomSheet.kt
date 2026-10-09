package io.github.anshuman2401.hermesmobile.feature.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anshuman2401.hermesmobile.data.context.ContextBreakdown
import io.github.anshuman2401.hermesmobile.data.context.SessionUsage
import io.github.anshuman2401.hermesmobile.feature.chat.ChatUiState
import io.github.anshuman2401.hermesmobile.feature.chat.CompressionPhase
import java.text.NumberFormat

private fun tokens(value: Long, estimated: Boolean = false): String =
    (if (estimated) "~" else "") + NumberFormat.getIntegerInstance().format(value)
private fun percentage(value: Double): String = NumberFormat.getNumberInstance().apply {
    maximumFractionDigits = 1
}.format(value) + "%"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextUsageBottomSheet(
    state: ChatUiState,
    onRefresh: () -> Unit,
    onToggleBreakdown: () -> Unit,
    onCompress: () -> Unit,
    onDismiss: () -> Unit,
    onDismissCompressionNotice: () -> Unit = {}
) {
    val presentation = state.contextUsage
    val snapshot = presentation.snapshot
    val context = presentation.live?.context ?: snapshot?.context
    val total = presentation.live?.total ?: snapshot?.usage?.total
    val compactFooter = LocalDensity.current.fontScale > 1.3f || LocalConfiguration.current.screenHeightDp < 600
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) }
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Context & usage", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                    val model = state.sessionModelName ?: presentation.live?.model ?: snapshot?.context?.model
                    val provider = snapshot?.usage?.account?.provider
                    val subtitle = listOfNotNull(model?.takeIf { it.isNotBlank() }, provider).joinToString(" · ")
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh,
                    enabled = !presentation.loading && !state.isRefreshing && !state.isSubmitting &&
                        !state.isStreaming && state.compressionPhase != CompressionPhase.REQUESTING) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh context and usage")
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close context and usage") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (context != null) {
                    if (context.capacity > 0) CapacityOverview(context, total)
                    else Text("Hermes has not reported context capacity for this session.", style = MaterialTheme.typography.bodyMedium)
                } else if (total != null) {
                    Text(tokens(total) + " session total", style = MaterialTheme.typography.bodyMedium)
                }
                if (presentation.detailsStale) Text("Refresh when the conversation is idle to update the breakdown.",
                    style = MaterialTheme.typography.bodySmall)
                when {
                    presentation.loading -> Row(Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("Reading context and account allowance…", style = MaterialTheme.typography.bodyMedium)
                    }
                    presentation.error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(presentation.error, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (!state.isStreaming && !state.isSubmitting)
                            TextButton(onClick = onRefresh, enabled = state.compressionPhase != CompressionPhase.REQUESTING) { Text("Refresh") }
                    }
                    snapshot != null -> {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column {
                            Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (presentation.expanded) "Collapse breakdown" else "Expand breakdown",
                                onClick = onToggleBreakdown).padding(vertical = 8.dp)
                                .semantics { stateDescription = if (presentation.expanded) "Expanded" else "Collapsed" },
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(24.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text("What uses this space", style = MaterialTheme.typography.titleMedium)
                                    Text("Conversation, tools and instructions", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if (presentation.expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null)
                            }
                            if (presentation.expanded && !presentation.detailsStale) {
                                if (snapshot.context.categories.isEmpty()) Text("Hermes has not built a category breakdown yet.", style = MaterialTheme.typography.bodySmall)
                                else snapshot.context.categories.forEach { category ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(category.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(tokens(category.tokens, true), style = MaterialTheme.typography.bodyMedium)
                                            if (snapshot.context.categoryTotal > 0) Text(percentage(category.tokens / snapshot.context.categoryTotal * 100),
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                Text("Category sizes are estimates and may differ from current usage.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AccountAllowance(snapshot.usage)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text("Context capacity and account allowance are separate limits.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (compactFooter) {
                    Text("Need more room?", style = MaterialTheme.typography.titleMedium)
                    Text("Summarize older turns while keeping recent exchanges.", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!compactFooter) {
                    Text("Need more room?", style = MaterialTheme.typography.titleMedium)
                    Text("Summarize older turns while keeping recent exchanges.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onCompress, enabled = state.canCompressContext,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                    if (state.compressionPhase == CompressionPhase.REQUESTING) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.compressionPhase == CompressionPhase.REQUESTING) "Compressing…" else "Compress context")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.compressionNotice ?: "Available when the conversation is idle",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                    if (state.compressionNotice != null && !state.isCompressionBusy) {
                        IconButton(onClick = onDismissCompressionNotice) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss compression notice")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapacityOverview(context: ContextBreakdown, total: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val largeFont = LocalDensity.current.fontScale > 1.2f
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val label = if (context.estimated) "Estimated context used" else "Context used"
            val amount = (if (context.estimated) "~" else "") + percentage(context.percent)
            if (maxWidth < 340.dp || largeFont) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(amount, style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(amount, style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp), modifier = Modifier.weight(1f))
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LinearProgressIndicator(progress = { (context.percent / 100).toFloat() },
            modifier = Modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = "Context used" },
            trackColor = MaterialTheme.colorScheme.surfaceVariant, gapSize = 0.dp)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 340.dp || largeFont) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TokenStat("Used", context.used, context.estimated)
                TokenStat("Remaining", context.remaining, context.estimated)
            } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TokenStat("Used", context.used, context.estimated, Modifier.weight(1f))
                TokenStat("Remaining", context.remaining, context.estimated, Modifier.weight(1f))
            }
        }
        Text(tokens(context.capacity) + " capacity" + (total?.let { " · " + tokens(it) + " session total" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TokenStat(label: String, value: Long, estimated: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(tokens(value, estimated) + " tokens", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun AccountAllowance(usage: SessionUsage) {
    val account = usage.account
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Account allowance", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        if (account.windows.isEmpty() && account.lines.isEmpty()) Text("Hermes did not report account allowance.", style = MaterialTheme.typography.bodySmall)
        account.windows.forEach { window ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(window.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(window.remainingPercent?.let { "$it% left" } ?: "Not reported",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                window.remainingPercent?.let { remaining ->
                    LinearProgressIndicator(progress = { remaining / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp)
                        .semantics { contentDescription = window.label + " allowance remaining" },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant, gapSize = 0.dp)
                }
                window.resetText?.let { Text("Resets $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                window.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        account.bankedResets?.let { Text("$it resets available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        account.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
