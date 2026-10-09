package io.github.anshuman2401.hermesmobile.data.gateway

/** Backend identity is independent of display text. No session/config metadata is retained. */
data class BotProfile(val name: String, val displayName: String, val hasAvatar: Boolean)

/** Bounded in-memory image only, not a URL or persistence record. */
class BotAvatar(val bytes: ByteArray, val mime: String) {
    override fun toString(): String = "BotAvatar([REDACTED])"
}

/**
 * Single-use, memory-only WebSocket ticket.
 * Value is redacted from all diagnostics and string representations.
 */
data class GatewayTicket(
    val value: String,
    val ttlSeconds: Int = 30
) {
    init {
        require(value.isNotBlank()) { "Ticket value must not be blank" }
        require(ttlSeconds > 0) { "Ticket TTL must be positive" }
        for (c in value) {
            require(c.code in 33..126 && c != '"' && c != '\\') {
                "Ticket value contains prohibited character (code: ${c.code})"
            }
        }
    }

    override fun toString(): String = "GatewayTicket(value=[REDACTED], ttlSeconds=$ttlSeconds)"
}

/**
 * Strongly-typed protocol failure representations.
 * Never leaks raw credentials, passwords, or tickets.
 */
sealed interface GatewayFailure {
    val isTerminal: Boolean get() = false

    data class Rpc(
        val code: Int,
        val message: String,
        val data: String? = null,
        val terminal: Boolean = false
    ) : GatewayFailure {
        override val isTerminal: Boolean get() = terminal || code == 4401 || code == 4403 || code == -32600 || code == -32601
    }
    data class Transport(
        val message: String,
        val causeClassName: String? = null,
        val statusCode: Int? = null,
        val terminal: Boolean = false
    ) : GatewayFailure {
        override val isTerminal: Boolean get() = terminal || statusCode == 401 || statusCode == 403 || statusCode == 4401 || statusCode == 4403
    }
    data class IncompatibleProtocol(val reason: String) : GatewayFailure {
        override val isTerminal: Boolean get() = true
    }
    data class SubmissionUnknown(val message: String, val causeClassName: String? = null) : GatewayFailure
    data class NotConnected(val message: String = "Gateway is not connected") : GatewayFailure
    data class AuthenticationRequired(val message: String = "Authentication required (401/4401)") : GatewayFailure {
        override val isTerminal: Boolean get() = true
    }
}

/** Attachment-specific interpretation of gateway failures; raw RPC codes stay here. */
enum class AttachmentFailureKind { UNSUPPORTED, VALIDATION, HOST, TRANSPORT, OTHER }

fun GatewayFailure.attachmentFailureKind(): AttachmentFailureKind = when (this) {
    is GatewayFailure.Rpc -> when (code) {
        -32601 -> AttachmentFailureKind.UNSUPPORTED
        4015, 4016, 4017, 4018 -> AttachmentFailureKind.VALIDATION
        5027, 5028 -> AttachmentFailureKind.HOST
        else -> AttachmentFailureKind.OTHER
    }
    is GatewayFailure.Transport, is GatewayFailure.NotConnected -> AttachmentFailureKind.TRANSPORT
    is GatewayFailure.IncompatibleProtocol -> AttachmentFailureKind.UNSUPPORTED
    else -> AttachmentFailureKind.OTHER
}

/**
 * Result wrapper for gateway operations.
 */
sealed interface GatewayResult<out T> {
    data class Success<T>(val value: T) : GatewayResult<T>
    data class Failure(val failure: GatewayFailure) : GatewayResult<Nothing>

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }
}

/**
 * Gateway capability flags.
 * Per ARCH-001, [perSessionExclusiveSubmit] must be true before the gateway is declared usable.
 */
data class GatewayCapabilities(
    val perSessionExclusiveSubmit: Boolean,
    val rawCapabilities: Map<String, Any?> = emptyMap()
)

/**
 * Result of gateway.ping keepalive probe.
 */
data class GatewayPingResult(
    val ok: Boolean
)

/**
 * Authoritative session summary from session.list.
 */
data class SessionSummary(
    val id: String,
    val title: String?,
    val preview: String?,
    val startedAt: Double?,
    val messageCount: Int,
    val source: String?,
    val lastActive: Double? = null
)

data class SessionListResult(
    val sessions: List<SessionSummary>
)

data class ActiveSessionItem(
    val id: String,
    val sessionKey: String? = null,
    val model: String? = null,
    val messageCount: Int = 0,
    val status: String? = null,
    val lastActive: Double? = null
)

data class ActiveSessionListResult(
    val sessions: List<ActiveSessionItem>
)


data class SessionMessage(
    val role: String,
    val content: String,
    /**
     * F007-S1: raw tool-row data from `session.resume` history. Host
     * role:"tool" rows carry name/context and NO content/text; these stay
     * null for every non-tool row (raw data at the adapter boundary, never
     * fabricated defaults).
     */
    val toolName: String? = null,
    val toolContext: String? = null,
    /**
     * F-007 polish (deferred from F007-S4): raw assistant-row reasoning from
     * `session.resume` history. The host forwards `reasoning` /
     * `reasoning_content` on assistant rows when persisted
     * (_HISTORY_ASSISTANT_DETAIL_KEYS, session_history.py); desktop ignores
     * these keys. Raw data at the adapter boundary — null when absent,
     * non-string, or blank; hydration decides the rendering surface.
     */
    val reasoning: String? = null,
    /**
     * CHAT-009 (FZ-9): raw history-row creation time as epoch MILLIS, parsed
     * tolerantly from the wire's fractional epoch-seconds `timestamp` field.
     * Null when the host row carried no parseable timestamp (missing key,
     * non-number, or null). Raw boundary data, never fabricated; the value
     * is never logged. NOT part of [HistoryPrependMerger]'s legacy five-field
     * stop-row comparison.
     */
    val createdAtMillis: Long? = null,
    /** Exact wire seconds for transient association only; never serialized to the compact cache. */
    val sourceTimestampSeconds: Double? = null,
    val editable: EditableMessage? = null,
    val rowId: Long? = null
) {
    override fun toString() = "SessionMessage(role=$role, [REDACTED])"
}

data class SessionCreateResult(
    val runtimeSessionId: String,
    val durableSessionId: String,
    val messageCount: Int,
    val messages: List<SessionMessage> = emptyList(),
    val info: Map<String, Any?> = emptyMap()
)

/**
 * F007-S2: which host stream a [GatewayEvent.ReasoningDelta] came from.
 * REASONING = `reasoning.delta`; THINKING = `thinking.delta`.
 */
enum class ReasoningKind {
    REASONING,
    THINKING
}

/**
 * Result of session.resume.
 * Handles both unpersisted draft and persisted database schemas from certified host.
 */
enum class ResumeActivityEvidence { UNKNOWN, NON_RUNNING, RUNNING }

data class SessionResumeResult(
    val runtimeSessionId: String,
    val durableSessionId: String,
    val messageCount: Int,
    val messages: List<SessionMessage> = emptyList(),
    val info: Map<String, Any?> = emptyMap(),
    val inflight: Any? = null,
    val running: Boolean = false,
    val startedAt: Double? = null,
    val status: String = "idle",
    /**
     * CHAT-004 (frozen plan TASK-001): the host's only truncation signal for
     * the resume window. Absent/blank/non-boolean parses to false (never
     * invented true). Drives the bounded load-earlier indicator honesty rule.
     */
    val messagesOmitted: Boolean = false,
    val activityEvidence: ResumeActivityEvidence = ResumeActivityEvidence.UNKNOWN,
    val observedRuntimeSessionId: String? = null,
    val observedDurableSessionId: String? = null,
    val activeProjection: ActiveSessionProjection? = null,
    /** Certified empty lazy resume shape; never inferred from defaulted activity fields. */
    val isUnpersistedDraft: Boolean = false,
    /** Optional authoritative memory-only snapshot; absence is not an empty list. */
    val taskSnapshot: io.github.anshuman2401.hermesmobile.domain.TaskSnapshot? = null
) {
    override fun toString() = "SessionResumeResult(messageCount=$messageCount, messages=[REDACTED], [REDACTED])"
}

enum class WirePresence { ABSENT, NULL, INVALID, VALUE }

/** Exact property evidence; a malformed array may retain valid bounded neighbours. */
data class ObservedProperty<T>(
    val presence: WirePresence = WirePresence.ABSENT,
    val value: T? = null,
    val truncated: Boolean = false
) {
    override fun toString() = "ObservedProperty(presence=$presence, value=[REDACTED], truncated=$truncated)"
}

data class ActiveSessionProjection(
    val running: ObservedProperty<Boolean>,
    val status: ObservedProperty<String>,
    val turnStartedAt: ObservedProperty<Double>,
    val inflight: ObservedProperty<ActiveInflightProjection>,
    val queued: ObservedProperty<ActiveQueuedProjection>,
    val retainedBytes: Int
) {
    override fun toString() = "ActiveSessionProjection([REDACTED])"
}

data class ActiveInflightProjection(
    val user: ObservedProperty<String>,
    val assistant: ObservedProperty<String>,
    val streaming: ObservedProperty<Boolean>,
    val displayKind: ObservedProperty<String>,
    val displayMetadata: ObservedProperty<ActiveDisplayMetadata>,
    val corrections: ObservedProperty<List<String>>,
    val correctionOffsets: ObservedProperty<List<Long>>,
    val error: ObservedProperty<String>,
    val status: ObservedProperty<String>,
    val recoverable: ObservedProperty<Boolean>,
    val errorSurface: ObservedProperty<ActiveErrorSurface>
) {
    override fun toString() = "ActiveInflightProjection([REDACTED])"
}

data class ActiveQueuedProjection(val user: ObservedProperty<String>) {
    override fun toString() = "ActiveQueuedProjection([REDACTED])"
}

/** Only scalar display fields produced by the pinned host are retained; rich/unknown data drops. */
data class ActiveDisplayMetadata(val fields: Map<String, ObservedProperty<*>>) {
    override fun toString() = "ActiveDisplayMetadata([REDACTED])"
}

data class ActiveErrorSurface(
    val layer: ObservedProperty<String>,
    val code: ObservedProperty<String>,
    val retryable: ObservedProperty<Boolean>
) {
    override fun toString() = "ActiveErrorSurface([REDACTED])"
}

data class PromptSubmitResult(
    val status: String,
    val userRowId: Long? = null
)

data class AttachImageResult(
    val path: String,
    val count: Int,
    val text: String,
    val bytes: Int
)

data class AttachFileResult(
    val name: String,
    val path: String,
    val refPath: String,
    val refText: String,
    val uploaded: Boolean
)

data class DetachImageResult(
    val detached: Boolean,
    val count: Int
)

/**
 * Result of `session.interrupt`. A correlated JSON-RPC result (no `error`)
 * proves the host received the interrupt request; [ok] carries the ack's
 * explicit `ok` flag when present and defaults to true when the ack omits
 * the field — an explicit `ok:false` is preserved, never overwritten.
 */
data class InterruptSessionResult(
    val ok: Boolean
)

/**
 * CHAT-011 S1 (TASK-003): result of the key-restricted `config.get` reasoning
 * probe. [effort] is the profile-default reasoning effort in the host
 * vocabulary verbatim — `low`/`medium`/`high`/…/`none` (reasoning disabled,
 * H9) — or null when the host reported no usable value (blank/missing).
 * The `display` flag is deliberately not carried: it gates TUI reasoning-trace
 * visibility, not label visibility (ASSUMPTION-004, open decision 5).
 */
data class ReasoningDefaultResult(
    val effort: String?
)

data class ReplayEventsResult(
    val count: Int,
    val epoch: String?,
    val latestSeq: Long?,
    val truncated: Boolean,
    val events: List<GatewayEvent>
)

data class GatewayUsage(
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null
)

/**
 * Typed inbound gateway events.
 */
sealed class GatewayEvent {
    abstract val sessionId: String?
    abstract val seq: Long?

    open val requiresSessionId: Boolean get() = false

    val hasValidSession: Boolean
        get() = if (requiresSessionId) !sessionId.isNullOrBlank() else true

    val hasValidSeq: Boolean
        get() = seq == null || seq!! > 0L

    data class ApprovalCancelled(
        override val sessionId: String?, override val seq: Long?, val requestId: String, val reason: String
    ) : GatewayEvent() {
        override val requiresSessionId = true
        override fun toString() = "ApprovalCancelled([redacted])"
    }

    data class Ready(
        val heartbeat: Boolean,
        val changeEvents: Boolean,
        val replayEpoch: String?,
        val skin: Map<String, Any?>? = null
    ) : GatewayEvent() {
        override val sessionId: String? = null
        override val seq: Long? = null
    }

    data class MessageStart(
        override val sessionId: String?,
        override val seq: Long?
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    data class MessageDelta(
        override val sessionId: String?,
        override val seq: Long?,
        val text: String,
        val rendered: String? = null
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    data class MessageInterim(
        override val sessionId: String?,
        override val seq: Long?,
        val text: String,
        val alreadyStreamed: Boolean? = null
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    data class MessageComplete(
        override val sessionId: String?,
        override val seq: Long?,
        val text: String?,
        /**
         * BLD-006 S4 (TASK-015 / D-03): the terminal status is nullable raw
         * data owned at this gateway boundary. Absent, non-string, blank, and
         * whitespace-only statuses stay distinguishable from a certified
         * "complete" — the adapter must never fabricate a success default
         * (contract line 114); mapping to UNKNOWN happens in the Chat reducer.
         */
        val status: String?,
        val usage: GatewayUsage? = null,
        /**
         * F007-S4: the host's final reasoning shipped inside
         * `message.complete.payload.reasoning` (tui_gateway
         * `_complete_turn_payload`, prompt_turn.py:620-631). Raw boundary
         * data: absent or non-string stays null — never fabricated.
         */
        val reasoning: String? = null
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    data class Error(
        override val sessionId: String?,
        override val seq: Long?,
        val message: String
    ) : GatewayEvent()

    data class ToolGenerating(
        override val sessionId: String?,
        override val seq: Long?,
        val name: String
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    data class ToolStart(
        override val sessionId: String?,
        override val seq: Long?,
        val toolId: String,
        val name: String,
        val context: String? = null,
        val args: Map<String, Any?> = emptyMap()
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    class SubagentUpdate(
        override val sessionId: String?, override val seq: Long?, val type: String,
        val child: io.github.anshuman2401.hermesmobile.data.subagent.Subagent?
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
        override fun toString() = "SubagentUpdate([redacted])"
    }

    data class ToolProgress(
        override val sessionId: String?,
        override val seq: Long?,
        val type: String,
        val payload: Map<String, Any?> = emptyMap()
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    data class ToolComplete(
        override val sessionId: String?,
        override val seq: Long?,
        val toolId: String,
        val name: String,
        val args: Map<String, Any?> = emptyMap(),
        val durationSeconds: Double? = null,
        val result: Any? = null,
        val summary: String? = null,
        val taskSnapshot: io.github.anshuman2401.hermesmobile.domain.TaskSnapshot? = null
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    data class TasksUpdated(
        override val sessionId: String?,
        override val seq: Long?,
        val snapshot: io.github.anshuman2401.hermesmobile.domain.TaskSnapshot?
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    /**
     * F007-S2: live reasoning stream from the host. `reasoning.delta`
     * ({text, verbose?}, always bound per agent_callbacks.py:96-98) decodes
     * with kind REASONING; `thinking.delta` ({text}, agent_callbacks.py:93)
     * decodes with kind THINKING. Non-string/absent text decodes safely as an
     * empty string — no crash, no fabricated text. The `reasoning.available`
     * mapping is intentionally NOT touched in this slice (stays ToolProgress).
     */
    data class ReasoningDelta(
        override val sessionId: String?,
        override val seq: Long?,
        val kind: ReasoningKind,
        val text: String
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    class SessionUsageUpdated(
        override val sessionId: String?,
        override val seq: Long?,
        val usage: io.github.anshuman2401.hermesmobile.data.context.LiveUsageSummary
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
        override fun toString() = "SessionUsageUpdated([redacted])"
    }

    data class SessionInfo(
        override val sessionId: String?,
        override val seq: Long?,
        val info: Map<String, Any?> = emptyMap()
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    data class StatusUpdate(
        override val sessionId: String?,
        override val seq: Long?,
        val status: String,
        val kind: String? = null
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    data class SessionTitle(
        override val sessionId: String?,
        override val seq: Long?,
        val title: String
    ) : GatewayEvent() {
        override val requiresSessionId: Boolean get() = true
    }

    data class SessionsChanged(
        override val sessionId: String? = null,
        override val seq: Long? = null
    ) : GatewayEvent()

    data class Unknown(
        val type: String,
        override val sessionId: String?,
        override val seq: Long?,
        val payload: Map<String, Any?> = emptyMap()
    ) : GatewayEvent()
}

/**
 * Signal dispatched when event replay cannot guarantee contiguous stream ordering,
 * requiring transcript replacement via authoritative session hydration.
 */
data class HistoryFallbackSignal(
    val durableSessionId: String?,
    val runtimeSessionId: String?,
    val reason: FallbackReason,
    val details: String? = null
)

enum class FallbackReason {
    EPOCH_MISMATCH,
    TRUNCATED,
    INVALID_ORDER_OR_GAP,
    DECODE_ERROR,
    EPOCH_CHANGED_READY,
    OVERFLOW
}

/**
 * Generation-bound transport frame wrapper per RPR-004 F-002.
 * Associates a transport frame with its originating connection generation to isolate stale socket callbacks.
 */
data class TaggedGatewayTransportFrame(
    val generation: Long,
    val frame: GatewayTransportFrame
) : GatewayTransportFrame
