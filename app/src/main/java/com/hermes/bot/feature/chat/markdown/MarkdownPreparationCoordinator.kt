package com.hermes.bot.feature.chat.markdown

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Main-thread-owned mailbox: one active preparation and one latest pending revision. */
internal class MarkdownPreparationCoordinator<T>(
    private val prepare: suspend (String, Boolean, T?) -> T,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val cadenceMillis: Long = 100
) {
    private data class Request(val source: String, val sealed: Boolean, val generation: Long)
    private val pending = Channel<Request>(Channel.CONFLATED)
    private var latest: Request? = null
    private var generation = 0L

    fun submit(source: String, sealed: Boolean) {
        val old = latest
        if (old?.source == source && old.sealed == sealed) return
        // A replacement must not publish or reuse the superseded document, even if
        // later revisions happen to return to the old prefix (ABA).
        if (old != null && (!source.startsWith(old.source) || (old.sealed && !sealed))) generation++
        Request(source, sealed, generation).also { latest = it; pending.trySend(it) }
    }

    suspend fun run(publish: (T) -> Unit) {
        var previous: T? = null
        var previousGeneration = -1L
        var lastStarted: Long? = null
        while (true) {
            var request = pending.receive()
            while (!request.sealed && lastStarted != null) {
                val wait = cadenceMillis - (nowMillis() - lastStarted)
                if (wait <= 0) break
                // A terminal revision wakes the timer immediately. Ordinary revisions
                // replace the mailbox without extending the start deadline.
                request = withTimeoutOrNull(wait) { pending.receive() } ?: break
            }
            if (previousGeneration != request.generation) previous = null
            lastStarted = nowMillis()
            val result = prepare(request.source, request.sealed, previous)
            currentCoroutineContext().ensureActive()
            val current = latest
            if (current != null && current.generation == request.generation &&
                current.sealed == request.sealed && current.source.startsWith(request.source)) {
                previous = result
                previousGeneration = request.generation
                publish(result)
            }
        }
    }
}
