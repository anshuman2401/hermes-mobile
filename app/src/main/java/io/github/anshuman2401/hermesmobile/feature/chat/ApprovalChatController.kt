package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.approval.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Visible-chat lifecycle only; no approval service or background polling. */
class ApprovalChatController(private val repository: ApprovalRepository, private val scope: CoroutineScope) {
    val state = repository.state
    private var stored: String? = null
    private var runtime: String? = null
    private var connected = false
    private var visible = false
    private var owner: ApprovalOwner? = null
    private var job: Job? = null
    private var epoch = 0L
    fun environment(storedId: String?, runtimeId: String?, isConnected: Boolean) {
        if (stored == storedId && runtime == runtimeId && connected == isConnected) return
        val changed = stored != storedId || runtime != runtimeId
        stored = storedId; runtime = runtimeId; connected = isConnected
        restart(clear = changed)
    }
    fun visibility(value: Boolean) {
        if (visible == value) return
        visible = value; restart(clear = !value)
    }
    private fun restart(clear: Boolean) {
        epoch++; job?.cancel(); owner = null
        repository.suspendObservation(clear)
        if (!visible || !connected || stored == null || runtime == null) return
        val expected = epoch; val sid = stored!!; val rid = runtime!!
        job = scope.launch {
            val captured = repository.capture(sid, rid) ?: return@launch
            if (expected != epoch || !repository.current(captured)) return@launch
            owner = captured; repository.attach(captured)
            repository.observe(captured); repository.refresh(captured)
            coroutineScope {
                launch {
                    repository.changes.collect {
                        if (expected != epoch) return@collect
                        repository.observe(captured)
                    }
                }
                launch {
                    while (isActive && expected == epoch) {
                        delay(5000)
                        if (state.value.blocksSend) repository.refresh(captured)
                    }
                }
            }
        }
    }
    fun decide(requestId: String, choice: ApprovalChoice) {
        val captured = owner ?: return
        if (!visible || !connected) return
        scope.launch { repository.decide(captured, requestId, choice) }
    }
    fun check() {
        val captured = owner ?: return
        if (visible && connected) scope.launch { repository.refresh(captured) }
    }
}
