package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.local.CachedLocalToolTraceEvent
import io.github.anshuman2401.hermesmobile.data.local.ToolStatus

/**
 * TRD1 slice 1 (TASK-002): pure, stateless decision policy for the turn-scoped
 * ExecutionTrace surface (GUD-001, `BackPressPolicy`/`ChatShellCleanupPolicy`
 * precedent). The Composable is an executor of these decisions; the ViewModel
 * owns the trace lifecycle (admission start, terminal seal) — this policy only
 * derives view data from the already-admitted timeline (ALT-002: no
 * reducer-maintained counters).
 *
 * All functions are pure and JVM-testable. Fail-closed: an absent assistant
 * row means no surface; an invalid start time hides the elapsed value (REQ-005).
 */
/**
 * PR-4 (plan CACHE-PR4 §6): observed status of a phone-captured trace row —
 * display-level vocabulary, mapped 1:1 from the stored DTO. Top-level per the
 * chg006-s1 hoisting learning (tests reference the type unqualified).
 */
enum class CachedToolTraceStatus { RUNNING, COMPLETE }

/**
 * PR-4 (§6): ONE phone-captured trace row for display — observed fields only
 * (id/name/status/duration/time). The stored redacted `context` never reaches
 * this entry, so no display row can carry context bytes.
 */
data class CachedToolTraceEntry(
    val id: String,
    val name: String,
    val status: CachedToolTraceStatus,
    val durationSeconds: Double?,
    val observedAtMillis: Long
)

/**
 * TRD1 slice 1: UI-level display entry wrapping the raw timeline for
 * rendering. The domain [TimelineEntry] contract stays frozen (CON-004,
 * ALT-001) — the header rides this display list, not the timeline.
 * Top-level per the chg006-s1 hoisting learning (tests reference the type
 * unqualified).
 */
sealed interface TraceDisplayEntry {
    val key: String

    /** A raw timeline entry, passed through untouched (REQ-004). */
    data class Raw(val entry: TimelineEntry) : TraceDisplayEntry {
        override val key: String = entry.id
    }

    /** The trace header, inserted before the turn's first activity row. */
    data class Header(val trace: TraceState) : TraceDisplayEntry {
        // Unique vs. entry IDs: entry IDs minted by the reducer never use
        // the `trace-header-` prefix.
        override val key: String = "trace-header-${trace.turnId}"
    }

    /**
     * PRD v2 § 1: A collapsed tool activity block card containing all tool steps
     * and reasoning for one assistant reply.
     */
    data class BlockCard(val block: ToolActivityBlock) : TraceDisplayEntry {
        override val key: String = "tool-block-${block.id}"
    }

    /**
     * CHAT-009 (FZ-12): a day-separator label row, inserted by
     * [ChatDaySeparatorPolicy] (OUTSIDE the trace grouping, per FZ-14).
     * [key] is minted by the policy per FZ-8 (`day-sep-<epochDay>-r<run>`),
     * deterministic and stable across recompositions of the same timeline.
     */
    data class DaySeparator(
        val label: String,
        override val key: String
    ) : TraceDisplayEntry

    /**
     * PR-4 (plan CACHE-PR4 §6): the phone-captured trace group node — one
     * bounded card appended AFTER the server-derived blocks (never mixed
     * into them); key can never collide with reducer row ids or the
     * `trace-header-` prefix (T-13).
     */
    data class CachedTrace(
        val entries: List<CachedToolTraceEntry>
    ) : TraceDisplayEntry {
        override val key: String = "cached-tool-trace"
    }
}

object ExecutionTracePolicy {

    /**
     * The stable provisional assistant row ID minted at admission
     * (ChatViewModel: `asst-$pendingTurnId` on both submit and retry paths).
     */
    fun traceAssistantId(turnId: String): String = "asst-$turnId"

    /**
     * Index of the traced turn's assistant row (`"asst-$turnId"`), or `null`
     * when the row is absent — fail-closed: no assistant row → no surface
     * (REQ-005; also makes a cleared-by-hydration trace render ungrouped,
     * ASSUMPTION-001).
     */
    fun spanStartIndex(timeline: List<TimelineEntry>, turnId: String): Int? {
        val assistantId = traceAssistantId(turnId)
        return timeline.indexOfFirst { it is AssistantMessage && it.id == assistantId }
            .takeIf { it >= 0 }
    }

    /**
     * IDs of the turn's activity rows: entries after the assistant row that
     * are [ToolActivity]/[GenericGatewayActivity]/[ReasoningEntry], stopping at
     * the first [UserMessage]/[AssistantMessage] boundary (the next turn).
     */
    fun activityRowIds(timeline: List<TimelineEntry>, turnId: String): Set<String> {
        val startIndex = spanStartIndex(timeline, turnId) ?: return emptySet()
        val ids = LinkedHashSet<String>()
        for (index in startIndex + 1 until timeline.size) {
            when (val entry = timeline[index]) {
                is UserMessage, is AssistantMessage -> return ids
                is ToolActivity, is GenericGatewayActivity, is ReasoningEntry -> ids.add(entry.id)
                // TurnNotice/ConnectionNotice/LocalDraftNotice are system
                // notices, never turn-attributed activity rows.
                else -> {}
            }
        }
        return ids
    }

    /** Action count = number of activity rows attributed to the turn (REQ-002). */
    fun actionCount(timeline: List<TimelineEntry>, turnId: String): Int =
        activityRowIds(timeline, turnId).size

    /**
     * Client-measured elapsed text from submit-confirmation to the terminal
     * event (wall clock per A2). Returns `null` when `startedAtMillis <= 0`
     * (fail-closed hide, REQ-005 — no placeholder). `mm:ss` under one hour,
     * `h:mm:ss` above; when `endedAtMillis == null` the live `nowMillis` is
     * used (UI-side ticker read, see RISK-004/ALT-004).
     */
    fun elapsedText(startedAtMillis: Long, endedAtMillis: Long?, nowMillis: Long): String? {
        if (startedAtMillis <= 0) return null
        val endMillis = endedAtMillis ?: nowMillis
        val elapsedSeconds = ((endMillis - startedAtMillis).coerceAtLeast(0L)) / 1000L
        val hours = elapsedSeconds / 3600L
        val minutes = (elapsedSeconds % 3600L) / 60L
        val seconds = elapsedSeconds % 60L
        return if (hours > 0L) {
            String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Builds the UI display list. When `trace == null` or the traced turn's
     * assistant row is absent, every entry passes through unchanged (the
     * timeline renders exactly as today — REQ-003). Otherwise a
     * [TraceDisplayEntry.Header] is inserted before `timeline[spanStartIndex
     * + 1]` — before the turn's first activity row, after the assistant row
     * (REQ-001: the user message and the assistant answer stay outside the
     * surface).
     */
    fun buildDisplayEntries(
        timeline: List<TimelineEntry>,
        trace: TraceState?
    ): List<TraceDisplayEntry> {
        val rawList: List<TraceDisplayEntry> =
            timeline.map { TraceDisplayEntry.Raw(it) }
        if (trace == null) return rawList
        val startIndex = spanStartIndex(timeline, trace.turnId) ?: return rawList
        // Header position: before the turn's first activity row, i.e. right
        // after the assistant row (startIndex + 1). With no activity rows the
        // header still lands directly after the assistant row.
        val headerIndex = (startIndex + 1).coerceAtMost(rawList.size)
        return rawList.subList(0, headerIndex) +
            TraceDisplayEntry.Header(trace) +
            rawList.subList(headerIndex, rawList.size)
    }

    /**
     * PR-4 (plan CACHE-PR4 §6): PURE projection of stored bounded DTOs into
     * display entries — observed fields only, input (observed) order
     * preserved, the redacted `context` never reaches a display row, null ⇒
     * empty (T-12).
     */
    fun buildCachedToolTraceEntryList(
        events: List<CachedLocalToolTraceEvent>
    ): List<CachedToolTraceEntry> = events.map { event ->
        CachedToolTraceEntry(
            id = event.localEventId,
            name = event.name,
            status = when (event.status) {
                ToolStatus.COMPLETE -> CachedToolTraceStatus.COMPLETE
                ToolStatus.RUNNING -> CachedToolTraceStatus.RUNNING
            },
            durationSeconds = event.durationSeconds,
            observedAtMillis = event.observedAtMillis
        )
    }

    /**
     * PR-4 (§6): status wording — RUNNING NEVER claims a completion and a
     * duration renders only when actually observed (T-13 pin).
     */
    fun cachedTraceStatusText(status: CachedToolTraceStatus, durationSeconds: Double?): String =
        when (status) {
            CachedToolTraceStatus.RUNNING -> "Completion not captured"
            CachedToolTraceStatus.COMPLETE -> if (durationSeconds != null) {
                "Completed in ${durationSeconds}s"
            } else {
                "Completed"
            }
        }

    /** Select display evidence only; never modify the captured trace or its persistence. */
    fun cachedToolTraceForDisplay(
        cachedToolTrace: List<CachedToolTraceEntry>,
        hasAcceptedStoredHistory: Boolean = false,
        savedSnapshotCapturedAtMillis: Long? = null
    ): List<CachedToolTraceEntry> =
        if (hasAcceptedStoredHistory && savedSnapshotCapturedAtMillis == null) emptyList() else cachedToolTrace

    /**
     * PRD v2 § 1: Pure block card display list builder delegating to
     * [ToolBlockPolicy], plus the OPTIONAL phone-captured trace group
     * (plan CACHE-PR4 §6). Without cachedToolTrace (default) this is
     * byte-identical to the pre-PR-4 projection; the `cached-tool-trace` node
     * appends only when non-empty — its key can never collide with reducer
     * row ids or the `trace-header-` prefix (T-13).
     */
    fun buildBlockDisplayEntries(
        timeline: List<TimelineEntry>,
        trace: TraceState?,
        cachedToolTrace: List<CachedToolTraceEntry> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): List<TraceDisplayEntry> {
        val blocks = ToolBlockPolicy.buildBlockDisplayEntries(timeline, trace, nowMillis)
        if (cachedToolTrace.isEmpty()) return blocks
        return blocks + TraceDisplayEntry.CachedTrace(cachedToolTrace)
    }
}
