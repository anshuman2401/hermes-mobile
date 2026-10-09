package com.hermes.bot.feature.chat.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermes.bot.data.subagent.*
import com.hermes.bot.feature.chat.*
import kotlinx.coroutines.delay

private fun statusLabel(child: Subagent, available: Boolean) = when {
    child.status.live && !available -> "Progress unavailable"
    !child.observed && child.status.live -> "Dispatching"
    else -> when (child.status) {
        SubagentStatus.QUEUED -> "Queued"
        SubagentStatus.RUNNING -> "Running"
        SubagentStatus.COMPLETED -> "Done"
        SubagentStatus.FAILED -> "Failed"
        SubagentStatus.INTERRUPTED -> "Interrupted"
        SubagentStatus.DISPATCHED -> "Dispatched"
        SubagentStatus.UNKNOWN -> "Outcome unknown"
    }
}

@Composable
private fun AgentStatus(child: Subagent, available: Boolean) {
    if (child.status.live && available) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
    else Icon(if (child.status == SubagentStatus.COMPLETED) Icons.Default.CheckCircle else Icons.Default.Person,
        contentDescription = statusLabel(child, available), modifier = Modifier.size(18.dp),
        tint = if (child.status == SubagentStatus.FAILED || child.status == SubagentStatus.INTERRUPTED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
}

@Composable
private fun AgentDuration(child: Subagent, available: Boolean): String? {
    var now by remember(child.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(child.id, child.status, available) {
        if (child.status.live && available && child.startedAtMillis != null) while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val millis = child.durationSeconds?.times(1000)?.toLong()
        ?: child.startedAtMillis?.let { if (child.status.live && available) (now - it).coerceAtLeast(0) else null }
    return millis?.let(ToolBlockPolicy::formatDuration)
}

@Composable
fun DelegatedTaskCards(tool: ToolActivity, timeline: List<TimelineEntry>, state: SubagentChatState, onOpen: (Subagent) -> Unit) {
    val rows = SubagentPresentation.rows(tool, timeline, state.roster.children)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Delegated tasks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEach { child ->
            Surface(onClick = { onOpen(child) }, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    AgentStatus(child, state.connected && (state.roster.available || !child.observed))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(child.goal, style = MaterialTheme.typography.titleSmall)
                        val time = AgentDuration(child, state.roster.available)
                        Text(listOfNotNull(child.model, statusLabel(child, state.connected && (state.roster.available || !child.observed)), time).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        child.activity.lastOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (child.projectionTruncated) Text("More delegated tasks are not shown.", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Agent details", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun SubagentLiveDock(state: SubagentChatState, onToggle: () -> Unit, onOpen: (Subagent) -> Unit) {
    val live = state.roster.children.filter { it.status.live }
    if (live.isEmpty()) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.roster.available) "${live.size} sub-agent${if (live.size == 1) "" else "s"} working" else "Progress unavailable", Modifier.weight(1f))
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = if (state.expanded) "Collapse sub-agents" else "Expand sub-agents")
            }
            if (state.expanded) {
                Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .25f).dp).verticalScroll(rememberScrollState())) {
                    live.forEach { child ->
                        TextButton(onClick = { onOpen(child) }, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
                            AgentStatus(child, state.roster.available)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(child.goal, style = MaterialTheme.typography.bodyMedium)
                                Text(child.activity.lastOrNull() ?: statusLabel(child, state.roster.available),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (state.roster.truncated) Text("More agents are not shown.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentDetailSheet(state: SubagentChatState, onBack: () -> Unit, onDismiss: () -> Unit,
    onDraft: (String) -> Unit, onSteer: () -> Unit, onStop: () -> Unit, onTranscript: () -> Unit) {
    if (state.selectedId == null) return
    val child = if (state.selectedFallback?.observed == true) state.roster.children.firstOrNull { it.id == state.selectedId } ?: state.selectedFallback
        else state.selectedFallback
    if (child == null) return
    val controls = state.roster.available && child.observed && child.status.live && !state.pending
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        BackHandler { onBack() }
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .8f).dp)
            .verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.transcriptVisible) "Agent transcript" else child.goal,
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = if (state.transcriptVisible) "Back to agent details" else "Close agent details")
                }
            }
            Text(listOfNotNull(statusLabel(child, state.roster.available), child.model, AgentDuration(child, state.roster.available)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall)
            if (state.transcriptVisible) {
                when {
                    state.transcriptLoading -> Text("Loading transcript…")
                    state.transcript?.available == true -> {
                        if (state.transcript.truncated) Text("Transcript truncated to the latest available tail.", style = MaterialTheme.typography.bodySmall)
                        Text(state.transcript.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Text("Transcript unavailable. The agent may have finished or belong to an earlier connection.")
                }
            } else {
                val stats = listOfNotNull(child.toolCount?.let { "$it tools" }, child.tokens?.let { "$it tokens" })
                if (stats.isNotEmpty()) Text(stats.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                Text("Recent activity", style = MaterialTheme.typography.titleSmall)
                if (child.activity.isEmpty()) Text("Waiting for activity.", style = MaterialTheme.typography.bodySmall)
                child.activity.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (child.observed && child.status.live) {
                    OutlinedTextField(value = state.draft, onValueChange = onDraft, enabled = controls && "steer" !in state.roster.disabledControls && child.acceptingSteer != false,
                        label = { Text("Guide this agent") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(onClick = onSteer, enabled = controls && "steer" !in state.roster.disabledControls && child.acceptingSteer != false && state.draft.isNotBlank(), modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Steer") }
                        TextButton(onClick = onStop, enabled = controls && "stop" !in state.roster.disabledControls, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Stop agent", color = MaterialTheme.colorScheme.error) }
                    }
                    if (!state.roster.available) Text("Reconnect to use agent controls.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onTranscript, enabled = child.observed && state.roster.available,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("View transcript") }
                state.feedback?.let { Text(it, modifier = Modifier.semantics { contentDescription = it; liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
