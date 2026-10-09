package com.hermes.bot.data.subagent

import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

/** Optional child methods do not participate in the gateway's required capability gate. */
interface SubagentRepository {
    val state: StateFlow<SubagentState>
    val ownerChanges: Flow<Unit>
    suspend fun capture(storedId: String, runtimeId: String): SubagentOwner?
    suspend fun isCurrent(owner: SubagentOwner): Boolean
    fun attach(owner: SubagentOwner)
    fun suspendObservation(clear: Boolean = false)
    suspend fun admit(owner: SubagentOwner, event: GatewayEvent.SubagentUpdate)
    suspend fun refresh(owner: SubagentOwner)
    suspend fun tail(owner: SubagentOwner, childId: String): SubagentTail?
    suspend fun control(owner: SubagentOwner, childId: String, text: String?): SubagentControl
}

data class SubagentState(val children: List<Subagent> = emptyList(), val available: Boolean = false,
    val notice: String? = null, val truncated: Boolean = false, val revision: Long = 0, val retiredIds: List<String> = emptyList(), val disabledControls: Set<String> = emptySet()) {
    override fun toString() = "SubagentState([redacted])"
}

/** Pure, bounded in-place reduction. Terminal state never regresses to live. */
object SubagentReducer {
    fun merge(old: Subagent?, next: Subagent, now: Long): Subagent {
        if (old?.finished == true) return old
        return next.copy(
            goal = if (next.goal == "Delegated task") old?.goal ?: next.goal else next.goal,
            parentId = next.parentId ?: old?.parentId, delegationId = next.delegationId ?: old?.delegationId,
            taskIndex = next.taskIndex ?: old?.taskIndex, model = next.model ?: old?.model,
            startedAtMillis = old?.startedAtMillis ?: next.startedAtMillis ?: now,
            durationSeconds = next.durationSeconds ?: old?.durationSeconds,
            toolCount = next.toolCount ?: old?.toolCount, tokens = next.tokens ?: old?.tokens,
            acceptingSteer = next.acceptingSteer ?: old?.acceptingSteer,
            activity = ((old?.activity.orEmpty()) + next.activity).fold(emptyList<String>()) { acc, item ->
                if (acc.lastOrNull() == item) acc else (acc + item).takeLast(24)
            })
    }
    fun event(state: SubagentState, next: Subagent, now: Long): SubagentState {
        if (next.id in state.retiredIds) return state
        val old = state.children.firstOrNull { it.id == next.id }
        if (old == null && state.children.size >= SubagentWireDecoder.MAX_CHILDREN) {
            val retired = state.children.firstOrNull { it.status.terminal || it.status == SubagentStatus.UNKNOWN }
                ?: return state.copy(truncated = true, revision = state.revision + 1)
            return event(state.copy(children = state.children.filter { it.id != retired.id },
                retiredIds = (state.retiredIds + retired.id).takeLast(256)), next, now)
        }
        val merged = merge(old, next, now)
        return state.copy(children = if (old == null) state.children + merged else state.children.map { if (it.id == next.id) merged else it },
            available = true, notice = null, revision = state.revision + 1)
    }
    fun snapshot(state: SubagentState, roster: SubagentRoster, now: Long): SubagentState {
        val incoming = (roster.children + roster.failures).filter { it.id !in state.retiredIds }.associateBy { it.id }
        val next = state.children.map { old -> incoming[old.id]?.let { merge(old, it, now) }
            ?: if (old.status.live) old.copy(status = SubagentStatus.UNKNOWN) else old }
        val known = next.mapTo(mutableSetOf()) { it.id }
        val combined = next + incoming.values.filter { it.id !in known }.map { merge(null, it, now) }
        val retained = combined.toMutableList()
        val retired = state.retiredIds.toMutableList()
        while (retained.size > SubagentWireDecoder.MAX_CHILDREN) {
            val index = retained.indexOfFirst { !it.status.live }.takeIf { it >= 0 } ?: retained.lastIndex
            retired += retained.removeAt(index).id
        }
        return state.copy(children = retained, retiredIds = retired.takeLast(256), available = true,
            notice = null, truncated = roster.truncated || combined.size > SubagentWireDecoder.MAX_CHILDREN,
            revision = state.revision + 1)
    }
}

class DefaultSubagentRepository(private val adapter: GatewayProtocolAdapter, private val sessions: SessionRepository,
    private val clock: () -> Long = System::currentTimeMillis) : SubagentRepository {
    private val mutable = MutableStateFlow(SubagentState())
    override val state: StateFlow<SubagentState> = mutable.asStateFlow()
    private var attached: SubagentOwner? = null
    private val unsupported = mutableSetOf<String>()
    private var generation: Long? = null
    override val ownerChanges: Flow<Unit> = merge(adapter.connectionStatus.map { Unit }, sessions.activeSession.map { it?.runtimeSessionId }.distinctUntilChanged().map { Unit })
    override suspend fun capture(storedId: String, runtimeId: String): SubagentOwner? =
        sessions.captureSendActivityOwner(storedId, runtimeId)?.let { SubagentOwner(storedId, runtimeId, it) }
    override suspend fun isCurrent(owner: SubagentOwner) = adapter.connectionStatus.value == com.hermes.bot.domain.ConnectionStatus.CONNECTED &&
        sessions.isCommandOwnerCurrent(owner.activity)
    override fun attach(owner: SubagentOwner) {
        if (generation != owner.activity.scope.gatewayGeneration) { unsupported.clear(); mutable.update { it.copy(disabledControls = emptySet()) }; generation = owner.activity.scope.gatewayGeneration }
        val old = attached
        if (old == null || old.storedId != owner.storedId || old.activity.authority.lease.origin != owner.activity.authority.lease.origin ||
            old.activity.authority.selection != owner.activity.authority.selection) mutable.value = SubagentState()
        attached = owner
    }
    override fun suspendObservation(clear: Boolean) { mutable.update { if (clear) SubagentState(disabledControls = it.disabledControls, revision = it.revision + 1) else it.copy(available = false, revision = it.revision + 1) } }
    override suspend fun admit(owner: SubagentOwner, event: GatewayEvent.SubagentUpdate) {
        if (event.sessionId != owner.runtimeId || !isCurrent(owner)) return
        val child = event.child ?: return
        if (attached?.storedId != owner.storedId) attach(owner)
        mutable.update { SubagentReducer.event(it, child, clock()) }
    }
    private fun unsupported(failure: GatewayFailure) = failure is GatewayFailure.IncompatibleProtocol ||
        (failure is GatewayFailure.Rpc && failure.code == -32601)
    private fun origin(owner: SubagentOwner) = CanonicalOrigin.parse(owner.activity.authority.lease.origin).getOrNull()
    override suspend fun refresh(owner: SubagentOwner) {
        if (!isCurrent(owner)) { if (attached === owner) suspendObservation(clear = true); return }
        if ("list" in unsupported) return
        val expected = mutable.value
        val origin = origin(owner) ?: return
        val result = try { adapter.listSubagents(owner.runtimeId, origin, owner.activity.scope.gatewayGeneration) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { GatewayResult.Failure(GatewayFailure.Transport("Roster unavailable")) }
        if (!isCurrent(owner) || attached !== owner) { if (attached === owner) suspendObservation(clear = true); return }
        when (result) {
            is GatewayResult.Success -> mutable.update { if (it === expected) SubagentReducer.snapshot(it, result.value, clock()) else it }
            is GatewayResult.Failure -> {
                if (unsupported(result.failure)) unsupported += "list"
                mutable.update { if (it !== expected) it else it.copy(available = false, notice = "Live roster unavailable.") }
            }
        }
    }
    override suspend fun tail(owner: SubagentOwner, childId: String): SubagentTail? {
        if (!isCurrent(owner) || "tail" in unsupported || state.value.children.none { it.id == childId && it.observed }) return null
        val origin = origin(owner) ?: return null
        val result = try { adapter.subagentTail(owner.runtimeId, childId, origin, owner.activity.scope.gatewayGeneration) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        if (!isCurrent(owner) || attached !== owner) return null
        if (result is GatewayResult.Failure && unsupported(result.failure)) unsupported += "tail"
        return result.getOrNull()
    }
    override suspend fun control(owner: SubagentOwner, childId: String, text: String?): SubagentControl {
        val method = if (text == null) "stop" else "steer"
        if (method in state.value.disabledControls) return SubagentControl.UNAVAILABLE
        val child = state.value.children.firstOrNull { it.id == childId && it.observed && it.status.live }
        if (!isCurrent(owner) || attached !== owner || !state.value.available || child == null ||
            (text != null && (text.isBlank() || child.acceptingSteer == false))) return SubagentControl.UNAVAILABLE
        val origin = origin(owner) ?: return SubagentControl.UNAVAILABLE
        val result = try { adapter.controlSubagent(owner.runtimeId, childId, text, origin, owner.activity.scope.gatewayGeneration) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { SubagentControl.UNKNOWN }
        if (!isCurrent(owner) || attached !== owner) return SubagentControl.UNKNOWN
        if (result == SubagentControl.UNAVAILABLE) mutable.update { it.copy(disabledControls = it.disabledControls + method) }
        return result
    }
}
