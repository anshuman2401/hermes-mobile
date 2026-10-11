package com.hermes.bot.feature.bots

import com.hermes.bot.data.bots.BotMemoryCache
import com.hermes.bot.data.bots.BotScope
import com.hermes.bot.data.gateway.BotAvatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.WeakHashMap

/** Shared decoded presentation images. No network, persistence, or platform security seam. */
class BotThumbnailCache<T>(
    memory: BotMemoryCache,
    private val isCurrent: (BotScope) -> Boolean,
    private val sizeOf: (T) -> Int,
    private val maxBytes: Int = 4 * 1024 * 1024,
    private val digestDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AutoCloseable {
    private data class Key(val scope: BotScope, val name: String, val digest: String)
    private val lock = Any()
    private val entries = LinkedHashMap<Key, T>(16, 0.75f, true)
    private val inFlight = mutableMapOf<Key, CompletableDeferred<T?>>()
    private val digests = WeakHashMap<BotAvatar, String>()
    private var bytes = 0
    private var epoch = 0L
    private var allowedNames: Set<String>? = null
    private val unsubscribe = memory.observeClear { clear() }
    private val unsubscribeRoster = memory.observeRoster { names -> synchronized(lock) {
        allowedNames = names
        entries.keys.filter { it.name !in names }.forEach { key -> entries.remove(key)?.let { bytes -= sizeOf(it) } }
    } }

    fun clear() = synchronized(lock) {
        epoch++; entries.clear(); inFlight.clear(); digests.clear(); bytes = 0; allowedNames = null
    }

    suspend fun get(scope: BotScope, name: String, avatar: BotAvatar?, decoder: suspend (ByteArray) -> T?): T? {
        if (avatar == null || !isCurrent(scope)) return null
        val operation = synchronized(lock) { epoch }
        val digest = withContext(digestDispatcher) {
            synchronized(lock) { digests[avatar] } ?: MessageDigest.getInstance("SHA-256")
                .digest(avatar.bytes).joinToString("") { "%02x".format(it) }.also {
                    synchronized(lock) { if (operation == epoch) digests[avatar] = it }
                }
        }
        val key = Key(scope, name, digest)
        var leader = false
        val pending = synchronized(lock) {
            if (operation != epoch || allowedNames?.contains(name) == false) return null
            entries[key]?.let { return it.takeIf { isCurrent(scope) } }
            inFlight[key] ?: CompletableDeferred<T?>().also { inFlight[key] = it; leader = true }
        }
        if (!leader) {
            try { return pending.await().takeIf { isCurrent(scope) && synchronized(lock) { epoch == operation && allowedNames?.contains(name) != false } } }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                // Another presentation may have owned the cancelled decode. This consumer is still visible.
                return if (isCurrent(scope) && synchronized(lock) { epoch == operation }) get(scope, name, avatar, decoder) else null
            }
        }
        try {
            val image = decodeBotAvatar(avatar.bytes, decoder)
            currentCoroutineContext().ensureActive()
            val accepted = synchronized(lock) {
                if (epoch != operation || !isCurrent(scope) || allowedNames?.contains(name) == false) null else image?.also {
                    val size = sizeOf(it)
                    if (size in 1..maxBytes) {
                        while (entries.isNotEmpty() && bytes + size > maxBytes) {
                            val oldest = entries.entries.first()
                            bytes -= sizeOf(oldest.value); entries.remove(oldest.key)
                        }
                        entries[key] = it; bytes += size
                    }
                }
            }
            pending.complete(accepted)
            return accepted
        } catch (cancelled: CancellationException) {
            synchronized(lock) { if (inFlight[key] === pending) inFlight.remove(key) }
            pending.cancel(cancelled)
            throw cancelled
        } catch (_: Exception) {
            pending.complete(null)
            return null
        } finally {
            synchronized(lock) { if (inFlight[key] === pending) inFlight.remove(key) }
        }
    }

    override fun close() { unsubscribe(); unsubscribeRoster(); clear() }
}
