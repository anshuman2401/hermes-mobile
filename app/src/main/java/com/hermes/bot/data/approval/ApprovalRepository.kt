package com.hermes.bot.data.approval

import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

/** Presentation and queue data are memory-only; approval never enters transcript caches. */
enum class ApprovalPhase { IDLE, LOADING, PENDING, SENDING, UNKNOWN, UNAVAILABLE }
data class ApprovalState(
    val requests: List<ApprovalRequest> = emptyList(), val phase: ApprovalPhase = ApprovalPhase.IDLE,
    val notice: String? = null, val connected: Boolean = false
) {
    val blocksSend get() = requests.isNotEmpty() || phase in setOf(ApprovalPhase.LOADING, ApprovalPhase.SENDING, ApprovalPhase.UNKNOWN)
    val canDecide get() = connected && phase == ApprovalPhase.PENDING && requests.isNotEmpty()
    override fun toString() = "ApprovalState([redacted])"
}
data class ApprovalOwner(val storedId: String, val runtimeId: String, val activity: SessionActivityOwner) {
    override fun toString() = "ApprovalOwner([redacted])"
}
interface ApprovalRepository {
    val state: StateFlow<ApprovalState>
    val changes: Flow<Unit>
    suspend fun capture(storedId: String, runtimeId: String): ApprovalOwner?
    suspend fun current(owner: ApprovalOwner): Boolean
    fun attach(owner: ApprovalOwner)
    fun suspendObservation(clear: Boolean)
    suspend fun observe(owner: ApprovalOwner)
    suspend fun refresh(owner: ApprovalOwner)
    suspend fun decide(owner: ApprovalOwner, requestId: String, choice: ApprovalChoice)
}

class DefaultApprovalRepository(private val adapter: GatewayProtocolAdapter, private val sessions: SessionRepository) : ApprovalRepository {
    private val mutable = MutableStateFlow(ApprovalState())
    override val state = mutable.asStateFlow()
    override val changes: Flow<Unit> = merge(adapter.approvalInbox.map { Unit }, adapter.connectionStatus.map { Unit },
        sessions.activeSession.map { it?.runtimeSessionId }.distinctUntilChanged().map { Unit })
    private var owner: ApprovalOwner? = null
    private val decision = Mutex()
    private var refreshing = false
    private var uncertainId: String? = null
    override suspend fun capture(storedId: String, runtimeId: String) =
        sessions.captureSendActivityOwner(storedId, runtimeId)?.let { ApprovalOwner(storedId, runtimeId, it) }
    override suspend fun current(owner: ApprovalOwner) = adapter.connectionStatus.value == com.hermes.bot.domain.ConnectionStatus.CONNECTED &&
        sessions.isCommandOwnerCurrent(owner.activity)
    override fun attach(owner: ApprovalOwner) {
        this.owner = owner; uncertainId = null
        mutable.value = ApprovalState(phase = ApprovalPhase.LOADING, connected = true)
    }
    override fun suspendObservation(clear: Boolean) {
        owner = null
        if (clear) uncertainId = null
        mutable.value = if (clear || (mutable.value.requests.isEmpty() && uncertainId == null)) ApprovalState()
        else mutable.value.copy(phase = ApprovalPhase.UNAVAILABLE,
            connected = false, notice = "Reconnect to review the current request.")
    }
    private fun origin(owner: ApprovalOwner) = CanonicalOrigin.parse(owner.activity.authority.lease.origin).getOrNull()
    override suspend fun observe(owner: ApprovalOwner) {
        if (this.owner !== owner) return
        if (!current(owner)) { suspendObservation(clear = true); return }
        val requests = adapter.approvalInbox.value.requests.filter { it.runtimeId == owner.runtimeId }
        mutable.update { old -> old.copy(requests = requests, connected = true,
            phase = when {
                old.phase in setOf(ApprovalPhase.SENDING, ApprovalPhase.UNKNOWN, ApprovalPhase.LOADING) -> old.phase
                requests.isEmpty() -> ApprovalPhase.IDLE
                else -> ApprovalPhase.PENDING
            }, notice = if (requests.isEmpty() && adapter.approvalInbox.value.cancelled[owner.runtimeId] == "timeout")
                "This request expired. The command did not run." else old.notice) }
    }
    override suspend fun refresh(owner: ApprovalOwner) {
        if (this.owner !== owner || refreshing) return
        if (!current(owner)) { suspendObservation(clear = true); return }
        val origin = origin(owner) ?: return
        refreshing = true
        try {
            val result = try { adapter.refreshApprovals(owner.runtimeId, origin, owner.activity.scope.gatewayGeneration) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { GatewayResult.Failure(GatewayFailure.Transport("Approval refresh unavailable")) }
            if (this.owner !== owner || !current(owner)) return
            observe(owner)
            when (result) {
                is GatewayResult.Success -> if (result.value && !decision.isLocked) {
                    uncertainId = null
                    mutable.update { it.copy(phase = if (it.requests.isEmpty()) ApprovalPhase.IDLE else ApprovalPhase.PENDING,
                        notice = if (it.requests.isEmpty() && adapter.approvalInbox.value.cancelled[owner.runtimeId] == "timeout")
                            "This request expired. The command did not run." else null) }
                }
                is GatewayResult.Failure -> mutable.update { old -> old.copy(
                    phase = if (uncertainId != null) ApprovalPhase.UNKNOWN else if (old.requests.isNotEmpty() && adapter.approvalInbox.value.available)
                        ApprovalPhase.PENDING else ApprovalPhase.UNAVAILABLE,
                    notice = if (uncertainId != null) "Decision outcome unknown. Check the host before deciding again."
                        else if (old.requests.isEmpty()) null else "Could not refresh approvals.") }
            }
        } finally { refreshing = false }
    }
    override suspend fun decide(owner: ApprovalOwner, requestId: String, choice: ApprovalChoice) {
        if (!decision.tryLock()) return
        try {
            if (this.owner !== owner || !current(owner) || !state.value.canDecide) return
            val request = state.value.requests.firstOrNull()?.takeIf { it.requestId == requestId && choice in it.choices } ?: return
            val origin = origin(owner) ?: return
            mutable.update { it.copy(phase = ApprovalPhase.SENDING, notice = "Sending your decision…") }
            uncertainId = requestId
            val outcome = try { adapter.answerApproval(request, choice, origin, owner.activity.scope.gatewayGeneration) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { ApprovalOutcome.UNKNOWN }
            if (this.owner !== owner || !current(owner)) return
            observe(owner)
            mutable.update { it.copy(phase = ApprovalPhase.UNKNOWN, notice = when(outcome) {
                ApprovalOutcome.SENT -> "Decision sent. Waiting for Hermes to update the turn."
                ApprovalOutcome.UNKNOWN -> "Decision outcome unknown. Check the host before deciding again."
                else -> "Decision not accepted. Checking the current request."
            }) }
        } finally { decision.unlock() }
        if (this.owner === owner) refresh(owner)
    }
}
