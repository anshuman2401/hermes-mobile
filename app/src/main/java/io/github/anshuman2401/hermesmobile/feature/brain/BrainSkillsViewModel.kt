package io.github.anshuman2401.hermesmobile.feature.brain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsFailureReason
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsLoadResult
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsRepository
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsSnapshot
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsToggleResult
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

sealed interface BrainSkillsLoadState {
    data object Loading : BrainSkillsLoadState
    data class Ready(val snapshot: BrainSkillsSnapshot) : BrainSkillsLoadState
    data class Error(val message: String) : BrainSkillsLoadState
}

data class BrainSkillsUiState(
    val selectedProfile: String? = null,
    val loadState: BrainSkillsLoadState = BrainSkillsLoadState.Loading,
    val busySkill: String? = null,
    val feedback: String? = null,
    val reloadRequired: Boolean = false
)

/** Presentation owner for the hidden installed-Skills page. */
class BrainSkillsViewModel(
    private val loadSkills: suspend () -> BrainSkillsLoadResult,
    private val toggleSkill: suspend (Long, String, Boolean) -> BrainSkillsToggleResult,
    private val readSelectedProfile: suspend () -> String? = { null },
    private val detailViewModelFactory: ((Long, String, String) -> BrainSkillDetailViewModel)? = null
) : ViewModel() {

    constructor(
        repository: BrainSkillsRepository,
        contextProfileRepository: ContextProfileRepository,
        detailViewModelFactory: ((Long, String, String) -> BrainSkillDetailViewModel)? = null
    ) : this(
        loadSkills = repository::load,
        toggleSkill = repository::toggle,
        readSelectedProfile = {
            when (val result = contextProfileRepository.readSelectedProfileAuthoritative()) {
                is SelectedContextReadResult.Selected -> result.name
                else -> null
            }
        },
        detailViewModelFactory = detailViewModelFactory
    )

    private val _uiState = MutableStateFlow(BrainSkillsUiState())
    val uiState: StateFlow<BrainSkillsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        if (_uiState.value.busySkill != null) return
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                loadState = BrainSkillsLoadState.Loading,
                busySkill = null,
                feedback = null,
                reloadRequired = false
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
                loadSkills()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BrainSkillsLoadResult.Unavailable(
                    io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsLoadFailure(
                        BrainSkillsFailureReason.UNAVAILABLE
                    )
                )
            }
            currentCoroutineContext().ensureActive()
            when (result) {
                is BrainSkillsLoadResult.Available -> publishLoaded(result.snapshot)
                is BrainSkillsLoadResult.Unavailable -> _uiState.update {
                    it.copy(
                        loadState = BrainSkillsLoadState.Error(
                            skillsFailureMessage(result.failure.reason)
                        ),
                        busySkill = null,
                        reloadRequired = false
                    )
                }
            }
        }
    }

    fun toggle(name: String, enabled: Boolean) {
        val state = _uiState.value
        if (state.busySkill != null || state.reloadRequired) return
        val ready = state.loadState as? BrainSkillsLoadState.Ready ?: return
        if (ready.snapshot.skills.none { it.name == name }) return
        val snapshotId = ready.snapshot.snapshotId
        _uiState.update { it.copy(busySkill = name, feedback = null) }
        viewModelScope.launch {
            val result = try {
                toggleSkill(snapshotId, name, enabled)
            } catch (cancelled: CancellationException) {
                _uiState.update {
                    it.copy(
                        busySkill = null,
                        reloadRequired = true,
                        feedback = "Change could not be confirmed. Retry loading skills before changing it again."
                    )
                }
                throw cancelled
            } catch (_: Exception) {
                BrainSkillsToggleResult.NotConfirmed(
                    BrainSkillsFailureReason.READBACK_UNAVAILABLE,
                    mayHaveSaved = true
                )
            }
            currentCoroutineContext().ensureActive()
            publishToggleResult(snapshotId, result)
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    internal fun createDetailViewModel(
        snapshotId: Long,
        name: String,
        selectedProfile: String
    ): BrainSkillDetailViewModel? = detailViewModelFactory?.invoke(snapshotId, name, selectedProfile)

    internal fun hasDetailViewModelFactory(): Boolean = detailViewModelFactory != null

    private fun publishLoaded(snapshot: BrainSkillsSnapshot) {
        _uiState.update {
            it.copy(
                selectedProfile = snapshot.selectedProfile,
                loadState = BrainSkillsLoadState.Ready(snapshot),
                busySkill = null,
                feedback = null,
                reloadRequired = false
            )
        }
    }

    private fun publishToggleResult(snapshotId: Long, result: BrainSkillsToggleResult) {
        val state = _uiState.value
        val current = state.loadState as? BrainSkillsLoadState.Ready ?: return
        if (current.snapshot.snapshotId != snapshotId) return
        when (result) {
            is BrainSkillsToggleResult.Saved -> _uiState.update {
                it.copy(
                    selectedProfile = result.snapshot.selectedProfile,
                    loadState = BrainSkillsLoadState.Ready(result.snapshot),
                    busySkill = null,
                    feedback = "Skill saved",
                    reloadRequired = false
                )
            }
            is BrainSkillsToggleResult.Rejected -> _uiState.update {
                it.copy(
                    loadState = BrainSkillsLoadState.Ready(
                        result.authoritativeSnapshot ?: current.snapshot
                    ),
                    busySkill = null,
                    feedback = skillsFailureMessage(result.reason),
                    reloadRequired = false
                )
            }
            is BrainSkillsToggleResult.NotConfirmed -> _uiState.update {
                it.copy(
                    loadState = BrainSkillsLoadState.Ready(
                        result.authoritativeSnapshot ?: current.snapshot
                    ),
                    busySkill = null,
                    feedback = notConfirmedMessage(result.reason),
                    reloadRequired = true
                )
            }
        }
    }

    private fun skillsFailureMessage(reason: BrainSkillsFailureReason): String = when (reason) {
        BrainSkillsFailureReason.INVALID_SELECTION -> "Select a valid profile before loading skills."
        BrainSkillsFailureReason.BUSY -> "Skills are still loading."
        BrainSkillsFailureReason.SKILL_UNAVAILABLE -> "That skill is no longer in the installed list."
        BrainSkillsFailureReason.WRITE_REJECTED -> "Hermes rejected the skill change."
        BrainSkillsFailureReason.MALFORMED -> "Installed skills could not be read."
        BrainSkillsFailureReason.STALE -> "The selected profile changed. Reload skills."
        else -> "Skills are unavailable right now."
    }

    private fun notConfirmedMessage(reason: BrainSkillsFailureReason): String = when (reason) {
        BrainSkillsFailureReason.READBACK_MISMATCH -> "Hermes did not confirm that skill change. Retry loading skills before changing it again."
        BrainSkillsFailureReason.WRITE_REJECTED -> "Hermes may not have applied that change. Retry loading skills before changing it again."
        else -> "Change could not be confirmed. Retry loading skills before changing it again."
    }
}
