package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.local.ConversationCacheReadOwner
import com.hermes.bot.data.local.ConversationCacheV2
import com.hermes.bot.data.local.VaultStorage

/** Display-only host evidence. Never persisted, attached to a runtime, or used for replay. */
class ChatPreviewSnapshot internal constructor(
    internal val owner: ConversationCacheReadOwner,
    val timeline: List<TimelineEntry>,
    val capturedAtMillis: Long,
    val title: String?,
    val messagesOmitted: Boolean
) {
    override fun toString() = "ChatPreviewSnapshot([redacted])"
}

/** Application-lifetime bounded memory. Lock order is vault authority gate -> memory lock. */
class ChatPreviewRepository(
    private val vault: VaultStorage,
    private val clock: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private data class Key(val partition: com.hermes.bot.data.local.CacheAccessPartition, val session: String)
    private class Entry(val snapshot: ChatPreviewSnapshot, val bytes: Int)
    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry>(4, 0.75f, true)
    private var bytes = 0
    private var closed = false
    private val stopObserving = vault.observeConversationPreviewInvalidation {
        synchronized(lock) { entries.clear(); bytes = 0 }
    }

    /** Publication and authority invalidation have one linearization point; no storage reads. */
    fun publishIfPresent(session: String, publish: (ChatPreviewSnapshot) -> Unit): Boolean {
        // Never hold the memory lock while acquiring the authority gate.
        val candidate = synchronized(lock) {
            if (closed) null else entries.entries.lastOrNull { it.key.session == session }?.value
        }
            ?: return false
        var published = false
        vault.withConversationPreviewOwner(candidate.snapshot.owner) {
            synchronized(lock) {
                val key = Key(candidate.snapshot.owner.partition, session)
                val entry = entries[key]
                if (entry !== candidate) return@synchronized
                val age = clock() - entry.snapshot.capturedAtMillis
                if (age < 0 || age > ConversationCacheV2.MAX_TTL_MILLIS) {
                    entries.remove(key); bytes -= entry.bytes
                } else {
                    publish(entry.snapshot)
                    published = true
                }
            }
        }
        return published
    }

    fun withOwner(owner: ConversationCacheReadOwner, publish: () -> Unit): Boolean =
        vault.withConversationPreviewOwner(owner, publish)

    fun observeInvalidation(observer: () -> Unit): () -> Unit = vault.observeConversationPreviewInvalidation(observer)

    /** Caller supplies only an accepted host projection or a validated encrypted snapshot. */
    fun remember(owner: ConversationCacheReadOwner, session: String, timeline: List<TimelineEntry>,
        capturedAtMillis: Long, title: String?, messagesOmitted: Boolean): Boolean {
        val safe = timeline.mapNotNull { entry -> when (entry) {
            is UserMessage -> entry.takeIf { it.state == UserMessageState.SENT &&
                it.sourceKind in setOf(ActiveSourceKind.STORED, ActiveSourceKind.COMPACT) }?.copy(editable = null)
            is AssistantMessage -> entry.takeIf { it.state == AssistantMessageState.COMPLETE &&
                it.sourceKind in setOf(ActiveSourceKind.STORED, ActiveSourceKind.COMPACT) }
                ?.copy(errorMessage = null, usage = null)
            is ToolActivity -> entry.takeIf { it.status == ToolStatus.COMPLETED }?.copy(
                toolId = null, context = null, argsSummary = null, resultSummary = null,
                isExpanded = false, storedSource = null, delegatedTasks = emptyList(), files = emptyList())
            // Only history projections are offered. Omit unclassified/live-only rows and payloads.
            else -> null
        } }
        if (session.isBlank() || safe.isEmpty()) return false
        val payload = GatewayJson.serialize(GatewayJson.fromAny(mapOf(
            "captured" to capturedAtMillis, "title" to title, "omitted" to messagesOmitted,
            "rows" to safe.map { entry -> mapOf("id" to entry.id, "timestamp" to entry.timestamp,
                "kind" to entry.javaClass.simpleName, "body" to when (entry) {
                    is UserMessage -> entry.text
                    is AssistantMessage -> entry.text
                    is ToolActivity -> entry.name
                    else -> ""
                }) }
        ))).toByteArray(Charsets.UTF_8).size
        var remembered = false
        vault.withConversationPreviewOwner(owner) {
            synchronized(lock) {
                if (closed) return@synchronized
                val key = Key(owner.partition, session)
                entries.remove(key)?.let { bytes -= it.bytes }
                if (payload > MAX_BYTES) return@synchronized
                while (entries.isNotEmpty() && (entries.size >= MAX_ENTRIES || bytes + payload > MAX_BYTES)) {
                    val oldest = entries.entries.first()
                    bytes -= oldest.value.bytes
                    entries.remove(oldest.key)
                }
                entries[key] = Entry(ChatPreviewSnapshot(owner, safe.toList(), capturedAtMillis, title, messagesOmitted), payload)
                bytes += payload
                remembered = true
            }
        }
        return remembered
    }

    override fun close() {
        stopObserving()
        synchronized(lock) { closed = true; entries.clear(); bytes = 0 }
    }

    companion object {
        const val MAX_ENTRIES = 4
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
