package com.hermes.bot.data.bots

import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class BotTarget(val localId: String, val normalizedOrigin: String)
data class BotScope(val target: BotTarget, val origin: CanonicalOrigin, val generation: Long, val cacheEpoch: Long = 0)
data class BotRow(val profile: BotProfile, val avatar: BotAvatar? = null)

/** Host metadata retained only for the current connected scope; never session history. */
class BotRosterSnapshot(val rows: List<BotRow>, val capturedAtMillis: Long, val revision: Long) {
    override fun toString() = "BotRosterSnapshot([REDACTED])"
}

private val noInvalidation = MutableStateFlow(0L)
interface BotRepository {
    val connectionStatus: StateFlow<ConnectionStatus>
    val invalidation: StateFlow<Long> get() = noInvalidation
    fun capture(target: BotTarget): BotScope?
    fun isCurrent(scope: BotScope): Boolean
    fun cached(scope: BotScope): BotRosterSnapshot? = null
    fun isFresh(scope: BotScope): Boolean = true
    fun clearCache() {}
    suspend fun favourites(scope: BotScope): com.hermes.bot.data.local.BotFavourites? = null
    suspend fun setFavourite(scope: BotScope, expected: com.hermes.bot.data.local.BotFavourites, name: String, favourite: Boolean): com.hermes.bot.data.local.BotFavourites =
        throw UnsupportedOperationException("Bot favourites unavailable")
    suspend fun load(scope: BotScope, publish: (List<BotRow>) -> Unit)
    suspend fun refresh(scope: BotScope, publish: (List<BotRow>) -> Unit) = load(scope, publish)
    suspend fun avatars(scope: BotScope, demand: Flow<List<String>>, publish: (List<BotRow>) -> Unit) {}
}

class DefaultBotRepository(
    internal val configuredGatewayAdapter: GatewayProtocolAdapter,
    private val vault: com.hermes.bot.data.local.VaultStorage? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    val memory: BotMemoryCache = BotMemoryCache()
) : BotRepository {
    override val connectionStatus = configuredGatewayAdapter.connectionStatus
    override val invalidation = memory.invalidation
    private val rosterMutex = Mutex()
    private val avatarPermits = Semaphore(3)
    private val avatarLock = Any()
    private val avatarReads = mutableMapOf<Triple<BotScope, Long, String>, Mutex>()
    init { memory.observeClear { synchronized(avatarLock) { avatarReads.clear() } } }
    override fun clearCache() = memory.clear()
    override fun capture(target: BotTarget): BotScope? {
        if (target.localId.isBlank()) return null
        val origin = CanonicalOrigin.parse(target.normalizedOrigin).getOrNull() ?: return null
        val generation = configuredGatewayAdapter.connectionGeneration
        if (!configuredGatewayAdapter.isScopedConnectionCurrent(origin, generation)) return null
        if (!memory.bind(target, generation)) return null
        return BotScope(target, origin, generation, memory.invalidation.value).takeIf(::isCurrent)
    }

    override fun isCurrent(scope: BotScope): Boolean = memory.owns(scope) && memory.connectionAllowed(scope.generation) &&
        configuredGatewayAdapter.isScopedConnectionCurrent(scope.origin, scope.generation)

    override fun cached(scope: BotScope): BotRosterSnapshot? = memory.read(scope, ::isCurrent)
    override fun isFresh(scope: BotScope): Boolean = cached(scope)?.let {
        val age = clock() - it.capturedAtMillis
        age in 0 until FRESH_MILLIS
    } == true

    override suspend fun favourites(scope: BotScope): com.hermes.bot.data.local.BotFavourites? {
        check(isCurrent(scope)) { "Bot connection changed" }
        val result = vault?.getBotFavourites(scope.target.localId, scope.target.normalizedOrigin)
        currentCoroutineContext().ensureActive()
        check(isCurrent(scope)) { "Bot connection changed" }
        return result
    }

    override suspend fun setFavourite(scope: BotScope, expected: com.hermes.bot.data.local.BotFavourites, name: String, favourite: Boolean): com.hermes.bot.data.local.BotFavourites {
        check(isCurrent(scope) && expected.profileId == scope.target.localId && expected.origin == scope.target.normalizedOrigin) { "Bot connection changed" }
        val result = vault?.setBotFavourite(expected, name, favourite) ?: error("Bot favourites unavailable")
        currentCoroutineContext().ensureActive()
        check(isCurrent(scope)) { "Bot connection changed" }
        return result
    }

    override suspend fun load(scope: BotScope, publish: (List<BotRow>) -> Unit) = loadRoster(scope, false, publish)
    override suspend fun refresh(scope: BotScope, publish: (List<BotRow>) -> Unit) = loadRoster(scope, true, publish)

    private suspend fun loadRoster(scope: BotScope, force: Boolean, publish: (List<BotRow>) -> Unit) {
        val before = cached(scope)?.revision
        rosterMutex.withLock {
            currentCoroutineContext().ensureActive()
            check(isCurrent(scope)) { "Bot connection changed" }
            val existing = cached(scope)
            if (existing != null && ((!force && isFresh(scope)) || existing.revision != before)) {
                publish(existing.rows)
                return
            }
            val result = configuredGatewayAdapter.listBotProfiles(scope.origin, scope.generation)
            currentCoroutineContext().ensureActive()
            check(isCurrent(scope)) { "Bot connection changed" }
            val profiles = (result as? GatewayResult.Success)?.value ?: error("Bot roster unavailable")
            synchronized(avatarLock) { avatarReads.clear() }
            check(memory.rememberRoster(scope, profiles, clock(), ::isCurrent)) { "Bot connection changed" }
            publish(cached(scope)?.rows ?: error("Bot connection changed"))
        }
    }

    /** A single actor owns the demand queue; cancellation never becomes a negative cache entry. */
    override suspend fun avatars(scope: BotScope, demand: Flow<List<String>>, publish: (List<BotRow>) -> Unit): Unit = coroutineScope {
        val revision = cached(scope)?.revision ?: return@coroutineScope
        val events = Channel<AvatarEvent>(Channel.UNLIMITED)
        val running = mutableMapOf<String, Job>()
        val attempted = mutableSetOf<String>()
        var wanted = emptyList<String>()
        val collector = launch { demand.collect { events.send(AvatarEvent.Demand(it.distinct())) } }
        try {
            for (event in events) {
                ensureActive()
                if (!isCurrent(scope) || cached(scope)?.revision != revision) return@coroutineScope
                when (event) {
                    is AvatarEvent.Demand -> {
                        val allowed = cached(scope)?.rows.orEmpty().filter { it.profile.hasAvatar }.map { it.profile.name }.toSet()
                        wanted = event.names.filter { it in allowed }
                        cached(scope)?.let { publish(it.rows) }
                        (running.keys - wanted.toSet()).forEach { name -> running.remove(name)?.cancel() }
                        attempted.retainAll(wanted.toSet())
                    }
                    is AvatarEvent.Done -> {
                        if (running[event.name] !== event.job) continue
                        running.remove(event.name)
                        cached(scope)?.let { snapshot ->
                            publish(snapshot.rows.map { row ->
                                if (row.profile.name == event.name && row.profile.hasAvatar && event.avatar != null)
                                    row.copy(avatar = event.avatar) else row
                            })
                        }
                    }
                }
                for (name in wanted) {
                    if (running.size >= 3) break
                    if (!attempted.add(name)) continue
                    val job = launch(start = CoroutineStart.LAZY) {
                        val avatar = readAvatar(scope, revision, name)
                        events.send(AvatarEvent.Done(name, currentCoroutineContext().job, avatar))
                    }
                    running[name] = job
                    job.start()
                }
            }
        } finally {
            collector.cancel()
            running.values.forEach { it.cancel() }
            events.close()
        }
    }

    private suspend fun readAvatar(scope: BotScope, revision: Long, name: String): BotAvatar? {
        fun retained() = cached(scope)?.rows?.firstOrNull { it.profile.name == name }?.avatar
        if (memory.wasAttempted(scope, revision, name)) return retained()
        val readLock = synchronized(avatarLock) { avatarReads.getOrPut(Triple(scope, revision, name)) { Mutex() } }
        return readLock.withLock {
            avatarPermits.withPermit {
                check(isCurrent(scope)) { "Bot connection changed" }
                if (cached(scope)?.revision != revision) return@withPermit null
                if (memory.wasAttempted(scope, revision, name)) return@withPermit retained()
                val avatar = try { configuredGatewayAdapter.getBotAvatar(name, scope.origin, scope.generation) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                currentCoroutineContext().ensureActive()
                if (memory.rememberAvatar(scope, revision, name, avatar, ::isCurrent)) avatar else null
            }
        }
    }

    private sealed interface AvatarEvent {
        class Demand(val names: List<String>) : AvatarEvent
        class Done(val name: String, val job: Job, val avatar: BotAvatar?) : AvatarEvent
    }

    companion object { const val FRESH_MILLIS = 60_000L }
}
