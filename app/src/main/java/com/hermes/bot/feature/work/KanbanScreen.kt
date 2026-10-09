package com.hermes.bot.feature.work

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.hermes.bot.data.kanban.*
import com.hermes.bot.design.AtelierEditorial
import com.hermes.bot.design.AtelierShapes
import com.hermes.bot.design.RelayOrbitIcon
import kotlinx.coroutines.awaitCancellation

private fun laneLabel(name: String) = name.replaceFirstChar(Char::titlecase)

private fun kanbanFailure(reason: KanbanFailure): String = when (reason) {
    KanbanFailure.OFFLINE -> "Offline — reconnect to load this board."
    KanbanFailure.AUTH_REQUIRED -> "Sign in again to manage Kanban."
    KanbanFailure.UNSUPPORTED -> "This Hermes host does not support Kanban."
    KanbanFailure.NOT_FOUND -> "This board or card is no longer available. Refresh."
    KanbanFailure.INVALID -> "Hermes could not accept that change. Refresh the board."
    KanbanFailure.UNAVAILABLE -> "Hermes could not be reached."
    KanbanFailure.UNKNOWN -> "The change may have reached Hermes. Refresh before trying again."
    KanbanFailure.STALE -> "The connection changed. Refresh before making another change."
    KanbanFailure.TOO_LARGE -> "This board is too large to load on this phone."
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun KanbanScreen(viewModel: KanbanViewModel, onSettings: () -> Unit, onReconnect: () -> Unit,
    sections: @Composable () -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.pageStarted()
            try { awaitCancellation() } finally { viewModel.pageStopped() }
        }
    }
    BackHandler(state.detail != null || state.draft != null) { viewModel.back() }
    var boardMenu by remember { mutableStateOf(false) }
    var moveCard by remember { mutableStateOf<KanbanCard?>(null) }
    var confirmMove by remember { mutableStateOf<Pair<String, String>?>(null) }
    var moveReason by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var createConfirm by remember { mutableStateOf(false) }
    val selectedBoard = state.boards.firstOrNull { it.slug == state.selectedBoard }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().imePadding().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.detail != null || state.draft != null) IconButton({ viewModel.back() }, enabled = !state.busy,
                modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            WorkSectionLabel("Work", Modifier.weight(1f))
            RelayOrbitIcon(size = 18.dp)
            Text("HERMES", Modifier.padding(start = 7.dp),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp))
            IconButton(onSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Settings, "Open settings") }
        }
        if (state.detail == null && state.draft == null) sections()
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let { Text(kanbanFailure(it), Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
        if (state.createdInTriage) Text("Card was created in Triage. Its move was refused; review it there.",
            Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
        if (state.needsReconciliation) Text("Refresh to reconcile Hermes before another change.",
            style = MaterialTheme.typography.bodySmall)
        if (!state.online) TextButton(onReconnect) { Text("Reconnect") }

        when {
            state.detailLoading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.draft != null -> {
                val draft = state.draft!!
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 120.dp)) {
                    item { KanbanHeading("New card.") }
                    item { Text("Destination: ${laneLabel(draft.targetLane)}", style = MaterialTheme.typography.titleSmall) }
                    item { KanbanField(draft.title, { viewModel.updateDraft(draft.copy(title = it)) },
                        label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item { KanbanField(draft.body, { viewModel.updateDraft(draft.copy(body = it)) },
                        label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3) }
                    if (draft.targetLane == "blocked") item { KanbanField(draft.blockReason,
                        { viewModel.updateDraft(draft.copy(blockReason = it)) }, label = { Text("Block reason") },
                        modifier = Modifier.fillMaxWidth()) }
                    if (draft.targetLane == "done") item { KanbanField(draft.completionSummary,
                        { viewModel.updateDraft(draft.copy(completionSummary = it)) }, label = { Text("Completion summary (optional)") },
                        modifier = Modifier.fillMaxWidth()) }
                    item { KanbanField(draft.priority, { viewModel.updateDraft(draft.copy(priority = it)) },
                        label = { Text("Priority") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item {
                        WorkSectionLabel("Assignee")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KanbanChip(draft.assignee.isBlank(), { viewModel.updateDraft(draft.copy(assignee = "")) },
                                label = { Text("Unassigned") })
                            state.catalog.assignees.forEach { name -> KanbanChip(draft.assignee == name,
                                { viewModel.updateDraft(draft.copy(assignee = name)) }, label = { Text(name) }) }
                        }
                    }
                    item {
                        WorkSectionLabel("Workspace")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("scratch", "dir", "worktree").forEach { kind -> KanbanChip(draft.workspaceKind == kind,
                                { viewModel.updateDraft(draft.copy(workspaceKind = kind)) }, label = { Text(laneLabel(kind)) }) }
                        }
                    }
                    if (draft.workspaceKind != "scratch") item { KanbanField(draft.workspacePath,
                        { viewModel.updateDraft(draft.copy(workspacePath = it)) }, label = { Text("Workspace path override (optional)") },
                        supportingText = { Text("Empty uses this board’s default workspace.") }, modifier = Modifier.fillMaxWidth()) }
                    item { KanbanField(draft.skills, { viewModel.updateDraft(draft.copy(skills = it)) },
                        label = { Text("Skills (comma separated)") }, modifier = Modifier.fillMaxWidth()) }
                    item {
                        val options = state.board?.lanes?.flatMap { it.cards }?.filter { it.status != "archived" } ?: emptyList()
                        var parentOpen by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton({ parentOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                                Text(options.firstOrNull { it.id == draft.parent }?.title ?: "No parent")
                            }
                            DropdownMenu(parentOpen, { parentOpen = false }) {
                                DropdownMenuItem(text = { Text("No parent") }, onClick = { parentOpen = false; viewModel.updateDraft(draft.copy(parent = "")) })
                                options.forEach { card -> DropdownMenuItem(text = { Text(card.title) }, onClick = {
                                    parentOpen = false; viewModel.updateDraft(draft.copy(parent = card.id)) }) }
                            }
                        }
                    }
                    item { KanbanField(draft.provider, { viewModel.updateDraft(draft.copy(provider = it)) },
                        label = { Text("Model provider override") }, modifier = Modifier.fillMaxWidth()) }
                    item { KanbanField(draft.model, { viewModel.updateDraft(draft.copy(model = it)) },
                        label = { Text("Model override") }, modifier = Modifier.fillMaxWidth()) }
                    if (draft.provider.isNotBlank()) item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.catalog.models[draft.provider].orEmpty().forEach { model ->
                                KanbanChip(draft.model == model, { viewModel.updateDraft(draft.copy(model = model)) },
                                    label = { Text(model) })
                            }
                        }
                    }
                    item {
                        WorkSectionLabel("Reasoning effort")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("", "none", "low", "medium", "high", "xhigh").forEach { effort ->
                                KanbanChip(draft.reasoningEffort == effort,
                                    { viewModel.updateDraft(draft.copy(reasoningEffort = effort)) },
                                    label = { Text(if (effort.isBlank()) "Inherit" else laneLabel(effort)) })
                            }
                        }
                    }
                    item { Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Goal mode", Modifier.weight(1f))
                        Switch(draft.goalMode, { viewModel.updateDraft(draft.copy(goalMode = it)) })
                    } }
                    item { Button({ if (draft.targetLane in KanbanLanes.consequential) createConfirm = true else viewModel.save() },
                        enabled = state.canManage && draft.valid(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp), colors = kanbanActionColors()) {
                        Text(if (state.busy) "Creating…" else "Create card")
                    } }
                }
            }
            state.detail != null -> {
                val detail = state.detail!!
                val card = detail.card
                var title by remember(card.id, card.title) { mutableStateOf(card.title) }
                var body by remember(card.id, card.body) { mutableStateOf(card.body.orEmpty()) }
                var priority by remember(card.id, card.priority) { mutableStateOf(card.priority.toString()) }
                var assignee by remember(card.id, card.assignee) { mutableStateOf(card.assignee.orEmpty()) }
                var provider by remember(card.id, card.providerOverride) { mutableStateOf(card.providerOverride.orEmpty()) }
                var model by remember(card.id, card.modelOverride) { mutableStateOf(card.modelOverride.orEmpty()) }
                var reasoning by remember(card.id, card.reasoningEffort) { mutableStateOf(card.reasoningEffort.orEmpty()) }
                var comment by remember(card.id) { mutableStateOf("") }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 120.dp)) {
                    item { KanbanHeading(card.title) }
                    item { Text("${laneLabel(card.status)} · Priority ${card.priority}" +
                        (card.assignee?.let { " · $it" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { OutlinedButton({ moveCard = card }, enabled = state.canManage,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Move card") } }
                    item { KanbanField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedButton({ viewModel.edit(card.id, mapOf("title" to title.trim())) },
                        enabled = state.canManage && title.isNotBlank() && title != card.title,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Save title") } }
                    item { KanbanField(body, { body = it }, label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3) }
                    item { OutlinedButton({ viewModel.edit(card.id, mapOf("body" to body)) },
                        enabled = state.canManage && body != card.body.orEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Save description") } }
                    item { KanbanField(priority, { priority = it }, label = { Text("Priority") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item {
                        WorkSectionLabel("Assignee")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KanbanChip(assignee.isBlank(), { assignee = "" }, label = { Text("Unassigned") })
                            state.catalog.assignees.forEach { name -> KanbanChip(assignee == name,
                                { assignee = name }, label = { Text(name) }) }
                        }
                    }
                    item { KanbanField(provider, { provider = it }, label = { Text("Model provider override") },
                        modifier = Modifier.fillMaxWidth()) }
                    item { KanbanField(model, { model = it }, label = { Text("Model override") },
                        modifier = Modifier.fillMaxWidth()) }
                    if (provider.isNotBlank()) item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.catalog.models[provider].orEmpty().forEach { option ->
                                KanbanChip(model == option, { model = option }, label = { Text(option) })
                            }
                        }
                    }
                    item {
                        WorkSectionLabel("Reasoning effort")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("", "none", "low", "medium", "high", "xhigh").forEach { effort ->
                                KanbanChip(reasoning == effort, { reasoning = effort },
                                    label = { Text(if (effort.isBlank()) "Inherit" else laneLabel(effort)) })
                            }
                        }
                    }
                    item { OutlinedButton({
                        val fields = mutableMapOf<String, Any?>()
                        priority.toIntOrNull()?.takeIf { it != card.priority }?.let { fields["priority"] = it }
                        if (assignee != card.assignee.orEmpty()) fields["assignee"] = assignee
                        if (model != card.modelOverride.orEmpty() || provider != card.providerOverride.orEmpty()) {
                            if (model.isBlank()) fields["clear_model_override"] = true
                            else { fields["model_override"] = model; fields["provider_override"] = provider }
                        }
                        if (reasoning != card.reasoningEffort.orEmpty()) {
                            if (reasoning.isBlank()) fields["clear_reasoning_effort"] = true
                            else fields["reasoning_effort"] = reasoning
                        }
                        if (fields.isNotEmpty()) viewModel.edit(card.id, fields)
                    }, enabled = state.canManage && priority.toIntOrNull() != null &&
                        (priority != card.priority.toString() || assignee != card.assignee.orEmpty() ||
                            model != card.modelOverride.orEmpty() || provider != card.providerOverride.orEmpty() ||
                            reasoning != card.reasoningEffort.orEmpty()),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Save options") } }
                    item { Text("Comments", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
                    items(detail.comments) { row -> WorkPanel {
                        Text(row.author, style = MaterialTheme.typography.titleSmall)
                        Text(row.body)
                    } }
                    item { KanbanField(comment, { comment = it }, label = { Text("Add comment") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2) }
                    item { Button({ viewModel.addComment(card.id, comment); comment = "" },
                        enabled = state.canManage && comment.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp), colors = kanbanActionColors()) { Text("Post comment") } }
                    item { TextButton({ deleteId = card.id }, enabled = state.canManage,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Delete card", color = MaterialTheme.colorScheme.error)
                    } }
                }
            }
            else -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            WorkSectionLabel("Kanban", Modifier.weight(1f))
                            IconButton({ viewModel.refresh() }, enabled = state.online && !state.loading && !state.busy,
                                modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Refresh, "Refresh board") }
                            Button({ viewModel.create() }, enabled = state.canManage,
                                modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                                colors = kanbanActionColors()) {
                                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("New card")
                            }
                        }
                    }
                    item {
                        Box {
                            Surface(onClick = { boardMenu = true },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).heightIn(min = 52.dp),
                                shape = AtelierShapes.field, color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(selectedBoard?.name ?: "Choose a board", style = MaterialTheme.typography.titleSmall)
                                        Text("${selectedBoard?.total ?: 0} cards · tap to switch board",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.Default.KeyboardArrowDown, "Switch board", Modifier.size(18.dp))
                                }
                            }
                            DropdownMenu(boardMenu, { boardMenu = false }) {
                                state.boards.forEach { board -> DropdownMenuItem(text = { Text(board.name) }, onClick = {
                                    boardMenu = false; viewModel.selectBoard(board.slug) }) }
                            }
                        }
                    }
                    item { Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        KanbanLanes.all.forEach { lane -> KanbanChip(state.selectedLane == lane,
                            { viewModel.selectLane(lane) }, modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text("${laneLabel(lane)} ${state.board?.lanes?.firstOrNull { it.name == lane }?.cards?.size ?: 0}") }) }
                    } }
                    item { WorkSectionLabel(laneLabel(state.selectedLane), Modifier.padding(bottom = 10.dp)) }
                    if (!state.loading && state.boards.isEmpty() && state.failure == null) item {
                        WorkPanel { Text("No Kanban boards are available on this Hermes host.") }
                    }
                    if (!state.loading && state.board != null && state.cards.isEmpty()) item {
                        WorkPanel { Text("No cards in ${laneLabel(state.selectedLane)}.") }
                    }
                    itemsIndexed(state.cards, key = { _, card -> card.id }) { index, card ->
                        val first = index == 0
                        val last = index == state.cards.lastIndex
                        Surface(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { viewModel.open(card.id) },
                            onLongClick = { moveCard = card }).semantics { role = Role.Button },
                            shape = RoundedCornerShape(topStart = if (first) 18.dp else 0.dp,
                                topEnd = if (first) 18.dp else 0.dp,
                                bottomStart = if (last) 18.dp else 0.dp,
                                bottomEnd = if (last) 18.dp else 0.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Text("P${card.priority}", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(card.title, Modifier.weight(1f).padding(start = 10.dp),
                                        style = MaterialTheme.typography.titleMedium)
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${card.commentCount} comments" + (card.assignee?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                (card.latestSummary ?: card.body)?.takeIf(String::isNotBlank)?.let {
                                    Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (card.warningCount > 0) Text("△ ${card.warningCount} warning${if (card.warningCount == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (moveCard != null) ModalBottomSheet(onDismissRequest = { moveCard = null },
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        val card = moveCard!!
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
            Text("Move “${card.title}”", style = MaterialTheme.typography.headlineSmall)
            KanbanLanes.all.filter { it in KanbanLanes.manual && it != card.status }.plus("archived")
                .distinct().forEach { lane ->
                    TextButton(onClick = {
                        moveCard = null
                        if (lane in KanbanLanes.consequential) confirmMove = card.id to lane
                        else viewModel.move(card.id, lane)
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(laneLabel(lane)) }
                }
        }
    }
    confirmMove?.let { (id, lane) -> AlertDialog(onDismissRequest = { confirmMove = null; moveReason = "" },
        shape = AtelierShapes.panel, containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text("Move to ${laneLabel(lane)}?") },
        text = { Column {
            Text(when (lane) { "ready" -> "Hermes may start this card immediately.";
                "done" -> "This marks work complete."; "archived" -> "This hides the card from active lanes.";
                else -> "This blocks the card until it is unblocked." })
            if (lane == "blocked" || lane == "done") KanbanField(moveReason, { moveReason = it },
                label = { Text(if (lane == "blocked") "Reason" else "Summary") })
        } }, confirmButton = { TextButton({ viewModel.move(id, lane,
            reason = if (lane == "blocked") moveReason else "",
            summary = if (lane == "done") moveReason else ""); confirmMove = null; moveReason = "" },
            enabled = lane != "blocked" || moveReason.isNotBlank()) { Text("Move") } },
        dismissButton = { TextButton({ confirmMove = null; moveReason = "" }) { Text("Cancel") } }) }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null },
        shape = AtelierShapes.panel, containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text("Delete card?") }, text = { Text("Delete this card from Hermes?") },
        confirmButton = { TextButton({ viewModel.delete(id); deleteId = null }) { Text("Delete") } },
        dismissButton = { TextButton({ deleteId = null }) { Text("Cancel") } }) }
    if (createConfirm) AlertDialog(onDismissRequest = { createConfirm = false },
        shape = AtelierShapes.panel, containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text("Create in ${laneLabel(state.draft?.targetLane ?: "triage") }?") },
        text = { Text("Hermes may act on this card when it becomes Ready. The card is created in Triage first.") },
        confirmButton = { TextButton({ createConfirm = false; viewModel.save() }) { Text("Create") } },
        dismissButton = { TextButton({ createConfirm = false }) { Text("Cancel") } })
}

@Composable
private fun KanbanHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, fontFamily = AtelierEditorial,
        fontSize = 48.sp, lineHeight = 52.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun KanbanChip(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    label: @Composable () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = label,
        modifier = modifier.heightIn(min = 48.dp), colors = workFilterColors())
}

@Composable
private fun KanbanField(value: String, onValueChange: (String) -> Unit,
    label: @Composable () -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = false,
    minLines: Int = 1, supportingText: (@Composable () -> Unit)? = null) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = label,
        modifier = modifier, singleLine = singleLine, minLines = minLines,
        supportingText = supportingText, shape = AtelierShapes.field)
}

@Composable
private fun kanbanActionColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.onSurface,
    contentColor = MaterialTheme.colorScheme.background
)
