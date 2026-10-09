package com.hermes.bot.feature.work

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.hermes.bot.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.bot.data.cron.CronJob
import com.hermes.bot.design.AtelierEditorial
import com.hermes.bot.design.atelierSelectionColor

/** Presentation only: all job state, ordering and operations remain owned by the ViewModel. */
@Composable
internal fun WorkCronList(
    state: WorkCronsState, listState: LazyListState, modifier: Modifier = Modifier,
    onProfile: (String?) -> Unit, onStatus: (CronStatusFilter) -> Unit,
    onRefresh: () -> Unit, onCreate: () -> Unit, onOpen: (CronJob) -> Unit
) {
    val jobs = state.visibleJobs
    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = { if (state.online && !state.loading && !state.busy) onRefresh() },
        state = rememberPullToRefreshState(),
        modifier = modifier.semantics {
            if (state.online && !state.loading && !state.busy) {
                customActions = listOf(CustomAccessibilityAction("Refresh crons") { onRefresh(); true })
            }
        }
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "heading") {
                ProfilePicker(state.profiles, state.profile, onProfile)
            }
            item(key = "controls") {
                // FlowRow wraps actions under the label at narrow widths or larger font scales.
                WorkListActions(state, onCreate)
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CronStatusFilter.entries.forEach { filter ->
                        TextButton(
                            onClick = { onStatus(filter) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = state.status == filter },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (state.status == filter) atelierSelectionColor() else MaterialTheme.colorScheme.background,
                                contentColor = if (state.status == filter) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) { Text(filter.name.lowercase().replaceFirstChar(Char::titlecase)) }
                    }
                }
                if (state.rosterUnavailable) WorkNotice("Profile roster is unavailable; showing profiles reported by jobs.")
                if (state.rosterCached) WorkNotice("Saved profile list — captured ${formatRun(state.rosterCapturedAtMillis?.div(1000.0))}. Latest roster unavailable.")
                if (!state.online && state.hasLoadedJobs) WorkNotice("Previously loaded jobs — offline; schedules may have changed.")
            }
            if (!state.loading && jobs.isEmpty()) item(key = "empty") {
                WorkPanel {
                    Text(
                        if (!state.online) "Reconnect to load crons."
                        else if (state.failure != null) "Crons could not be loaded."
                        else if (!state.hasLoadedJobs) "Waiting to load crons."
                        else if (state.jobs.isEmpty()) "No crons yet. Add a scheduled task to get started."
                        else "No jobs match these filters.", style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            itemsIndexed(jobs, key = { _, job -> "job:" + job.profile.length + ":" + job.profile + job.id }) { index, job ->
                val first = index == 0
                val last = index == jobs.lastIndex
                Surface(
                    onClick = { onOpen(job) }, modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
                    shape = RoundedCornerShape(
                        topStart = if (first) 18.dp else 0.dp, topEnd = if (first) 18.dp else 0.dp,
                        bottomStart = if (last) 18.dp else 0.dp, bottomEnd = if (last) 18.dp else 0.dp
                    ),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(job.name, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                CronStatus(job.enabled)
                                Text(job.profile, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(job.scheduleDisplay, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            Text("Next: ${formatNext(job.nextRun)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            job.lastStatus?.let { Text("Last run: ${lastStatus(it)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "host-note") {
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_insights_clock), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Scheduled work runs on Hermes, even when this phone is closed.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkListActions(state: WorkCronsState, onCreate: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        WorkSectionLabel("Crons", Modifier.weight(1f))
        Button(onClick = onCreate, enabled = state.canManage, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.background)) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("New cron")
        }
    }
}

@Composable
private fun ProfilePicker(profiles: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.heightIn(min = 52.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected ?: "All profiles", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(Icons.Default.KeyboardArrowDown, "Filter by profile", Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All profiles") }, onClick = { expanded = false; onSelect(null) })
            profiles.forEach { profile ->
                DropdownMenuItem(text = { Text(profile) }, onClick = { expanded = false; onSelect(profile) })
            }
        }
    }
}

@Composable
internal fun WorkHeading(text: String, fontSize: androidx.compose.ui.unit.TextUnit = 48.sp,
                         lineHeight: androidx.compose.ui.unit.TextUnit = 52.sp) {
    Text(text, Modifier.padding(top = 8.dp).semantics { heading() }, fontFamily = AtelierEditorial,
        fontSize = fontSize, lineHeight = lineHeight, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun WorkSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.semantics { heading() },
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun WorkPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun CronStatus(enabled: Boolean) {
    val color = if (enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(if (enabled) "Active" else "Paused", color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WorkNotice(text: String) {
    Text(text, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun workFilterColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.background,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = atelierSelectionColor(), selectedLabelColor = MaterialTheme.colorScheme.onSurface
)
