package io.github.anshuman2401.hermesmobile.data.bots

import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ensureActive

data class BotTarget(val localId: String, val normalizedOrigin: String)
data class BotScope(val target: BotTarget, val origin: CanonicalOrigin, val generation: Long)
data class BotRow(val profile: BotProfile, val avatar: BotAvatar? = null)

interface BotRepository {
    val connectionStatus: StateFlow<ConnectionStatus>
    fun capture(target: BotTarget): BotScope?
    fun isCurrent(scope: BotScope): Boolean
    suspend fun favourites(scope: BotScope): io.github.anshuman2401.hermesmobile.data.local.BotFavourites? = null
    suspend fun setFavourite(scope: BotScope, expected: io.github.anshuman2401.hermesmobile.data.local.BotFavourites, name: String, favourite: Boolean): io.github.anshuman2401.hermesmobile.data.local.BotFavourites =
        throw UnsupportedOperationException("Bot favourites unavailable")
    suspend fun load(scope: BotScope, publish: (List<BotRow>) -> Unit)
}

class DefaultBotRepository(internal val configuredGatewayAdapter: GatewayProtocolAdapter, private val vault: io.github.anshuman2401.hermesmobile.data.local.VaultStorage? = null) : BotRepository {
    override val connectionStatus = configuredGatewayAdapter.connectionStatus
    override fun capture(target: BotTarget): BotScope? {
        if (target.localId.isBlank()) return null
        val origin = CanonicalOrigin.parse(target.normalizedOrigin).getOrNull() ?: return null
        val scope = BotScope(target, origin, configuredGatewayAdapter.connectionGeneration)
        return scope.takeIf(::isCurrent)
    }

    override fun isCurrent(scope: BotScope): Boolean =
        configuredGatewayAdapter.isScopedConnectionCurrent(scope.origin, scope.generation)

    override suspend fun favourites(scope: BotScope): io.github.anshuman2401.hermesmobile.data.local.BotFavourites? {
        check(isCurrent(scope)) { "Bot connection changed" }
        val result = vault?.getBotFavourites(scope.target.localId, scope.target.normalizedOrigin)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(isCurrent(scope)) { "Bot connection changed" }
        return result
    }

    override suspend fun setFavourite(scope: BotScope, expected: io.github.anshuman2401.hermesmobile.data.local.BotFavourites, name: String, favourite: Boolean): io.github.anshuman2401.hermesmobile.data.local.BotFavourites {
        check(isCurrent(scope) && expected.profileId == scope.target.localId && expected.origin == scope.target.normalizedOrigin) { "Bot connection changed" }
        val result = vault?.setBotFavourite(expected, name, favourite) ?: error("Bot favourites unavailable")
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(isCurrent(scope)) { "Bot connection changed" }
        return result
    }

    override suspend fun load(scope: BotScope, publish: (List<BotRow>) -> Unit) {
        fun checkCurrent() {
            check(isCurrent(scope)) { "Bot connection changed" }
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        checkCurrent()
        val result = configuredGatewayAdapter.listBotProfiles(scope.origin, scope.generation)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        checkCurrent()
        val profiles = (result as? GatewayResult.Success)?.value ?: error("Bot roster unavailable")
        var rows = profiles.map { BotRow(it) }
        publish(rows)
        for ((index, profile) in profiles.withIndex()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            checkCurrent()
            if (!profile.hasAvatar) continue
            val avatar = try {
                configuredGatewayAdapter.getBotAvatar(profile.name, scope.origin, scope.generation)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            checkCurrent()
            if (avatar != null) {
                rows = rows.mapIndexed { rowIndex, row -> if (rowIndex == index) row.copy(avatar = avatar) else row }
                publish(rows)
            }
        }
    }
}
