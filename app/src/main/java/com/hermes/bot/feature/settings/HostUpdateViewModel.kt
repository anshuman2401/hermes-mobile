package com.hermes.bot.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.insights.InsightsScope
import com.hermes.bot.data.update.HostUpdateCheck
import com.hermes.bot.data.update.HostUpdateFailure
import com.hermes.bot.data.update.HostUpdateRepository
import com.hermes.bot.data.update.HostUpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class HostUpdatePhase { IDLE, STARTING, WAITING, COMPLETE, FAILED, UNKNOWN }

data class HostUpdateUiState(
    val loading: Boolean = false,
    val check: HostUpdateCheck? = null,
    val checkedAt: Long? = null,
    val failure: HostUpdateFailure? = null,
    val phase: HostUpdatePhase = HostUpdatePhase.IDLE,
    val confirmationOpen: Boolean = false,
    val canTrack: Boolean = false
) {
    val canStart: Boolean get() = !loading && canTrack && phase == HostUpdatePhase.IDLE && check?.appUpdateEligible == true
    override fun toString() = "HostUpdateUiState([private])"
}

/** Foreground page owner. The host action survives leaving this screen; Android never repeats its POST. */
class HostUpdateViewModel(
    private val repository: HostUpdateRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val waitForPoll: suspend () -> Unit = { delay(5_000) }
) : ViewModel() {
    private val mutable = MutableStateFlow(HostUpdateUiState())
    val state: StateFlow<HostUpdateUiState> = mutable
    private var job: Job? = null
    private var generation = 0L
    private var original: InsightsScope? = null
    private var scope: InsightsScope? = null
    private var actionId: String? = null

    fun open() {
        leave()
        val request = ++generation
        mutable.value = HostUpdateUiState(loading = true)
        job = viewModelScope.launch {
            try {
                when (val captured = repository.capture()) {
                    is HostUpdateResult.Failed -> if (request == generation) mutable.value = HostUpdateUiState(failure = captured.reason)
                    is HostUpdateResult.Ready -> {
                        if (request != generation) return@launch
                        original = captured.value; scope = captured.value
                        val status = repository.status(captured.value)
                        if (request != generation) return@launch
                        if (status is HostUpdateResult.Failed) {
                            loadWithoutTracking(captured.value, status.reason, request)
                            return@launch
                        }
                        val current = (status as HostUpdateResult.Ready).value
                        mutable.value = mutable.value.copy(canTrack = true,
                            phase = if (current.running) HostUpdatePhase.WAITING else HostUpdatePhase.IDLE)
                        loadCheck(captured.value, request)
                        if (current.running && request == generation) poll(request)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { if (request == generation) mutable.value = mutable.value.copy(loading = false, failure = HostUpdateFailure.UNAVAILABLE) }
        }
    }

    fun refresh() {
        if (mutable.value.loading || mutable.value.phase == HostUpdatePhase.STARTING) return
        val captured = scope ?: return open()
        val request = ++generation
        job?.cancel()
        mutable.value = mutable.value.copy(loading = true, failure = null)
        job = viewModelScope.launch {
            try {
                val status = repository.status(captured)
                if (request != generation) return@launch
                when (status) {
                    is HostUpdateResult.Failed -> {
                        mutable.value = mutable.value.copy(loading = false, failure = status.reason, canTrack = false,
                            confirmationOpen = false, phase = if (actionId != null) HostUpdatePhase.UNKNOWN else mutable.value.phase)
                        if (actionId == null && mutable.value.phase == HostUpdatePhase.IDLE)
                            loadWithoutTracking(captured, status.reason, request)
                    }
                    is HostUpdateResult.Ready -> {
                        if (actionId != null && !status.value.running) {
                            applyTerminal(status.value.actionId, status.value.outcome, request)
                        } else {
                            mutable.value = mutable.value.copy(canTrack = true,
                                phase = if (status.value.running) HostUpdatePhase.WAITING else mutable.value.phase)
                            loadCheck(captured, request)
                            if (status.value.running && request == generation) poll(request)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { if (request == generation) mutable.value = mutable.value.copy(loading = false, failure = HostUpdateFailure.UNAVAILABLE) }
        }
    }

    fun requestUpdate() {
        if (mutable.value.canStart) mutable.value = mutable.value.copy(confirmationOpen = true)
    }

    fun dismissConfirmation() { mutable.value = mutable.value.copy(confirmationOpen = false) }

    fun confirmUpdate() {
        if (!mutable.value.canStart || !mutable.value.confirmationOpen) return
        val captured = scope ?: return
        val request = ++generation
        job?.cancel()
        mutable.value = mutable.value.copy(confirmationOpen = false, phase = HostUpdatePhase.STARTING, failure = null)
        job = viewModelScope.launch {
            try {
                when (val result = repository.start(captured)) {
                    is HostUpdateResult.Failed -> if (request == generation) {
                        mutable.value = mutable.value.copy(phase = if (result.reason == HostUpdateFailure.UNKNOWN) HostUpdatePhase.UNKNOWN else HostUpdatePhase.IDLE,
                            failure = result.reason, check = null, canTrack = false)
                    }
                    is HostUpdateResult.Ready -> if (request == generation) {
                        actionId = result.value.actionId
                        mutable.value = mutable.value.copy(phase = HostUpdatePhase.WAITING)
                        poll(request)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { if (request == generation) mutable.value = mutable.value.copy(phase = HostUpdatePhase.UNKNOWN, failure = HostUpdateFailure.UNKNOWN) }
        }
    }

    private suspend fun loadWithoutTracking(captured: InsightsScope, failure: HostUpdateFailure, request: Long) {
        val canRead = failure in setOf(HostUpdateFailure.UNAVAILABLE, HostUpdateFailure.MALFORMED, HostUpdateFailure.UNSUPPORTED)
        mutable.value = mutable.value.copy(loading = canRead, failure = failure, check = null,
            checkedAt = null, canTrack = false, confirmationOpen = false)
        if (!canRead) return
        loadCheck(captured, request)
        if (request == generation && mutable.value.check != null)
            mutable.value = mutable.value.copy(failure = HostUpdateFailure.STATUS_UNAVAILABLE)
    }

    private suspend fun loadCheck(captured: InsightsScope, request: Long) {
        when (val result = repository.check(captured)) {
            is HostUpdateResult.Failed -> if (request == generation) mutable.value = mutable.value.copy(loading = false, failure = result.reason, check = null)
            is HostUpdateResult.Ready -> if (request == generation) mutable.value = mutable.value.copy(loading = false,
                check = result.value, checkedAt = clock(), failure = null)
        }
    }

    private suspend fun poll(request: Long) {
        while (request == generation && mutable.value.phase == HostUpdatePhase.WAITING) {
            waitForPoll()
            if (request != generation) return
            val fresh = repository.capture()
            if (fresh !is HostUpdateResult.Ready) continue // Host may be restarting.
            val initial = original ?: return
            if (fresh.value.profile != initial.profile || fresh.value.localId != initial.localId ||
                !fresh.value.origin.isSameOrigin(initial.origin)) {
                mutable.value = mutable.value.copy(phase = HostUpdatePhase.UNKNOWN, failure = HostUpdateFailure.STALE)
                return
            }
            scope = fresh.value
            when (val status = repository.status(fresh.value)) {
                is HostUpdateResult.Failed -> if (status.reason == HostUpdateFailure.AUTH || status.reason == HostUpdateFailure.ORIGIN) {
                    mutable.value = mutable.value.copy(phase = HostUpdatePhase.UNKNOWN, failure = status.reason)
                    return
                }
                is HostUpdateResult.Ready -> if (!status.value.running) {
                    applyTerminal(status.value.actionId, status.value.outcome, request)
                    return
                }
            }
        }
    }

    private suspend fun applyTerminal(id: String?, outcome: String?, request: Long) {
        if (request != generation) return
        val confirmed = actionId != null && id == actionId
        if (confirmed && outcome == "success") {
            val current = scope ?: return
            val checked = repository.check(current)
            if (request != generation) return
            if (checked is HostUpdateResult.Ready && !checked.value.available && checked.value.behind == 0) {
                mutable.value = mutable.value.copy(phase = HostUpdatePhase.COMPLETE, failure = null, loading = false,
                    check = checked.value, checkedAt = clock())
            } else {
                mutable.value = mutable.value.copy(phase = HostUpdatePhase.UNKNOWN, failure = HostUpdateFailure.UNKNOWN, loading = false)
            }
            return
        }
        mutable.value = when {
            confirmed && outcome in setOf("partial", "failed", "refused", "interrupted") -> mutable.value.copy(phase = HostUpdatePhase.FAILED,
                failure = if (outcome == "interrupted") HostUpdateFailure.INTERRUPTED else HostUpdateFailure.REFUSED, loading = false)
            else -> mutable.value.copy(phase = HostUpdatePhase.UNKNOWN, failure = HostUpdateFailure.UNKNOWN, loading = false)
        }
    }

    fun leave() {
        generation++; job?.cancel(); job = null
        original = null; scope = null; actionId = null
        mutable.value = HostUpdateUiState()
    }

    override fun onCleared() { leave() }
}
