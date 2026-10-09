package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.subagent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Lifecycle-owned presentation; all reads, controls and event reduction go through the repository. */
data class SubagentChatState(
    val roster: SubagentState = SubagentState(), val expanded: Boolean = true,
    val selectedId: String? = null, val selectedFallback: Subagent? = null,
    val transcriptVisible: Boolean = false, val transcript: SubagentTail? = null,
    val transcriptLoading: Boolean = false, val draft: String = "", val pending: Boolean = false,
    val feedback: String? = null, val connected: Boolean = false
) { override fun toString() = "SubagentChatState([redacted])" }

class SubagentChatController(private val repository: SubagentRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(SubagentChatState())
    val state = mutable.asStateFlow()
    private var stored: String? = null
    private var runtime: String? = null
    private var connected = false
    private var visible = false
    private var owner: SubagentOwner? = null
    private var polling: Job? = null
    private var tailJob: Job? = null
    private var epoch = 0L
    private var detailEpoch = 0L
    init {
        scope.launch { repository.state.collect { roster -> mutable.update { it.copy(roster = roster) } } }
        scope.launch { repository.ownerChanges.collect { close(); repository.suspendObservation(clear = true); restart() } }
    }
    fun environment(storedId: String?, runtimeId: String?, isConnected: Boolean) {
        if (stored == storedId && runtime == runtimeId && connected == isConnected) return
        val changed = stored != storedId || runtime != runtimeId
        stored = storedId; runtime = runtimeId; connected = isConnected
        mutable.update { it.copy(connected = isConnected) }
        if (changed) repository.suspendObservation(clear = true)
        if (changed || !isConnected) close()
        restart()
    }
    fun visibility(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) close()
        restart()
    }
    private fun restart() {
        epoch++; polling?.cancel(); tailJob?.cancel(); owner = null
        repository.suspendObservation()
        if (!visible || !connected || stored == null || runtime == null) return
        val request = epoch; val storedId = stored!!; val runtimeId = runtime!!
        polling = scope.launch {
            try {
                val captured = repository.capture(storedId, runtimeId) ?: return@launch
                if (request != epoch || !repository.isCurrent(captured)) return@launch
                owner = captured; repository.attach(captured)
                startTail()
                while (isActive && request == epoch) { repository.refresh(captured); delay(5000) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { repository.suspendObservation() }
        }
    }
    suspend fun admit(event: GatewayEvent.SubagentUpdate) {
        if (!visible || !connected || event.sessionId != runtime) return
        val captured = owner ?: repository.capture(stored ?: return, runtime ?: return) ?: return
        repository.admit(captured, event)
    }
    fun toggleDock() { mutable.update { it.copy(expanded = !it.expanded) } }
    fun open(child: Subagent) {
        detailEpoch++
        tailJob?.cancel()
        mutable.update { it.copy(selectedId = child.id, selectedFallback = child, transcriptVisible = false,
            transcript = null, transcriptLoading = false, draft = "", pending = false, feedback = null) }
    }
    fun close() { detailEpoch++; tailJob?.cancel(); mutable.update { it.copy(selectedId = null, selectedFallback = null,
        transcriptVisible = false, transcript = null, transcriptLoading = false, draft = "", pending = false, feedback = null) } }
    fun back() { if (state.value.transcriptVisible) { tailJob?.cancel(); mutable.update { it.copy(transcriptVisible = false, transcript = null, transcriptLoading = false) } } else close() }
    fun draft(text: String) { mutable.update { it.copy(draft = text.take(4096)) } }
    fun transcript() { if (state.value.selectedFallback?.observed != true) return; mutable.update { it.copy(transcriptVisible = true, transcript = null, transcriptLoading = true) }; startTail() }
    private fun startTail() {
        tailJob?.cancel()
        val id = state.value.selectedId ?: return
        if (!state.value.transcriptVisible) return
        val captured = owner
        if (captured == null || !visible || !connected) { mutable.update { it.copy(transcriptLoading = false) }; return }
        val request = epoch
        tailJob = scope.launch {
            try {
                while (isActive && request == epoch && state.value.selectedId == id && state.value.transcriptVisible) {
                    val result = repository.tail(captured, id)
                    if (request != epoch || state.value.selectedId != id) return@launch
                    mutable.update { it.copy(transcript = result, transcriptLoading = false) }
                    if (result == null || (repository.state.value.children.firstOrNull { it.id == id }?.status?.terminal == true)) return@launch
                    delay(2000)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(transcriptLoading = false, transcript = null) } }
        }
    }
    fun control(steer: Boolean) {
        val initial = state.value
        if (initial.pending || !visible || !connected || initial.selectedFallback?.observed != true) return
        val id = initial.selectedId ?: return
        val captured = owner ?: return
        val text = if (steer) initial.draft.trim().takeIf(String::isNotBlank) ?: return else null
        val request = epoch; val detailRequest = detailEpoch
        mutable.update { it.copy(pending = true, feedback = null) }
        scope.launch {
            val outcome = try { repository.control(captured, id, text) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { SubagentControl.UNKNOWN }
            if (request != epoch || detailRequest != detailEpoch || state.value.selectedId != id) return@launch
            mutable.update { it.copy(pending = false, draft = if (outcome == SubagentControl.QUEUED) "" else it.draft,
                feedback = when (outcome) {
                    SubagentControl.QUEUED -> "Instruction queued."
                    SubagentControl.STOP_REQUESTED -> "Stop requested."
                    SubagentControl.REJECTED -> "The host rejected this request. The agent may have finished or belong to an earlier connection."
                    SubagentControl.UNKNOWN -> "Outcome unknown. This request will not be retried automatically."
                    SubagentControl.UNAVAILABLE -> "Agent controls unavailable."
                }) }
        }
    }
}
