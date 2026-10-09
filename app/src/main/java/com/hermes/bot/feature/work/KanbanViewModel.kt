package com.hermes.bot.feature.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.kanban.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class KanbanDraft(
    val title: String = "", val body: String = "", val priority: String = "0",
    val assignee: String = "", val workspaceKind: String = "scratch", val workspacePath: String = "",
    val skills: String = "", val parent: String = "", val model: String = "",
    val provider: String = "", val reasoningEffort: String = "", val goalMode: Boolean = false,
    val blockReason: String = "", val completionSummary: String = "",
    val targetLane: String = "triage", val idempotencyKey: String = UUID.randomUUID().toString()
) {
    fun valid() = title.isNotBlank() && title.length <= 512 && priority.toIntOrNull() != null &&
        workspaceKind in setOf("scratch", "dir", "worktree") && targetLane in KanbanLanes.manual &&
        (targetLane != "blocked" || blockReason.isNotBlank())
    fun toCreate() = KanbanCreate(title, body, priority.toInt(), assignee.ifBlank { null }, workspaceKind,
        workspacePath.ifBlank { null }, skills.split(',').map(String::trim).filter(String::isNotBlank),
        parent.ifBlank { null }, model.ifBlank { null }, provider.ifBlank { null },
        reasoningEffort.ifBlank { null }, goalMode, idempotencyKey)
}

data class KanbanUiState(
    val online: Boolean = false, val loading: Boolean = false, val busy: Boolean = false,
    val boards: List<KanbanBoardMeta> = emptyList(), val selectedBoard: String? = null,
    val board: KanbanBoard? = null, val selectedLane: String = "todo",
    val detail: KanbanDetail? = null, val detailLoading: Boolean = false,
    val catalog: KanbanCatalog = KanbanCatalog(emptyList(), emptyMap()),
    val draft: KanbanDraft? = null, val failure: KanbanFailure? = null,
    val needsReconciliation: Boolean = false, val live: Boolean = false,
    val createdInTriage: Boolean = false
) {
    val cards: List<KanbanCard> get() = board?.lanes?.firstOrNull { it.name == selectedLane }?.cards ?: emptyList()
    val canManage: Boolean get() = online && board != null && !loading && !busy &&
        failure == null && !needsReconciliation
}

class KanbanViewModel(private val repository: KanbanRepository) : ViewModel() {
    private val mutable = MutableStateFlow(KanbanUiState())
    val uiState: StateFlow<KanbanUiState> = mutable
    private var pageActive = false
    private var connectionKey: String? = null
    private var epoch = 0L
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var eventsJob: Job? = null

    fun connectionChanged(online: Boolean, key: String?) {
        if (mutable.value.online == online && connectionKey == key) return
        val changed = connectionKey != key
        val uncertain = mutable.value.busy || mutable.value.needsReconciliation
        connectionKey = key
        epoch++
        readJob?.cancel(); eventsJob?.cancel(); writeJob?.cancel()
        mutable.value = if (changed) KanbanUiState(online = online) else mutable.value.copy(
            online = online, loading = false, busy = false, live = false,
            draft = null, needsReconciliation = uncertain, failure = if (online) null else KanbanFailure.OFFLINE)
        if (pageActive && online && !uncertain) refresh()
    }

    fun pageStarted() {
        if (pageActive) return
        pageActive = true
        if (mutable.value.online) refresh()
    }
    fun pageStopped() {
        pageActive = false
        readJob?.cancel(); eventsJob?.cancel()
        mutable.value = mutable.value.copy(loading = false, live = false)
    }

    fun refresh() {
        if (!mutable.value.online || mutable.value.busy) return
        val ticket = ++epoch
        readJob?.cancel(); eventsJob?.cancel()
        mutable.value = mutable.value.copy(loading = true, failure = null, live = false)
        readJob = viewModelScope.launch {
            try {
                when (val result = repository.boards()) {
                    is KanbanResult.Failure -> if (ticket == epoch) mutable.value = mutable.value.copy(
                        loading = false, failure = result.reason)
                    is KanbanResult.Success -> {
                        val (boards, current) = result.value
                        val remembered = try { repository.rememberedBoard() }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { null }
                        val selected = mutable.value.selectedBoard?.takeIf { old -> boards.any { it.slug == old } }
                            ?: remembered?.takeIf { old -> boards.any { it.slug == old } }
                            ?: current.takeIf { old -> boards.any { it.slug == old } }
                            ?: boards.firstOrNull()?.slug
                        if (ticket != epoch) return@launch
                        mutable.value = mutable.value.copy(boards = boards, selectedBoard = selected)
                        if (selected == null) { mutable.value = mutable.value.copy(loading = false, board = null); return@launch }
                        loadBoard(selected, ticket)
                        if (ticket == epoch && mutable.value.board != null) {
                            val catalog = repository.catalog(selected)
                            if (ticket == epoch && catalog is KanbanResult.Success) mutable.value = mutable.value.copy(catalog = catalog.value)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (ticket == epoch) mutable.value = mutable.value.copy(loading = false, failure = KanbanFailure.UNAVAILABLE) }
        }
    }

    private suspend fun loadBoard(slug: String, ticket: Long) {
        when (val result = repository.board(slug)) {
            is KanbanResult.Failure -> if (ticket == epoch) mutable.value = mutable.value.copy(loading = false, failure = result.reason)
            is KanbanResult.Success -> if (ticket == epoch && mutable.value.selectedBoard == slug) {
                mutable.value = mutable.value.copy(board = result.value, loading = false, failure = null,
                    needsReconciliation = false)
                if (pageActive) observe(slug, result.value.cursor, ticket)
            }
        }
    }

    private fun observe(slug: String, cursor: Long, ticket: Long) {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            try {
                mutable.value = mutable.value.copy(live = true)
                repository.events(slug, cursor).collect {
                    if (ticket != epoch || mutable.value.selectedBoard != slug) return@collect
                    delay(300)
                    val result = repository.board(slug)
                    if (ticket == epoch && result is KanbanResult.Success) mutable.value = mutable.value.copy(board = result.value)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            finally { if (ticket == epoch) mutable.value = mutable.value.copy(live = false) }
        }
    }

    fun selectBoard(slug: String) {
        if (mutable.value.busy || mutable.value.boards.none { it.slug == slug }) return
        val ticket = ++epoch
        readJob?.cancel(); eventsJob?.cancel()
        mutable.value = mutable.value.copy(selectedBoard = slug, selectedLane = "todo", board = null,
            detail = null, draft = null, loading = true, failure = null, createdInTriage = false)
        readJob = viewModelScope.launch {
            try {
                try { repository.rememberBoard(slug) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
                loadBoard(slug, ticket)
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (ticket == epoch) mutable.value = mutable.value.copy(loading = false, failure = KanbanFailure.UNAVAILABLE) }
        }
    }
    fun selectLane(lane: String) { if (lane in KanbanLanes.all) mutable.value = mutable.value.copy(selectedLane = lane) }
    fun open(id: String) {
        val slug = mutable.value.selectedBoard ?: return
        mutable.value = mutable.value.copy(detailLoading = true, detail = null)
        val ticket = epoch
        viewModelScope.launch {
            val result = repository.detail(slug, id)
            if (ticket == epoch && mutable.value.selectedBoard == slug)
                mutable.value = when (result) {
                    is KanbanResult.Success -> mutable.value.copy(detail = result.value, detailLoading = false)
                    is KanbanResult.Failure -> mutable.value.copy(detailLoading = false, failure = result.reason)
                }
        }
    }
    fun back() { if (!mutable.value.busy) mutable.value = mutable.value.copy(detail = null, draft = null, detailLoading = false) }
    fun create() {
        val lane = mutable.value.selectedLane.takeIf { it !in setOf("scheduled", "running", "review") } ?: "triage"
        val kind = mutable.value.boards.firstOrNull { it.slug == mutable.value.selectedBoard }?.defaultWorkspaceKind ?: "scratch"
        mutable.value = mutable.value.copy(draft = KanbanDraft(targetLane = lane, workspaceKind = kind),
            createdInTriage = false)
    }
    fun updateDraft(draft: KanbanDraft) { if (!mutable.value.busy) mutable.value = mutable.value.copy(draft = draft) }

    fun save() {
        val slug = mutable.value.selectedBoard ?: return
        val draft = mutable.value.draft?.takeIf(KanbanDraft::valid) ?: return
        if (!mutable.value.canManage) return
        write(slug) {
            val made = repository.create(slug, draft.toCreate())
            if (made is KanbanResult.Failure) return@write made
            val card = (made as KanbanResult.Success).value
            if (draft.targetLane == "triage") made else {
                val moveFields = mutableMapOf<String, Any?>("status" to draft.targetLane)
                if (draft.targetLane == "blocked") moveFields["block_reason"] = draft.blockReason.trim()
                if (draft.targetLane == "done") moveFields["summary"] = draft.completionSummary.trim()
                val moved = repository.patch(slug, card.id, moveFields)
                if (moved is KanbanResult.Failure && moved.reason != KanbanFailure.UNKNOWN) {
                    mutable.value = mutable.value.copy(selectedLane = "triage", createdInTriage = true)
                }
                moved
            }
        }
    }

    fun move(id: String, lane: String, reason: String = "", summary: String = "") {
        val slug = mutable.value.selectedBoard ?: return
        if (lane !in KanbanLanes.manual || !mutable.value.canManage) return
        val fields = mutableMapOf<String, Any?>("status" to lane)
        if (lane == "blocked") fields["block_reason"] = reason.trim()
        if (lane == "done") fields["summary"] = summary.trim()
        write(slug) { repository.patch(slug, id, fields) }
    }
    fun edit(id: String, fields: Map<String, Any?>) {
        val slug = mutable.value.selectedBoard ?: return
        if (mutable.value.canManage) write(slug) { repository.patch(slug, id, fields) }
    }
    fun addComment(id: String, body: String) {
        val slug = mutable.value.selectedBoard ?: return
        if (mutable.value.canManage && body.isNotBlank()) write(slug) { repository.comment(slug, id, body) }
    }
    fun delete(id: String) {
        val slug = mutable.value.selectedBoard ?: return
        if (mutable.value.canManage) write(slug, reopenDetail = false) { repository.delete(slug, id) }
    }

    private fun <T> write(slug: String, reopenDetail: Boolean = true,
        action: suspend () -> KanbanResult<T>) {
        if (mutable.value.busy) return
        val ticket = epoch
        val detailId = mutable.value.detail?.card?.id
        mutable.value = mutable.value.copy(busy = true, failure = null)
        writeJob = viewModelScope.launch {
            val result = try { action() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { KanbanResult.Failure(KanbanFailure.UNKNOWN) }
            if (ticket != epoch || mutable.value.selectedBoard != slug) return@launch
            when (result) {
                is KanbanResult.Failure -> {
                    mutable.value = mutable.value.copy(busy = false, failure = result.reason,
                        needsReconciliation = result.reason == KanbanFailure.UNKNOWN, draft = null)
                    if (result.reason != KanbanFailure.UNKNOWN && mutable.value.createdInTriage) refresh()
                }
                is KanbanResult.Success -> {
                    mutable.value = mutable.value.copy(busy = false, draft = null, detail = null,
                        createdInTriage = false)
                    refresh()
                    if (reopenDetail && detailId != null) open(detailId)
                }
            }
        }
    }
}
