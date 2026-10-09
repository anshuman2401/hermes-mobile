package io.github.anshuman2401.hermesmobile.data.session

import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.SessionModelOption
import io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult
import io.github.anshuman2401.hermesmobile.data.gateway.AttachImageResult
import io.github.anshuman2401.hermesmobile.data.gateway.AttachFileResult
import io.github.anshuman2401.hermesmobile.data.gateway.DetachImageResult
import io.github.anshuman2401.hermesmobile.data.local.Draft
import io.github.anshuman2401.hermesmobile.data.local.SessionSelection
import io.github.anshuman2401.hermesmobile.data.local.SessionSummaryCache
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.feature.chat.ChatStreamingStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class SessionListOutcome(
    val sessions: List<SessionSummary>,
    val isOfflineCache: Boolean,
    val selectedProfileName: String? = null,
    /**
     * Scoped-session 404 self-heal: set to the fixed sanitized notice
     * (ScopedSessionsResult.PROFILE_GONE_NOTICE) exactly when this load
     * recovered from a stale selected workspace (host 404 profile-not-exist;
     * preference cleared, unscoped fallback used for this load). Host
     * 'detail' text is never placed here.
     */
    val staleProfileNotice: String? = null,
    val recoveryRequirement: WorkspaceRecoveryRequest? = null,
    val activityScope: SessionActivityScope? = null
)

/** Memory-only metadata authority. Neither runtime IDs nor credentials enter activity events. */
class SessionActivityScope internal constructor(
    internal val partition: io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartition,
    internal val gatewayGeneration: Long
) { override fun toString() = "SessionActivityScope([redacted])" }

class SessionActivityOwner internal constructor(
    internal val repository: SessionRepository,
    internal val scope: SessionActivityScope,
    internal val authority: io.github.anshuman2401.hermesmobile.data.local.WorkspaceRecoveryOwner,
    internal val storedSessionId: String,
    internal val runtimeSessionId: String
) { override fun toString() = "SessionActivityOwner([redacted])" }

class SessionActivityUpdate internal constructor(
    val scope: SessionActivityScope,
    val storedSessionId: String,
    val lastActive: Double
) { override fun toString() = "SessionActivityUpdate([redacted])" }

/** One-use, memory-only original request owner. Never recaptured after response. */
class WorkspaceRecoveryRequest internal constructor(internal val owner: io.github.anshuman2401.hermesmobile.data.local.WorkspaceRecoveryOwner, val selectedName: String) {
    private val used = java.util.concurrent.atomic.AtomicBoolean(false)
    internal fun consume(): Boolean = used.compareAndSet(false, true)
    override fun toString() = "WorkspaceRecoveryRequest([redacted])"
}
enum class WorkspaceRecoveryApplyResult { Applied, Stale, Failed, Unsupported }

/**
 * Repository boundary for managing Hermes chat sessions, durable resume, and history hydration.
 * Follows session-and-turn-state-machine.md.
 */
interface SessionRepository {
    val activeSession: StateFlow<SessionHandle?>
    val activeMessages: StateFlow<List<SessionMessage>>

    /**
     * HOME-S3: streaming state of the app's active session, as observable
     * in this app. Null storedSessionId = no active session; isStreaming is
     * true only while that session's current turn is streaming in-app.
     */
    val activeTurnStreaming: StateFlow<ChatStreamingStatus>

    val activityUpdates: Flow<SessionActivityUpdate> get() = emptyFlow()
    /** Existing server/readiness signals request a current-owner refetch; never carry summary data. */
    val listInvalidations: Flow<Unit> get() = emptyFlow()
    suspend fun captureMessageEditAccess(storedId: String, runtimeId: String): io.github.anshuman2401.hermesmobile.data.turn.MessageEditAccess? = null
    suspend fun capturePickerAccess(runtimeSessionId: String): SessionPickerAccess? = null
    suspend fun captureSendActivityOwner(storedSessionId: String, runtimeSessionId: String): SessionActivityOwner? = null
    suspend fun recordSendActivity(owner: SessionActivityOwner) {}
    suspend fun isCommandOwnerCurrent(owner: SessionActivityOwner): Boolean = false
    suspend fun isSessionActivityScopeCurrent(scope: SessionActivityScope): Boolean = false
    /** Structural connection check during an owned HTTP cookie/epoch handoff; never authorizes credentials. */
    suspend fun isSessionActivityConnectionCurrent(scope: SessionActivityScope): Boolean = false
    /** Called after presentation awaits, so a lagging page cannot undo an observed write. */
    suspend fun reconcileSessionActivity(outcome: SessionListOutcome): SessionListOutcome? = outcome

    suspend fun applyWorkspaceRecovery(request: WorkspaceRecoveryRequest): WorkspaceRecoveryApplyResult = WorkspaceRecoveryApplyResult.Unsupported
    suspend fun getSessionPins(): io.github.anshuman2401.hermesmobile.data.local.SessionPins? = null
    suspend fun setSessionPinned(scope: io.github.anshuman2401.hermesmobile.data.local.SessionPinScope, sessionId: String, pinned: Boolean): io.github.anshuman2401.hermesmobile.data.local.SessionPins =
        throw UnsupportedOperationException("Local pins unavailable")
    suspend fun getSessionFilters(): io.github.anshuman2401.hermesmobile.data.local.SessionFilters? = null
    suspend fun saveSessionFilters(filters: io.github.anshuman2401.hermesmobile.data.local.SessionFilters): io.github.anshuman2401.hermesmobile.data.local.SessionFilters =
        throw UnsupportedOperationException("Local filters unavailable")

    /** Network-free startup read. Null means no eligible cache; failure means local storage is unavailable. */
    suspend fun loadCachedSessions(): Result<SessionListOutcome?> = Result.success(null)
    suspend fun listSessions(forceRefresh: Boolean = false): Result<SessionListOutcome>
    /**
     * CHAT-004 TASK-003 (frozen plan): read-only history snapshot for the
     * load-earlier feature. Passthrough of `session.resume` for the given
     * durable session id. MUST NOT mutate hydration state (no
     * activeMessages replacement, no watermark/replay mutation, no handle
     * change) — see DefaultSessionRepository.fetchHistorySnapshot.
     */
    suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult>
    suspend fun captureStoredHistoryScope(
        localProfileId: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin,
        remoteProfile: String, durableSessionId: String, requestGeneration: Long, tailGeneration: Long
    ): Result<io.github.anshuman2401.hermesmobile.data.history.StoredHistoryScope> =
        Result.failure(io.github.anshuman2401.hermesmobile.data.history.StoredHistoryException(io.github.anshuman2401.hermesmobile.data.history.StoredHistoryFailure.UNAVAILABLE))

    suspend fun fetchStoredHistoryPage(
        scope: io.github.anshuman2401.hermesmobile.data.history.StoredHistoryScope,
        budget: io.github.anshuman2401.hermesmobile.data.history.StoredHistoryBudget, offset: Int = 0
    ): Result<io.github.anshuman2401.hermesmobile.data.history.StoredHistoryPage> =
        Result.failure(io.github.anshuman2401.hermesmobile.data.history.StoredHistoryException(io.github.anshuman2401.hermesmobile.data.history.StoredHistoryFailure.UNAVAILABLE))

    suspend fun createDraftSession(title: String? = null): Result<SessionHandle>
    suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult>
    suspend fun resumeStoredHistory(scope: io.github.anshuman2401.hermesmobile.data.history.StoredHistoryScope): Result<SessionResumeResult> =
        Result.failure(io.github.anshuman2401.hermesmobile.data.history.StoredHistoryException(io.github.anshuman2401.hermesmobile.data.history.StoredHistoryFailure.UNAVAILABLE))

    suspend fun isStoredHistoryCurrent(scope: io.github.anshuman2401.hermesmobile.data.history.StoredHistoryScope,
        budget: io.github.anshuman2401.hermesmobile.data.history.StoredHistoryBudget? = null, requireConnection: Boolean = true): Boolean = false

    /** Independent stored readability and captured-socket attachment; unsupported consumers fail closed. */
    suspend fun isStoredHistoryPublicationCurrent(
        scope: io.github.anshuman2401.hermesmobile.data.history.StoredHistoryScope,
        budget: io.github.anshuman2401.hermesmobile.data.history.StoredHistoryBudget
    ): Boolean = false

    suspend fun openStoredHistory(
        scope: io.github.anshuman2401.hermesmobile.data.history.StoredHistoryScope,
        budget: io.github.anshuman2401.hermesmobile.data.history.StoredHistoryBudget,
        onStored: suspend (Result<io.github.anshuman2401.hermesmobile.data.history.StoredHistoryPage>) -> Unit,
        onResumed: suspend (Result<SessionResumeResult>) -> Unit
    ): Result<Unit> = Result.failure(io.github.anshuman2401.hermesmobile.data.history.StoredHistoryException(
        io.github.anshuman2401.hermesmobile.data.history.StoredHistoryFailure.UNAVAILABLE))

    suspend fun restoreSelectedSession(): SessionHandle?
    suspend fun clearActiveSession()
    suspend fun getActiveSession(): SessionHandle?
    suspend fun attachImage(runtimeSessionId: String, bytes: ByteArray, name: String, ext: String?): GatewayResult<AttachImageResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Image attachment unsupported"))
    suspend fun attachFile(runtimeSessionId: String, bytes: ByteArray, mimeType: String, name: String): GatewayResult<AttachFileResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("File attachment unsupported"))
    suspend fun detachImage(runtimeSessionId: String, serverPath: String): GatewayResult<DetachImageResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Image detach unsupported"))
    suspend fun listSessionModelOptions(runtimeSessionId: String): GatewayResult<List<SessionModelOption>> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Model picker unsupported"))
    suspend fun setSessionModel(runtimeSessionId: String, option: SessionModelOption): GatewayResult<SessionPickerSetResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Model picker unsupported"))
    suspend fun setSessionReasoning(runtimeSessionId: String, effort: String): GatewayResult<SessionPickerSetResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Reasoning picker unsupported"))
    suspend fun activeSessionModels(): Result<Map<String, String>> = Result.success(emptyMap())

    // Profile-scoped draft and cache lookups at repository boundary
    suspend fun getDraft(storedSessionId: String): Draft? = null
    suspend fun saveDraft(draft: Draft) {}
    suspend fun deleteDraft(storedSessionId: String) {}
    suspend fun getAllDrafts(): List<Draft> = emptyList()
    suspend fun getSessionSelection(): SessionSelection? = null
    suspend fun getSessionSummaryCache(): SessionSummaryCache? = null
}

/** Strip compact tool payloads before the actual cache caller/serializer. Text provenance remains intact. */
internal fun SessionResumeResult.withoutToolPayloads(): SessionResumeResult = copy(
    messages = messages.map { message ->
        if (message.role.equals("tool", ignoreCase = true))
            message.copy(content = "", toolContext = null, reasoning = null, sourceTimestampSeconds = null)
        else message.copy(toolContext = null, sourceTimestampSeconds = null)
    }, info = emptyMap(), inflight = null, activeProjection = null,
    observedRuntimeSessionId = null, observedDurableSessionId = null
)
