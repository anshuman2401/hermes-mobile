package io.github.anshuman2401.hermesmobile.data.local

import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary

/**
 * Logical record schema definitions per specs/architecture/contracts/local-persistence-contract.md.
 */

data class ConnectionProfile(
    val localId: String,
    val normalizedOrigin: String,
    val displayLabel: String,
    val lastSuccessfulProtocol: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class AuthCredential(
    val driverKind: String, // e.g. "cookie_session"
    val providerId: String,
    val cookies: Map<String, String>, // cookie name to value
    val canonicalOrigin: String = "", // Bound canonical origin per RPR-003
    val expiryEpochSeconds: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DRIVER_COOKIE_SESSION = "cookie_session"
    }

    override fun toString(): String {
        return "AuthCredential([REDACTED])"
    }
}

/** In-memory only; exact issuing identity, never a persisted credential or log payload. */
class AuthCredentialLease internal constructor(
    internal val profileLocalId: String,
    internal val origin: String,
    internal val epoch: String,
    internal val credential: AuthCredential,
    internal val cacheEpoch: String = epoch
) {
    override fun toString(): String = "AuthCredentialLease([REDACTED])"
}

sealed interface AuthCredentialMutationResult {
    class Accepted(val receipt: AuthCredentialLease?) : AuthCredentialMutationResult
    data object Stale : AuthCredentialMutationResult
    data object Failed : AuthCredentialMutationResult
}

data class ProfileIndex(
    val profileIds: List<String> = emptyList(),
    val activeProfileId: String? = null,
    val migratedProfileId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class SessionSelection(
    val storedSessionId: String,
    val lastKnownTitle: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val profileLocalId: String? = null,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class SessionSummaryCache(
    val sessions: List<SessionSummary>,
    val syncTimeEpochSeconds: Long = System.currentTimeMillis() / 1000,
    val profileLocalId: String? = null,
    /**
     * Profile scope stamp: the workspace (context profile) name the cached
     * sessions were fetched for. Null = unscoped (written by the gateway
     * adapter path). The repository offline-fallback guard requires this to
     * match the selected profile before serving cache data as profile-scoped,
     * so an unscoped cache entry is never mislabeled as a profile's sessions.
     */
    val cachedProfileName: String? = null,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_RETENTION_SECONDS = 7 * 24 * 3600L // 7 days
    }

    /**
     * Freshness predicate enforced at the repository boundary (CHG-003-S5).
     * A cache is fresh up to and including exactly MAX_RETENTION_SECONDS after its
     * sync time; anything strictly older is stale past expiry and MUST NOT be
     * surfaced as session list data (it behaves as a failure/offline state instead).
     */
    fun isFresh(currentEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        return (currentEpochSeconds - syncTimeEpochSeconds) <= MAX_RETENTION_SECONDS
    }

    fun isStale(currentEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        return !isFresh(currentEpochSeconds)
    }
}

/**
 * PR-1 REQ-01: the resolved partition gate for an offline summary record —
 * the signed-in profile, its origin binding, the cache epoch (rotation), and
 * the workspace scope tag a record's identity is bound to.
 */
data class CacheAccessPartition(
    val profileLocalId: String,
    val originSha256: String,
    val cacheEpoch: String,
    val workspaceScopeTag: String
)

/** Why a partition could not be resolved; offline serving fails closed on all. */
enum class CacheUnavailableReason {
    NO_ACTIVE_PROFILE,
    PROFILE_ABSENT,
    CREDENTIAL_ABSENT,
    CREDENTIAL_ORIGIN_MISMATCH,
    CACHE_ACCESS_STATE_ABSENT,
    CACHE_ACCESS_STATE_INVALID
}

sealed interface CacheAccessPartitionResult {
    data class Available(val partition: CacheAccessPartition) : CacheAccessPartitionResult
    data class Unavailable(val reason: CacheUnavailableReason) : CacheAccessPartitionResult
}

/**
 * PR-1 REQ-03: outcome of a partition-bound summary read. Only [Available]
 * may be served; every other outcome deletes the record and never falls back.
 */
sealed interface CacheReadResult<out T> {
    data class Available<T>(val value: T) : CacheReadResult<T>
    /** Quarantined residue or an unparsable future schema — never served. */
    object Unavailable : CacheReadResult<Nothing>
    object PartitionMismatch : CacheReadResult<Nothing>
    object Expired : CacheReadResult<Nothing>
    object Corrupt : CacheReadResult<Nothing>
    object Absent : CacheReadResult<Nothing>
}

/** PR-1 REQ-06: rotation reasons persisted in the cache access state. */
enum class CacheEpochRotationReason(val raw: String) {
    /** First rotation-state record created by the initial authentication. */
    INITIAL_AUTH("INITIAL_AUTH"),
    CREDENTIAL_REPLACED("CREDENTIAL_REPLACED"),
    AUTH_EXPIRED("AUTH_EXPIRED"),
    ORIGIN_REPLACED("ORIGIN_REPLACED"),
    SIGNED_OUT("SIGNED_OUT")
}

/**
 * PR-6 REQ-02: the keep-vs-clear cache choice carried EXPLICITLY from the
 * sign-out dialog to the storage boundary. Retention is only ever an
 * explicitly designated [KEEP] — there is no third or implicit state.
 */
enum class SignOutCacheChoice {
    /**
     * Retain this profile's partitioned ciphertext: after a later sign-in the
     * fresh epoch cannot address the retained records (reads stay
     * fail-closed), and [CacheEpochRotationReason.SIGNED_OUT] rotation still
     * confines them (REQ-01).
     */
    KEEP,

    /**
     * PURGE-physical: [io.github.anshuman2401.hermesmobile.data.local.EncryptedVault] deletes this
     * profile's cache subtree (v2 ownership boundary) after the credential
     * removal, then its validated conversation indexes, then reconciles the
     * global catalog — content → index → catalog, always local.
     */
    CLEAR;

    /**
     * REQ-01 ROTATE-always: both choices rotate the cache epoch with the
     * sign-out reason — rotation is a function of credential removal, never
     * of the choice.
     */
    fun rotationReason(): CacheEpochRotationReason = CacheEpochRotationReason.SIGNED_OUT

    /** REQ-02: the only authorized retention predicate — true iff [KEEP]. */
    fun retainsCiphertext(): Boolean = this == KEEP

    /** REQ-05: gates the content/index/catalog purge — true iff [CLEAR]. */
    fun physicallyPurges(): Boolean = this == CLEAR
}

data class Draft(
    val storedSessionId: String,
    val composerText: String,
    val editTimeEpochSeconds: Long = System.currentTimeMillis() / 1000,
    val profileLocalId: String? = null,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_INACTIVITY_SECONDS = 30 * 24 * 3600L // 30 days
    }

    fun isExpired(currentEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        return (currentEpochSeconds - editTimeEpochSeconds) > MAX_INACTIVITY_SECONDS
    }

    override fun toString(): String {
        return "Draft(textLength=${composerText.length}, [REDACTED])"
    }
}

enum class PendingTurnState {
    PREPARED,
    WRITE_ATTEMPTED,
    CONFIRMED_ADMITTED,
    RESOLVED,
    UNKNOWN
}

/** Optional schema-1 metadata; never authorizes replay or a fresh request. */
data class PendingEditMetadata(val rowId: Long, val ownerKey: String) {
    init { require(rowId > 0 && ownerKey.matches(Regex("[a-f0-9]{64}"))) }
    override fun toString() = "PendingEditMetadata([REDACTED])"
}

data class PendingTurnRecord(
    val pendingTurnId: String,
    val storedSessionId: String,
    val runtimeSessionId: String? = null,
    val promptContent: String,
    val state: PendingTurnState = PendingTurnState.PREPARED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val submissionEvidenceFlag: Boolean = false,
    val edit: PendingEditMetadata? = null,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_AGE_MS = 24 * 3600 * 1000L // 24 hours
    }

    fun isExpiredUncertain(nowMs: Long = System.currentTimeMillis()): Boolean {
        return (nowMs - updatedAt) > MAX_AGE_MS
    }

    /**
     * UNKNOWN state strictly prohibits automatic transmission.
     */
    fun canAutoSend(): Boolean {
        return edit == null && state != PendingTurnState.UNKNOWN && !isExpiredUncertain()
    }

    override fun toString(): String {
        return "PendingTurnRecord(state=$state, [REDACTED])"
    }
}

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

data class LocalDiagnostic(
    val errorCategory: String,
    val code: String,
    val state: String,
    val timestamp: Long = System.currentTimeMillis(),
    val appVersion: String = "0.1.0",
    val hostVersion: String? = null
)

data class ContextProfileSelection(
    val selectedProfileName: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * CHAT-011 S1 (SEC-003): per-workspace default model + reasoning effort —
 * a sticky preference learned from host echoes, never a cache of host state
 * (PAT-001 singleton seam, NOT the TTL cache partition pattern). Holds ONLY
 * the workspace name, model string, effort string, update time, and schema
 * version — no tokens, secrets, provider credentials, or host config blobs.
 * [reasoningEffort] is the host vocabulary verbatim (including `none` for
 * reasoning-disabled); model and effort resolve together or the record is
 * not saved (atomic seed).
 */
data class ProfileModelDefault(
    val workspaceName: String,
    val modelName: String,
    val reasoningEffort: String,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        /**
         * Vault map key for a workspace selection: `profile?.trim()`; blank
         * or null maps to the literal key `"default"` (trim only, no
         * case-folding — host profile names are case-sensitive; the app and
         * the repository both read the same `getSelectedContextProfile()`
         * string, so keys always match).
         */
        fun workspaceKey(profile: String?): String {
            val trimmed = profile?.trim().orEmpty()
            return trimmed.ifEmpty { DEFAULT_WORKSPACE_KEY }
        }

        const val DEFAULT_WORKSPACE_KEY = "default"
    }
}

/**
 * Durable hold for an undelivered `session.interrupt` (T3 plan, FD-001/FD-003/FD-004).
 *
 * Keyed by the durable stored session id so the hold survives process death. The
 * runtime session id is an enqueue-time snapshot only and is never trusted across
 * a restart (FD-001 cost c / RISK-004): the flush path must resolve the live
 * runtime id from current state before sending. Expiry deletes silently — a hold
 * older than [TTL_MS] at flush time is dropped, never sent.
 */
data class PendingInterruptRecord(
    val storedSessionId: String,
    val runtimeSessionId: String? = null,
    val enqueuedAtMs: Long = System.currentTimeMillis(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val TTL_MS = 60_000L
    }

    /** FD-003: strictly older than 60 s at flush time means dropped, not sent. */
    fun isExpired(nowMs: Long): Boolean = (nowMs - enqueuedAtMs) > TTL_MS

    /** SEC-001: session identifiers must never reach logs or diagnostics. */
    override fun toString(): String {
        return "PendingInterruptRecord(storedSessionId=[REDACTED], hasRuntimeSessionId=${runtimeSessionId != null}, enqueuedAtMs=$enqueuedAtMs, schemaVersion=$schemaVersion)"
    }
}

/**
 * PR-2 REQ-01: one sanitized roster row captured for the offline Workspaces
 * sheet. Names arrive sanitized at live-parse time and are re-validated on read.
 */
data class CachedContextProfile(
    val name: String,
    val isRunning: Boolean = false,
    val isActive: Boolean = false
)

/**
 * PR-2 (architecture §3): the offline workspace roster record — headerless,
 * encrypted, bound to one signed-in access partition (P/O/E/W). Caps and TTL:
 * 0-100 entries, 64 KiB serialized payload, 24h from [capturedAtEpochMillis].
 * The host-active profile name is NOT part of the payload: the captured
 * active row carries it via [CachedContextProfile.isActive].
 */
data class ContextProfileCacheV2(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val recordId: String,
    val profileLocalId: String,
    val originSha256: String,
    val cacheEpoch: String,
    val workspaceScopeTag: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val capturedAtEpochMillis: Long,
    val selectedWorkspaceName: String?,
    val entries: List<CachedContextProfile>
) {
    /** Record-ID template: `context-profiles:{P}:{O}:{E}:{W}`. */
    fun recordId(): String =
        "context-profiles:$profileLocalId:$originSha256:$cacheEpoch:$workspaceScopeTag"

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
        const val MAX_ENTRIES: Int = 100
        /** Architecture §3 line 126 — NOT the summary record's 256 KiB cap (OQ-01). */
        const val MAX_PAYLOAD_BYTES: Int = 64 * 1024
        const val MAX_TTL_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}

/**
 * PR-3 (PAT-01): observable ordering seam — each value fires after one
 * crash-critical conversation-cache step has been durably committed (REQ-01
 * write order: content → index → catalog; delete order: content → index →
 * catalog), so fault-injection tests can abort EXACTLY between steps.
 */
enum class CacheWriteStage {
    STAGE_CONVERSATION_RECORD_WRITTEN,
    STAGE_CONVERSATION_INDEX_WRITTEN,
    STAGE_CONVERSATION_INDEX_UPDATED,
    STAGE_CONVERSATION_CATALOG_WRITTEN,
    STAGE_CONVERSATION_RECORD_DELETED,
    STAGE_CONVERSATION_INDEX_DELETED,
    STAGE_CONVERSATION_CATALOG_DELETED,
    // PR-4 (plan CACHE-PR4): phone-captured trace append seam — fires ONLY
    // after the trace-bearing content record is durably committed, or when a
    // guarded append skipped without writing anything.
    STAGE_CONVERSATION_TRACE_APPENDED,
    STAGE_CONVERSATION_TRACE_SKIPPED,
    // PR-5 (plan CACHE-PR5 REQ-02): append-only reset phase boundaries — the
    // roster purge (phase 4) and the summary purge (phase 5) of the seven-
    // phase all-cache deletion. Fixed stage names only — never ids/paths (SEC-04).
    STAGE_ROSTER_DELETED,
    STAGE_SUMMARY_DELETED
}

// ---------------------------------------------------------------------------
// PR-5 (plan CACHE-PR5; V2 §7 lines 247/248 re-anchored — the reference type
// was never declared on main, so PR-5 declares it beside PR-1's result
// types): the best-effort local-reset outcome. Complete, or Residual(phase)
// naming the fixed phase token at which an I/O error aborted the sequence.
// Swallowed at the UI boundary (OQ-02) — no vocabulary beyond these tokens
// (SEC-02), and every phase is delete-if-present so a re-run completes
// (REQ-03).
// ---------------------------------------------------------------------------
sealed interface CacheClearResult {
    data object Complete : CacheClearResult
    data class Residual(val phase: CacheClearResiduePhase) : CacheClearResult
}

enum class CacheClearResiduePhase {
    CONVERSATION_CACHE,
    ROSTER,
    SUMMARIES,
    DIRECTORY_SYNC,
    PENDING_INTERRUPTS,
    WHOLE_WIPE
}

// ---------------------------------------------------------------------------
// PR-4 (plan CACHE-PR4 §5.3): phone-captured tool-trace record types. Bounded
// by construction (REQ-04): NEVER payload / args / result / summary / raw
// terminal output / correlation id / prompt bytes — only the observed safe
// fields (id, seq, name, bounded context flag, status, duration, time).
// ---------------------------------------------------------------------------

/** Closed vocabulary of the observed gateway event that produced a row. */
enum class ToolTraceSourceType {
    TOOL_GENERATING,
    TOOL_START,
    TOOL_PROGRESS_GENERIC,
    TOOL_COMPLETE
}

/**
 * Observed tool status (REQ-01): COMPLETE ONLY from an observed terminal
 * event, RUNNING otherwise — never fabricated in either direction (T-12).
 */
enum class ToolStatus {
    RUNNING,
    COMPLETE
}

/**
 * One redacted observed tool event captured on THIS device (plan §5.3).
 * [context] is the host's bounded context LABEL or null — never a payload:
 * progress rows (whose payload may hold terminal-like bytes) always map
 * null (T-12). toString never emits name or context (logging surface,
 * §5.2 redaction style).
 */
data class CachedLocalToolTraceEvent(
    val localEventId: String,
    val sourceSeq: Long?,
    val sourceType: ToolTraceSourceType,
    val toolId: String?,
    val name: String,
    val context: String?,
    val status: ToolStatus,
    val durationSeconds: Double?,
    // PR-4 §5.3 NEVER-list: runtimeSessionId is never persisted on a row —
    // the dedupe tuple's runtime component comes from the ADMISSION CONTEXT
    // at append time (REQ-04), never from stored bytes (review F1).
    val observedAtMillis: Long
) {
    override fun toString(): String =
        "CachedLocalToolTraceEvent(localEventId=$localEventId, sourceType=$sourceType, " +
            "toolId=[REDACTED], name=[REDACTED], context=[REDACTED], status=$status, " +
            "durationSeconds=$durationSeconds, observedAtMillis=$observedAtMillis)"
}

// ---------------------------------------------------------------------------
// PR-3 (REQ-01..REQ-03): offline conversation snapshot cache record types.
// One conversation row set rides under the SAME partition identity binding as
// the summary/roster records; the per-partition index is the SOLE authority on
// which records exist, and the global catalog is opaque maintenance metadata
// (never authorizing). All types live beside the persistence models (§5.2).
// ---------------------------------------------------------------------------

/**
 * One persisted history row of a conversation snapshot. [content] is never
 * modified after the write — snapshots are immutable REPLACED wholes (REQ-09).
 */
data class CachedRemoteHistoryRow(
    val ordinal: Int,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolContext: String? = null,
    val reasoning: String? = null,
    val createdAtMillis: Long? = null
) {
    // Plan §5.2: [REDACTED]-style toString — content/reasoning never stringify.
    override fun toString(): String =
        "CachedRemoteHistoryRow(ordinal=$ordinal, role=$role, content=[REDACTED], " +
            "toolName=[REDACTED], toolContext=[REDACTED], reasoning=[REDACTED], " +
            "createdAtMillis=$createdAtMillis)"
}

/**
 * The conversation snapshot payload written under record ID
 * `conversation:<P>:<O>:<E>:<W>:<sid>` (exact template, plan §5.2) and AAD
 * `CONVERSATION:2:recordId:P:O:E:W:sid`.
 *
 * Identity discipline: [recordId] must always equal [recordIdFor] of the
 * partition + durable-session fields it carries; the vault re-derives and
 * compares it on both write and read (fail-closed).
 */
data class ConversationCacheV2(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val recordId: String,
    val profileLocalId: String,
    val originSha256: String,
    val cacheEpoch: String,
    val workspaceScopeTag: String,
    val durableSessionId: String,
    val selectedWorkspaceName: String?,
    /** Immutable capture window (§5.2) — replaced wholesale, never merged. */
    val snapshot: CachedRemoteHistorySnapshot,
    val lastAccessedAtMillis: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /**
     * PR-4 (CON-01): the phone-captured tool trace rides WITH the record —
     * bounded (count + payload byte caps) and carried through every
     * replace-on-resume; deleted only by rotation/purge/whole-record loss.
     */
    val localTrace: List<CachedLocalToolTraceEvent> = emptyList()
) {
    /** Record-ID template: `conversation:<P>:<O>:<E>:<W>:<sid>`. */
    fun recordId(): String = recordIdFor(
        profileLocalId = profileLocalId,
        originSha256 = originSha256,
        cacheEpoch = cacheEpoch,
        workspaceScopeTag = workspaceScopeTag,
        durableSessionId = durableSessionId
    )

    // Plan §5.2: [REDACTED]-style toString — ids, workspace name, and rows
    // never stringify (logging surface; see the four pre-existing overrides).
    override fun toString(): String =
        "ConversationCacheV2(schemaVersion=$schemaVersion, recordId=[REDACTED], " +
            "profileLocalId=[REDACTED], originSha256=[REDACTED], cacheEpoch=[REDACTED], " +
            "workspaceScopeTag=[REDACTED], durableSessionId=[REDACTED], " +
            "selectedWorkspaceName=[REDACTED], snapshot=[REDACTED rows=${snapshot.rows.size}], " +
            "lastAccessedAtMillis=$lastAccessedAtMillis, createdAtMillis=$createdAtMillis, " +
            "updatedAtMillis=$updatedAtMillis)"

    companion object {
        /** Shared template builder — the vault and the repository agree via this single source. */
        fun recordIdFor(
            profileLocalId: String,
            originSha256: String,
            cacheEpoch: String,
            workspaceScopeTag: String,
            durableSessionId: String
        ): String =
            "conversation:$profileLocalId:$originSha256:$cacheEpoch:$workspaceScopeTag:$durableSessionId"

        const val CURRENT_SCHEMA_VERSION: Int = 2
        /** Plan §5.2 — 768 KiB plaintext cap, re-checked after decryption (REQ-02). */
        const val MAX_SNAPSHOT_BYTES: Int = 768 * 1024
        /** Content TTL: fresh up to and including 7 days from capture (§5, REQ-02). */
        const val MAX_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
        /**
         * GCM ciphertext overhead: 12 B IV + 16 B tag (repair finding 2 — the
         * pre-decrypt guard adds 4-byte length + len(recordId) + 16 B slack
         * on top; a flat 128 B under-counted the record-id header).
         */
        const val CIPHERTEXT_OVERHEAD_BYTES: Int = 32
        // PR-4 (plan CACHE-PR4 REQ-01): phone-captured trace caps — count
        // bound first (oldest-first eviction), byte bound re-checked inside
        // the record-level plaintext cap above.
        const val MAX_LOCAL_TRACE_EVENTS: Int = 128
    }
}

/**
 * The immutable capture window of a conversation snapshot (plan §5.2).
 * [snapshotId] is minted locally (UUID) per capture, non-secret; [rows]
 * mirror `SessionMessage` 1:1 and NEVER carry runtimeSessionId, pending-turn
 * ids, or replay watermarks (SEC-01/02). [clientTruncated] is always false in
 * PR-3 — oversize writes are skipped whole, so no partial snapshot exists.
 */
data class CachedRemoteHistorySnapshot(
    /** Minted locally (UUID) at capture; non-secret. */
    val snapshotId: String,
    /** Wall clock of the last successful resume capture — the TTL base. */
    val capturedAtMillis: Long,
    /** Passthrough from SessionResumeResult.messagesOmitted. */
    val messagesOmitted: Boolean,
    /** Always false in PR-3 (REQ-06 skips whole writes, no partial rows). */
    val clientTruncated: Boolean,
    val rows: List<CachedRemoteHistoryRow>
) {
    // Plan §5.2: [REDACTED]-style toString — rows never stringify.
    override fun toString(): String =
        "CachedRemoteHistorySnapshot(snapshotId=[REDACTED], capturedAtMillis=$capturedAtMillis, " +
            "messagesOmitted=$messagesOmitted, clientTruncated=$clientTruncated, " +
            "rows=[REDACTED size=${rows.size}])"
}

/** One entry of the per-partition conversation index — the SOLE existence authority. */
data class ConversationIndexEntry(
    /** `sha256Hex(recordId)` — never the record ID itself (SEC-014). */
    val recordIdHash: String,
    val bytesEstimate: Long,
    val expiresAtMillis: Long,
    val lastAccessedAtMillis: Long
)

/**
 * Per-partition index under `conversation-index:<P>:<O>:<E>:<W>` / AAD
 * `CONVERSATION_INDEX:2:...` (exact templates, plan §5.2). Its validated
 * [entries] decide which conversation records are servable; content files
 * absent from it are unreachable and recovery-deleted (§5.3).
 */
data class ConversationPartitionIndexV2(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val recordId: String,
    val profileLocalId: String,
    val originSha256: String,
    val cacheEpoch: String,
    val workspaceScopeTag: String,
    /** Increments on EVERY write (REQ-01 write order). */
    val revision: Long,
    val entries: List<ConversationIndexEntry>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    /** Record-ID template: `conversation-index:<P>:<O>:<E>:<W>`. */
    fun recordId(): String =
        "conversation-index:$profileLocalId:$originSha256:$cacheEpoch:$workspaceScopeTag"

    // Plan §5.2: [REDACTED]-style toString — ids/hashes never stringify.
    override fun toString(): String =
        "ConversationPartitionIndexV2(schemaVersion=$schemaVersion, recordId=[REDACTED], " +
            "profileLocalId=[REDACTED], originSha256=[REDACTED], cacheEpoch=[REDACTED], " +
            "workspaceScopeTag=[REDACTED], revision=$revision, " +
            "entries=[REDACTED size=${entries.size}], createdAtMillis=$createdAtMillis, " +
            "updatedAtMillis=$updatedAtMillis)"

    companion object {
        /** Shared template builder for the vault's derived contexts. */
        fun recordIdFor(
            profileLocalId: String,
            originSha256: String,
            cacheEpoch: String,
            workspaceScopeTag: String
        ): String =
            "conversation-index:$profileLocalId:$originSha256:$cacheEpoch:$workspaceScopeTag"

        const val CURRENT_SCHEMA_VERSION: Int = 2
        /** Per-index entry cap (plan §5 — 256 entries; also the per-partition record cap). */
        const val MAX_ENTRIES: Int = 256
    }
}

/**
 * Global catalog entry — MAINTENANCE ONLY. It authorizes nothing: readers must
 * resolve through the partition index, never here (plan §5.2).
 */
data class GlobalCatalogEntry(
    /** `sha256Hex(indexRecordId)` — the index's identity, never its contents. */
    val indexLocatorSha256: String,
    val observedIndexRevision: Long,
    val bytesEstimate: Long,
    val oldestLastAccessedAtMillis: Long,
    val newestExpiryAtMillis: Long
)

/**
 * Opaque global catalog (`global-conversation-catalog`, schema 1), bound by
 * the fixed AAD literal `GLOBAL_CONVERSATION_CATALOG:1:global-conversation-catalog:catalog-v1`
 * (exact, plan §5.2). Rebuilt deterministically from validated partition
 * indexes (§5.3); a revision-stale copy is rebuilt, never trusted (REQ-03).
 */
data class GlobalConversationCatalogV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val recordId: String,
    val entries: List<GlobalCatalogEntry>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    // Plan §5.2: [REDACTED]-style toString — locators never stringify.
    override fun toString(): String =
        "GlobalConversationCatalogV1(schemaVersion=$schemaVersion, recordId=[REDACTED], " +
            "entries=[REDACTED size=${entries.size}], createdAtMillis=$createdAtMillis, " +
            "updatedAtMillis=$updatedAtMillis)"

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val RECORD_ID: String = "global-conversation-catalog"
    }
}
