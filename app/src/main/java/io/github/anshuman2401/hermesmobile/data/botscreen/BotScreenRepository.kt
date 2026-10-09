package io.github.anshuman2401.hermesmobile.data.botscreen

import io.github.anshuman2401.hermesmobile.data.bots.BotRepository
import io.github.anshuman2401.hermesmobile.data.bots.BotScope
import io.github.anshuman2401.hermesmobile.data.bots.BotTarget
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class BotScreenState(
    val loading: Boolean = true,
    val status: ScreenStatus? = null,
    val frame: ScreenFrame? = null,
    val attached: Boolean = false,
    val holding: Boolean = false,
    val busy: Boolean = false,
    val retryRequired: Boolean = false,
    val error: String? = null
)

/** Owns profile/origin/generation admission and presentation of screen and lease state. */
class BotScreenRepository(
    private val bots: BotRepository,
    private val gateway: GatewayProtocolAdapter,
    stream: ScreenStream = RfbScreenStream()
) {
    private val serial = AtomicLong(0)
    private val mutation = AtomicLong(0)
    private val refreshing = AtomicBoolean(false)
    private val protocol = BotScreenProtocolAdapter(gateway, stream)
    private val _state = MutableStateFlow(BotScreenState())
    val state: StateFlow<BotScreenState> = _state
    val changes: Flow<Unit> = protocol.changes
    private var scope: BotScope? = null
    private var profile: String? = null

    suspend fun enter(target: BotTarget, selectedProfile: String) {
        val old = scope
        val oldProfile = profile
        val token = serial.incrementAndGet()
        mutation.incrementAndGet()
        protocol.close()
        scope = null; profile = null
        _state.value = BotScreenState()
        if (old != null && oldProfile != null && bots.isCurrent(old)) protocol.releaseIfHeld(old, oldProfile)
        protocol.forgetViewer()
        val captured = bots.capture(target)
        if (captured == null || selectedProfile.isBlank()) {
            _state.value = BotScreenState(loading = false, error = "Bot connection unavailable")
            return
        }
        scope = captured; profile = selectedProfile
        refresh(token)
    }

    suspend fun refresh() = refresh(serial.get())
    private suspend fun refresh(token: Long) {
        if (!refreshing.compareAndSet(false, true)) return
        try {
        val captured = scope ?: return
        val selected = profile ?: return
        if (!current(token, captured)) { invalidate(); return }
        if (_state.value.busy) return
        val revision = mutation.get()
        when (val result = protocol.status(captured, selected)) {
            is GatewayResult.Failure -> if (current(token, captured) && mutation.get() == revision) {
                _state.value = _state.value.copy(loading = false, busy = false, error = "Bot Screen unavailable. Retry when connected.")
            }
            is GatewayResult.Success -> if (current(token, captured) && mutation.get() == revision) {
                val holding = _state.value.attached && !_state.value.retryRequired && protocol.owns(result.value.lease)
                protocol.setInputEnabled(holding)
                _state.value = _state.value.copy(loading = false, busy = false, status = result.value,
                    holding = holding, error = if (_state.value.retryRequired) _state.value.error else null)
                if (result.value.running && !_state.value.attached && !_state.value.retryRequired) attach(token, captured, selected)
                if (!result.value.running && _state.value.attached) {
                    protocol.close()
                    _state.value = _state.value.copy(attached = false, frame = null, holding = false)
                }
            }
        }
        } finally { refreshing.set(false) }
    }

    suspend fun start() {
        val token = serial.get(); val captured = scope ?: return; val selected = profile ?: return
        if (!current(token, captured) || _state.value.busy) return
        mutation.incrementAndGet()
        _state.value = _state.value.copy(busy = true, error = null)
        val result = protocol.start(captured, selected)
        if (!current(token, captured)) return
        if (result is GatewayResult.Failure) {
            _state.value = _state.value.copy(busy = false, error = "Could not start screen. Check its status before retrying.")
        } else _state.value = _state.value.copy(busy = false)
        refresh(token)
    }

    private suspend fun attach(token: Long, captured: BotScope, selected: String) {
        if (!current(token, captured)) return
        val observed = when (val result = protocol.connect(captured, selected,
            onFrame = { frame -> if (current(token, captured)) {
                _state.value = _state.value.copy(frame = frame, attached = true, error = null)
            } },
            onClosed = { if (current(token, captured)) {
                protocol.setInputEnabled(false)
                _state.value = _state.value.copy(attached = false, holding = false, frame = null, retryRequired = true,
                    error = "Screen connection lost. Reconnect to check control.")
            } })) {
            is GatewayResult.Failure -> {
                if (current(token, captured)) _state.value = _state.value.copy(retryRequired = true, error = "Could not open screen. Retry.")
                return
            }
            is GatewayResult.Success -> result.value
        }
        if (!current(token, captured)) { protocol.close(); return }
        val holding = protocol.owns(observed.lease)
        _state.value = _state.value.copy(status = observed, holding = holding, attached = true, retryRequired = false)
        protocol.setInputEnabled(holding)
    }

    suspend fun reconnect() {
        val token = serial.get(); val captured = scope ?: return; val selected = profile ?: return
        if (!current(token, captured)) return
        mutation.incrementAndGet()
        protocol.close()
        _state.value = _state.value.copy(attached = false, frame = null, retryRequired = false, error = null)
        val status = protocol.status(captured, selected)
        if (!current(token, captured)) return
        if (status is GatewayResult.Success) {
            _state.value = _state.value.copy(status = status.value)
            if (status.value.running) attach(token, captured, selected)
        } else _state.value = _state.value.copy(error = "Screen unavailable. Retry.")
    }

    suspend fun takeOver() {
        val token = serial.get(); val captured = scope ?: return; val selected = profile ?: return
        if (!current(token, captured) || _state.value.busy || !_state.value.attached) return
        mutation.incrementAndGet()
        _state.value = _state.value.copy(busy = true, error = null)
        val result = protocol.takeOver(captured, selected)
        if (!current(token, captured)) return
        if (result is GatewayResult.Success && protocol.owns(result.value)) {
            protocol.setInputEnabled(true)
            _state.value = _state.value.copy(busy = false, holding = true,
                status = _state.value.status?.copy(lease = result.value))
        } else {
            protocol.setInputEnabled(false)
            _state.value = _state.value.copy(busy = false, holding = false, error = "Control outcome unknown. Checking screen.")
            refresh(token)
        }
    }

    suspend fun handBack() {
        val token = serial.get(); val captured = scope ?: return; val selected = profile ?: return
        if (!current(token, captured) || _state.value.busy) return
        mutation.incrementAndGet()
        protocol.setInputEnabled(false)
        _state.value = _state.value.copy(busy = true, error = null)
        val result = protocol.handBack(captured, selected)
        if (!current(token, captured)) return
        if (result is GatewayResult.Success && result.value.holder == ScreenLease.Holder.BOT) {
            _state.value = _state.value.copy(busy = false, holding = false,
                status = _state.value.status?.copy(lease = result.value))
        } else {
            _state.value = _state.value.copy(busy = false, holding = false,
                error = "Handback not confirmed. Check control before leaving.")
            refresh(token)
        }
    }

    suspend fun leave() {
        val captured = scope; val selected = profile
        serial.incrementAndGet()
        mutation.incrementAndGet()
        protocol.close()
        scope = null; profile = null
        _state.value = BotScreenState(loading = false)
        if (captured != null && selected != null && bots.isCurrent(captured)) protocol.releaseIfHeld(captured, selected)
        protocol.forgetViewer()
    }

    fun closeImmediately() {
        serial.incrementAndGet()
        mutation.incrementAndGet()
        protocol.close()
        protocol.forgetViewer()
        scope = null; profile = null
    }

    fun pointer(x: Int, y: Int, buttons: Int) { if (_state.value.holding) protocol.pointer(x, y, buttons) }
    fun key(keysym: Int) { if (_state.value.holding) { protocol.key(keysym, true); protocol.key(keysym, false) } }
    fun scroll(down: Boolean) {
        val frame = _state.value.frame ?: return
        if (!_state.value.holding) return
        val x = frame.width / 2; val y = frame.height / 2
        protocol.pointer(x, y, if (down) 16 else 8)
        protocol.pointer(x, y, 0)
    }

    private fun current(token: Long, captured: BotScope): Boolean =
        serial.get() == token && scope == captured && bots.isCurrent(captured)
    private fun invalidate() { mutation.incrementAndGet(); protocol.close(); _state.value = BotScreenState(loading = false, error = "Bot connection changed") }
}
