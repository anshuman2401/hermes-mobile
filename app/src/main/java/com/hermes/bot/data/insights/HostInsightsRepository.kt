package com.hermes.bot.data.insights

import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.context.ContextProfileErrorReason
import com.hermes.bot.data.context.ContextProfileRepository
import com.hermes.bot.data.context.ContextProfilesResult
import com.hermes.bot.data.context.RosterSource
import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.local.AuthCredentialLease
import com.hermes.bot.data.local.SelectedContextReadResult
import com.hermes.bot.data.local.VaultStorage
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.CancellationException

class InsightsScope internal constructor(
    val profile: String,
    internal val localId: String,
    internal val origin: CanonicalOrigin,
    internal var receipt: AuthCredentialLease,
    internal val generation: Long,
    internal val requiresSelectedContext: Boolean = true
) { override fun toString() = "InsightsScope([private])" }
class AnalyticsRoster internal constructor(val profiles: List<String>, internal val owner: InsightsScope) {
    override fun toString() = "AnalyticsRoster([private])"
}
interface HostInsightsRepository {
    suspend fun capture(): InsightsRead<InsightsScope>
    suspend fun isCurrent(scope: InsightsScope): Boolean
    suspend fun health(scope: InsightsScope): InsightsRead<HostHealth>
    suspend fun usage(scope: InsightsScope, days: Int): InsightsRead<HostUsage>
    suspend fun analyticsRoster(): InsightsRead<AnalyticsRoster> = InsightsRead.Failed(InsightsFailure.UNAVAILABLE)
    suspend fun analyticsRosterCurrent(roster: AnalyticsRoster): Boolean = false
    suspend fun analyticsUsage(roster: AnalyticsRoster, profile: String, days: Int): InsightsRead<HostUsage> =
        InsightsRead.Failed(InsightsFailure.UNAVAILABLE)
    suspend fun logs(scope: InsightsScope, file: HostLogFile, level: HostLogLevel): InsightsRead<HostLogTail>
}
class DefaultHostInsightsRepository(
    private val vault: VaultStorage,
    private val auth: AuthRepository,
    private val gateway: GatewayProtocolAdapter,
    private val adapter: HostInsightsAdapter = HostInsightsAdapter(auth),
    private val profiles: ContextProfileRepository? = null
) : HostInsightsRepository {
    private fun validAnalyticsProfile(name: String): Boolean =
        name.matches(Regex("[A-Za-z0-9._-]{1,80}")) && name !in setOf("current", "hermes", "test", "tmp", "root", "sudo")

    override suspend fun capture(): InsightsRead<InsightsScope> = try {
        val profile = (vault.readSelectedContextProfileAuthoritative() as? SelectedContextReadResult.Selected)?.name
        val localId = vault.getActiveProfileId()
        val connection = localId?.let { vault.getConnectionProfile(it) }
        val origin = connection?.let { CanonicalOrigin.parse(it.normalizedOrigin).getOrNull() }
        if (profile == null || !profile.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}")) || profile in setOf("current", "hermes", "test", "tmp", "root", "sudo") || localId == null || origin == null) {
            InsightsRead.Failed(InsightsFailure.UNAVAILABLE)
        } else {
            val lease = vault.readAuthCredentialLease(origin.originUrl)
            if (lease == null) InsightsRead.Failed(InsightsFailure.AUTH)
            else {
                val scope = InsightsScope(profile, localId, origin, lease, gateway.connectionGeneration)
                if (isCurrent(scope)) InsightsRead.Ready(scope) else InsightsRead.Failed(InsightsFailure.STALE)
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { InsightsRead.Failed(InsightsFailure.UNAVAILABLE) }

    override suspend fun isCurrent(scope: InsightsScope): Boolean = ownerCurrent(scope) && auth.isResponseCurrent(scope.receipt)

    private suspend fun ownerCurrent(scope: InsightsScope): Boolean = try {
        (!scope.requiresSelectedContext || (vault.readSelectedContextProfileAuthoritative() as? SelectedContextReadResult.Selected)?.name == scope.profile) &&
            vault.getActiveProfileId() == scope.localId &&
            vault.getConnectionProfile(scope.localId)?.normalizedOrigin == scope.origin.originUrl &&
            gateway.isScopedConnectionCurrent(scope.origin, scope.generation)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { false }

    private suspend fun <T> guarded(scope: InsightsScope, read: suspend () -> InsightsRead<T>): InsightsRead<T> {
        if (!isCurrent(scope)) return InsightsRead.Failed(InsightsFailure.STALE)
        val result = read()
        if (result is InsightsRead.Failed && result.reason == InsightsFailure.AUTH && ownerCurrent(scope)) return result
        return if (isCurrent(scope)) result else InsightsRead.Failed(InsightsFailure.STALE)
    }
    override suspend fun health(scope: InsightsScope): InsightsRead<HostHealth> = guarded(scope) {
        val status = adapter.status(scope)
        if (status is InsightsRead.Failed && status.reason == InsightsFailure.AUTH) return@guarded status
        if (!isCurrent(scope)) return@guarded InsightsRead.Failed(InsightsFailure.STALE)
        val system = adapter.system(scope)
        if (system is InsightsRead.Failed && system.reason == InsightsFailure.AUTH) return@guarded system
        InsightsRead.Ready(HostHealth(status, system))
    }
    override suspend fun usage(scope: InsightsScope, days: Int): InsightsRead<HostUsage> = guarded(scope) {
        require(days in listOf(7, 30, 90))
        adapter.usage(scope, days)
    }
    private suspend fun captureAnalytics(profile: String): InsightsRead<InsightsScope> {
        return try {
            if (!validAnalyticsProfile(profile)) return InsightsRead.Failed(InsightsFailure.MALFORMED)
            val localId = vault.getActiveProfileId() ?: return InsightsRead.Failed(InsightsFailure.UNAVAILABLE)
            val connection = vault.getConnectionProfile(localId) ?: return InsightsRead.Failed(InsightsFailure.UNAVAILABLE)
            val origin = CanonicalOrigin.parse(connection.normalizedOrigin).getOrNull()
                ?: return InsightsRead.Failed(InsightsFailure.UNAVAILABLE)
            val lease = vault.readAuthCredentialLease(origin.originUrl) ?: return InsightsRead.Failed(InsightsFailure.AUTH)
            val scope = InsightsScope(profile, localId, origin, lease, gateway.connectionGeneration, requiresSelectedContext = false)
            if (isCurrent(scope)) InsightsRead.Ready(scope) else InsightsRead.Failed(InsightsFailure.STALE)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { InsightsRead.Failed(InsightsFailure.UNAVAILABLE) }
    }

    override suspend fun analyticsRoster(): InsightsRead<AnalyticsRoster> {
        val source = profiles ?: return InsightsRead.Failed(InsightsFailure.UNAVAILABLE)
        val owner = when (val captured = captureAnalytics("default")) {
            is InsightsRead.Failed -> return captured
            is InsightsRead.Ready -> captured.value
        }
        val result = try { source.getProfiles() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return InsightsRead.Failed(InsightsFailure.UNAVAILABLE) }
        if (!ownerCurrent(owner)) return InsightsRead.Failed(InsightsFailure.STALE)
        return when (result) {
            is ContextProfilesResult.Error -> InsightsRead.Failed(
                if (result.reason == ContextProfileErrorReason.UNAUTHORIZED) InsightsFailure.AUTH else InsightsFailure.UNAVAILABLE)
            is ContextProfilesResult.Success -> if (result.source != RosterSource.LIVE) InsightsRead.Failed(InsightsFailure.UNAVAILABLE)
                else InsightsRead.Ready(AnalyticsRoster(result.profiles.map { it.name }
                    .filter(::validAnalyticsProfile).distinct(), owner))
        }
    }

    override suspend fun analyticsRosterCurrent(roster: AnalyticsRoster): Boolean = ownerCurrent(roster.owner)

    override suspend fun analyticsUsage(roster: AnalyticsRoster, profile: String, days: Int): InsightsRead<HostUsage> {
        if (profile !in roster.profiles || !analyticsRosterCurrent(roster)) return InsightsRead.Failed(InsightsFailure.STALE)
        return when (val captured = captureAnalytics(profile)) {
            is InsightsRead.Failed -> captured
            is InsightsRead.Ready -> {
                val scope = captured.value
                if (scope.localId != roster.owner.localId || scope.origin != roster.owner.origin || scope.generation != roster.owner.generation)
                    InsightsRead.Failed(InsightsFailure.STALE)
                else usage(scope, days)
            }
        }
    }
    override suspend fun logs(scope: InsightsScope, file: HostLogFile, level: HostLogLevel): InsightsRead<HostLogTail> = guarded(scope) {
        adapter.logs(scope, file, level)
    }
}
