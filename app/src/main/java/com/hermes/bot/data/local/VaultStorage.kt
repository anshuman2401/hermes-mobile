package com.hermes.bot.data.local

import java.io.File

sealed interface ProfileCommitResult {
    data object SAVED : ProfileCommitResult
    data object STALE : ProfileCommitResult
    data class FAILED(val exceptionClass: String) : ProfileCommitResult
}

sealed interface ProfileReadResult {
    data class AVAILABLE(val profile: ConnectionProfile) : ProfileReadResult
    data object ABSENT : ProfileReadResult
    data class FAILED(val exceptionClass: String) : ProfileReadResult
}

/** Authority is distinct from the legacy nullable/recovering reader. */
sealed interface SelectedContextReadResult {
    class Selected(val name: String) : SelectedContextReadResult {
        override fun toString() = "SelectedContextReadResult.Selected([redacted])"
    }
    data object Absent : SelectedContextReadResult
    data object Unreadable : SelectedContextReadResult
    data object Failed : SelectedContextReadResult
}
class WorkspaceRecoveryOwner internal constructor(
    internal val lease: AuthCredentialLease,
    val selection: SelectedContextReadResult
) { override fun toString() = "WorkspaceRecoveryOwner([redacted])" }
sealed interface WorkspaceRecoveryOwnerResult {
    class Captured(val owner: WorkspaceRecoveryOwner) : WorkspaceRecoveryOwnerResult {
        override fun toString() = "WorkspaceRecoveryOwnerResult.Captured([redacted])"
    }
    data object Unavailable : WorkspaceRecoveryOwnerResult
}
enum class WorkspaceClearResult { Applied, Stale, Failed, Unsupported }

/** Local read authority; cookie renewal cannot change this owner. Never persist or display it. */
class ConversationCacheReadOwner internal constructor(
    internal val partition: CacheAccessPartition,
    internal val selectedContext: String?,
    internal val loginGeneration: Long,
    internal val cacheGeneration: Long,
    internal val previewGeneration: Long = 0
) { override fun toString() = "ConversationCacheReadOwner([redacted])" }

/**
 * The one linearization point shared by connection invalidation and the final
 * profile replacement. Its critical sections intentionally contain no suspension.
 */
class ConnectionProfileGenerationGate {
    private var generation = 0L

    @Synchronized fun beginAttempt(): Long = ++generation

    @Synchronized fun invalidate(): Long = ++generation

    @Synchronized fun isCurrent(expectedGeneration: Long): Boolean = generation == expectedGeneration

    @Synchronized fun currentGeneration(): Long = generation

    @Synchronized fun commitIfCurrent(
        expectedGeneration: Long,
        commit: () -> ProfileCommitResult
    ): ProfileCommitResult = if (generation == expectedGeneration) commit() else ProfileCommitResult.STALE
}

/**
 * PR-5 (plan CACHE-PR5) / V2 §7 line 248 re-anchored: main landed PR-1
 * without this interface, so PR-5 declares the lifecycle member it needs —
 * the all-cache local deletion — as the ONLY member (the §7 rotate/purge
 * members were landed inside saveAuthCredential/clearAuthCredential's
 * rotation funnel instead; PR-5 does not invent empty API surface for them).
 *
 * PAT-01: defaulted so fake/delegating VaultStorage implementations compile
 * unchanged. Product code never relies on the default — EncryptedVault
 * performs the real seven-phase deletion behind VaultStorage.clearCache().
 */
interface CacheLifecycleStore {
    suspend fun clearAllCacheRecords(): CacheClearResult = CacheClearResult.Complete
}

/**
 * Encrypted vault / persistence boundary contract.
 * Backed by Android Keystore per local-persistence-contract.md.
 */
interface VaultStorage : CacheLifecycleStore {

    // Connection profiles & multi-profile index
    suspend fun listConnectionProfiles(): List<ConnectionProfile>
    suspend fun getConnectionProfile(localId: String? = null): ConnectionProfile?
    suspend fun saveConnectionProfile(profile: ConnectionProfile)
    suspend fun deleteConnectionProfile(localId: String): Boolean
    suspend fun clearConnectionProfile(localId: String? = null)

    suspend fun getActiveProfileId(): String?
    suspend fun setActiveProfileId(localId: String): Boolean
    suspend fun getMigratedProfileId(): String? = null

    /** Reads without recovery/deletion so an authenticated update cannot mutate corrupt prior bytes. */
    suspend fun readConnectionProfileForUpdate(localId: String? = null): ProfileReadResult = try {
        getConnectionProfile(localId)?.let(ProfileReadResult::AVAILABLE) ?: ProfileReadResult.ABSENT
    } catch (exception: Exception) {
        ProfileReadResult.FAILED(exception.javaClass.simpleName)
    }

    /** Implementations that cannot prove a guarded atomic replacement fail closed. */
    suspend fun saveConnectionProfileIfCurrent(
        profile: ConnectionProfile,
        generation: Long,
        generationGate: ConnectionProfileGenerationGate
    ): ProfileCommitResult = ProfileCommitResult.FAILED("UnsupportedConditionalProfileCommit")

    // Silent recovery: unsupported stores fail closed.
    fun invalidateLoginOwners() {}
    suspend fun captureLoginOwner(origin: String): LoginOwner? = null
    suspend fun readRememberedLogin(owner: LoginOwner): RememberedLogin? = null
    suspend fun readRememberedLogin(owner: LoginOwner, explicitConnect: Boolean): RememberedLogin? =
        readRememberedLogin(owner)
    suspend fun rememberedEndpoint(host: String? = null): com.hermes.bot.data.origin.CanonicalOrigin? = null
    suspend fun rememberLoginPreference(origin: String): Boolean = true
    suspend fun hasRememberedLogin(origin: String): Boolean = false
    suspend fun hasAutomaticLogin(origin: String): Boolean = false
    suspend fun forgetRememberedLogin(origin: String) {}
    suspend fun noteSuccessfulEndpoint(origin: String) {}
    suspend fun selectConnectionForEndpoint(origin: String): Boolean = false
    suspend fun commitRememberedLogin(owner: LoginOwner, credential: AuthCredential,
        username: String, password: CharArray): Boolean = false
    suspend fun commitRememberedLogin(owner: LoginOwner, credential: AuthCredential,
        username: String, password: CharArray, remember: Boolean): Boolean =
        if (remember) commitRememberedLogin(owner, credential, username, password) else false
    suspend fun rejectRememberedLogin(owner: LoginOwner) {}
    suspend fun prepareLocalLogin(origin: String): Boolean = false

    // Auth credential (keyed per-profile)
    suspend fun getAuthCredential(profileLocalId: String? = null): AuthCredential?
    suspend fun saveAuthCredential(credential: AuthCredential, profileLocalId: String? = null)
    /**
     * PR-1 REQ-06: clears the credential. The partition epoch rotates BEFORE
     * the clear; a rotation record is only rotated when one already exists —
     * sign-out (SIGNED_OUT) never mints a usable one (SEC-01).
     */
    suspend fun clearAuthCredential(
        profileLocalId: String? = null,
        reason: CacheEpochRotationReason = CacheEpochRotationReason.CREDENTIAL_REPLACED,
        // PR-6 REQ-02/REQ-07: the KEEP/CLEAR choice is retained (defaulted
        // KEEP for `by delegate` fakes and direct legacy calls — retention is
        // the safe default). The ENFORCEMENT point is AuthRepository.signOut's
        // non-defaulted parameter; product code never relies on this default.
        cacheChoice: SignOutCacheChoice = SignOutCacheChoice.KEEP
    )

    /** Unsupported stores fail closed; no read-then-write emulation of atomicity. */
    suspend fun readAuthCredentialLease(origin: String): AuthCredentialLease? = null
    suspend fun mutateAuthCredentialIfCurrent(
        lease: AuthCredentialLease,
        replacement: AuthCredential?,
        clear: Boolean = false
    ): AuthCredentialMutationResult = AuthCredentialMutationResult.Failed

    // Local session preferences; implementations without a store cannot claim a saved pin.
    suspend fun getSessionPins(): SessionPins? = null
    suspend fun setSessionPinned(scope: SessionPinScope, sessionId: String, pinned: Boolean): SessionPins =
        throw UnsupportedOperationException("Local pins unavailable")
    suspend fun getSessionFilters(): SessionFilters? = null
    suspend fun saveSessionFilters(filters: SessionFilters): SessionFilters =
        throw UnsupportedOperationException("Local filters unavailable")

    /** Device-only board selection; implementations without encrypted storage do not persist it. */
    suspend fun getKanbanBoardSelection(profileId: String, origin: String): String? = null
    suspend fun saveKanbanBoardSelection(profileId: String, origin: String, slug: String): Boolean = false

    // Ordered bot preferences; unsupported stores never claim a successful save.
    suspend fun getBotFavourites(profileId: String, origin: String): BotFavourites? = null
    suspend fun setBotFavourite(expected: BotFavourites, name: String, favourite: Boolean): BotFavourites =
        throw UnsupportedOperationException("Bot favourites unavailable")

    // Session selection
    suspend fun getSessionSelection(): SessionSelection?
    suspend fun saveSessionSelection(selection: SessionSelection)
    /** Captured owner and exact selected context are compared at the serialized write boundary. */
    suspend fun saveSessionSelectionIfCurrent(
        selection: SessionSelection, expected: AuthCredentialLease, selectedContext: String?
    ): Boolean = false
    suspend fun clearSessionSelection()

    // PR-1: partitioned session summary cache (offline-readable session list)
    /**
     * Resolve the partition gate for offline summary reads/writes: active
     * profile + origin-bound credential + persisted rotation state (REQ-06),
     * scoped to the current workspace selection (REQ-04). Unavailable means
     * offline summary serving must fail closed (SEC-02).
     */
    suspend fun activeAccessPartition(): CacheAccessPartitionResult

    /**
     * Read the partition-bound schema-3 summary record. 7-day retention,
     * origin/workspace/epoch/profile identity binding, payload size/count caps,
     * and capture-clock skew are enforced at read time (REQ-03); failures
     * delete the record and never fall back to legacy data (REQ-05).
     */
    suspend fun readSessionSummaryCache(partition: CacheAccessPartition): CacheReadResult<SessionSummaryCache>

    /**
     * Write the summary record under [partition]'s identity (schema 3, durable
     * atomic write, count/size capped).
     */
    suspend fun saveSessionSummaryCache(cache: SessionSummaryCache, partition: CacheAccessPartition)

    /**
     * Remove every partition's summary record and quarantine residue under
     * cache/. Rotation state and profiles are preserved (REQ-08 granular clear).
     */
    suspend fun clearSessionSummaryCache()

    // PR-2: partitioned context-profile (workspace) roster cache — the
    // read-only Workspaces sheet's offline source (PAT-01 defaults).
    /**
     * Read the partition-bound schema-2 roster. 24h TTL from the capture
     * instant, origin/workspace/epoch/profile identity, selected-name ⇔ W
     * consistency and entry/byte caps are enforced at read time (REQ-03);
     * every failure deletes/quarantines the record and never falls back.
     */
    suspend fun readContextProfileCache(
        partition: CacheAccessPartition
    ): CacheReadResult<ContextProfileCacheV2> = CacheReadResult.Absent

    /**
     * Write the roster under [partition]'s identity (schema 2, durable atomic
     * write). An entry/byte-cap breach or an identity/tag mismatch skips the
     * write entirely — callers treat capture as best-effort (REQ-02).
     */
    suspend fun saveContextProfileCache(
        cache: ContextProfileCacheV2,
        partition: CacheAccessPartition
    ) {}

    // Local unsent intent, authenticated partition and durable conversation bound.
    suspend fun readChatQueue(partition: CacheAccessPartition, storedId: String): String? =
        throw UnsupportedOperationException("Queue storage unavailable")
    suspend fun writeChatQueue(partition: CacheAccessPartition, storedId: String, json: String, expectedJson: String?): Boolean = false

    // Drafts (30 days inactivity expiry)
    suspend fun getDraft(storedSessionId: String): Draft?
    suspend fun saveDraft(draft: Draft)
    suspend fun deleteDraft(storedSessionId: String)
    suspend fun getAllDrafts(): List<Draft>
    suspend fun clearDrafts()

    // Pending-turn ledger
    suspend fun getPendingTurn(pendingTurnId: String): PendingTurnRecord?
    suspend fun getAllPendingTurns(): List<PendingTurnRecord>
    suspend fun savePendingTurn(record: PendingTurnRecord)
    suspend fun updatePendingTurnState(pendingTurnId: String, newState: PendingTurnState)
    suspend fun deletePendingTurn(pendingTurnId: String)
    suspend fun clearPendingTurns()

    // Pending-interrupt holds (T3: stop taps held across a connection gap)
    suspend fun savePendingInterrupt(record: PendingInterruptRecord)
    suspend fun getPendingInterrupts(): List<PendingInterruptRecord>
    suspend fun deletePendingInterrupt(storedSessionId: String)
    suspend fun clearPendingInterrupts()

    // Diagnostics (ring buffer)
    suspend fun recordDiagnostic(diagnostic: LocalDiagnostic)
    suspend fun getDiagnostics(): List<LocalDiagnostic>
    suspend fun clearDiagnostics()

    // Granular resets
    /**
     * PR-5 REQ-02/REQ-03 (re-specified: parameter signature unchanged, result
     * now carries the residue): the SEVEN-PHASE all-cache local deletion —
     * conversation content → per-partition index envelopes → catalog → roster
     * records → session summaries, then the directory-sync seam → pending
     * interrupts — as ONE mutex-held run. Deletes exactly those five content
     * families, prefix-scoped so `cache_access_*` rotation state can never
     * match; credentials, connection profiles, session selection, drafts,
     * pending turns, diagnostics, quarantine/disposition and the partition
     * epoch all survive (REQ-06/SEC-03/SEC-04). Every phase is
     * delete-if-present (idempotent: an interruption leaves only opaque AEAD
     * ciphertext plus a non-authorizing catalog that §5.3 recovery rebuilds;
     * a re-run completes — REQ-03/SEC-02). I/O errors convert to a
     * residue-carrying [CacheClearResult]; the flow never blocks, never
     * deletes a Hermes session remotely, and opens no diagnostics vocabulary
     * (OQ-02). See [CacheLifecycleStore.clearAllCacheRecords] for the phase
     * order itself.
     */
    suspend fun clearCache(): CacheClearResult
    suspend fun clearCredentials()
    /**
     * PR-5 REQ-04/REQ-10 (re-specified: parameter signature unchanged, result
     * now carries the residue): the whole-vault wipe, SEC-05-ordered —
     * credentials + selection first, then the cache families, then the local
     * residue — with `migrationChecked = false` so migration re-runs on next
     * init. Covers all five cache families via the recursive layout wipe
     * (zero cache files is the completeness assertion; no new wipe logic).
     * Falls back to the residue-carrying [CacheClearResult] on I/O failure;
     * deletion order does not change (REQ-10).
     */
    suspend fun clearAll(): CacheClearResult

    // Context profile (workspace) selection
    suspend fun readSelectedContextProfileAuthoritative(): SelectedContextReadResult = SelectedContextReadResult.Failed
    suspend fun captureWorkspaceRecoveryOwner(): WorkspaceRecoveryOwnerResult = WorkspaceRecoveryOwnerResult.Unavailable
    suspend fun clearSelectedContextProfileIfCurrent(expectedLease: AuthCredentialLease, expectedContext: String): WorkspaceClearResult = WorkspaceClearResult.Unsupported
    suspend fun getSelectedContextProfile(): String? = null
    suspend fun saveSelectedContextProfile(name: String) {}
    suspend fun clearSelectedContextProfile() {}

    // CHAT-011 S1 (PAT-001 defaults): per-workspace default model + reasoning
    // effort preference record — singleton file `profile_model_defaults.enc`,
    // map keyed by workspace name. Read returns null for an unknown workspace,
    // an absent record, an unreadable/corrupt file, or a future schema
    // (fail-closed exactly like the selection singleton). A vault write
    // failure propagates so the seed path can swallow it (§state-ownership).
    suspend fun getProfileModelDefault(workspace: String): ProfileModelDefault? = null
    suspend fun saveProfileModelDefault(record: ProfileModelDefault) {}

    // PR-3 (PAT-01 defaults): offline conversation snapshot cache — read/write
    // bound to the ACTIVE resolved partition; failure mappings (Miss/
    // Unavailable/Corrupt/PartitionMismatch) survive here so a nonexistent
    // snapshot degrades exactly like a failed live fetch (REQ-09).

    /**
     * Read the partition-bound conversation snapshot for [storedSessionId]
     * (7-day TTL, identity binding, payload caps — REQ-02 read order).
     */
    suspend fun readConversationSnapshot(
        partition: CacheAccessPartition,
        storedSessionId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CacheReadResult<ConversationCacheV2> = CacheReadResult.Absent

    /** Process-local gate: callbacks must be short, non-suspending and perform no vault IO. */
    fun withConversationPreviewOwner(owner: ConversationCacheReadOwner, publish: () -> Unit): Boolean = false
    fun observeConversationPreviewInvalidation(observer: () -> Unit): () -> Unit = {}

    /** Unsupported stores cannot authorize a connected cache preview. */
    suspend fun captureConversationCacheReadOwner(): ConversationCacheReadOwner? = null
    suspend fun isConversationCacheReadOwnerCurrent(owner: ConversationCacheReadOwner): Boolean = false
    suspend fun readConversationSnapshotForOwner(
        storedSessionId: String, owner: ConversationCacheReadOwner
    ): CacheReadResult<ConversationCacheV2> = CacheReadResult.Unavailable

    /**
     * Atomically REPLACE the snapshot for [cache.durableSessionId] under
     * [partition]'s identity — write order content → index → caps/evict →
     * catalog (REQ-01). Returns false when the write is skipped (identity
     * mismatch, over-cap record, partition-unavailable guard) and never throws
     * for those cases; genuine I/O failures propagate so the caller can map
     * them to a soft write failure (REQ-10, roster-style guard).
     */
    suspend fun replaceConversationSnapshot(
        cache: ConversationCacheV2,
        partition: CacheAccessPartition
    ): Boolean = false

    /** Unsupported stores never fall back to ambient snapshot access. */
    suspend fun replaceConversationSnapshotIfCurrent(
        cache: ConversationCacheV2, partition: CacheAccessPartition,
        expected: AuthCredentialLease, selectedContext: String?
    ): Boolean = false

    suspend fun readConversationSnapshotIfCurrent(
        partition: CacheAccessPartition, storedSessionId: String,
        expected: AuthCredentialLease, selectedContext: String?
    ): CacheReadResult<ConversationCacheV2> = CacheReadResult.Unavailable

    /**
     * PR-4 (plan CACHE-PR4 §5.5, PAT-01 default): append ONE phone-captured
     * tool-trace event to an EXISTING, unexpired snapshot record under
     * [partition]'s identity. §5.2 write order (content → index → catalog)
     * still holds; miss/expired/mismatch records are NEVER created by an
     * append (trace never authorizes). Best-effort: guarded skips and I/O
     * failures return false and never surface to chat.
     */
    suspend fun appendConversationToolTrace(
        partition: CacheAccessPartition,
        storedSessionId: String,
        event: CachedLocalToolTraceEvent
    ): Boolean = false

    /** Drop a single session's snapshot + index entry (delete-flow ordering). */
    suspend fun deleteConversationSnapshot(
        partition: CacheAccessPartition,
        storedSessionId: String
    ) {}

    /**
     * Run §5.3 conversation-cache recovery now (index validation, orphan
     * content scan, quarantine sweep, catalog rebuild). Also invoked at vault
     * construction; best-effort and never fatal (REQ-03).
     */
    suspend fun runConversationCacheRecovery() {}

    /** REQ-08: remove one profile+origin's conversation content/index/catalog. */
    suspend fun purgeConversationCache(profileLocalId: String, originSha256: String) {}

    /** REQ-08: remove ALL conversation snapshots/indexes/catalog; rotation survives. */
    suspend fun clearConversationCache() {}
}

/**
 * P2-1 (review, REQ-02/PAT-01): best-effort parent-directory fsync so a
 * rotation-state rename survives sudden power loss. Every failure —
 * including the JVM unit-test android.system.Os stub — is swallowed:
 * durability is best-effort and must never block or fail the mutation it
 * guards (PAT-01/RISK-03 fail-soft).
 */
fun fsyncDirectoryBestEffort(directory: File) {
    try {
        val fd = android.system.Os.open(
            directory.absolutePath,
            android.system.OsConstants.O_RDONLY,
            0
        )
        try {
            android.system.Os.fsync(fd)
        } finally {
            try {
                android.system.Os.close(fd)
            } catch (_: Throwable) {
                // Best-effort close.
            }
        }
    } catch (_: Throwable) {
        // Best-effort: no Android runtime / unsupported filesystem / missing dir.
    }
}

/**
 * P3-3 (review): cache epochs are 128 bits from [java.security.SecureRandom],
 * base64url-encoded without padding — the binding schema's shape. Replaces
 * "millis-UUID"; epochs are only ever compared for equality (rotation
 * detection), so the millisecond prefix bought nothing while leaking time,
 * and 128 random bits make rotation detection collision-safe.
 */
fun mintSecureCacheEpoch(): String =
    java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
