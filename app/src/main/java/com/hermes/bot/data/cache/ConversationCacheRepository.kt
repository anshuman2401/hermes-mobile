package com.hermes.bot.data.cache

import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.local.CacheAccessPartitionResult
import com.hermes.bot.data.local.CacheReadResult
import com.hermes.bot.data.local.CachedRemoteHistoryRow
import com.hermes.bot.data.local.CachedRemoteHistorySnapshot
import com.hermes.bot.data.local.ConversationCacheV2
import com.hermes.bot.data.local.CachedLocalToolTraceEvent
import com.hermes.bot.data.local.ToolStatus
import com.hermes.bot.data.local.ToolTraceSourceType
import com.hermes.bot.data.local.AuthCredentialLease
import com.hermes.bot.data.local.VaultStorage
import com.hermes.bot.data.local.ConversationCacheReadOwner
import com.hermes.bot.data.gateway.GatewayEvent
import java.util.UUID
import kotlinx.coroutines.CancellationException

// ---------------------------------------------------------------------------
// PR-3 (plan CACHE-PR3, §5.1): the application-facing conversation cache
// contract. Result types live here (the plan states them in this file); the
// repository is an interface + default impl over [VaultStorage] with every
// failure mapped to a §5.1 read outcome or a §5 write result. The index is
// the SOLE existence authority below the vault; this layer adds no policy of
// its own beyond the fail-closed partition gate [VaultStorage.activeAccessPartition].
// ---------------------------------------------------------------------------

/**
 * Loaded snapshot view served to the chat path (plan §5.1 read outcome
 * `Snapshot(value: CachedConversationSnapshot)`): partition identity is
 * already validated by the vault, so only rendering-relevant fields surface.
 */
data class CachedConversationSnapshot(
    /** Stored session id the snapshot is bound to (ASSUMPTION-03). */
    val durableSessionId: String,
    /** Capture wall clock — the ChatUiState strip flag value (§6). */
    val capturedAtMillis: Long,
    /** Passthrough from SessionResumeResult.messagesOmitted (non-paginating). */
    val messagesOmitted: Boolean,
    val rows: List<CachedRemoteHistoryRow>,
    /**
     * PR-4 (CON-01): the phone-captured tool trace riding the record —
     * projected to the display by the chat path with its provenance caption.
     */
    val localTrace: List<CachedLocalToolTraceEvent> = emptyList()
)

/**
 * Read outcomes (exact names, plan §5.1): `Snapshot` / `Miss` / `Unavailable`
 * / `Corrupt` / `PartitionMismatch`. Chat shows the labelled strip only for
 * `Snapshot`; everything else falls through to live behavior or silence —
 * never a silent local substitute (OQ-05, REQ-09).
 */
sealed interface ConversationCacheReadResult {
    data class Snapshot(val value: CachedConversationSnapshot) : ConversationCacheReadResult
    /** Absent / no index entry / expired-cleaned — never a failed prompt. */
    data object Miss : ConversationCacheReadResult
    /** Unavailable — partition gate, I/O, decrypt, parse, or size failure. */
    data object Unavailable : ConversationCacheReadResult
    /** Corrupt — parse/decryption rejection of a structurally present record. */
    data object Corrupt : ConversationCacheReadResult
    /** PartitionMismatch — record identity does not match the resolved partition. */
    data object PartitionMismatch : ConversationCacheReadResult
}

/**
 * Write outcomes (exact names, plan §5.1): `Written` / `Skipped` / `Failed`.
 * Content → index → catalog in that order (REQ-01); a later-step failure
 * leaves earlier steps orphaned-but-harmless (REQ-04, §5.3 recovery).
 */
sealed interface ConversationCacheWriteResult {
    /** Steps (1)–(4) all committed (catalog maintenance is best-effort §5.2). */
    data object Written : ConversationCacheWriteResult
    /** Oversize / zero-row / guard-skipped — silently, never partially (REQ-06). */
    data object Skipped : ConversationCacheWriteResult
    /** §5.1 write failure — swallowed to `Failed` by the repository (REQ-10). */
    data object Failed : ConversationCacheWriteResult
}

/**
 * Application-facing offline conversation cache (plan §5.1: interface +
 * default impl over [VaultStorage]).
 *
 * [load] serves a fresh snapshot for the exact signed-in partition; any
 * failure maps to a §5.1 outcome and the chat path stays live/quiet.
 * [replaceRemoteSnapshot] atomically REPLACES the stored snapshot after a
 * successful remote resume (Hermes authoritative — OQ-05): canonical durable
 * id, omission/truncation flags, wall clock. Never called on any other path —
 * a failed resume or an offline serve never rewrites the cache (T-10(c)).
 */
interface ConversationCacheRepository {
    suspend fun load(storedSessionId: String): ConversationCacheReadResult

    /** Captures known selection (including authoritative absence), never an unreadable default. */
    suspend fun captureReadOwner(): ConversationCacheReadOwner? = null
    suspend fun isReadOwnerCurrent(owner: ConversationCacheReadOwner): Boolean = false
    suspend fun loadForReadOwner(
        storedSessionId: String, owner: ConversationCacheReadOwner
    ): ConversationCacheReadResult = ConversationCacheReadResult.Unavailable

    suspend fun replaceRemoteSnapshot(
        storedSessionId: String,
        resume: SessionResumeResult,
        nowMillis: Long
    ): ConversationCacheWriteResult

    /** Payload ownership survives awaited partition acquisition; unsupported caches fail closed. */
    suspend fun replaceRemoteSnapshotScoped(
        storedSessionId: String, resume: SessionResumeResult, nowMillis: Long,
        expected: AuthCredentialLease, selectedContext: String?
    ): ConversationCacheWriteResult = ConversationCacheWriteResult.Skipped

    suspend fun loadScoped(
        storedSessionId: String, expected: AuthCredentialLease, selectedContext: String?
    ): ConversationCacheReadResult = ConversationCacheReadResult.Unavailable

    /**
     * PR-4 (plan CACHE-PR4 §5.1/§5.5): record ONE exact-runtime observed tool
     * event onto the stored snapshot's phone-captured trace (CON-01 append
     * hook). Never creates a record; every outcome (miss, guard, cap, I/O) is
     * a soft Written/Skipped/Failed that never surfaces to chat.
     * `runtimeSessionId` must be the EXACT currently-attached runtime id —
     * admission proven by the caller's collector guard (mismatches are
     * dropped before this is invoked; RISK-01: no correlation ids are
     * invented anywhere in this seam).
     */
    suspend fun recordObservedToolEvent(
        storedSessionId: String,
        runtimeSessionId: String,
        event: GatewayEvent,
        observedAtMillis: Long
    ): ConversationCacheWriteResult
}

/**
 * Default [VaultStorage]-backed implementation. Guard order: blank id ⇒
 * Miss/Failed (never a partition-shaped record id), partition gate failure ⇒
 * Unavailable/Failed (fail closed — no partition, no cache; RISK-04), vault
 * skip/exception ⇒ Skipped/Failed verbatim (roster-style soft write guard).
 */
class VaultConversationCacheRepository(
    private val vaultStorage: VaultStorage
) : ConversationCacheRepository {

    override suspend fun captureReadOwner(): ConversationCacheReadOwner? = vaultStorage.captureConversationCacheReadOwner()

    override suspend fun isReadOwnerCurrent(owner: ConversationCacheReadOwner): Boolean =
        vaultStorage.isConversationCacheReadOwnerCurrent(owner)

    override suspend fun loadForReadOwner(
        storedSessionId: String, owner: ConversationCacheReadOwner
    ): ConversationCacheReadResult = loadInternal(storedSessionId, null, null, owner)

    override suspend fun load(storedSessionId: String): ConversationCacheReadResult =
        loadInternal(storedSessionId, null, null)

    override suspend fun loadScoped(
        storedSessionId: String, expected: AuthCredentialLease, selectedContext: String?
    ): ConversationCacheReadResult = loadInternal(storedSessionId, expected, selectedContext)

    private suspend fun loadInternal(
        storedSessionId: String, expected: AuthCredentialLease?, selectedContext: String?,
        readOwner: ConversationCacheReadOwner? = null
    ): ConversationCacheReadResult {
        if (storedSessionId.isBlank()) return ConversationCacheReadResult.Miss
        val partition = readOwner?.partition ?: when (val gate = resolvePartition()) {
            is PartitionGate.Resolved -> gate.partition
            is PartitionGate.Closed -> return ConversationCacheReadResult.Unavailable
        }
        return try {
            val read = if (readOwner != null) vaultStorage.readConversationSnapshotForOwner(storedSessionId, readOwner)
                else if (expected == null) vaultStorage.readConversationSnapshot(partition, storedSessionId)
                else vaultStorage.readConversationSnapshotIfCurrent(partition, storedSessionId, expected, selectedContext)
            when (read) {
                is CacheReadResult.Available -> ConversationCacheReadResult.Snapshot(
                    CachedConversationSnapshot(
                        durableSessionId = read.value.durableSessionId,
                        capturedAtMillis = read.value.snapshot.capturedAtMillis,
                        messagesOmitted = read.value.snapshot.messagesOmitted,
                        rows = read.value.snapshot.rows,
                        // PR-4 (CON-01): the carried trace surfaces with the
                        // same single load the rows came from.
                        localTrace = read.value.localTrace
                    )
                )
                // Absent / no index entry / expired-cleaned ⇒ Miss (§5.1).
                CacheReadResult.Absent -> ConversationCacheReadResult.Miss
                CacheReadResult.Expired -> ConversationCacheReadResult.Miss
                // Unavailable — guard, I/O, decrypt, parse, oversize ⇒ Unavailable.
                CacheReadResult.Unavailable -> ConversationCacheReadResult.Unavailable
                // Corrupt — parse/decryption rejection ⇒ Corrupt (§5.1 keeps it
                // distinct; recovery §5.3 owns the re-deletion of the record).
                CacheReadResult.Corrupt -> ConversationCacheReadResult.Corrupt
                // PartitionMismatch — identity ≠ resolved partition ⇒ same (§5.1).
                CacheReadResult.PartitionMismatch -> ConversationCacheReadResult.PartitionMismatch
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ConversationCacheReadResult.Unavailable
        }
    }

    override suspend fun replaceRemoteSnapshot(
        storedSessionId: String,
        resume: SessionResumeResult,
        nowMillis: Long
    ): ConversationCacheWriteResult = replaceRemoteSnapshotInternal(storedSessionId, resume, nowMillis, null, null)

    override suspend fun replaceRemoteSnapshotScoped(
        storedSessionId: String, resume: SessionResumeResult, nowMillis: Long,
        expected: AuthCredentialLease, selectedContext: String?
    ): ConversationCacheWriteResult = replaceRemoteSnapshotInternal(storedSessionId, resume, nowMillis, expected, selectedContext)

    private suspend fun replaceRemoteSnapshotInternal(
        storedSessionId: String, resume: SessionResumeResult, nowMillis: Long,
        expected: AuthCredentialLease?, selectedContext: String?
    ): ConversationCacheWriteResult {
        // Blank id ⇒ Failed — never build a partition-shaped record id from it.
        if (storedSessionId.isBlank()) return ConversationCacheWriteResult.Failed
        // REQ-09 guard: an empty capture never overwrites an existing snapshot.
        if (resume.messages.isEmpty()) return ConversationCacheWriteResult.Skipped
        val partition = when (val gate = resolvePartition()) {
            is PartitionGate.Resolved -> gate.partition
            // No partition, no cache — fail closed (RISK-04).
            is PartitionGate.Closed -> return ConversationCacheWriteResult.Failed
        }
        val recordId = ConversationCacheV2.recordIdFor(
            profileLocalId = partition.profileLocalId,
            originSha256 = partition.originSha256,
            cacheEpoch = partition.cacheEpoch,
            workspaceScopeTag = partition.workspaceScopeTag,
            durableSessionId = storedSessionId
        )
        val cache = ConversationCacheV2(
            recordId = recordId,
            profileLocalId = partition.profileLocalId,
            originSha256 = partition.originSha256,
            cacheEpoch = partition.cacheEpoch,
            workspaceScopeTag = partition.workspaceScopeTag,
            durableSessionId = storedSessionId,
            selectedWorkspaceName = if (expected == null) vaultStorage.getSelectedContextProfile() else selectedContext,
            snapshot = CachedRemoteHistorySnapshot(
                // Minted locally at capture; non-secret (SEC-01).
                snapshotId = UUID.randomUUID().toString(),
                capturedAtMillis = nowMillis,
                messagesOmitted = resume.messagesOmitted,
                // PR-3 never truncates: oversize writes are skipped whole
                // (REQ-06), so no partial snapshot exists.
                clientTruncated = false,
                rows = resume.messages.mapIndexed { position, message ->
                    CachedRemoteHistoryRow(
                        ordinal = position,
                        role = message.role,
                        content = message.content,
                        toolName = message.toolName,
                        toolContext = message.toolContext,
                        reasoning = message.reasoning,
                        createdAtMillis = message.createdAtMillis
                    )
                }
            ),
            lastAccessedAtMillis = nowMillis,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )
        return try {
            val written = if (expected == null) vaultStorage.replaceConversationSnapshot(cache, partition)
                else vaultStorage.replaceConversationSnapshotIfCurrent(cache, partition, expected, selectedContext)
            if (written) {
                ConversationCacheWriteResult.Written
            } else {
                // Vault returns false only for guarded skips (identity mismatch,
                // over-cap record, partition guard) — never for I/O (that throws).
                ConversationCacheWriteResult.Skipped
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // REQ-10: soft write failure — swallowed, never surfaces to chat.
            ConversationCacheWriteResult.Failed
        }
    }

    /**
     * PR-4 (plan CACHE-PR4 §5.5): wire the PR-3-reserved append hook —
     * only the four mapped observation types produce a DTO; args/result/
     * summary/payload are never read (SEC-06), and the write goes through
     * the defaulted append seam (PAT-01) under the same §5.2 write chain.
     */
    override suspend fun recordObservedToolEvent(
        storedSessionId: String,
        runtimeSessionId: String,
        event: GatewayEvent,
        observedAtMillis: Long
    ): ConversationCacheWriteResult {
        // §5.5 guard: a blank id never builds a record id — Skipped, not a
        // write failure (the caller simply has nothing to attach to).
        if (storedSessionId.isBlank()) return ConversationCacheWriteResult.Skipped
        // REQ-04 runtime component = the ADMISSION CONTEXT (never stored —
        // §5.3 NEVER-list): the caller passes the EXACT currently admitted
        // runtime and the event must carry that same runtime; re-asserted
        // here fail-closed as defense in depth (the VM collector guard is
        // the primary admission, ASSUMPTION-08).
        if (runtimeSessionId.isBlank() || event.sessionId != runtimeSessionId) {
            return ConversationCacheWriteResult.Skipped
        }
        val dto = when (event) {
            is GatewayEvent.ToolGenerating -> CachedLocalToolTraceEvent(
                localEventId = UUID.randomUUID().toString(),
                sourceSeq = event.seq,
                sourceType = ToolTraceSourceType.TOOL_GENERATING,
                toolId = null,
                name = event.name,
                context = null,
                status = ToolStatus.RUNNING,
                durationSeconds = null,
                observedAtMillis = observedAtMillis
            )
            is GatewayEvent.ToolStart -> CachedLocalToolTraceEvent(
                localEventId = UUID.randomUUID().toString(),
                sourceSeq = event.seq,
                sourceType = ToolTraceSourceType.TOOL_START,
                toolId = event.toolId.takeIf { !it.isNullOrBlank() },
                name = event.name,
                // The host's own context label, preserved as-is — it is not
                // a payload (args of this event are never touched).
                context = event.context?.takeIf { it.isNotBlank() },
                status = ToolStatus.RUNNING,
                durationSeconds = null,
                observedAtMillis = observedAtMillis
            )
            is GatewayEvent.ToolProgress -> CachedLocalToolTraceEvent(
                localEventId = UUID.randomUUID().toString(),
                sourceSeq = event.seq,
                sourceType = ToolTraceSourceType.TOOL_PROGRESS_GENERIC,
                toolId = null,
                // REQ-04 copy: the payload (and its type) never persists —
                // the fixed label is the ONLY name a generic progress row
                // ever carries, and context stays null (T-12).
                name = "Tool progress",
                context = null,
                status = ToolStatus.RUNNING,
                durationSeconds = null,
                observedAtMillis = observedAtMillis
            )
            is GatewayEvent.ToolComplete -> CachedLocalToolTraceEvent(
                localEventId = UUID.randomUUID().toString(),
                sourceSeq = event.seq,
                sourceType = ToolTraceSourceType.TOOL_COMPLETE,
                toolId = event.toolId.takeIf { !it.isNullOrBlank() },
                name = event.name,
                context = null,
                status = ToolStatus.COMPLETE,
                durationSeconds = event.durationSeconds,
                observedAtMillis = observedAtMillis
            )
            else -> return ConversationCacheWriteResult.Skipped
        }
        val partition = when (val gate = resolvePartition()) {
            is PartitionGate.Resolved -> gate.partition
            // No partition, no cache — the observation is dropped (the trace
            // never blocks or authorizes the live tool path).
            is PartitionGate.Closed -> return ConversationCacheWriteResult.Skipped
        }
        return try {
            if (vaultStorage.appendConversationToolTrace(partition, storedSessionId, dto)) {
                ConversationCacheWriteResult.Written
            } else {
                ConversationCacheWriteResult.Skipped
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Soft write failure — the tool path already rendered; the trace
            // is advisory evidence only (never surfaces to chat).
            ConversationCacheWriteResult.Failed
        }
    }

    /** Fail-closed partition gate — the sole authority on WHERE cache lives. */
    private sealed interface PartitionGate {
        data class Resolved(val partition: com.hermes.bot.data.local.CacheAccessPartition) : PartitionGate
        data object Closed : PartitionGate
    }

    private suspend fun resolvePartition(): PartitionGate = when (val result = vaultStorage.activeAccessPartition()) {
        is CacheAccessPartitionResult.Available -> PartitionGate.Resolved(result.partition)
        is CacheAccessPartitionResult.Unavailable -> PartitionGate.Closed
    }
}
