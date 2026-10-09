package io.github.anshuman2401.hermesmobile.feature.brain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.anshuman2401.hermesmobile.data.brain.BrainMainSettingsApplyResult
import io.github.anshuman2401.hermesmobile.data.brain.BrainMainSettingsDraft
import io.github.anshuman2401.hermesmobile.data.brain.BrainMainSettingsFailure
import io.github.anshuman2401.hermesmobile.data.brain.BrainMainSettingsFailureReason
import io.github.anshuman2401.hermesmobile.data.brain.BrainMainSettingsLoadResult
import io.github.anshuman2401.hermesmobile.data.brain.BrainMainSettingsRepository
import io.github.anshuman2401.hermesmobile.data.brain.BrainMainSettingsSnapshot
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

sealed interface BrainMainSettingsLoadState {
    data object Loading : BrainMainSettingsLoadState
    data class Ready(val snapshot: BrainMainSettingsSnapshot) : BrainMainSettingsLoadState
    data class Error(val message: String) : BrainMainSettingsLoadState
}

data class BrainMainModelDialogState(
    val snapshot: BrainMainSettingsSnapshot,
    val draft: BrainMainSettingsDraft,
    val saving: Boolean = false,
    val confirmationWarning: String? = null
)

data class BrainMainSettingsUiState(
    val selectedProfile: String? = null,
    val loadState: BrainMainSettingsLoadState = BrainMainSettingsLoadState.Loading,
    val dialog: BrainMainModelDialogState? = null,
    val feedback: String? = null,
    val reloadRequired: Boolean = false
)

/**
 * Presentation owner for the hidden Models page. The primary constructor is
 * a small callback seam for deterministic JVM tests; the repository constructor
 * below is the production path and keeps all wire/persistence work behind the
 * repository boundary.
 */
class BrainMainSettingsViewModel(
    private val loadSettings: suspend () -> BrainMainSettingsLoadResult,
    private val applySettings: suspend (Long, BrainMainSettingsDraft) -> BrainMainSettingsApplyResult,
    private val confirmMainWarning: suspend (Long) -> BrainMainSettingsApplyResult,
    private val readSelectedProfile: suspend () -> String? = { null }
) : ViewModel() {

    constructor(
        repository: BrainMainSettingsRepository,
        contextProfileRepository: ContextProfileRepository
    ) : this(
        loadSettings = repository::load,
        applySettings = repository::apply,
        confirmMainWarning = repository::confirmMainWarning,
        readSelectedProfile = {
            when (val result = contextProfileRepository.readSelectedProfileAuthoritative()) {
                is SelectedContextReadResult.Selected -> result.name
                else -> null
            }
        }
    )

    private val _uiState = MutableStateFlow(BrainMainSettingsUiState())
    val uiState: StateFlow<BrainMainSettingsUiState> = _uiState.asStateFlow()

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
                loadState = BrainMainSettingsLoadState.Loading,
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
                BrainMainSettingsLoadResult.Unavailable(
                    io.github.anshuman2401.hermesmobile.data.brain.BrainMainSettingsFailure(
                        BrainMainSettingsFailureReason.UNAVAILABLE
                    )
                )
            }
            currentCoroutineContext().ensureActive()
            when (result) {
                is BrainMainSettingsLoadResult.Available -> {
                    _uiState.update {
                        it.copy(
                            selectedProfile = result.snapshot.selectedProfile,
                            loadState = BrainMainSettingsLoadState.Ready(result.snapshot),
                            dialog = null,
                            feedback = null,
                            reloadRequired = false
                        )
                    }
                }
                is BrainMainSettingsLoadResult.Unavailable -> {
                    _uiState.update {
                        it.copy(
                            loadState = BrainMainSettingsLoadState.Error(
                                loadFailureMessage(result.failure.reason)
                            ),
                            dialog = null
                        )
                    }
                }
            }
        }
    }

    fun openModelDialog() {
        if (_uiState.value.reloadRequired) return
        val snapshot = (_uiState.value.loadState as? BrainMainSettingsLoadState.Ready)?.snapshot ?: return
        if (_uiState.value.dialog != null) return
        _uiState.update {
            it.copy(
                dialog = BrainMainModelDialogState(
                    snapshot = snapshot,
                    draft = snapshot.toDraft()
                )
            )
        }
    }

    fun selectModel(provider: String, model: String) {
        val dialog = _uiState.value.dialog ?: return
        if (dialog.saving || dialog.confirmationWarning != null || _uiState.value.reloadRequired) return
        val providerRow = dialog.snapshot.providers.firstOrNull { it.slug == provider } ?: return
        if (!providerRow.authenticated || model !in providerRow.models) return
        _uiState.update { state ->
            state.copy(dialog = dialog.copy(draft = dialog.draft.copy(provider = provider, model = model)))
        }
    }

    fun selectReasoning(effort: String) {
        val dialog = _uiState.value.dialog ?: return
        if (dialog.saving || dialog.confirmationWarning != null || _uiState.value.reloadRequired) return
        val policy = brainReasoningPolicy(
            providers = dialog.snapshot.providers,
            provider = dialog.draft.provider,
            model = dialog.draft.model,
            savedEffort = dialog.snapshot.reasoningEffort
        )
        if (!policy.enabled || effort !in policy.options) return
        _uiState.update { state ->
            state.copy(dialog = dialog.copy(draft = dialog.draft.copy(reasoningEffort = effort)))
        }
    }

    fun apply() {
        val dialog = _uiState.value.dialog ?: return
        if (dialog.saving || dialog.confirmationWarning != null || _uiState.value.reloadRequired) return
        val snapshotId = dialog.snapshot.snapshotId
        _uiState.update { it.copy(dialog = dialog.copy(saving = true)) }
        applyJob?.cancel()
        applyJob = viewModelScope.launch {
            val result = try {
                applySettings(snapshotId, dialog.draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BrainMainSettingsApplyResult.Failed(
                    BrainMainSettingsFailure(BrainMainSettingsFailureReason.READBACK_UNCONFIRMED)
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
        applyJob?.cancel()
        applyJob = viewModelScope.launch {
            val result = try {
                confirmMainWarning(snapshotId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BrainMainSettingsApplyResult.Failed(
                    BrainMainSettingsFailure(BrainMainSettingsFailureReason.READBACK_UNCONFIRMED)
                )
            }
            currentCoroutineContext().ensureActive()
            publishApplyResult(snapshotId, dialog, result)
        }
    }

    /** Declining a host warning only removes the one-use acknowledgement state. */
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

    private fun publishApplyResult(
        snapshotId: Long,
        attemptedDialog: BrainMainModelDialogState,
        result: BrainMainSettingsApplyResult
    ) {
        val currentDialog = _uiState.value.dialog ?: return
        if (currentDialog.snapshot.snapshotId != snapshotId) return
        when (result) {
            is BrainMainSettingsApplyResult.Saved -> {
                _uiState.update {
                    it.copy(
                        selectedProfile = result.snapshot.selectedProfile,
                        loadState = BrainMainSettingsLoadState.Ready(result.snapshot),
                        dialog = null,
                        feedback = "Model settings saved",
                        reloadRequired = false
                    )
                }
            }
            is BrainMainSettingsApplyResult.Partial -> {
                _uiState.update {
                    it.copy(
                        selectedProfile = result.snapshot.selectedProfile,
                        loadState = BrainMainSettingsLoadState.Ready(result.snapshot),
                        dialog = attemptedDialog.copy(
                            snapshot = result.snapshot,
                            saving = false,
                            confirmationWarning = null
                        ),
                        feedback = "Model saved; reasoning not saved",
                        reloadRequired = false
                    )
                }
            }
            is BrainMainSettingsApplyResult.ConfirmationRequired -> {
                _uiState.update {
                    it.copy(
                        dialog = attemptedDialog.copy(
                            saving = false,
                            confirmationWarning = result.warning
                        )
                    )
                }
            }
            is BrainMainSettingsApplyResult.Failed -> {
                val requiresReload = result.failure.requiresReload(result.knownMainSaved)
                val message = if (requiresReload && result.failure.reason == BrainMainSettingsFailureReason.READBACK_UNCONFIRMED) {
                    if (result.knownMainSaved) {
                        "Main model may have saved; settings were not confirmed. Reload before retrying."
                    } else {
                        "Settings may have saved; they were not confirmed. Reload before retrying."
                    }
                } else if (requiresReload) {
                    "Settings may have saved; they were not confirmed. Reload before retrying."
                } else {
                    "Could not save model settings. Your draft is still editable."
                }
                _uiState.update {
                    it.copy(
                        dialog = attemptedDialog.copy(saving = false, confirmationWarning = null),
                        feedback = message,
                        reloadRequired = requiresReload
                    )
                }
            }
        }
    }

    private fun BrainMainSettingsFailure.requiresReload(knownMainSaved: Boolean): Boolean =
        reason == BrainMainSettingsFailureReason.STALE ||
            reason == BrainMainSettingsFailureReason.READBACK_UNCONFIRMED ||
            (reason == BrainMainSettingsFailureReason.WRITE_REJECTED && knownMainSaved)

    private fun loadFailureMessage(reason: BrainMainSettingsFailureReason): String = when (reason) {
        BrainMainSettingsFailureReason.INVALID_SELECTION -> "No selected profile is available."
        BrainMainSettingsFailureReason.REASONING_UNAVAILABLE -> "Reasoning settings are unavailable."
        BrainMainSettingsFailureReason.MALFORMED -> "Hermes returned unusable model settings."
        BrainMainSettingsFailureReason.STALE -> "The connection changed. Retry to load model settings."
        else -> "Could not load model settings. Retry to try again."
    }
}

internal fun BrainMainSettingsSnapshot.toDraft(): BrainMainSettingsDraft = BrainMainSettingsDraft(
    provider = main?.provider,
    model = main?.model,
    reasoningEffort = reasoningEffort
)
