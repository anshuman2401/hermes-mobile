package com.hermes.bot.data.update

import com.hermes.bot.data.insights.HostInsightsRepository
import com.hermes.bot.data.insights.InsightsRead
import com.hermes.bot.data.insights.InsightsScope
import com.hermes.bot.data.insights.InsightsFailure
import kotlinx.coroutines.CancellationException

interface HostUpdateRepository {
    suspend fun capture(): HostUpdateResult<InsightsScope>
    suspend fun isCurrent(scope: InsightsScope): Boolean
    suspend fun check(scope: InsightsScope): HostUpdateResult<HostUpdateCheck>
    suspend fun start(scope: InsightsScope): HostUpdateResult<HostUpdateStart>
    suspend fun status(scope: InsightsScope): HostUpdateResult<HostUpdateStatus>
}

/** Reuses the selected connection/profile/auth/gateway owner from host insights. */
class DefaultHostUpdateRepository(
    private val owners: HostInsightsRepository,
    private val adapter: HostUpdateAdapter
) : HostUpdateRepository {
    override suspend fun capture(): HostUpdateResult<InsightsScope> = when (val captured = owners.capture()) {
        is InsightsRead.Ready -> HostUpdateResult.Ready(captured.value)
        is InsightsRead.Failed -> HostUpdateResult.Failed(captured.reason.updateFailure())
    }

    override suspend fun isCurrent(scope: InsightsScope): Boolean = owners.isCurrent(scope)

    override suspend fun check(scope: InsightsScope): HostUpdateResult<HostUpdateCheck> = guarded(scope) { adapter.check(scope) }

    override suspend fun status(scope: InsightsScope): HostUpdateResult<HostUpdateStatus> = guarded(scope) { adapter.status(scope) }

    override suspend fun start(scope: InsightsScope): HostUpdateResult<HostUpdateStart> {
        if (!isCurrent(scope)) return HostUpdateResult.Failed(HostUpdateFailure.STALE)
        // Refresh eligibility immediately before the one-shot write. A missing or stale result never enables it.
        val eligibility = check(scope)
        if (eligibility !is HostUpdateResult.Ready) return eligibility as HostUpdateResult.Failed
        if (!eligibility.value.appUpdateEligible) return HostUpdateResult.Failed(HostUpdateFailure.NOT_ELIGIBLE)
        if (!isCurrent(scope)) return HostUpdateResult.Failed(HostUpdateFailure.STALE)
        // After this boundary any failure may mean the host accepted the write. Never retry automatically.
        return adapter.start(scope)
    }

    private suspend fun <T> guarded(scope: InsightsScope, read: suspend () -> HostUpdateResult<T>): HostUpdateResult<T> {
        return try {
            if (!isCurrent(scope)) return HostUpdateResult.Failed(HostUpdateFailure.STALE)
            val result = read()
            if (result is HostUpdateResult.Failed && result.reason == HostUpdateFailure.AUTH) return result
            if (!isCurrent(scope)) HostUpdateResult.Failed(HostUpdateFailure.STALE) else result
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { HostUpdateResult.Failed(HostUpdateFailure.UNAVAILABLE) }
    }
}

private fun InsightsFailure.updateFailure(): HostUpdateFailure = when (this) {
    InsightsFailure.AUTH -> HostUpdateFailure.AUTH
    InsightsFailure.STALE -> HostUpdateFailure.STALE
    InsightsFailure.ORIGIN -> HostUpdateFailure.ORIGIN
    InsightsFailure.UNSUPPORTED -> HostUpdateFailure.UNSUPPORTED
    InsightsFailure.MALFORMED -> HostUpdateFailure.MALFORMED
    InsightsFailure.UNAVAILABLE -> HostUpdateFailure.UNAVAILABLE
}
