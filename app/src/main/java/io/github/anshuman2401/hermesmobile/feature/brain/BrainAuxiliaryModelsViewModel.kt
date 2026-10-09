package io.github.anshuman2401.hermesmobile.feature.brain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryAssignment
import io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryDraft
import io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryModelsApplyFailureReason
import io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryModelsApplyResult
import io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryModelsLoadFailureReason
import io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryModelsLoadResult
import io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryModelsRepository
import io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryModelsSnapshot
import io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryTask
import io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryTaskRow
import io.github.anshuman2401.hermesmobile.data.context.ContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface BrainAuxiliaryModelsLoadState {
    data object Loading : BrainAuxiliaryModelsLoadState
    data class Ready(
        val snapshot: BrainAuxiliaryModelsSnapshot,
        val partial: Boolean
    ) : BrainAuxiliaryModelsLoadState
    data class Error(val message: String) : BrainAuxiliaryModelsLoadState
}

data class BrainAuxiliaryModelDialogState(
    val snapshot: BrainAuxiliaryModelsSnapshot,
    val task: BrainAuxiliaryTask,
    val draft: BrainAuxiliaryDraft,
    val saving: Boolean = false,
    val confirmationWarning: String? = null
)

data class BrainAuxiliaryModelsUiState(
    val selectedProfile: String? = null,
    val loadState: BrainAuxiliaryModelsLoadState = BrainAuxiliaryModelsLoadState.Loading,
    val dialog: BrainAuxiliaryModelDialogState? = null,
    val feedback: String? = null,
    val reloadRequired: Boolean = false
)

/**
 * Presentation owner for the hidden auxiliary-models page. The callback
 * constructor keeps the stateful UI behavior deterministic in JVM tests; the
 * repository constructor is the only production path.
 */
class BrainAuxiliaryModelsViewModel(
    private val loadSettings: suspend () -> BrainAuxiliaryModelsLoadResult,
    private val applySettings: suspend (Long, BrainAuxiliaryTask, BrainAuxiliaryDraft) -> BrainAuxiliaryModelsApplyResult,
    private val confirmAuxiliaryWarning: suspend (Long) -> BrainAuxiliaryModelsApplyResult,
    private val readSelectedProfile: suspend () -> String? = { null }
) : ViewModel() {

    constructor(
        repository: BrainAuxiliaryModelsRepository,
        contextProfileRepository: ContextProfileRepository
    ) : this(
        loadSettings = repository::load,
        applySettings = repository::apply,
        confirmAuxiliaryWarning = repository::confirmWarning,
        readSelectedProfile = {
            when (val result = contextProfileRepository.readSelectedProfileAuthoritative()) {
                is SelectedContextReadResult.Selected -> result.name
                else -> null
            }
        }
    )

    private val _uiState = MutableStateFlow(BrainAuxiliaryModelsUiState())
    val uiState: StateFlow<BrainAuxiliaryModelsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var applyJob: Job? = null

    init {
        load()
    }

    fun load() {
        if (_uiState.value.dialog?.saving == true) return
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                loadState = BrainAuxiliaryModelsLoadState.Loading,
                dialog = null,
                feedback = null
            )
        }
        loadJob = viewModelScope.launch {
            val localProfile = try {
                readSelectedProfile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            currentCoroutineContext().ensureActive()
            if (localProfile != null) {
                _uiState.update { it.copy(selectedProfile = localProfile) }
            }

            val result = try {
                loadSettings()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BrainAuxiliaryModelsLoadResult.Unavailable(
                    io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryModelsLoadFailure(
                        BrainAuxiliaryModelsLoadFailureReason.UNAVAILABLE
                    )
                )
            }
            currentCoroutineContext().ensureActive()
            when (result) {
                is BrainAuxiliaryModelsLoadResult.Available -> publishLoaded(result.snapshot, false)
                is BrainAuxiliaryModelsLoadResult.Partial -> publishLoaded(result.snapshot, true)
                is BrainAuxiliaryModelsLoadResult.Unavailable -> {
                    _uiState.update {
                        it.copy(
                            loadState = BrainAuxiliaryModelsLoadState.Error(
                                auxiliaryLoadFailureMessage(result.failure.reason)
                            ),
                            dialog = null
                        )
                    }
                }
            }
        }
    }

    fun openTaskDialog(task: BrainAuxiliaryTask) {
        val state = _uiState.value
        if (state.reloadRequired || state.dialog != null) return
        val ready = state.loadState as? BrainAuxiliaryModelsLoadState.Ready ?: return
        val row = ready.snapshot.tasks.firstOrNull { it.task == task } ?: return
        if (row.assignment == null) return
        _uiState.update {
            it.copy(
                dialog = BrainAuxiliaryModelDialogState(
                    snapshot = ready.snapshot,
                    task = task,
                    draft = row.assignment.toDraft()
                )
            )
        }
    }

    fun openModelDialog(task: BrainAuxiliaryTask) = openTaskDialog(task)

    fun selectModel(provider: String, model: String) {
        val dialog = _uiState.value.dialog ?: return
        if (dialog.saving || dialog.confirmationWarning != null || _uiState.value.reloadRequired) return
        val providerRow = dialog.snapshot.providers.firstOrNull { it.slug == provider } ?: return
        if (!dialog.snapshot.catalogAvailable ||
            !providerRow.authenticated ||
            providerRow.requiresEndpointPassthrough ||
            model !in providerRow.models
        ) return
        _uiState.update { state ->
            state.copy(
                dialog = dialog.copy(
                    draft = BrainAuxiliaryDraft.Concrete(provider, model)
                )
            )
        }
    }

    fun returnToAuto() {
        val dialog = _uiState.value.dialog ?: return
        if (dialog.saving || dialog.confirmationWarning != null || _uiState.value.reloadRequired) return
        val row = dialog.snapshot.tasks.firstOrNull { it.task == dialog.task } ?: return
        if (row.assignment == null) return
        _uiState.update { state -> state.copy(dialog = dialog.copy(draft = BrainAuxiliaryDraft.Auto)) }
    }

    fun apply() {
        val dialog = _uiState.value.dialog ?: return
        if (dialog.saving || dialog.confirmationWarning != null || _uiState.value.reloadRequired) return
        val snapshotId = dialog.snapshot.snapshotId
        val task = dialog.task
        val draft = dialog.draft
        _uiState.update { it.copy(dialog = dialog.copy(saving = true)) }
        applyJob = viewModelScope.launch {
            val result = try {
                applySettings(snapshotId, task, draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BrainAuxiliaryModelsApplyResult.NotConfirmed(
                    reason = BrainAuxiliaryModelsApplyFailureReason.READBACK_UNAVAILABLE,
                    mayHaveSaved = true
                )
            }
            currentCoroutineContext().ensureActive()
            publishApplyResult(snapshotId, dialog, result)
        }
    }

    fun confirmWarning() {
        val dialog = _uiState.value.dialog ?: return
        if (dialog.saving || dialog.confirmationWarning == null || _uiState.value.reloadRequired) return
        val snapshotId = dialog.snapshot.snapshotId
        _uiState.update { it.copy(dialog = dialog.copy(saving = true)) }
        applyJob = viewModelScope.launch {
            val result = try {
                confirmAuxiliaryWarning(snapshotId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BrainAuxiliaryModelsApplyResult.NotConfirmed(
                    reason = BrainAuxiliaryModelsApplyFailureReason.READBACK_UNAVAILABLE,
                    mayHaveSaved = true
                )
            }
            currentCoroutineContext().ensureActive()
            publishApplyResult(snapshotId, dialog, result)
        }
    }

    /** Declining a warning only discards the frozen continuation. */
    fun declineWarning() {
        val dialog = _uiState.value.dialog ?: return
        if (dialog.saving) return
        _uiState.update { it.copy(dialog = dialog.copy(confirmationWarning = null)) }
    }

    fun dismissModelDialog() {
        val dialog = _uiState.value.dialog ?: return
        if (dialog.saving) return
        _uiState.update { it.copy(dialog = null) }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    private fun publishLoaded(snapshot: BrainAuxiliaryModelsSnapshot, partial: Boolean) {
        _uiState.update {
            it.copy(
                selectedProfile = snapshot.selectedProfile,
                loadState = BrainAuxiliaryModelsLoadState.Ready(snapshot, partial),
                dialog = null,
                feedback = null,
                reloadRequired = false
            )
        }
    }

    private fun publishApplyResult(
        snapshotId: Long,
        attemptedDialog: BrainAuxiliaryModelDialogState,
        result: BrainAuxiliaryModelsApplyResult
    ) {
        val currentDialog = _uiState.value.dialog ?: return
        if (currentDialog.snapshot.snapshotId != snapshotId) return
        when (result) {
            is BrainAuxiliaryModelsApplyResult.Saved -> {
                _uiState.update {
                    it.copy(
                        selectedProfile = result.snapshot.selectedProfile,
                        loadState = BrainAuxiliaryModelsLoadState.Ready(
                            result.snapshot,
                            partial = !result.snapshot.catalogAvailable ||
                                result.snapshot.tasks.any { row -> row.assignment == null }
                        ),
                        dialog = null,
                        feedback = "Auxiliary model saved",
                        reloadRequired = false
                    )
                }
            }
            is BrainAuxiliaryModelsApplyResult.ConfirmationRequired -> {
                _uiState.update {
                    it.copy(
                        dialog = attemptedDialog.copy(
                            saving = false,
                            confirmationWarning = result.warning
                        )
                    )
                }
            }
            is BrainAuxiliaryModelsApplyResult.NotConfirmed -> {
                val authoritative = result.authoritativeSnapshot
                _uiState.update { state ->
                    state.copy(
                        loadState = authoritative?.let {
                            BrainAuxiliaryModelsLoadState.Ready(
                                it,
                                it.tasks.any { row -> row.assignment == null } || !it.catalogAvailable
                            )
                        } ?: state.loadState,
                        dialog = attemptedDialog.copy(
                            snapshot = authoritative ?: attemptedDialog.snapshot,
                            saving = false,
                            confirmationWarning = null
                        ),
                        feedback = if (result.mayHaveSaved) {
                            "Settings may have saved; they were not confirmed. Reload before retrying."
                        } else {
                            "Settings were not confirmed. Reload before retrying."
                        },
                        reloadRequired = true
                    )
                }
            }
            is BrainAuxiliaryModelsApplyResult.Rejected -> {
                val requiresReload = result.reason == BrainAuxiliaryModelsApplyFailureReason.STALE ||
                    result.reason == BrainAuxiliaryModelsApplyFailureReason.INVALID_SELECTION
                _uiState.update {
                    it.copy(
                        dialog = attemptedDialog.copy(saving = false, confirmationWarning = null),
                        feedback = if (requiresReload) {
                            "The connection changed. Reload before retrying."
                        } else {
                            "Could not save this auxiliary model. Your draft is still editable."
                        },
                        reloadRequired = it.reloadRequired || requiresReload
                    )
                }
            }
        }
    }

    private fun auxiliaryLoadFailureMessage(reason: BrainAuxiliaryModelsLoadFailureReason): String = when (reason) {
        BrainAuxiliaryModelsLoadFailureReason.INVALID_SELECTION -> "No selected profile is available."
        BrainAuxiliaryModelsLoadFailureReason.MALFORMED -> "Hermes returned unusable auxiliary model settings."
        BrainAuxiliaryModelsLoadFailureReason.STALE -> "The connection changed. Reload to load auxiliary models."
        BrainAuxiliaryModelsLoadFailureReason.UNAVAILABLE -> "Could not load auxiliary model settings."
    }
}

internal fun BrainAuxiliaryAssignment.toDraft(): BrainAuxiliaryDraft =
    if (provider.equals("auto", ignoreCase = true) && model.isEmpty()) {
        BrainAuxiliaryDraft.Auto
    } else {
        BrainAuxiliaryDraft.Concrete(provider, model)
    }

internal fun BrainAuxiliaryDraft.matches(assignment: BrainAuxiliaryAssignment): Boolean = when (this) {
    BrainAuxiliaryDraft.Auto ->
        assignment.provider.equals("auto", ignoreCase = true) &&
            assignment.model.isEmpty() && assignment.reasoningEffort == null
    is BrainAuxiliaryDraft.Concrete ->
        assignment.provider == provider && assignment.model == model
}

internal fun BrainAuxiliaryTaskRow.currentDisplay(): String? = assignment?.let {
    if (it.provider.equals("auto", ignoreCase = true) && it.model.isEmpty()) {
        "Auto"
    } else if (it.provider.isBlank() && it.model.isBlank()) {
        "Not configured"
    } else if (it.provider.isBlank()) {
        it.model
    } else if (it.model.isBlank()) {
        "${it.provider} (provider default)"
    } else {
        "${it.provider}/${it.model}"
    }
}
