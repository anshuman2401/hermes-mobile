package com.hermes.bot.data.brain

import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.auth.StaleAuthenticatedResponseException
import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.gateway.json.JsonNull
import com.hermes.bot.data.gateway.json.JsonObject
import com.hermes.bot.data.local.AuthCredentialLease
import com.hermes.bot.data.local.VaultStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** The fixed, owner-visible auxiliary assignment slots. */
enum class BrainAuxiliaryTask(
    val wireKey: String,
    val displayName: String,
    val purpose: String
) {
    Vision("vision", "Vision", "Image analysis"),
    Compression("compression", "Compression", "Context compaction"),
    SkillsHub("skills_hub", "Skills Hub", "Skill search"),
    Approval("approval", "Approval", "Smart auto-approve"),
    Mcp("mcp", "MCP", "MCP tool routing"),
    TitleGeneration("title_generation", "Title Generation", "Session titles"),
    Review("review", "Review", "/review reviewer subagent"),
    TriageSpecifier("triage_specifier", "Triage Specifier", "Kanban spec fleshing"),
    KanbanDecomposer("kanban_decomposer", "Kanban Decomposer", "Task decomposition"),
    ProfileDescriber("profile_describer", "Profile Describer", "Auto profile descriptions"),
    Curator("curator", "Curator", "Skill-usage review");

    companion object {
        fun fromWireKey(key: String): BrainAuxiliaryTask? = entries.firstOrNull { it.wireKey == key }
    }
}

sealed interface BrainAuxiliaryDraft {
    data object Auto : BrainAuxiliaryDraft
    data class Concrete(val provider: String, val model: String) : BrainAuxiliaryDraft
}

/** Public assignment projection; endpoint values and credentials never cross this boundary. */
data class BrainAuxiliaryAssignment(
    val task: BrainAuxiliaryTask,
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
    val requiresEndpointPassthrough: Boolean = false
)

enum class BrainAuxiliaryTaskUnavailableReason {
    MISSING_FROM_HOST
}

data class BrainAuxiliaryTaskRow(
    val task: BrainAuxiliaryTask,
    val assignment: BrainAuxiliaryAssignment?,
    val unavailableReason: BrainAuxiliaryTaskUnavailableReason? = null
)

data class BrainAuxiliaryModelsSnapshot(
    val snapshotId: Long,
    val selectedProfile: String,
    val tasks: List<BrainAuxiliaryTaskRow>,
    val providers: List<BrainModelProvider>,
    val catalogAvailable: Boolean
)

enum class BrainAuxiliaryModelsLoadFailureReason {
    INVALID_SELECTION,
    UNAVAILABLE,
    MALFORMED,
    STALE
}

data class BrainAuxiliaryModelsLoadFailure(
    val reason: BrainAuxiliaryModelsLoadFailureReason
)

sealed interface BrainAuxiliaryModelsLoadResult {
    data class Available(val snapshot: BrainAuxiliaryModelsSnapshot) : BrainAuxiliaryModelsLoadResult
    data class Partial(val snapshot: BrainAuxiliaryModelsSnapshot) : BrainAuxiliaryModelsLoadResult
    data class Unavailable(val failure: BrainAuxiliaryModelsLoadFailure) : BrainAuxiliaryModelsLoadResult
}

enum class BrainAuxiliaryModelsApplyFailureReason {
    INVALID_SELECTION,
    STALE,
    TASK_UNAVAILABLE,
    INVALID_DRAFT,
    CATALOG_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
    ENDPOINT_UNSUPPORTED,
    WRITE_REJECTED,
    CONFIRMATION_REJECTED,
    READBACK_UNAVAILABLE,
    READBACK_MISMATCH,
    READBACK_MALFORMED,
    AUTH_UNAVAILABLE
}

sealed interface BrainAuxiliaryModelsApplyResult {
    data class Saved(val snapshot: BrainAuxiliaryModelsSnapshot) : BrainAuxiliaryModelsApplyResult

    data class ConfirmationRequired(
        val snapshotId: Long,
        val warning: String
    ) : BrainAuxiliaryModelsApplyResult

    data class NotConfirmed(
        val reason: BrainAuxiliaryModelsApplyFailureReason,
        val mayHaveSaved: Boolean,
        val authoritativeSnapshot: BrainAuxiliaryModelsSnapshot? = null
    ) : BrainAuxiliaryModelsApplyResult

    data class Rejected(
        val reason: BrainAuxiliaryModelsApplyFailureReason
    ) : BrainAuxiliaryModelsApplyResult
}

/**
 * Selected-profile auxiliary model assignment orchestration. Wire ownership
 * remains in [BrainModelSettingsReader] and [BrainModelReadTransport]; this
 * repository owns the fixed task projection, sparse mutation, mutex and
 * authoritative readback.
 */
class BrainAuxiliaryModelsRepository(
    private val vaultStorage: VaultStorage,
    private val authRepository: AuthRepository,
    private val gatewayAdapter: GatewayProtocolAdapter? = null,
    private val transport: BrainModelReadTransport = OkHttpBrainModelReadTransport(),
    private val reader: BrainModelSettingsReader = BrainModelSettingsReader(
        vaultStorage = vaultStorage,
        authRepository = authRepository,
        transport = transport,
        gatewayAdapter = gatewayAdapter
    )
) {
    internal val configuredVaultStorage get() = vaultStorage
    internal val configuredAuthRepository get() = authRepository
    internal val configuredGatewayAdapter get() = gatewayAdapter

    private val operationMutex = Mutex()
    private var nextSnapshotId = 0L
    private var loaded: LoadedState? = null
    private var pendingConfirmation: PendingConfirmation? = null

    suspend fun load(): BrainAuxiliaryModelsLoadResult = operationMutex.withLock {
        pendingConfirmation = null
        val target = when (val capture = reader.captureTargetResult()) {
            BrainModelTargetCaptureResult.InvalidSelection -> {
                loaded = null
                return@withLock BrainAuxiliaryModelsLoadResult.Unavailable(
                    BrainAuxiliaryModelsLoadFailure(BrainAuxiliaryModelsLoadFailureReason.INVALID_SELECTION)
                )
            }
            is BrainModelTargetCaptureResult.Unavailable -> {
                loaded = null
                return@withLock BrainAuxiliaryModelsLoadResult.Unavailable(
                    BrainAuxiliaryModelsLoadFailure(capture.reason.toAuxiliaryFailureReason())
                )
            }
            is BrainModelTargetCaptureResult.Captured -> capture.target
        }

        val catalog = reader.readCaptured(target)
        val catalogSettings = (catalog as? BrainModelCapturedReadResult.Available)?.settings
        val catalogFailure = (catalog as? BrainModelCapturedReadResult.Unavailable)?.reason
        val catalogReceipt = when (catalog) {
            is BrainModelCapturedReadResult.Available -> catalog.receipt
            is BrainModelCapturedReadResult.Unavailable -> catalog.receipt
        }

        // A catalog failure may still leave a usable accepted receipt. That
        // receipt can support an auxiliary-only authoritative read; it never
        // authorizes ambient-profile discovery or a fabricated catalog.
        if (catalogReceipt == null || catalogFailure == BrainModelReadFailure.STALE ||
            !reader.isTargetCurrent(target, catalogReceipt)
        ) {
            loaded = null
            return@withLock BrainAuxiliaryModelsLoadResult.Unavailable(
                BrainAuxiliaryModelsLoadFailure(
                    (catalogFailure ?: BrainModelReadFailure.STALE).toAuxiliaryFailureReason()
                )
            )
        }

        val auxiliary = readAuxiliaryCaptured(target, catalogReceipt)
        val available = auxiliary as? AuxiliaryRead.Available
        if (available == null) {
            loaded = null
            val failure = auxiliary as AuxiliaryRead.Unavailable
            return@withLock BrainAuxiliaryModelsLoadResult.Unavailable(
                BrainAuxiliaryModelsLoadFailure(failure.reason.toAuxiliaryFailureReason())
            )
        }
        if (!reader.isTargetCurrent(target, available.receipt)) {
            loaded = null
            return@withLock BrainAuxiliaryModelsLoadResult.Unavailable(
                BrainAuxiliaryModelsLoadFailure(BrainAuxiliaryModelsLoadFailureReason.STALE)
            )
        }

        currentCoroutineContext().ensureActive()
        val snapshot = makeSnapshot(
            snapshotId = ++nextSnapshotId,
            selectedProfile = target.selectedProfile,
            assignments = available.assignments,
            providers = catalogSettings?.providers ?: emptyList(),
            catalogAvailable = catalogSettings != null
        )
        loaded = LoadedState(snapshot, target, available.receipt)
        if (catalogSettings != null && snapshot.tasks.all { it.assignment != null }) {
            BrainAuxiliaryModelsLoadResult.Available(snapshot)
        } else {
            BrainAuxiliaryModelsLoadResult.Partial(snapshot)
        }
    }

    suspend fun apply(
        snapshotId: Long,
        task: BrainAuxiliaryTask,
        draft: BrainAuxiliaryDraft
    ): BrainAuxiliaryModelsApplyResult = operationMutex.withLock {
        val waiting = pendingConfirmation
        if (waiting != null && waiting.snapshot.snapshotId == snapshotId &&
            waiting.task == task && waiting.draft == draft
        ) {
            return@withLock BrainAuxiliaryModelsApplyResult.ConfirmationRequired(
                snapshotId,
                CONFIRMATION_WARNING
            )
        }
        pendingConfirmation = null
        val state = loaded ?: return@withLock notConfirmed(
            BrainAuxiliaryModelsApplyFailureReason.STALE,
            mayHaveSaved = false
        )
        if (state.snapshot.snapshotId != snapshotId) {
            return@withLock notConfirmed(BrainAuxiliaryModelsApplyFailureReason.STALE, false)
        }
        if (!reader.isTargetCurrent(state.target, state.receipt)) {
            loaded = null
            return@withLock notConfirmed(BrainAuxiliaryModelsApplyFailureReason.STALE, false)
        }

        val row = state.snapshot.tasks.firstOrNull { it.task == task }
            ?: return@withLock BrainAuxiliaryModelsApplyResult.Rejected(
                BrainAuxiliaryModelsApplyFailureReason.TASK_UNAVAILABLE
            )
        if (row.assignment == null) {
            return@withLock BrainAuxiliaryModelsApplyResult.Rejected(
                BrainAuxiliaryModelsApplyFailureReason.TASK_UNAVAILABLE
            )
        }
        if (matches(row.assignment, draft)) {
            return@withLock BrainAuxiliaryModelsApplyResult.Saved(state.snapshot)
        }

        when (val validation = validateDraft(state.snapshot, draft)) {
            null -> Unit
            else -> return@withLock BrainAuxiliaryModelsApplyResult.Rejected(validation)
        }

        when (val mutation = mutate(
            target = state.target,
            expected = state.receipt,
            task = task,
            draft = draft,
            confirmed = false
        )) {
            is Mutation.Warning -> {
                retainAcceptedReceipt(state, state.target, mutation.receipt)
                pendingConfirmation = PendingConfirmation(
                    snapshot = state.snapshot,
                    target = state.target,
                    receipt = mutation.receipt,
                    task = task,
                    draft = draft
                )
                BrainAuxiliaryModelsApplyResult.ConfirmationRequired(
                    state.snapshot.snapshotId,
                    CONFIRMATION_WARNING
                )
            }
            is Mutation.Success -> reconcile(state, task, draft, mutation.receipt)
            is Mutation.Rejected -> {
                mutation.receipt?.let { retainAcceptedReceipt(state, state.target, it) }
                BrainAuxiliaryModelsApplyResult.Rejected(mutation.reason)
            }
            is Mutation.Ambiguous -> reconcileAmbiguous(state, task, draft, mutation)
        }
    }

    /** Consumes the one frozen warning acknowledgement; it never auto-replays. */
    suspend fun confirmWarning(snapshotId: Long): BrainAuxiliaryModelsApplyResult = operationMutex.withLock {
        val pending = pendingConfirmation
            ?: return@withLock notConfirmed(BrainAuxiliaryModelsApplyFailureReason.STALE, false)
        if (pending.snapshot.snapshotId != snapshotId) {
            return@withLock notConfirmed(BrainAuxiliaryModelsApplyFailureReason.STALE, false)
        }
        pendingConfirmation = null
        if (!reader.isTargetCurrent(pending.target, pending.receipt)) {
            loaded = null
            return@withLock notConfirmed(BrainAuxiliaryModelsApplyFailureReason.STALE, false)
        }
        when (val mutation = mutate(
            target = pending.target,
            expected = pending.receipt,
            task = pending.task,
            draft = pending.draft,
            confirmed = true
        )) {
            is Mutation.Success -> reconcile(
                LoadedState(pending.snapshot, pending.target, pending.receipt),
                pending.task,
                pending.draft,
                mutation.receipt
            )
            is Mutation.Warning -> BrainAuxiliaryModelsApplyResult.Rejected(
                BrainAuxiliaryModelsApplyFailureReason.CONFIRMATION_REJECTED
            )
            is Mutation.Rejected -> {
                mutation.receipt?.let {
                    retainAcceptedReceipt(
                        LoadedState(pending.snapshot, pending.target, pending.receipt),
                        pending.target,
                        it
                    )
                }
                BrainAuxiliaryModelsApplyResult.Rejected(mutation.reason)
            }
            is Mutation.Ambiguous -> reconcileAmbiguous(
                LoadedState(pending.snapshot, pending.target, pending.receipt),
                pending.task,
                pending.draft,
                mutation
            )
        }
    }

    private suspend fun reconcile(
        state: LoadedState,
        task: BrainAuxiliaryTask,
        draft: BrainAuxiliaryDraft,
        receipt: AuthCredentialLease
    ): BrainAuxiliaryModelsApplyResult {
        return try {
            val readback = readAuxiliaryCaptured(state.target, receipt)
            val available = readback as? AuxiliaryRead.Available
                ?: return invalidateAfterReadback(
                    (readback as AuxiliaryRead.Unavailable).reason.toApplyFailureReason(),
                    mayHaveSaved = true
                )
            if (!reader.isTargetCurrent(state.target, available.receipt)) {
                loaded = null
                return notConfirmed(BrainAuxiliaryModelsApplyFailureReason.STALE, true)
            }
            val authoritative = makeSnapshot(
                snapshotId = state.snapshot.snapshotId,
                selectedProfile = state.snapshot.selectedProfile,
                assignments = available.assignments,
                providers = state.snapshot.providers,
                catalogAvailable = state.snapshot.catalogAvailable
            )
            if (authoritative.tasks.firstOrNull { it.task == task }?.assignment?.let { matches(it, draft) } == true) {
                loaded = LoadedState(authoritative, state.target, available.receipt)
                BrainAuxiliaryModelsApplyResult.Saved(authoritative)
            } else {
                loaded = null
                BrainAuxiliaryModelsApplyResult.NotConfirmed(
                    BrainAuxiliaryModelsApplyFailureReason.READBACK_MISMATCH,
                    mayHaveSaved = true,
                    authoritativeSnapshot = authoritative
                )
            }
        } catch (cancelled: CancellationException) {
            loaded = null
            pendingConfirmation = null
            throw cancelled
        }
    }

    private suspend fun reconcileAmbiguous(
        state: LoadedState,
        task: BrainAuxiliaryTask,
        draft: BrainAuxiliaryDraft,
        mutation: Mutation.Ambiguous
    ): BrainAuxiliaryModelsApplyResult {
        return try {
            val receipt = mutation.receipt
                ?: return invalidateAfterReadback(
                    mutation.reason,
                    mayHaveSaved = true
                )
            val readback = readAuxiliaryCaptured(state.target, receipt)
            val available = readback as? AuxiliaryRead.Available
                ?: return invalidateAfterReadback(
                    (readback as AuxiliaryRead.Unavailable).reason.toApplyFailureReason(),
                    mayHaveSaved = true
                )
            if (!reader.isTargetCurrent(state.target, available.receipt)) {
                loaded = null
                return notConfirmed(BrainAuxiliaryModelsApplyFailureReason.STALE, true)
            }
            val authoritative = makeSnapshot(
                snapshotId = state.snapshot.snapshotId,
                selectedProfile = state.snapshot.selectedProfile,
                assignments = available.assignments,
                providers = state.snapshot.providers,
                catalogAvailable = state.snapshot.catalogAvailable
            )
            // An ambiguous or malformed acknowledgement is never upgraded to
            // Saved solely because a later read happens to show the requested
            // pair. The readback is still published as authoritative evidence.
            loaded = null
            BrainAuxiliaryModelsApplyResult.NotConfirmed(
                mutation.reason,
                mayHaveSaved = true,
                authoritativeSnapshot = authoritative
            )
        } catch (cancelled: CancellationException) {
            loaded = null
            pendingConfirmation = null
            throw cancelled
        }
    }

    private suspend fun readAuxiliaryCaptured(
        target: BrainModelCapturedTarget,
        expected: AuthCredentialLease
    ): AuxiliaryRead {
        var acceptedReceipt: AuthCredentialLease? = null
        return try {
            if (!reader.isTargetCurrent(target, expected)) {
                return AuxiliaryRead.Unavailable(BrainModelReadFailure.STALE, expected)
            }
            val path = reader.requestPath(AUXILIARY_PATH, target.selectedProfile)
            val exchanged = authRepository.exchangeAuthenticated(
                target.origin,
                path,
                expected
            ) { headers ->
                transport.openAuxiliaryRead(
                    target.origin,
                    path,
                    headers,
                    OkHttpBrainModelReadTransport.DEFAULT_TIMEOUT_MILLIS
                )
            }
            if (exchanged.isFailure) {
                if (exchanged.exceptionOrNull() is StaleAuthenticatedResponseException) {
                    return AuxiliaryRead.Unavailable(BrainModelReadFailure.STALE, acceptedReceipt)
                }
                return AuxiliaryRead.Unavailable(BrainModelReadFailure.UNAVAILABLE, acceptedReceipt)
            }
            val response = exchanged.getOrThrow()
            acceptedReceipt = response.receipt
            response.response.use { http ->
                if (http.statusCode !in 200..299) {
                    return AuxiliaryRead.Unavailable(BrainModelReadFailure.UNAVAILABLE, acceptedReceipt)
                }
                val body = http.readBoundedBody()
                AuxiliaryRead.Available(parseAuxiliary(body), response.receipt)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleAuthenticatedResponseException) {
            AuxiliaryRead.Unavailable(BrainModelReadFailure.STALE, acceptedReceipt)
        } catch (_: BrainModelMalformedException) {
            AuxiliaryRead.Unavailable(BrainModelReadFailure.MALFORMED, acceptedReceipt)
        } catch (_: BrainModelTransportException) {
            AuxiliaryRead.Unavailable(BrainModelReadFailure.UNAVAILABLE, acceptedReceipt)
        } catch (_: Exception) {
            AuxiliaryRead.Unavailable(BrainModelReadFailure.UNAVAILABLE, acceptedReceipt)
        }
    }

    private suspend fun mutate(
        target: BrainModelCapturedTarget,
        expected: AuthCredentialLease,
        task: BrainAuxiliaryTask,
        draft: BrainAuxiliaryDraft,
        confirmed: Boolean
    ): Mutation {
        val path = reader.requestPath(SET_PATH, target.selectedProfile)
        val body = mutationBody(task, draft, confirmed)
        var acceptedReceipt: AuthCredentialLease? = null
        return try {
            val exchanged = authRepository.exchangeAuthenticated(
                target.origin,
                path,
                expected
            ) { headers ->
                transport.openAuxiliaryMutation(
                    target.origin,
                    path,
                    headers,
                    body,
                    OkHttpBrainModelReadTransport.DEFAULT_TIMEOUT_MILLIS
                )
            }
            if (exchanged.isFailure) {
                val failure = exchanged.exceptionOrNull()
                return if (failure is StaleAuthenticatedResponseException) {
                    Mutation.Ambiguous(null, BrainAuxiliaryModelsApplyFailureReason.STALE)
                } else {
                    Mutation.Ambiguous(null, BrainAuxiliaryModelsApplyFailureReason.AUTH_UNAVAILABLE)
                }
            }
            val response = exchanged.getOrThrow()
            acceptedReceipt = response.receipt
            response.response.use { http ->
                if (http.statusCode in 400..499) {
                    return@use Mutation.Rejected(
                        BrainAuxiliaryModelsApplyFailureReason.WRITE_REJECTED,
                        response.receipt
                    )
                }
                if (http.statusCode !in 200..299) {
                    return@use Mutation.Ambiguous(
                        response.receipt,
                        BrainAuxiliaryModelsApplyFailureReason.WRITE_REJECTED
                    )
                }
                val root = GatewayJson.parseOrNull(http.readBoundedBody().toString(Charsets.UTF_8))
                    ?.asObjectOrNull()
                if (root == null) {
                    return@use Mutation.Ambiguous(
                        response.receipt,
                        BrainAuxiliaryModelsApplyFailureReason.READBACK_MALFORMED
                    )
                }
                val confirmationRequired = root["confirm_required"]?.asBooleanOrNull() == true
                val ok = root["ok"]?.asBooleanOrNull() == true
                if (!confirmed && confirmationRequired && !ok) {
                    return@use Mutation.Warning(response.receipt)
                }
                if (confirmed && confirmationRequired && !ok) {
                    return@use Mutation.Rejected(
                        BrainAuxiliaryModelsApplyFailureReason.CONFIRMATION_REJECTED,
                        response.receipt
                    )
                }
                if (!ok || !ackMatches(root, task)) {
                    return@use Mutation.Ambiguous(
                        response.receipt,
                        BrainAuxiliaryModelsApplyFailureReason.WRITE_REJECTED
                    )
                }
                Mutation.Success(response.receipt)
            }
        } catch (cancelled: CancellationException) {
            loaded = null
            pendingConfirmation = null
            throw cancelled
        } catch (_: Exception) {
            Mutation.Ambiguous(acceptedReceipt, BrainAuxiliaryModelsApplyFailureReason.AUTH_UNAVAILABLE)
        }
    }

    private fun validateDraft(
        snapshot: BrainAuxiliaryModelsSnapshot,
        draft: BrainAuxiliaryDraft
    ): BrainAuxiliaryModelsApplyFailureReason? = when (draft) {
        BrainAuxiliaryDraft.Auto -> null
        is BrainAuxiliaryDraft.Concrete -> {
            if (draft.provider.isBlank() || draft.model.isBlank()) {
                BrainAuxiliaryModelsApplyFailureReason.INVALID_DRAFT
            } else if (!snapshot.catalogAvailable) {
                BrainAuxiliaryModelsApplyFailureReason.CATALOG_UNAVAILABLE
            } else {
                val provider = snapshot.providers.firstOrNull { it.slug == draft.provider }
                when {
                    provider == null || !provider.authenticated || draft.model !in provider.models ->
                        BrainAuxiliaryModelsApplyFailureReason.PROVIDER_UNAVAILABLE
                    provider.requiresEndpointPassthrough ->
                        BrainAuxiliaryModelsApplyFailureReason.ENDPOINT_UNSUPPORTED
                    else -> null
                }
            }
        }
    }

    private fun mutationBody(
        task: BrainAuxiliaryTask,
        draft: BrainAuxiliaryDraft,
        confirmed: Boolean
    ): String {
        val fields = linkedMapOf<String, Any?>(
            "scope" to "auxiliary",
            "task" to task.wireKey
        )
        when (draft) {
            BrainAuxiliaryDraft.Auto -> {
                fields["provider"] = "auto"
                fields["model"] = ""
                fields["reasoning_effort"] = null
            }
            is BrainAuxiliaryDraft.Concrete -> {
                fields["provider"] = draft.provider
                fields["model"] = draft.model
            }
        }
        if (confirmed) fields["confirm_expensive_model"] = true
        return GatewayJson.serialize(GatewayJson.fromAny(fields))
    }

    private fun ackMatches(root: JsonObject, task: BrainAuxiliaryTask): Boolean {
        if (root["scope"]?.asStringOrNull() != "auxiliary") return false
        val tasks = root["tasks"]?.asArrayOrNull()?.list ?: return false
        return tasks.size == 1 && tasks[0].asStringOrNull() == task.wireKey
    }

    private fun parseAuxiliary(bytes: ByteArray): Map<BrainAuxiliaryTask, BrainAuxiliaryAssignment> {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw BrainModelMalformedException()
        }
        val root = (GatewayJson.parseOrNull(text) as? JsonObject) ?: throw BrainModelMalformedException()
        val rows = root["tasks"]?.asArrayOrNull()?.list ?: throw BrainModelMalformedException()
        val assignments = linkedMapOf<BrainAuxiliaryTask, BrainAuxiliaryAssignment>()
        rows.forEach { value ->
            val row = value as? JsonObject ?: throw BrainModelMalformedException()
            val key = safeString(row["task"], allowEmpty = false)
            val task = BrainAuxiliaryTask.fromWireKey(key) ?: return@forEach
            if (assignments.containsKey(task)) throw BrainModelMalformedException()
            val provider = safeString(row["provider"], allowEmpty = true)
            val model = safeString(row["model"], allowEmpty = true)
            val effort = when (val raw = row["reasoning_effort"]) {
                null, JsonNull -> null
                else -> safeString(raw, allowEmpty = true)
            }
            assignments[task] = BrainAuxiliaryAssignment(
                task = task,
                provider = provider,
                model = model,
                reasoningEffort = effort,
                requiresEndpointPassthrough = endpointPresent(row)
            )
        }
        return assignments
    }

    private fun endpointPresent(row: JsonObject): Boolean {
        val baseUrl = row["base_url"]
        val localEndpoint = row["local_endpoint"]?.asBooleanOrNull() == true
        return localEndpoint || (baseUrl != null && baseUrl != JsonNull &&
            (baseUrl.asStringOrNull()?.isNotBlank() == true || baseUrl.asStringOrNull() == null))
    }

    private fun safeString(value: com.hermes.bot.data.gateway.json.JsonValue?, allowEmpty: Boolean): String {
        val string = (value as? com.hermes.bot.data.gateway.json.JsonString)?.value
            ?: throw BrainModelMalformedException()
        if (!allowEmpty && string.isBlank()) throw BrainModelMalformedException()
        if (string.length > 512 || string.any { it.isISOControl() }) throw BrainModelMalformedException()
        return string
    }

    private fun makeSnapshot(
        snapshotId: Long,
        selectedProfile: String,
        assignments: Map<BrainAuxiliaryTask, BrainAuxiliaryAssignment>,
        providers: List<BrainModelProvider>,
        catalogAvailable: Boolean
    ): BrainAuxiliaryModelsSnapshot {
        val tasks = BrainAuxiliaryTask.entries.map { task ->
            assignments[task]?.let { BrainAuxiliaryTaskRow(task, it) }
                ?: BrainAuxiliaryTaskRow(
                    task,
                    assignment = null,
                    unavailableReason = BrainAuxiliaryTaskUnavailableReason.MISSING_FROM_HOST
                )
        }
        return BrainAuxiliaryModelsSnapshot(
            snapshotId = snapshotId,
            selectedProfile = selectedProfile,
            tasks = tasks,
            providers = providers,
            catalogAvailable = catalogAvailable
        )
    }

    private fun matches(
        current: BrainAuxiliaryAssignment,
        draft: BrainAuxiliaryDraft
    ): Boolean = when (draft) {
        BrainAuxiliaryDraft.Auto ->
            current.provider == "auto" && current.model.isEmpty() && current.reasoningEffort == null
        is BrainAuxiliaryDraft.Concrete ->
            current.provider == draft.provider && current.model == draft.model
    }

    private fun invalidateAfterReadback(
        reason: BrainAuxiliaryModelsApplyFailureReason,
        mayHaveSaved: Boolean
    ): BrainAuxiliaryModelsApplyResult {
        loaded = null
        return notConfirmed(reason, mayHaveSaved)
    }

    private suspend fun retainAcceptedReceipt(
        state: LoadedState,
        target: BrainModelCapturedTarget,
        receipt: AuthCredentialLease
    ) {
        val current = loaded ?: return
        if (current.snapshot.snapshotId != state.snapshot.snapshotId || current.target != target) return
        if (!reader.isTargetCurrent(target, receipt)) return
        loaded = current.copy(receipt = receipt)
    }

    private fun notConfirmed(
        reason: BrainAuxiliaryModelsApplyFailureReason,
        mayHaveSaved: Boolean
    ) = BrainAuxiliaryModelsApplyResult.NotConfirmed(reason, mayHaveSaved)

    private data class LoadedState(
        val snapshot: BrainAuxiliaryModelsSnapshot,
        val target: BrainModelCapturedTarget,
        val receipt: AuthCredentialLease
    )

    private data class PendingConfirmation(
        val snapshot: BrainAuxiliaryModelsSnapshot,
        val target: BrainModelCapturedTarget,
        val receipt: AuthCredentialLease,
        val task: BrainAuxiliaryTask,
        val draft: BrainAuxiliaryDraft
    )

    private sealed interface AuxiliaryRead {
        data class Available(
            val assignments: Map<BrainAuxiliaryTask, BrainAuxiliaryAssignment>,
            val receipt: AuthCredentialLease
        ) : AuxiliaryRead

        data class Unavailable(
            val reason: BrainModelReadFailure,
            val receipt: AuthCredentialLease?
        ) : AuxiliaryRead
    }

    private sealed interface Mutation {
        data class Success(val receipt: AuthCredentialLease) : Mutation
        data class Warning(val receipt: AuthCredentialLease) : Mutation
        data class Rejected(
            val reason: BrainAuxiliaryModelsApplyFailureReason,
            val receipt: AuthCredentialLease?
        ) : Mutation
        data class Ambiguous(
            val receipt: AuthCredentialLease?,
            val reason: BrainAuxiliaryModelsApplyFailureReason
        ) : Mutation
    }

    private fun BrainModelReadFailure.toAuxiliaryFailureReason(): BrainAuxiliaryModelsLoadFailureReason = when (this) {
        BrainModelReadFailure.INVALID_SELECTION -> BrainAuxiliaryModelsLoadFailureReason.INVALID_SELECTION
        BrainModelReadFailure.MALFORMED -> BrainAuxiliaryModelsLoadFailureReason.MALFORMED
        BrainModelReadFailure.STALE -> BrainAuxiliaryModelsLoadFailureReason.STALE
        BrainModelReadFailure.UNAVAILABLE -> BrainAuxiliaryModelsLoadFailureReason.UNAVAILABLE
    }

    private fun BrainModelReadFailure.toApplyFailureReason(): BrainAuxiliaryModelsApplyFailureReason = when (this) {
        BrainModelReadFailure.STALE -> BrainAuxiliaryModelsApplyFailureReason.STALE
        BrainModelReadFailure.MALFORMED -> BrainAuxiliaryModelsApplyFailureReason.READBACK_MALFORMED
        BrainModelReadFailure.INVALID_SELECTION -> BrainAuxiliaryModelsApplyFailureReason.INVALID_SELECTION
        BrainModelReadFailure.UNAVAILABLE -> BrainAuxiliaryModelsApplyFailureReason.READBACK_UNAVAILABLE
    }

    companion object {
        const val AUXILIARY_PATH = "/api/model/auxiliary"
        const val SET_PATH = "/api/model/set"
        const val CONFIRMATION_WARNING = "The host requires confirmation for this model. Review the selection and confirm to apply it."
    }

    private class BrainModelMalformedException : IllegalArgumentException()
}
