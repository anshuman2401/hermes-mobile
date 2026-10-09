package com.hermes.bot.data.botscreen

import com.hermes.bot.data.bots.BotScope
import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

/** Keeps display tickets and viewer capabilities below the repository boundary. */
internal class BotScreenProtocolAdapter(
    private val gateway: GatewayProtocolAdapter,
    private val stream: ScreenStream
) {
    val changes: Flow<Unit> = gateway.events.filter {
        it is GatewayEvent.Unknown && (it.type == "display.lease" || it.type == "display.status")
    }.map { Unit }

    private var viewerId: String? = null
    private var viewerHash: String? = null

    suspend fun status(scope: BotScope, profile: String): GatewayResult<ScreenStatus> =
        gateway.screenStatus(profile, scope.origin, scope.generation)

    suspend fun start(scope: BotScope, profile: String): GatewayResult<ScreenStatus> =
        gateway.screenStart(profile, scope.origin, scope.generation)

    suspend fun connect(scope: BotScope, profile: String, onFrame: (ScreenFrame) -> Unit,
                        onClosed: () -> Unit): GatewayResult<ScreenStatus> {
        val result = gateway.screenObserve(profile, scope.origin, scope.generation, viewerId)
        if (result is GatewayResult.Failure) return result
        val observed = (result as GatewayResult.Success).value
        viewerId = observed.viewerId
        viewerHash = MessageDigest.getInstance("SHA-256").digest(observed.viewerId.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }
        try {
            stream.open(scope.origin, observed.ticket, onFrame, onClosed)
        } catch (_: Exception) {
            stream.close()
            return GatewayResult.Failure(GatewayFailure.Transport("Could not open Bot Screen stream"))
        }
        return GatewayResult.Success(observed.status)
    }

    fun owns(lease: ScreenLease): Boolean =
        lease.holder == ScreenLease.Holder.HUMAN && viewerHash != null && lease.viewerHash == viewerHash

    suspend fun takeOver(scope: BotScope, profile: String): GatewayResult<ScreenLease>? =
        viewerId?.let { gateway.screenAcquire(profile, it, scope.origin, scope.generation) }

    suspend fun handBack(scope: BotScope, profile: String): GatewayResult<ScreenLease>? =
        viewerId?.let { gateway.screenRelease(profile, it, scope.origin, scope.generation) }

    suspend fun releaseIfHeld(scope: BotScope, profile: String) {
        val id = viewerId ?: return
        val hash = viewerHash ?: return
        val status = gateway.screenStatus(profile, scope.origin, scope.generation)
        if (status is GatewayResult.Success && status.value.lease.holder == ScreenLease.Holder.HUMAN &&
            status.value.lease.viewerHash == hash) {
            gateway.screenRelease(profile, id, scope.origin, scope.generation)
        }
    }

    fun setInputEnabled(enabled: Boolean) = stream.setInputEnabled(enabled)
    fun pointer(x: Int, y: Int, buttons: Int) = stream.pointer(x, y, buttons)
    fun key(keysym: Int, down: Boolean) = stream.key(keysym, down)
    fun close() = stream.close()
    fun forgetViewer() { viewerId = null; viewerHash = null }
}
