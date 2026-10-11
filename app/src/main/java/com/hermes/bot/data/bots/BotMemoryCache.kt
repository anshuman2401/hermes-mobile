package com.hermes.bot.data.bots

import com.hermes.bot.data.gateway.BotAvatar
import com.hermes.bot.data.gateway.BotProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-lifetime metadata and bounded encoded images, with a synchronous clear barrier. */
class BotMemoryCache(private val maxAvatarBytes: Int = 8 * 1024 * 1024) {
    private val lock = Any()
    private val epoch = MutableStateFlow(0L)
    val invalidation = epoch.asStateFlow()
    @Volatile private var owner: Pair<BotTarget, Long>? = null
    @Volatile private var blockedGeneration = -1L
    private var profiles: List<BotProfile>? = null
    private var fetchedAt = 0L
    private var revision = 0L
    private val avatars = LinkedHashMap<String, BotAvatar>(16, 0.75f, true)
    private val attempts = mutableMapOf<String, Boolean>()
    private var bytes = 0
    private val observers = mutableSetOf<() -> Unit>()
    private val rosterObservers = mutableSetOf<(Set<String>) -> Unit>()

    fun observeClear(observer: () -> Unit): () -> Unit = synchronized(lock) {
        observers.add(observer)
        return@synchronized { synchronized(lock) { observers.remove(observer); Unit } }
    }

    fun observeRoster(observer: (Set<String>) -> Unit): () -> Unit = synchronized(lock) {
        rosterObservers.add(observer)
        profiles?.let { observer(it.filter { profile -> profile.hasAvatar }.map { profile -> profile.name }.toSet()) }
        return@synchronized { synchronized(lock) { rosterObservers.remove(observer); Unit } }
    }

    fun clear() = synchronized(lock) { clearLocked() }
    private fun clearLocked() {
        owner = null; profiles = null; avatars.clear(); attempts.clear(); bytes = 0
        epoch.value++
        observers.toList().forEach { it() }
    }

    fun disconnect(generation: Long) = synchronized(lock) {
        blockedGeneration = maxOf(blockedGeneration, generation)
        clearLocked()
    }

    fun connectionAllowed(generation: Long): Boolean = generation > blockedGeneration

    fun bind(target: BotTarget, generation: Long): Boolean = synchronized(lock) {
        if (!connectionAllowed(generation)) return@synchronized false
        val next = target to generation
        if (owner != null && owner != next) clearLocked()
        owner = next
        true
    }

    fun owns(scope: BotScope): Boolean = scope.cacheEpoch == epoch.value && owner == (scope.target to scope.generation)

    fun read(scope: BotScope, current: (BotScope) -> Boolean): BotRosterSnapshot? = synchronized(lock) {
        if (!owns(scope) || !current(scope)) return@synchronized null
        val images = avatars.toMap() // Snapshot reads must not turn server ordering into eviction priority.
        profiles?.let { BotRosterSnapshot(it.map { profile -> BotRow(profile, images[profile.name]) }, fetchedAt, revision) }
    }

    fun rememberRoster(scope: BotScope, incoming: List<BotProfile>, at: Long, current: (BotScope) -> Boolean): Boolean = synchronized(lock) {
        if (!owns(scope) || !current(scope)) return@synchronized false
        profiles = incoming.toList(); fetchedAt = at; revision++; attempts.clear()
        val names = incoming.filter { it.hasAvatar }.map { it.name }.toSet()
        avatars.keys.toList().filter { it !in names }.forEach { avatars.remove(it)?.let { image -> bytes -= image.bytes.size } }
        rosterObservers.toList().forEach { it(names) }
        true
    }

    fun wasAttempted(scope: BotScope, expectedRevision: Long, name: String): Boolean = synchronized(lock) {
        owns(scope) && revision == expectedRevision &&
            (attempts[name] == false || (attempts[name] == true && avatars[name] != null))
    }

    fun rememberAvatar(scope: BotScope, expectedRevision: Long, name: String, avatar: BotAvatar?, current: (BotScope) -> Boolean): Boolean = synchronized(lock) {
        if (!owns(scope) || !current(scope) || revision != expectedRevision || profiles?.any { it.name == name && it.hasAvatar } != true)
            return@synchronized false
        attempts[name] = avatar != null && avatar.bytes.size <= maxAvatarBytes
        if (avatar != null) {
            avatars.remove(name)?.let { bytes -= it.bytes.size }
            if (avatar.bytes.size <= maxAvatarBytes) {
                while (avatars.isNotEmpty() && bytes + avatar.bytes.size > maxAvatarBytes) {
                    val oldest = avatars.entries.first()
                    bytes -= oldest.value.bytes.size; avatars.remove(oldest.key)
                }
                avatars[name] = avatar; bytes += avatar.bytes.size
            }
        }
        true
    }
}
