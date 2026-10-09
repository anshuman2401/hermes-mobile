package com.hermes.bot.feature.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * BLD-006 S5 (TASK-021, D-04): 32 ms coalescing publication scheduler over
 * already-admitted presentation updates for the active assistant turn.
 *
 * - [admit] accumulates admitted text into a pending buffer; nothing is
 *   published synchronously here.
 * - A single collector loop publishes the coalesced pending text at most once
 *   per [publicationIntervalMs] (default 32 ms). The interval is a constructor
 *   parameter so deterministic virtual-time tests can shrink it (D-04).
 * - [flush] synchronously publishes and clears whatever is pending, so a
 *   terminal/error/tool boundary never trails the sealed text (TASK-023).
 * - [dispose] stops the loop and drains one last time, so cancellation/
 *   session-switch/disposal never silently drops already-admitted text
 *   (TASK-023). Admission after disposal is rejected; text that was never
 *   admitted to this scheduler is never claimed as retained.
 *
 * Publication count is bounded by wall time, never by delta count: admitting
 * 1000 fragments in one 32 ms window yields exactly one publication.
 */
class DeltaPublicationScheduler(
    /** Receives the coalesced pending text for the active assistant turn. */
    private val onPublish: (String) -> Unit,
    /** Coalescing window; constructor-configurable for deterministic tests. */
    private val publicationIntervalMs: Long = DEFAULT_PUBLICATION_INTERVAL_MS,
    /**
     * Context the publication loop runs on. The production wiring (see
     * [ChatViewModel]) supplies a ViewModel-scoped main context; the
     * constructor parameter makes cadence and context injectable for
     * deterministic virtual-time tests (PAT-004).
     */
    private val schedulerContext: CoroutineContext
) {

    /**
     * Coalesced text not yet published. Emits when a delta is admitted.
     *
     * PR-38 review polish: the pending aggregate is accumulated in one reused
     * scratch buffer instead of reallocating a new `current + text` string on
     * every admission. Admissions carry the up-to-1-MiB presentation window,
     * so per-admission concatenation previously copied up to 1 MiB per
     * admitted fragment; the scratch buffer appends in amortized O(delta) and
     * materializes a String only once per actual publication. Flush,
     * coalescing, drain, and disposal semantics are unchanged.
     */
    private val pendingScratch = StringBuilder()

    /** Signal for the publication loop: at least one fragment is pending. */
    private val pendingNonEmpty = MutableStateFlow(false)

    private val schedulerScope = CoroutineScope(schedulerContext)
    private var loop: Job? = null
    private var disposed = false

    /**
     * Admits an already-retained text fragment into the pending publication
     * buffer. O(1) per fragment; no publication happens here. Admission after
     * [dispose] is rejected: text that was never scheduled is never claimed
     * as retained.
     */
    fun admit(text: String) {
        if (text.isEmpty() || disposed) return
        pendingScratch.append(text)
        pendingNonEmpty.value = true
        ensureLoop()
    }

    /**
     * Synchronously publishes whatever is currently pending (TASK-023 flush
     * rules): the caller's boundary (tool row, generic activity, error,
     * terminal event, cancellation, session switch, ViewModel clear) never
     * trails the sealed text. No-op when nothing is pending.
     */
    fun flush() {
        if (disposed) return
        drainAndPublish()
    }

    /** Drains the pending buffer and hands the coalesced text to the sink. */
    private fun drainAndPublish() {
        if (pendingScratch.isEmpty()) return
        val toPublish = pendingScratch.toString()
        pendingScratch.setLength(0)
        pendingNonEmpty.value = false
        onPublish(toPublish)
    }

    /**
     * Stops the loop after a final flush of admitted pending text. Never
     * claims retention for text that was never admitted to this scheduler.
     * [flush] intentionally runs even though [disposed] is already set:
     * cancellation/session-switch/clear must still drain admitted text.
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        loop?.cancel()
        loop = null
        drainAndPublish()
    }

    /** Lazily starts the publication loop on the first admitted fragment. */
    private fun ensureLoop() {
        if (loop?.isActive == true) return
        loop = schedulerScope.launch {
            while (true) {
                // Suspend until at least one fragment is pending, then wait one
                // full window so fragments arriving during the window coalesce
                // into a single publication.
                pendingNonEmpty.first { it }
                delay(publicationIntervalMs)
                flush()
            }
        }
    }

    companion object {
        /** Frozen D-04 coalescing window for live streams. */
        const val DEFAULT_PUBLICATION_INTERVAL_MS: Long = 32L
    }
}
