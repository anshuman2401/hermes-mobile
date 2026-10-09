package com.hermes.bot.data.context

import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.gateway.GatewayRedactor
import com.hermes.bot.data.history.StoredHistoryBudget
import com.hermes.bot.data.history.StoredHistoryProtocolAdapter
import com.hermes.bot.data.history.StoredHistoryScope
import com.hermes.bot.data.local.CacheAccessPartitionResult
import com.hermes.bot.data.local.VaultStorage
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Memory-only, already-redacted presentation text. */
class BotThreadReply(val text: String) {
    override fun toString() = "BotThreadReply([REDACTED])"
}

fun interface BotThreadPreviewRepository {
    suspend fun getReply(profile: String, durableId: String): BotThreadReply?
}

/** Independent read scopes never claim the session repository's active chat ownership. */
class DefaultBotThreadPreviewRepository(
    private val vault: VaultStorage,
    private val auth: AuthRepository,
    private val adapter: StoredHistoryProtocolAdapter
) : BotThreadPreviewRepository {
    override suspend fun getReply(profile: String, durableId: String): BotThreadReply? {
        var scope: StoredHistoryScope? = null
        try {
            if (!profile.matches(Regex("[A-Za-z0-9._-]{1,80}")) ||
                !durableId.matches(Regex("[A-Za-z0-9._:-]{1,256}")) || durableId.contains("..") || durableId == ".") return null
            if (vault.getSelectedContextProfile() != profile) return null
            val partition = (vault.activeAccessPartition() as? CacheAccessPartitionResult.Available)?.partition ?: return null
            val credential = vault.getAuthCredential(partition.profileLocalId) ?: return null
            val origin = CanonicalOrigin.parse(credential.canonicalOrigin).getOrNull() ?: return null
            if (origin.pathPrefix.isNotEmpty()) return null
            val lease = vault.readAuthCredentialLease(origin.originUrl) ?: return null
            if (lease.profileLocalId != partition.profileLocalId || lease.credential != credential) return null
            val captured = StoredHistoryScope(partition.profileLocalId, origin, profile, durableId, 0, 0, lease, profile)
            scope = captured
            val budget = StoredHistoryBudget(captured).apply { acceptedLimit = 30 }
            val page = adapter.fetchPage(captured, budget).getOrNull() ?: return null
            currentCoroutineContext().ensureActive()
            if (!captured.current || page.scope !== captured || page.requestedSessionId != durableId ||
                page.resolvedSessionId != durableId || page.rows.size > 30 ||
                vault.getSelectedContextProfile() != profile ||
                vault.activeAccessPartition() != CacheAccessPartitionResult.Available(partition.copy(cacheEpoch = budget.receipt.cacheEpoch)) ||
                !auth.isResponseCurrent(budget.receipt)) return null
            // Only this exchange may advance the captured epoch through an accepted cookie successor.
            // The partition check may suspend; confirm ownership again before returning content.
            if (vault.getSelectedContextProfile() != profile || !auth.isResponseCurrent(budget.receipt)) return null
            val text = page.rows.asReversed().firstNotNullOfOrNull { row ->
                if (row.role != "assistant") null else row.fields["content"]?.asStringOrNull()?.trim()?.takeIf { it.isNotBlank() }
            } ?: return null
            return BotThreadReply(GatewayRedactor.redactString(text).take(280))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        finally { scope?.invalidate() }
    }
}
