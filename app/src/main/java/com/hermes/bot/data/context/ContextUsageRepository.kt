package com.hermes.bot.data.context

import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.local.SelectedContextReadResult
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

interface ContextUsageRepository {
    suspend fun capture(storedId: String, runtimeId: String): ContextUsageOwner?
    suspend fun isCurrent(owner: ContextUsageOwner): Boolean
    suspend fun read(owner: ContextUsageOwner): GatewayResult<ContextUsageSnapshot>
    /** Fixed, scoped lifecycle categories; never raw transport events or error text. */
    val signals: Flow<ContextUsageSignal>
    /** Normalize an event from the chat's existing collector; do not start another event subscription. */
    suspend fun admitEvent(storedId: String, event: GatewayEvent): ContextUsageSignal? = null
}

class DefaultContextUsageRepository(
    private val adapter: GatewayProtocolAdapter,
    private val sessions: SessionRepository
) : ContextUsageRepository {
    override suspend fun capture(storedId: String, runtimeId: String): ContextUsageOwner? =
        sessions.captureSendActivityOwner(storedId, runtimeId)?.let { ContextUsageOwner(storedId, runtimeId, it) }
    override suspend fun isCurrent(owner: ContextUsageOwner): Boolean =
        adapter.connectionStatus.value == com.hermes.bot.domain.ConnectionStatus.CONNECTED &&
            owner.activity?.let { sessions.isCommandOwnerCurrent(it) } == true
    override suspend fun read(owner: ContextUsageOwner): GatewayResult<ContextUsageSnapshot> = coroutineScope {
        val activity = owner.activity
        if (activity == null || !isCurrent(owner)) return@coroutineScope unavailable()
        val origin = CanonicalOrigin.parse(activity.authority.lease.origin).getOrNull()
            ?: return@coroutineScope unavailable()
        val profile = (activity.authority.selection as? SelectedContextReadResult.Selected)?.name ?: "default"
        val generation = activity.scope.gatewayGeneration
        val context = async { if (isCurrent(owner)) adapter.sessionContextBreakdown(owner.runtimeId, profile, origin, generation) else unavailable() }
        val usage = async { if (isCurrent(owner)) adapter.sessionUsage(owner.runtimeId, profile, origin, generation) else unavailable() }
        val c = context.await()
        val u = usage.await()
        if (!isCurrent(owner)) return@coroutineScope unavailable()
        if (c is GatewayResult.Success && u is GatewayResult.Success)
            GatewayResult.Success(ContextUsageSnapshot(c.value, u.value))
        else unavailable()
    }
    private fun unavailable(): GatewayResult.Failure =
        GatewayResult.Failure(GatewayFailure.NotConnected("Context usage unavailable"))

    override val signals: Flow<ContextUsageSignal> = merge(
        adapter.connectionStatus.map { ContextUsageSignal(ContextUsageSignalKind.INVALIDATED) },
        sessions.activeSession.map { it?.runtimeSessionId }.distinctUntilChanged()
            .map { ContextUsageSignal(ContextUsageSignalKind.INVALIDATED) }
    )

    override suspend fun admitEvent(storedId: String, event: GatewayEvent): ContextUsageSignal? {
        val kind = when (event) {
            is GatewayEvent.SessionUsageUpdated -> ContextUsageSignalKind.USAGE_UPDATED
            is GatewayEvent.StatusUpdate -> when (event.kind) {
                "compacted" -> ContextUsageSignalKind.COMPACTED
                "ready" -> ContextUsageSignalKind.READY
                else -> null
            }
            is GatewayEvent.SessionInfo -> if (event.info["running"] == false) ContextUsageSignalKind.STOPPED else null
            is GatewayEvent.Error -> ContextUsageSignalKind.FAILED
            else -> null
        } ?: return null
        val runtime = event.sessionId?.takeIf { it.isNotBlank() } ?: return null
        val owner = capture(storedId, runtime) ?: return null
        return if (isCurrent(owner)) ContextUsageSignal(kind, runtime, (event as? GatewayEvent.SessionUsageUpdated)?.usage) else null
    }
}
