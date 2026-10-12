package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.GatewayUsage

/**
 * Domain timeline entry contract per ARCH-001 (chat-rendering.md).
 * The Chat feature consumes typed domain timeline entries, never raw gateway JSON.
 */
sealed interface TimelineEntry {
    val id: String

    /**
     * Epoch millis. CHAT-009 (FZ-7/FZ-10): `0L` = absent (the host history
     * row had no parseable timestamp; history hydration). Live paths always
     * mint real wall-clock values. Rendering gates captions on `> 0`.
     */
    val timestamp: Long
}

enum class UserMessageState {
    SENDING,
    SENT,
    UNKNOWN
}

data class UserMessage(
    override val id: String,
    val text: String,
    val state: UserMessageState = UserMessageState.SENT,
    override val timestamp: Long = System.currentTimeMillis(),
    val sourceKind: ActiveSourceKind = ActiveSourceKind.COMPACT,
    val editable: com.hermes.bot.data.gateway.EditableMessage? = null
) : TimelineEntry

enum class AssistantMessageState {
    STREAMING,
    COMPLETE,
    INTERRUPTED,
    FAILED,
    UNKNOWN
}

data class AssistantMessage(
    override val id: String,
    val text: String,
    val state: AssistantMessageState = AssistantMessageState.COMPLETE,
    /**
     * PR-36 review note: deliberately UNRENDERED in the transcript row
     * (see AssistantMessageBubble's FAILED branch). The raw gateway error
     * line is redactor-passed but not classified for a durable, copyable
     * transcript surface (SEC-004); the FAILED chip carries the typed
     * non-secret status and ChatUiState.errorMessage is the user-facing
     * bounded channel. Kept for VM diagnostics/tests only.
     */
    val errorMessage: String? = null,
    val usage: GatewayUsage? = null,
    /**
     * BLD-006 S4 (D-04): SOURCE_OVERFLOW terminal/display flag — the canonical
     * source hit the 8 MiB hard ceiling and the omitted suffix was not
     * retained. Never claimed as stored.
     */
    val sourceOverflow: Boolean = false,
    override val timestamp: Long = System.currentTimeMillis(),
    val sourceKind: ActiveSourceKind = ActiveSourceKind.COMPACT
) : TimelineEntry

enum class ToolStatus {
    PREPARING,
    RUNNING,
    COMPLETED
}

data class ToolActivity(
    override val id: String,
    val toolId: String?,
    val name: String,
    val status: ToolStatus,
    val context: String? = null,
    val argsSummary: String? = null,
    val resultSummary: String? = null,
    val durationSeconds: Double? = null,
    val isExpanded: Boolean = false,
    override val timestamp: Long = System.currentTimeMillis(),
    val storedSource: StoredToolSource? = null,
    val delegatedTasks: List<com.hermes.bot.data.subagent.Subagent> = emptyList(),
    val files: List<com.hermes.bot.data.files.ConversationFile> = emptyList()
) : TimelineEntry

/** Exact source occurrence; transient and never mapped to the offline cache. */
data class StoredToolSource(
    val callRowId: Long, val callIndex: Int, val contextRowId: Long?,
    val resultRowId: Long, val argumentsPresent: Boolean, val sourceOverflow: Boolean
)

data class GenericGatewayActivity(
    override val id: String,
    val eventType: String,
    val summary: String,
    val payloadPreview: String? = null,
    val isExpanded: Boolean = false,
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineEntry

/**
 * CHAT-018 PR-A (REQ-002): one agent commentary segment (`message.interim`)
 * from the active turn. Rendered ONLY inside the turn's tool activity card
 * (collapsed preview row + bottom sheet detail) — NEVER as a reply bubble in
 * any turn state (owner-locked design; see
 * specs/requirements/CHAT-018-interim-commentary-requirements-draft-v1.md).
 *
 * NOT snapshot-persisted: no history mapper emits this row (live-only in
 * offline snapshots — the roundtrip drops it by construction, the
 * ModelChangeNoticeRow precedent). A session switch/reconnect re-hydrates the
 * timeline from host history, discarding live notes; PR-B adds the reload
 * heuristic. Live note IDs are `note-<uuid>`.
 */
data class AgentNoteEntry(
    override val id: String,
    val text: String,
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineEntry

/**
 * F007-S2: which live host stream a [ReasoningEntry] accumulates.
 * Mirrors [com.hermes.bot.data.gateway.ReasoningKind] at the chat-feature
 * boundary (the feature consumes typed domain entries, never gateway types).
 */
enum class ReasoningDisplayKind {
    REASONING,
    THINKING
}

/**
 * F007-S2: a live reasoning block for the active streaming turn. Rendered as
 * a collapsed-by-default dim block between assistant segments; expanding it
 * shows the accumulated reasoning text. Text accumulates across deltas under
 * the [ReasoningPolicy] UTF-8 budget with a disclosed truncation label.
 */
data class ReasoningEntry(
    override val id: String,
    val kind: ReasoningDisplayKind,
    val text: String,
    val isExpanded: Boolean = false,
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineEntry

/**
 * F007-S2: bounded accumulation policy for live reasoning text.
 *
 * The plan caps accumulated reasoning at 32 KiB UTF-8 with a labeled
 * truncation, consistent with the existing bounded patterns
 * (BoundedPayloadMapper budgets / AssistantTurnStreamBuffer windows).
 * Accumulation appends scalar-aligned admitted prefixes; once the budget is
 * reached later text is dropped from retention and the visible truncation
 * label is appended once — the omitted suffix is never claimed as stored
 * (mirrors D-04's disclosed-bounds contract).
 *
 * The helper is pure/stateless: each call walks the already-bounded existing
 * text (it is capped at the budget, so the walk is bounded) and appends the
 * largest scalar-aligned prefix of the delta that fits, plus the label when
 * truncation occurs. Consecutive calls after truncation are no-ops because
 * the label's presence pins the existing text at its final length.
 */
object ReasoningPolicy {
    /** Frozen F007-S2 accumulation budget: 32 KiB UTF-8 of reasoning text. */
    const val MAX_ACCUMULATED_UTF8_BYTES: Int = 32 * 1024

    /** Visible label appended when the accumulation budget was exceeded. */
    const val TRUNCATION_LABEL: String =
        "\nReasoning truncated. (exceeded the 32 KiB display budget)"

    private val TRUNCATION_LABEL_UTF8_BYTES: Int =
        TRUNCATION_LABEL.toByteArray(Charsets.UTF_8).size

    /**
     * Appends [delta] to the accumulated reasoning stream [existing] under
     * the budget. Surrogate-normalized, scalar-aligned; never splits a pair
     * or UTF-8 sequence. Idempotent after truncation: the label's presence
     * marks the terminal state and later deltas are not admitted.
     */
    fun accumulate(existing: String, delta: String): String {
        if (delta.isEmpty() || existing.endsWith(TRUNCATION_LABEL)) return existing
        val normalized = BoundedPayloadMapper.normalizeSurrogates(delta)
        if (normalized.isEmpty()) return existing

        val existingBytes = BoundedPayloadMapper.utf8ByteCount(existing)
        if (existingBytes >= MAX_ACCUMULATED_UTF8_BYTES) {
            return existing + TRUNCATION_LABEL
        }

        val remaining = MAX_ACCUMULATED_UTF8_BYTES - existingBytes
        val deltaBytes = BoundedPayloadMapper.utf8ByteCount(normalized)
        return if (deltaBytes <= remaining) {
            existing + normalized
        } else {
            // Scalar-aligned prefix within the remaining budget (room for the
            // label); the label marks the truncation of the original stream.
            val keep = (remaining - TRUNCATION_LABEL_UTF8_BYTES).coerceAtLeast(0)
            val kept = AssistantTurnStreamBuffer.scalarAlignedPrefixUtf8(
                normalized,
                keep.toLong()
            )
            existing + kept + TRUNCATION_LABEL
        }
    }
}

enum class NoticeType {
    INFO,
    WARNING,
    ERROR
}

data class TurnNotice(
    override val id: String,
    val title: String,
    val message: String,
    val type: NoticeType = NoticeType.INFO,
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineEntry

data class ConnectionNotice(
    override val id: String,
    val message: String,
    val isStale: Boolean = false,
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineEntry

data class LocalDraftNotice(
    override val id: String,
    val message: String,
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineEntry

/**
 * CHAT-017 v2 (REQ-001): standalone light-gray model/reasoning change line,
 * appended IMMEDIATELY when a [GatewayEvent.SessionInfo] ack reports a
 * model/effort change for the session (copy via the unchanged
 * [ModelChangeNoticePolicy.diff]). A change-log row, not a reply boundary:
 * it is appended to the live timeline at ack time, never attached to any
 * assistant row.
 *
 * LIVE-ONLY (REQ-004): never written to the offline conversation snapshot
 * (the snapshot mapper knows no notice row types) and never produced by
 * history hydration — history rows carry no model attribution (v1 REQ-005
 * still holds). After app restart the row is gone; the current model remains
 * visible via the picker caption. Matches TurnNotice/ConnectionNotice
 * semantics.
 */
data class ModelChangeNoticeRow(
    override val id: String,
    val text: String,
    override val timestamp: Long = System.currentTimeMillis()
) : TimelineEntry

/** In-memory source groups. These never cross the compact snapshot serializer boundary. */
enum class ActiveSourceKind { STORED, COMPACT, LOCAL_USER, STREAM, INFLIGHT, QUEUED, CORRECTION, LIVE }

internal data class ActiveFoldMessage(
    val id: String,
    val role: String,
    val parts: List<TimelineEntry>,
    val sourceKind: ActiveSourceKind,
    val sourceTimestampSeconds: Double? = null,
    val pending: Boolean? = null,
    val interim: Boolean? = null,
    val sourceMetadata: Any? = null,
    val retainedSourceBytes: Long = 0,
    val retainedSource: Any? = sourceMetadata
) {
    val text: String get() = parts.joinToString("") { when (it) {
        is UserMessage -> if (role == "user") it.text else ""
        is AssistantMessage -> if (role == "assistant") it.text else ""
        else -> ""
    } }
    val error: Boolean get() = parts.any { it is AssistantMessage && (it.state == AssistantMessageState.FAILED || it.errorMessage != null) }
    val live: Boolean get() = pending == true || interim == true || sourceKind in setOf(ActiveSourceKind.STREAM, ActiveSourceKind.INFLIGHT)
    override fun toString() = "ActiveFoldMessage([REDACTED])"
}

/** Desktop's source-order fold, applied only inside one captured chat open. */
internal object ActiveHistoryFold {
    private fun normalized(text: String) = text.trim().replace(Regex("\\s+"), " ")
    private fun extension(a: String, b: String): Boolean {
        val x = a.trim(); val y = b.trim()
        return x == y || (x.isNotEmpty() && y.isNotEmpty() && (x.startsWith(y) || y.startsWith(x)))
    }
    private fun anchor(a: ActiveFoldMessage, b: ActiveFoldMessage): Boolean {
        if (a.role != b.role || normalized(a.text) != normalized(b.text)) return false
        val x = a.sourceTimestampSeconds; val y = b.sourceTimestampSeconds
        return if (x != null && y != null) x == y else normalized(a.text).isNotEmpty()
    }
    fun suppressInflightUser(stored: List<ActiveFoldMessage>, runtime: List<ActiveFoldMessage>, user: String): Boolean {
        val last = runtime.lastOrNull()
        val index = if (last == null) -1 else stored.indexOfLast { anchor(last, it) }
        if (last != null && index < 0) return false
        val userIndex = stored.indexOfLast { it.role == "user" }
        // The resume anchor can be the active user row itself. Tool activity
        // after that row does not make the inflight copy a second prompt.
        if (userIndex < 0 || userIndex < index) return false
        val candidate = stored[userIndex]
        if (normalized(candidate.text).isEmpty() || normalized(candidate.text) != normalized(user)) return false
        // Keep the old conservative boundary at an answer or an unknown row:
        // text equality alone must not hide a later repeated prompt.
        return stored.drop(userIndex + 1).all { row ->
            row.role == "tool" || (row.role == "assistant" && row.text.isBlank() && row.parts.isNotEmpty() &&
                row.parts.all { it is ToolActivity || it is ReasoningEntry ||
                    (it is GenericGatewayActivity && it.eventType == "history.tool") })
        }
    }

    fun removeRepresentedTail(stored: List<ActiveFoldMessage>, current: List<ActiveFoldMessage>): List<ActiveFoldMessage> {
        val completed = current.indexOfLast { it.role == "assistant" && !it.live }
        val userIndex = (completed + 1 until current.size - 1).firstOrNull { i ->
            current[i].sourceKind == ActiveSourceKind.LOCAL_USER && current[i + 1].sourceKind == ActiveSourceKind.STREAM
        } ?: return current
        val user = current[userIndex]; val answer = current[userIndex + 1]
        val represented = stored.indices.any { i -> i + 1 < stored.size && stored[i].role == "user" &&
            stored[i + 1].role == "assistant" && normalized(user.text) == normalized(stored[i].text) &&
            extension(answer.text, stored[i + 1].text) }
        if (!represented) return current
        return current.filterIndexed { i, row -> i != userIndex && i != userIndex + 1 &&
            !(i > userIndex + 1 && row.sourceKind == ActiveSourceKind.QUEUED && normalized(row.text) == normalized(user.text)) }
    }

    private fun jsWhitespace(c: Char): Boolean = c in '\u0009'..'\u000d' || c == ' ' || c == '\u00a0' ||
        c == '\u1680' || c in '\u2000'..'\u200a' || c in "\u2028\u2029\u202f\u205f\u3000\ufeff"
    private const val JS_SPACE = "\\x09-\\x0d\\x20\\x{a0}\\x{1680}\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}\\x{feff}"
    private val referenceLine = Regex("^@(?:file|folder|url|image|tool|line|terminal|session):(?:`[^`\\n]+`|\"[^\"\\n]+\"|'[^'\\n]+'|[^" + JS_SPACE + "]+)$")
    internal fun textWithoutReferenceLines(text: String): String = text.split('\n').filterNot {
        referenceLine.matches(it.trimEnd(::jsWhitespace))
    }.joinToString("\n").trim(::jsWhitespace)
    private fun reply(text: String): String = textWithoutReferenceLines(text)

    private fun sourcePart(part: TimelineEntry): TimelineEntry = when (part) {
        is UserMessage -> part.copy(timestamp = 0)
        is AssistantMessage -> part.copy(timestamp = 0)
        is ToolActivity -> part.copy(isExpanded = false, timestamp = 0)
        is ReasoningEntry -> part.copy(isExpanded = false, timestamp = 0)
        is GenericGatewayActivity -> part.copy(isExpanded = false, timestamp = 0)
        is AgentNoteEntry -> part.copy(timestamp = 0)
        else -> part
    }
    internal fun equivalent(a: ActiveFoldMessage?, b: ActiveFoldMessage): Boolean = a != null &&
        a.copy(parts = a.parts.map(::sourcePart), retainedSource = null, retainedSourceBytes = 0) ==
            b.copy(parts = b.parts.map(::sourcePart), retainedSource = null, retainedSourceBytes = 0)

    /** F05: structural same-ID overlay, settled suppression, then ONE first STREAM activation. */
    fun overlay(next: List<ActiveFoldMessage>, baseline: List<ActiveFoldMessage>, current: List<ActiveFoldMessage>): List<ActiveFoldMessage> {
        val before = baseline.associateBy { it.id }
        val out = next.toMutableList()
        var activationUsed = false
        val activationId = next.firstOrNull { it.sourceKind == ActiveSourceKind.STREAM && before[it.id] == null }?.id
        for (row in current) {
            if (equivalent(before[row.id], row)) continue
            val same = out.indexOfFirst { it.id == row.id }
            if (same >= 0) { out[same] = row; continue }
            val synthetic = row.sourceKind == ActiveSourceKind.STREAM || row.sourceKind == ActiveSourceKind.INFLIGHT
            if (row.role == "assistant" && synthetic && row.pending != true && !row.error) {
                val text = reply(row.text)
                if (text.isNotEmpty() && out.withIndex().any { (i, candidate) -> i > out.indexOfLast { it.role == "user" } &&
                    candidate.role == "assistant" && !candidate.live && before[candidate.id] == null && reply(candidate.text) == text }) continue
            }
            val activation = if (!activationUsed && row.sourceKind == ActiveSourceKind.STREAM) out.indexOfFirst {
                it.id == activationId
            } else -1
            if (activation >= 0) {
                val prior = out[activation]
                val parts = if (prior.text.isEmpty() || row.text.startsWith(prior.text)) row.parts else prior.parts + row.parts
                out[activation] = row.copy(parts = parts)
                activationUsed = true
            } else out.add(row)
        }
        return out
    }
}
