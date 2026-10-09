package io.github.anshuman2401.hermesmobile.feature.chat.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.anshuman2401.hermesmobile.domain.TaskStatus
import io.github.anshuman2401.hermesmobile.feature.chat.TaskProgressPresentation
import io.github.anshuman2401.hermesmobile.feature.chat.TaskProgressState

/** Read-only server checklist. JSON, network requests and task mutations stay outside Compose. */
@Composable
fun TaskProgressPanel(
    state: TaskProgressState,
    connected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snapshot = state.snapshot ?: return
    if (snapshot.items.isEmpty()) return
    val live = state.live && connected && !state.unavailable
    val title = if (state.ended || !snapshot.hasOpenTasks) "Previous tasks" else "Tasks"
    val count = "${snapshot.completedCount} / ${snapshot.countedCount} done"
    val rows = remember(snapshot.items) { TaskProgressPresentation.rows(snapshot.items) }
    val imeHeight = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val availableHeight = windowHeight - imeHeight - 160.dp
    val maximumHeight = (availableHeight * 0.30f).coerceIn(96.dp, 240.dp)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 6.dp).heightIn(max = maximumHeight),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = if (state.expanded) "Collapse tasks" else "Expand tasks", onClick = onToggle)
                    .semantics { stateDescription = if (state.expanded) "Expanded" else "Collapsed" }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(count, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (state.expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    items(rows, key = { it.first.id }) { (item, depth) ->
                        val label = TaskProgressPresentation.statusLabel(item.status, live)
                        Row(Modifier.fillMaxWidth().padding(start = (depth * 12).dp, top = 6.dp, bottom = 6.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "${item.content}. $label" },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TaskStatusGlyph(item.status, live)
                            Column(Modifier.weight(1f)) {
                                Text(item.content, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    color = if (item.status == TaskStatus.COMPLETED || item.status == TaskStatus.CANCELLED)
                                        MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                if (item.status == TaskStatus.IN_PROGRESS || item.status == TaskStatus.CANCELLED)
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else if (snapshot.hasOpenTasks && !state.ended) {
                Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TaskProgressPresentation.currentTask(state)?.let { current ->
                        TaskStatusGlyph(current.status, live)
                        Text(current.content, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
            val notice = when {
                state.unavailable -> "Task details unavailable · Last known tasks"
                !connected -> "Offline · Last known tasks"
                state.ended && snapshot.hasOpenTasks -> "Last known tasks · Some are unfinished"
                snapshot.descriptionsTruncated -> "Long task descriptions shortened"
                else -> null
            }
            if (notice != null) Text(notice, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
        }
    }
}

@Composable
private fun TaskStatusGlyph(status: TaskStatus, live: Boolean) {
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        when (status) {
            TaskStatus.COMPLETED -> Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            TaskStatus.CANCELLED -> Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            TaskStatus.IN_PROGRESS -> if (live) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                else OpenTaskRing()
            TaskStatus.PENDING -> OpenTaskRing()
        }
    }
}

@Composable
private fun OpenTaskRing() {
    Box(Modifier.size(12.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.CircleShape))
}
