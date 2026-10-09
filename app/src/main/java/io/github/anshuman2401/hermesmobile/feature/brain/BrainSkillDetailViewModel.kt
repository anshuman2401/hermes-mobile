package io.github.anshuman2401.hermesmobile.feature.brain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsContentFailureReason
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsContentResult
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BrainSkillDetailIdentity(
    val snapshotId: Long,
    val name: String,
    val selectedProfile: String
)

sealed interface BrainSkillDetailState {
    data object Loading : BrainSkillDetailState
    data class Empty(val identity: BrainSkillDetailIdentity) : BrainSkillDetailState
    data class Document(val document: SkillDocument) : BrainSkillDetailState
    data class Error(val message: String, val canRetry: Boolean) : BrainSkillDetailState
    data class Unavailable(val message: String) : BrainSkillDetailState
}

data class BrainSkillDetailUiState(
    val identity: BrainSkillDetailIdentity,
    val state: BrainSkillDetailState = BrainSkillDetailState.Loading
)

/** Read-only detail owner. The callback seam keeps JVM tests independent of transport construction. */
class BrainSkillDetailViewModel(
    val identity: BrainSkillDetailIdentity,
    private val readContent: suspend (Long, String) -> BrainSkillsContentResult,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parseDocument: (String) -> SkillDocument = SkillDocumentPolicy::parse
) : ViewModel() {

    /** Callback-first overload used by deterministic JVM tests. */
    constructor(
        readContent: suspend (Long, String) -> BrainSkillsContentResult,
        identity: BrainSkillDetailIdentity,
        workDispatcher: CoroutineDispatcher = Dispatchers.IO,
        parseDocument: (String) -> SkillDocument = SkillDocumentPolicy::parse
    ) : this(identity, readContent, workDispatcher, parseDocument)

    constructor(
        snapshotId: Long,
        name: String,
        selectedProfile: String,
        readContent: suspend (Long, String) -> BrainSkillsContentResult,
        workDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        BrainSkillDetailIdentity(snapshotId, name, selectedProfile),
        readContent,
        workDispatcher
    )

    constructor(
        identity: BrainSkillDetailIdentity,
        repository: BrainSkillsRepository,
        workDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(identity, repository::readContent, workDispatcher)

    private val _uiState = MutableStateFlow(BrainSkillDetailUiState(identity))
    val uiState: StateFlow<BrainSkillDetailUiState> = _uiState.asStateFlow()

    private var attempt = 0L
    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() {
        if ((_uiState.value.state as? BrainSkillDetailState.Error)?.canRetry == true) load()
    }

    private fun load() {
        val currentAttempt = ++attempt
        loadJob?.cancel()
        _uiState.update { it.copy(state = BrainSkillDetailState.Loading) }
        loadJob = viewModelScope.launch(workDispatcher) {
            val result = try {
                readContent(identity.snapshotId, identity.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BrainSkillsContentResult.Unavailable(BrainSkillsContentFailureReason.UNAVAILABLE)
            }
            currentCoroutineContext().ensureActive()
            if (currentAttempt != attempt) return@launch
            when (result) {
                is BrainSkillsContentResult.Available -> {
                    // Parsing is deliberately kept off the main dispatcher. The
                    // source string is passed unchanged to the lossless policy.
                    val document = try {
                        withContext(workDispatcher) { parseDocument(result.content) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (currentAttempt == attempt) {
                            _uiState.update {
                                it.copy(
                                    state = BrainSkillDetailState.Error(
                                        "The skill document could not be formatted.",
                                        canRetry = true
                                    )
                                )
                            }
                        }
                        return@launch
                    }
                    currentCoroutineContext().ensureActive()
                    if (currentAttempt == attempt) {
                        _uiState.update {
                            it.copy(
                                state = if (result.content.isEmpty()) {
                                    BrainSkillDetailState.Empty(identity)
                                } else {
                                    BrainSkillDetailState.Document(document)
                                }
                            )
                        }
                    }
                }
                is BrainSkillsContentResult.Unavailable -> _uiState.update {
                    it.copy(state = contentState(result.reason))
                }
            }
        }
    }

    private fun contentState(reason: BrainSkillsContentFailureReason): BrainSkillDetailState = when (reason) {
        BrainSkillsContentFailureReason.STALE,
        BrainSkillsContentFailureReason.BUSY -> BrainSkillDetailState.Unavailable(
            "This skill detail is no longer available. Return to Skills and reload the inventory."
        )
        BrainSkillsContentFailureReason.MISSING -> BrainSkillDetailState.Error(
            "Hermes could not find this skill document.", canRetry = true
        )
        BrainSkillsContentFailureReason.REFUSED -> BrainSkillDetailState.Error(
            "Hermes refused the skill document read.", canRetry = true
        )
        BrainSkillsContentFailureReason.MALFORMED -> BrainSkillDetailState.Error(
            "Hermes returned an unreadable skill document.", canRetry = true
        )
        BrainSkillsContentFailureReason.TOO_LARGE -> BrainSkillDetailState.Error(
            "The skill document is too large to read safely.", canRetry = false
        )
        BrainSkillsContentFailureReason.AUTH_UNAVAILABLE,
        BrainSkillsContentFailureReason.UNAVAILABLE -> BrainSkillDetailState.Error(
            "The skill document is unavailable right now.", canRetry = true
        )
    }
}
