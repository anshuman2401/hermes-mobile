package io.github.anshuman2401.hermesmobile.data.kanban

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Only server-backed board data is represented here; drafts never become cards. */
data class KanbanBoardMeta(val slug: String, val name: String, val total: Int,
    val isCurrent: Boolean, val defaultWorkspaceKind: String?, val defaultWorkdir: String?)

data class KanbanCard(val id: String, val title: String, val body: String?, val status: String,
    val priority: Int, val assignee: String?, val createdAt: Long?, val commentCount: Int,
    val latestSummary: String?, val warningCount: Int, val modelOverride: String? = null,
    val providerOverride: String? = null, val reasoningEffort: String? = null)

data class KanbanLane(val name: String, val cards: List<KanbanCard>)
data class KanbanBoard(val lanes: List<KanbanLane>, val cursor: Long)
data class KanbanComment(val author: String, val body: String, val createdAt: Long?)
data class KanbanDetail(val card: KanbanCard, val comments: List<KanbanComment>)
data class KanbanCatalog(val assignees: List<String>, val models: Map<String, List<String>>)

data class KanbanCreate(
    val title: String, val body: String, val priority: Int, val assignee: String?,
    val workspaceKind: String, val workspacePath: String?, val skills: List<String>,
    val parent: String?, val model: String?, val provider: String?,
    val reasoningEffort: String?, val goalMode: Boolean, val idempotencyKey: String
)

enum class KanbanFailure { OFFLINE, AUTH_REQUIRED, UNSUPPORTED, NOT_FOUND, INVALID, UNAVAILABLE, UNKNOWN, STALE, TOO_LARGE }
sealed interface KanbanResult<out T> {
    data class Success<T>(val value: T) : KanbanResult<T>
    data class Failure(val reason: KanbanFailure) : KanbanResult<Nothing>
}

/** Host-owned lanes. Scheduled, Running and Review are never manual targets. */
object KanbanLanes {
    val all = listOf("triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done")
    val manual = setOf("triage", "todo", "ready", "blocked", "done", "archived")
    val consequential = setOf("ready", "blocked", "done", "archived")
}

interface KanbanRepository {
    suspend fun rememberedBoard(): String? = null
    suspend fun rememberBoard(slug: String): Boolean = false
    fun events(slug: String, cursor: Long): Flow<Long> = emptyFlow()
    suspend fun boards(): KanbanResult<Pair<List<KanbanBoardMeta>, String>>
    suspend fun board(slug: String): KanbanResult<KanbanBoard>
    suspend fun detail(slug: String, id: String): KanbanResult<KanbanDetail>
    suspend fun catalog(slug: String): KanbanResult<KanbanCatalog>
    suspend fun create(slug: String, draft: KanbanCreate): KanbanResult<KanbanCard>
    suspend fun patch(slug: String, id: String, fields: Map<String, Any?>): KanbanResult<KanbanCard>
    suspend fun comment(slug: String, id: String, body: String): KanbanResult<Unit>
    suspend fun delete(slug: String, id: String): KanbanResult<Unit>
}
