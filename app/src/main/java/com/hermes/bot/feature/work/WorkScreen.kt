package com.hermes.bot.feature.work

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.hermes.bot.design.AppThemePreference
import com.hermes.bot.design.AtelierTheme
import com.hermes.bot.design.RelayOrbitIcon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import com.hermes.bot.data.cron.*
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class WorkSection { CRONS, KANBAN, FILES }

@Composable
internal fun WorkSectionTabs(section: WorkSection, onSelect: (WorkSection) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        WorkSection.entries.forEach { item ->
            TextButton(onClick = { onSelect(item) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(item.name.lowercase().replaceFirstChar(Char::titlecase),
                    color = if (section == item) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider()
}

/** Work owns Crons, Kanban and host-managed Files; Crons remains the default. */
@Composable
fun WorkScreen(
    viewModel: WorkCronsViewModel?, modifier: Modifier = Modifier,
    currentTheme: AppThemePreference = AppThemePreference.SYSTEM,
    onNavigateToSettings: () -> Unit = {},
    onReconnect: () -> Unit = {},
    kanbanViewModel: KanbanViewModel? = null,
    filesViewModel: ManagedFilesViewModel? = null,
    filesConnectionKey: String? = null,
    filesOnline: Boolean = false
) {
    AtelierTheme(currentTheme) {
        var section by remember { mutableStateOf(WorkSection.CRONS) }
        val tabs: @Composable () -> Unit = { WorkSectionTabs(section) { section = it } }
        Box(modifier.fillMaxSize()) {
            when (section) {
                WorkSection.CRONS -> WorkContent(viewModel, Modifier.fillMaxSize(), onNavigateToSettings, onReconnect, tabs)
                WorkSection.KANBAN -> if (kanbanViewModel != null)
                    KanbanScreen(kanbanViewModel, onNavigateToSettings, onReconnect, tabs)
                    else WorkContent(viewModel, Modifier.fillMaxSize(), onNavigateToSettings, onReconnect, tabs)
                WorkSection.FILES -> ManagedFilesScreen(filesViewModel, filesConnectionKey, filesOnline,
                    onNavigateToSettings, onReconnect, onRootBack = { section = WorkSection.CRONS }, sections = tabs)
            }
        }
    }
}

@Composable
private fun WorkContent(viewModel: WorkCronsViewModel?, modifier: Modifier, onNavigateToSettings: () -> Unit,
    onReconnect: () -> Unit, sections: @Composable () -> Unit) {
    if (viewModel == null) {
        Column(modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)) {
            com.hermes.bot.design.AtelierAppChrome(title = "Work") {
                IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Settings, "Open settings") }
            }
            sections()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Crons are unavailable in this preview.") }
        }
        return
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.pageStarted()
            try { awaitCancellation() } finally { viewModel.pageStopped() }
        }
    }
    BackHandler(enabled = state.selected != null || state.draft != null || state.busy) { viewModel.back() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val detailState = androidx.compose.foundation.lazy.rememberLazyListState()
    val editorState = androidx.compose.foundation.lazy.rememberLazyListState()

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().imePadding().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.selected != null || state.draft != null) IconButton(onClick = { viewModel.back() }, enabled = !state.busy,
                modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            WorkSectionLabel("Work", Modifier.weight(1f))
            RelayOrbitIcon(size = 18.dp)
            Text("HERMES", Modifier.padding(start = 7.dp), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp))
            IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Open settings")
            }
        }
        if (state.selected == null && state.draft == null) sections()
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.retryAttempt > 0) Text("Retrying connection…", Modifier.padding(vertical = 8.dp))
        state.failure?.let { Text(failureText(it), Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
        if ((!state.online || (state.failure == CronFailure.UNAVAILABLE && !state.needsReconciliation)) && !state.loading && !state.busy) {
            TextButton(onClick = { if (state.online) viewModel.refresh() else onReconnect() },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
        }
        if (!state.online && state.failure != CronFailure.OFFLINE) Text("Offline — reconnect to manage crons.", Modifier.padding(vertical = 8.dp))
        if (state.needsReconciliation) Text("Return to the cron list and swipe down to check server state before making another change.", Modifier.padding(vertical = 8.dp))

        val draft = state.draft
        val selected = state.selected
        if (draft != null) {
            LazyColumn(modifier = Modifier.weight(1f), state = editorState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                item { WorkHeading(if (draft.original == null) "New cron." else "Edit cron.") }
                item {
                    WorkSectionLabel("Profile")
                    if (draft.original != null) Text(draft.profile) else ProfileChips(state.profiles, draft.profile, includeAll = false, enabled = !state.busy) {
                        viewModel.updateDraft(draft.copy(profile = it ?: ""))
                    }
                    if (draft.profile.isBlank()) Text("Choose a profile for this job.", style = MaterialTheme.typography.bodySmall)
                }
                item { OutlinedTextField(draft.name, { viewModel.updateDraft(draft.copy(name = it)) }, label = { Text("Name") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) }
                item {
                    if (draft.original?.scriptOnly == true) Text("Script job — script configuration is managed on Hermes.")
                    OutlinedTextField(draft.prompt, { viewModel.updateDraft(draft.copy(prompt = it)) }, label = { Text("Task instructions") }, minLines = 4,
                        enabled = !state.busy && draft.original?.scriptOnly != true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Text("Schedule")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SchedulePreset.entries.forEach { preset -> FilterChip(selected = draft.preset == preset,
                            onClick = { viewModel.updateDraft(draft.copy(preset = preset)) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp), colors = workFilterColors(),
                            label = { Text(preset.name.lowercase().replaceFirstChar(Char::titlecase)) }) }
                    }
                }
                if (draft.preset == SchedulePreset.ADVANCED) item {
                    OutlinedTextField(draft.custom, { viewModel.updateDraft(draft.copy(custom = it)) }, label = { Text("Cron expression") },
                        supportingText = { Text("Five fields: minute hour day month weekday. Hermes validates the expression.") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                } else item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (draft.preset != SchedulePreset.HOURLY) OutlinedTextField(draft.hour, { viewModel.updateDraft(draft.copy(hour = it)) }, label = { Text("Hour (0–23)") },
                            singleLine = true, enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(draft.minute, { viewModel.updateDraft(draft.copy(minute = it)) }, label = { Text("Minute (0–59)") },
                            singleLine = true, enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                }
                if (draft.preset == SchedulePreset.WEEKLY) item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 0 to "Sun").forEach { (day, label) ->
                            FilterChip(draft.day == day, { viewModel.updateDraft(draft.copy(day = day)) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp), colors = workFilterColors(), label = { Text(label) })
                        }
                    }
                }
                item {
                    Text(draft.summary(), style = MaterialTheme.typography.titleSmall)
                    Text("Timezone: the owning profile’s configured Hermes timezone. Jobs run on Hermes while this phone is closed.", style = MaterialTheme.typography.bodySmall)
                    if (draft.original == null) Text("Results are saved on Hermes. Delivery to other channels can be configured on the host.", style = MaterialTheme.typography.bodySmall)
                }
                item { Button(onClick = { viewModel.save() }, enabled = state.canManage && draft.valid(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.background)) { Text(if (state.busy) "Saving…" else "Save cron") } }
            }
        } else if (selected != null) {
            LazyColumn(modifier = Modifier.weight(1f), state = detailState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                item {
                    WorkHeading(selected.name, fontSize = 34.sp, lineHeight = 40.sp)
                    Text(selected.profile, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CronStatus(selected.enabled)
                }
                item { WorkPanel {
                    WorkSectionLabel("Schedule")
                    Text(selected.scheduleDisplay)
                    Text("Next run: ${formatNext(selected.nextRun)}", style = MaterialTheme.typography.bodySmall)
                    Text("Uses the profile’s configured Hermes timezone.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { WorkPanel {
                    WorkSectionLabel("Task instructions")
                    Text(selected.prompt.ifBlank { if (selected.scriptOnly) "Script job managed on Hermes." else "No task instructions reported." })
                } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ viewModel.edit() }, enabled = state.canManage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Edit") }
                        OutlinedButton({ viewModel.act(if (selected.enabled) CronAction.PAUSE else CronAction.RESUME) }, enabled = state.canManage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(if (selected.enabled) "Pause" else "Resume") }
                        TextButton({ viewModel.confirmDelete() }, enabled = state.canManage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Delete cron", color = MaterialTheme.colorScheme.error) }
                    }
                }
                item {
                    Text("Recent runs", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    when {
                        state.runsLoading -> CircularProgressIndicator()
                        state.runsError -> { Text("Run history could not be loaded."); TextButton({ viewModel.loadRuns() }, enabled = state.online) { Text("Retry history") } }
                        !state.online -> Text("Reconnect to load run history.")
                        state.runs.isEmpty() -> Text("No recent runs reported.")
                    }
                }
                items(state.runs) { run ->
                    Text("${formatRun(run.startedAt)} · ${if (run.active) "Running" else "Not active"}")
                }
            }
        } else {
            WorkCronList(
                state = state, listState = listState, modifier = Modifier.weight(1f),
                onProfile = viewModel::filterProfile, onStatus = viewModel::filterStatus,
                onRefresh = viewModel::refresh, onCreate = viewModel::create, onOpen = viewModel::open
            )
        }
    }

    if (state.deleteConfirmation) AlertDialog(onDismissRequest = { viewModel.dismissDelete() }, title = { Text("Delete cron?") },
        text = { Text("Delete “${state.selected?.name}” from Hermes? Future scheduled runs will stop.", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ viewModel.act(CronAction.DELETE) }, enabled = state.canManage) { Text("Delete") } },
        dismissButton = { TextButton({ viewModel.dismissDelete() }) { Text("Cancel") } })
}

@Composable
private fun ProfileChips(profiles: List<String>, selected: String?, includeAll: Boolean, enabled: Boolean = true, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (includeAll) FilterChip(selected == null, { onSelect(null) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), colors = workFilterColors(), label = { Text("All") })
        profiles.forEach { profile -> FilterChip(selected == profile, { onSelect(profile) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), colors = workFilterColors(), label = { Text(profile) }) }
    }
}

internal fun formatNext(value: String?): String = if (value == null) "Not reported" else try {
    OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("d MMM, HH:mm XXX"))
} catch (_: Exception) { "Time unavailable" }

internal fun formatRun(value: Double?): String = if (value == null || !value.isFinite()) "Time unavailable" else try {
    Instant.ofEpochSecond(value.toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm z"))
} catch (_: Exception) { "Time unavailable" }

internal fun lastStatus(value: String): String = when (value) {
    "ok", "success" -> "Succeeded"
    "error", "failed" -> "Failed"
    "delivery_failed" -> "Delivery failed"
    "running" -> "Running"
    else -> "Status unavailable"
}

private fun failureText(reason: CronFailure): String = when (reason) {
    CronFailure.OFFLINE -> "Offline — reconnect to manage crons."
    CronFailure.AUTH_REQUIRED -> "Sign in again to manage crons."
    CronFailure.UNSUPPORTED -> "This Hermes host does not support this cron operation."
    CronFailure.NOT_FOUND -> "This job or profile is no longer available. Refresh the list."
    CronFailure.INVALID -> "Hermes could not accept this schedule or returned an unsupported response."
    CronFailure.UNAVAILABLE -> "Hermes could not be reached."
    CronFailure.UNKNOWN -> "The change may have reached Hermes. Check the refreshed list before trying again."
    CronFailure.STALE -> "The connection changed. Refresh before making another change."
}
