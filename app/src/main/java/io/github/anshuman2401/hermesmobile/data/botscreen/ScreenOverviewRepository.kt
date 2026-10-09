package io.github.anshuman2401.hermesmobile.data.botscreen

import io.github.anshuman2401.hermesmobile.data.bots.BotRepository
import io.github.anshuman2401.hermesmobile.data.bots.BotScope
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult

/** A scoped status/stop seam for the Bots page; it never opens a viewer or holds a lease. */
interface ScreenOverviewRepository {
    suspend fun status(scope: BotScope, profile: String): ScreenStatus?
    suspend fun stop(scope: BotScope, profile: String): ScreenStatus?
}

class DefaultScreenOverviewRepository(
    private val bots: BotRepository,
    private val gateway: GatewayProtocolAdapter
) : ScreenOverviewRepository {
    override suspend fun status(scope: BotScope, profile: String): ScreenStatus? {
        if (!bots.isCurrent(scope)) return null
        val result = gateway.screenStatus(profile, scope.origin, scope.generation)
        return if (bots.isCurrent(scope)) (result as? GatewayResult.Success)?.value else null
    }

    override suspend fun stop(scope: BotScope, profile: String): ScreenStatus? {
        if (!bots.isCurrent(scope)) return null
        val result = gateway.screenStop(profile, scope.origin, scope.generation)
        return if (bots.isCurrent(scope)) (result as? GatewayResult.Success)?.value else null
    }
}
