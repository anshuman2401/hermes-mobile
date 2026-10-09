package io.github.anshuman2401.hermesmobile.data.brain

import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.ReasoningDefaultResult
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayRedactor
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject
import io.github.anshuman2401.hermesmobile.data.local.AuthCredentialLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BrainMainSettingsSnapshot(
    val snapshotId: Long,
    val selectedProfile: String,
    val main: BrainMainModel?,
    val providers: List<BrainModelProvider>,
    val reasoningEffort: String
)

data class BrainMainSettingsDraft(
    val provider: String?,
    val model: String?,
    val reasoningEffort: String
)

enum class BrainMainSettingsFailureReason {
    INVALID_SELECTION,
    UNAVAILABLE,
    MALFORMED,
    STALE,
    REASONING_UNAVAILABLE,
    WRITE_REJECTED,
    READBACK_UNCONFIRMED,
    CONFIRMATION_REJECTED
}

data class BrainMainSettingsFailure(
    val reason: BrainMainSettingsFailureReason,
    val message: String = reason.name.lowercase().replace('_', ' ')
)

sealed interface BrainMainSettingsLoadResult {
    data class Available(val snapshot: BrainMainSettingsSnapshot) : BrainMainSettingsLoadResult
    data class Unavailable(val failure: BrainMainSettingsFailure) : BrainMainSettingsLoadResult
}

sealed interface BrainMainSettingsApplyResult {
    data class Saved(val snapshot: BrainMainSettingsSnapshot) : BrainMainSettingsApplyResult
    data class Partial(
        val snapshot: BrainMainSettingsSnapshot,
        val mainSaved: Boolean,
        val reasoningSaved: Boolean
    ) : BrainMainSettingsApplyResult

    data class ConfirmationRequired(
        val snapshotId: Long,
        val warning: String
    ) : BrainMainSettingsApplyResult

    data class Failed(
        val failure: BrainMainSettingsFailure,
        val knownMainSaved: Boolean = false
    ) : BrainMainSettingsApplyResult
}

/**
 * Selected-profile main model and reasoning settings orchestration. The reader
 * owns captured REST target/receipt mechanics; this repository owns draft,
 * mutation ordering, warning acknowledgement, and readback projection.
 */
class BrainMainSettingsRepository(
    private val vaultStorage: io.github.anshuman2401.hermesmobile.data.local.VaultStorage,
    private val authRepository: AuthRepository,
    private val gatewayAdapter: GatewayProtocolAdapter,
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

    suspend fun load(): BrainMainSettingsLoadResult = operationMutex.withLock {
        pendingConfirmation = null
        val target = when (val capture = reader.captureTargetResult()) {
            BrainModelTargetCaptureResult.InvalidSelection ->
                return@withLock BrainMainSettingsLoadResult.Unavailable(
                    BrainMainSettingsFailure(BrainMainSettingsFailureReason.INVALID_SELECTION)
                )
            is BrainModelTargetCaptureResult.Unavailable ->
                return@withLock unavailable(capture.reason.toFailureReason())
            is BrainModelTargetCaptureResult.Captured -> capture.target
        }
        val read = when (val result = reader.readCaptured(target)) {
            is BrainModelCapturedReadResult.Available -> result
            is BrainModelCapturedReadResult.Unavailable -> {
                loaded = null
                return@withLock result.failure()
            }
        }
        val generation = target.generation
            ?: return@withLock unavailable(BrainMainSettingsFailureReason.REASONING_UNAVAILABLE)
        val reasoning = gatewayAdapter.getProfileReasoningDefault(
            target.selectedProfile,
            target.origin,
            generation
        )
        val effort = when (reasoning) {
            is GatewayResult.Failure -> {
                loaded = null
                return@withLock BrainMainSettingsLoadResult.Unavailable(
                    BrainMainSettingsFailure(BrainMainSettingsFailureReason.REASONING_UNAVAILABLE)
                )
            }
            is GatewayResult.Success -> normalizeHostEffort(reasoning.value)
        }
        if (!reader.isTargetCurrent(target, read.receipt)) {
            loaded = null
            return@withLock unavailable(BrainMainSettingsFailureReason.STALE)
        }
        val snapshot = BrainMainSettingsSnapshot(
            snapshotId = ++nextSnapshotId,
            selectedProfile = read.settings.selectedProfile,
            main = read.settings.main,
            providers = read.settings.providers,
            reasoningEffort = effort
        )
        loaded = LoadedState(snapshot, target, read.receipt)
        BrainMainSettingsLoadResult.Available(snapshot)
    }

    suspend fun apply(
        snapshotId: Long,
        draft: BrainMainSettingsDraft
    ): BrainMainSettingsApplyResult = operationMutex.withLock {
        pendingConfirmation = null
        val state = loaded ?: return@withLock staleApply()
        if (state.snapshot.snapshotId != snapshotId) return@withLock staleApply()
        if (!reader.isTargetCurrent(state.target, state.receipt)) {
            loaded = null
            return@withLock staleApply()
        }
        executeApply(state, draft, state.receipt)
    }

    /** A host warning may be acknowledged once for the exact frozen apply. */
    suspend fun confirmMainWarning(snapshotId: Long): BrainMainSettingsApplyResult = operationMutex.withLock {
        val pending = pendingConfirmation
        if (pending == null || pending.snapshotId != snapshotId) return@withLock staleApply()
        pendingConfirmation = null
        if (!reader.isTargetCurrent(pending.target, pending.receipt)) {
            loaded = null
            return@withLock staleApply()
        }
        when (val main = sendMain(pending.target, pending.draft, pending.receipt, confirmed = true)) {
            is Mutation.Success -> executeAfterMain(
                pending.state,
                pending.draft,
                main.receipt,
                mainWasRequested = pending.mainChanged,
                reasoningWasRequested = pending.reasoningChanged
            )
            is Mutation.Warning -> BrainMainSettingsApplyResult.Failed(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.CONFIRMATION_REJECTED)
            )
            is Mutation.Failure -> reconcileFailedMain(pending.state, pending.draft, main)
        }
    }

    private suspend fun executeApply(
        state: LoadedState,
        draft: BrainMainSettingsDraft,
        receipt: AuthCredentialLease
    ): BrainMainSettingsApplyResult {
        val normalized = normalizeDraft(draft)
            ?: return BrainMainSettingsApplyResult.Failed(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.WRITE_REJECTED)
            )
        val effortChanged = normalized.reasoningEffort != state.snapshot.reasoningEffort
        if (effortChanged && canonicalEffort(normalized.reasoningEffort) == null) {
            return BrainMainSettingsApplyResult.Failed(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.WRITE_REJECTED)
            )
        }
        val mainChanged = !sameMain(state.snapshot.main, normalized)
        val reasoningSupported = supportsReasoning(state.snapshot.providers, normalized)
        val reasoningChanged = reasoningSupported && effortChanged
        if (!mainChanged && !reasoningChanged) {
            return BrainMainSettingsApplyResult.Saved(state.snapshot)
        }
        if (!mainChanged) {
            return executeReasoningOnly(state, normalized, receipt)
        }
        val main = sendMain(state.target, normalized, receipt, confirmed = false)
        return when (main) {
            is Mutation.Warning -> {
                pendingConfirmation = PendingConfirmation(
                    state = state,
                    snapshotId = state.snapshot.snapshotId,
                    target = state.target,
                    receipt = main.receipt,
                    draft = normalized,
                    mainChanged = mainChanged,
                    reasoningChanged = reasoningChanged
                )
                BrainMainSettingsApplyResult.ConfirmationRequired(
                    state.snapshot.snapshotId,
                    main.warning
                )
            }
            is Mutation.Success -> executeAfterMain(
                state,
                normalized,
                main.receipt,
                mainWasRequested = mainChanged,
                reasoningWasRequested = reasoningChanged
            )
            is Mutation.Failure -> reconcileFailedMain(state, normalized, main)
        }
    }

    private suspend fun executeReasoningOnly(
        state: LoadedState,
        draft: BrainMainSettingsDraft,
        receipt: AuthCredentialLease
    ): BrainMainSettingsApplyResult {
        val reasoning = sendReasoning(state.target, draft.reasoningEffort, receipt)
        return when (reasoning) {
            is Mutation.Success -> reconcileAfterReasoning(state, draft, reasoning.receipt, mainSaved = true)
            is Mutation.Warning -> reconcileFailedReasoning(
                state,
                draft,
                Mutation.Failure(reasoning.receipt, BrainMainSettingsFailure(BrainMainSettingsFailureReason.WRITE_REJECTED))
            )
            is Mutation.Failure -> reconcileFailedReasoning(state, draft, reasoning)
        }
    }

    private suspend fun executeAfterMain(
        state: LoadedState,
        draft: BrainMainSettingsDraft,
        receipt: AuthCredentialLease,
        mainWasRequested: Boolean,
        reasoningWasRequested: Boolean
    ): BrainMainSettingsApplyResult {
        if (!reader.isTargetCurrent(state.target, receipt)) {
            loaded = null
            return BrainMainSettingsApplyResult.Failed(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.STALE)
            )
        }
        if (!reasoningWasRequested) {
            return reconcileAfterMainOnly(state, draft, receipt)
        }
        val reasoning = sendReasoning(state.target, draft.reasoningEffort, receipt)
        return when (reasoning) {
            is Mutation.Success -> reconcileFinal(state, draft, reasoning.receipt, mainWasRequested, true)
            is Mutation.Warning -> reconcileFailedReasoning(
                state,
                draft,
                Mutation.Failure(reasoning.receipt, BrainMainSettingsFailure(BrainMainSettingsFailureReason.WRITE_REJECTED))
            )
            is Mutation.Failure -> reconcileFailedReasoning(state, draft, reasoning)
        }
    }

    private suspend fun reconcileAfterMainOnly(
        state: LoadedState,
        draft: BrainMainSettingsDraft,
        receipt: AuthCredentialLease
    ): BrainMainSettingsApplyResult {
        val readback = readback(state, receipt)
            ?: return BrainMainSettingsApplyResult.Failed(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.READBACK_UNCONFIRMED),
                knownMainSaved = true
            )
        return if (sameMain(readback.snapshot.main, draft)) {
            updateLoaded(readback)
            BrainMainSettingsApplyResult.Saved(readback.snapshot)
        } else {
            BrainMainSettingsApplyResult.Failed(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.READBACK_UNCONFIRMED),
                knownMainSaved = false
            )
        }
    }

    private suspend fun reconcileAfterReasoning(
        state: LoadedState,
        draft: BrainMainSettingsDraft,
        receipt: AuthCredentialLease,
        mainSaved: Boolean
    ): BrainMainSettingsApplyResult = reconcileFinal(state, draft, receipt, mainSaved, true)

    private suspend fun reconcileFinal(
        state: LoadedState,
        draft: BrainMainSettingsDraft,
        receipt: AuthCredentialLease,
        mainSaved: Boolean,
        reasoningSaved: Boolean
    ): BrainMainSettingsApplyResult {
        val readback = readback(state, receipt)
            ?: return BrainMainSettingsApplyResult.Failed(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.READBACK_UNCONFIRMED),
                knownMainSaved = mainSaved
            )
        val mainMatches = sameMain(readback.snapshot.main, draft)
        val reasoningMatches = readback.snapshot.reasoningEffort == draft.reasoningEffort
        updateLoaded(readback)
        return when {
            mainMatches && reasoningMatches -> BrainMainSettingsApplyResult.Saved(readback.snapshot)
            mainMatches && mainSaved -> BrainMainSettingsApplyResult.Partial(
                readback.snapshot,
                mainSaved = true,
                reasoningSaved = reasoningSaved && reasoningMatches
            )
            else -> BrainMainSettingsApplyResult.Failed(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.READBACK_UNCONFIRMED),
                knownMainSaved = mainMatches
            )
        }
    }

    private suspend fun reconcileFailedMain(
        state: LoadedState,
        draft: BrainMainSettingsDraft,
        failure: Mutation.Failure
    ): BrainMainSettingsApplyResult {
        val readback = failure.receipt?.let { readback(state, it) }
        if (readback == null) {
            return BrainMainSettingsApplyResult.Failed(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.WRITE_REJECTED),
                knownMainSaved = false
            )
        }
        val mainMatches = sameMain(readback.snapshot.main, draft)
        updateLoaded(readback)
        return BrainMainSettingsApplyResult.Failed(
            BrainMainSettingsFailure(BrainMainSettingsFailureReason.READBACK_UNCONFIRMED),
            knownMainSaved = mainMatches
        )
    }

    private suspend fun reconcileFailedReasoning(
        state: LoadedState,
        draft: BrainMainSettingsDraft,
        failure: Mutation.Failure
    ): BrainMainSettingsApplyResult {
        val readback = failure.receipt?.let { readback(state, it) }
            ?: return BrainMainSettingsApplyResult.Failed(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.WRITE_REJECTED),
                knownMainSaved = true
            )
        val mainMatches = sameMain(readback.snapshot.main, draft)
        val reasoningMatches = readback.snapshot.reasoningEffort == draft.reasoningEffort
        updateLoaded(readback)
        return if (mainMatches && !reasoningMatches) {
            BrainMainSettingsApplyResult.Partial(readback.snapshot, mainSaved = true, reasoningSaved = false)
        } else {
            BrainMainSettingsApplyResult.Failed(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.READBACK_UNCONFIRMED),
                knownMainSaved = mainMatches
            )
        }
    }

    private suspend fun readback(state: LoadedState, receipt: AuthCredentialLease): Readback? {
        val read = reader.readCaptured(state.target, receipt)
        val available = read as? BrainModelCapturedReadResult.Available ?: return null
        val generation = state.target.generation ?: return null
        val reasoning = gatewayAdapter.getProfileReasoningDefault(
            state.target.selectedProfile,
            state.target.origin,
            generation
        ) as? GatewayResult.Success ?: return null
        if (!reader.isTargetCurrent(state.target, available.receipt)) return null
        val snapshot = BrainMainSettingsSnapshot(
            snapshotId = state.snapshot.snapshotId,
            selectedProfile = available.settings.selectedProfile,
            main = available.settings.main,
            providers = available.settings.providers,
            reasoningEffort = normalizeHostEffort(reasoning.value)
        )
        return Readback(snapshot, state.target, available.receipt)
    }

    private suspend fun sendMain(
        target: BrainModelCapturedTarget,
        draft: BrainMainSettingsDraft,
        receipt: AuthCredentialLease,
        confirmed: Boolean
    ): Mutation {
        val provider = draft.provider?.takeIf { it.isNotBlank() }
            ?: return Mutation.Failure(null, BrainMainSettingsFailure(BrainMainSettingsFailureReason.WRITE_REJECTED))
        val model = draft.model?.takeIf { it.isNotBlank() }
            ?: return Mutation.Failure(null, BrainMainSettingsFailure(BrainMainSettingsFailureReason.WRITE_REJECTED))
        val body = linkedMapOf<String, Any?>(
            "scope" to "main",
            "provider" to provider,
            "model" to model
        )
        if (confirmed) body["confirm_expensive_model"] = true
        return mutate(
            target = target,
            expected = receipt,
            path = reader.requestPath("/api/model/set", target.selectedProfile),
            body = GatewayJson.serialize(GatewayJson.fromAny(body)),
            main = true
        )
    }

    private suspend fun sendReasoning(
        target: BrainModelCapturedTarget,
        effort: String,
        receipt: AuthCredentialLease
    ): Mutation = mutate(
        target = target,
        expected = receipt,
        path = reader.requestPath("/api/config", target.selectedProfile),
        body = GatewayJson.serialize(
            GatewayJson.fromAny(
                linkedMapOf(
                    "config" to linkedMapOf(
                        "agent" to linkedMapOf("reasoning_effort" to effort)
                    )
                )
            )
        ),
        main = false
    )

    private suspend fun mutate(
        target: BrainModelCapturedTarget,
        expected: AuthCredentialLease,
        path: String,
        body: String,
        main: Boolean
    ): Mutation {
        var acceptedReceipt: AuthCredentialLease? = null
        return try {
            val exchanged = authRepository.exchangeAuthenticated(
                target.origin,
                path,
                expected
            ) { headers ->
                if (main) transport.openMainMutation(
                    target.origin, path, headers, body, OkHttpBrainModelReadTransport.DEFAULT_TIMEOUT_MILLIS
                ) else transport.openReasoningMutation(
                    target.origin, path, headers, body, OkHttpBrainModelReadTransport.DEFAULT_TIMEOUT_MILLIS
                )
            }
            if (exchanged.isFailure) {
                Mutation.Failure(null, BrainMainSettingsFailure(BrainMainSettingsFailureReason.WRITE_REJECTED))
            } else {
                val response = exchanged.getOrThrow()
                val receipt = response.receipt
                acceptedReceipt = receipt
                val result = response.response.use { http ->
                    if (http.statusCode !in 200..299) {
                        Mutation.Failure(receipt, BrainMainSettingsFailure(BrainMainSettingsFailureReason.WRITE_REJECTED))
                    } else {
                        val root = GatewayJson.parseOrNull(http.readBoundedBody().toString(Charsets.UTF_8)) as? JsonObject
                            ?: return@use Mutation.Failure(
                                receipt,
                                BrainMainSettingsFailure(BrainMainSettingsFailureReason.MALFORMED)
                            )
                        val ok = root["ok"]?.asBooleanOrNull()
                        if (main && root["confirm_required"]?.asBooleanOrNull() == true && ok != true) {
                            val warning = (root["message"]?.asStringOrNull()
                                ?: root["warning"]?.asStringOrNull()
                                ?: "Host confirmation required")
                                .let { GatewayRedactor.redactString(it).take(512) }
                            Mutation.Warning(receipt, warning.ifBlank { "Host confirmation required" })
                        } else if (ok == true) {
                            Mutation.Success(receipt)
                        } else {
                            Mutation.Failure(receipt, BrainMainSettingsFailure(BrainMainSettingsFailureReason.WRITE_REJECTED))
                        }
                    }
                }
                result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Mutation.Failure(acceptedReceipt, BrainMainSettingsFailure(BrainMainSettingsFailureReason.WRITE_REJECTED))
        }
    }

    private fun updateLoaded(readback: Readback) {
        loaded = LoadedState(readback.snapshot, readback.target, readback.receipt)
    }

    private fun normalizeDraft(draft: BrainMainSettingsDraft): BrainMainSettingsDraft? {
        val effort = draft.reasoningEffort.trim()
        val provider = draft.provider?.trim()?.takeIf { it.isNotBlank() }
        val model = draft.model?.trim()?.takeIf { it.isNotBlank() }
        if ((provider == null) != (model == null)) return null
        return draft.copy(
            provider = provider,
            model = model,
            reasoningEffort = canonicalEffort(effort) ?: effort
        )
    }

    private fun supportsReasoning(
        providers: List<BrainModelProvider>,
        draft: BrainMainSettingsDraft
    ): Boolean {
        val provider = draft.provider ?: return false
        val model = draft.model ?: return false
        val row = providers.firstOrNull { it.slug == provider } ?: return true
        return row.capabilities[model]?.reasoning ?: true
    }

    private fun sameMain(main: BrainMainModel?, draft: BrainMainSettingsDraft): Boolean =
        main?.provider == draft.provider && main?.model == draft.model

    private fun normalizeHostEffort(result: ReasoningDefaultResult): String =
        normalizeHostEffort(result.effort)

    private fun normalizeHostEffort(value: String?): String {
        val normalized = value?.trim()?.lowercase()
        return when {
            normalized.isNullOrBlank() -> "medium"
            normalized == "false" || normalized == "disabled" || normalized == "off" -> "none"
            else -> normalized
        }
    }

    private fun canonicalEffort(value: String): String? {
        val normalized = value.trim().lowercase()
        return normalized.takeIf { it in CANONICAL_EFFORTS }
    }

    private fun unavailable(reason: BrainMainSettingsFailureReason): BrainMainSettingsLoadResult.Unavailable =
        BrainMainSettingsLoadResult.Unavailable(BrainMainSettingsFailure(reason))

    private fun staleApply(): BrainMainSettingsApplyResult.Failed =
        BrainMainSettingsApplyResult.Failed(BrainMainSettingsFailure(BrainMainSettingsFailureReason.STALE))

    private sealed interface Mutation {
        val receipt: AuthCredentialLease?

        data class Success(override val receipt: AuthCredentialLease) : Mutation
        data class Warning(override val receipt: AuthCredentialLease, val warning: String) : Mutation
        data class Failure(
            override val receipt: AuthCredentialLease?,
            val failure: BrainMainSettingsFailure
        ) : Mutation
    }

    private data class LoadedState(
        val snapshot: BrainMainSettingsSnapshot,
        val target: BrainModelCapturedTarget,
        val receipt: AuthCredentialLease
    )

    private data class PendingConfirmation(
        val state: LoadedState,
        val snapshotId: Long,
        val target: BrainModelCapturedTarget,
        val receipt: AuthCredentialLease,
        val draft: BrainMainSettingsDraft,
        val mainChanged: Boolean,
        val reasoningChanged: Boolean
    )

    private data class Readback(
        val snapshot: BrainMainSettingsSnapshot,
        val target: BrainModelCapturedTarget,
        val receipt: AuthCredentialLease
    )

    private fun BrainModelCapturedReadResult.Unavailable.failure(): BrainMainSettingsLoadResult.Unavailable =
        unavailable(reason.toFailureReason())

    private fun BrainModelReadFailure.toFailureReason(): BrainMainSettingsFailureReason = when (this) {
        BrainModelReadFailure.INVALID_SELECTION -> BrainMainSettingsFailureReason.INVALID_SELECTION
        BrainModelReadFailure.MALFORMED -> BrainMainSettingsFailureReason.MALFORMED
        BrainModelReadFailure.STALE -> BrainMainSettingsFailureReason.STALE
        BrainModelReadFailure.UNAVAILABLE -> BrainMainSettingsFailureReason.UNAVAILABLE
    }

    companion object {
        private val CANONICAL_EFFORTS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
    }
}
