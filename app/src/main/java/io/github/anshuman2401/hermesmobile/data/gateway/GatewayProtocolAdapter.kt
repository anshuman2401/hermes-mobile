package io.github.anshuman2401.hermesmobile.data.gateway

import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract boundary for Hermes TUI Gateway transport and protocol operations.
 * Per ARCH-001, GatewayProtocolAdapter is the ONLY component permitted to know
 * endpoints, auth headers/cookies, WebSocket details, JSON-RPC IDs, event envelopes,
 * sequence numbers, or raw error codes.
 */
interface GatewayProtocolAdapter {
    suspend fun screenStatus(profile: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<io.github.anshuman2401.hermesmobile.data.botscreen.ScreenStatus> =
        GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Bot Screen unavailable"))
    suspend fun screenStart(profile: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<io.github.anshuman2401.hermesmobile.data.botscreen.ScreenStatus> =
        GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Bot Screen unavailable"))
    suspend fun screenStop(profile: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<io.github.anshuman2401.hermesmobile.data.botscreen.ScreenStatus> =
        GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Bot Screen unavailable"))
    suspend fun screenObserve(profile: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long, viewerId: String? = null): GatewayResult<io.github.anshuman2401.hermesmobile.data.botscreen.ScreenObservation> =
        GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Bot Screen unavailable"))
    suspend fun screenAcquire(profile: String, viewerId: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<io.github.anshuman2401.hermesmobile.data.botscreen.ScreenLease> =
        GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Bot Screen unavailable"))
    suspend fun screenRelease(profile: String, viewerId: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<io.github.anshuman2401.hermesmobile.data.botscreen.ScreenLease> =
        GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Bot Screen unavailable"))
    val approvalInbox: StateFlow<io.github.anshuman2401.hermesmobile.data.approval.ApprovalInbox>
        get() = MutableEmptyApprovalInbox
    suspend fun refreshApprovals(runtimeId: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin,
        generation: Long): GatewayResult<Boolean> = GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Approvals unavailable"))
    suspend fun answerApproval(request: io.github.anshuman2401.hermesmobile.data.approval.ApprovalRequest,
        choice: io.github.anshuman2401.hermesmobile.data.approval.ApprovalChoice, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin,
        generation: Long): io.github.anshuman2401.hermesmobile.data.approval.ApprovalOutcome = io.github.anshuman2401.hermesmobile.data.approval.ApprovalOutcome.UNAVAILABLE

    suspend fun listSubagents(runtimeId: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<io.github.anshuman2401.hermesmobile.data.subagent.SubagentRoster> =
        GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Subagents unavailable"))
    suspend fun subagentTail(runtimeId: String, childId: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<io.github.anshuman2401.hermesmobile.data.subagent.SubagentTail> =
        GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Transcript unavailable"))
    suspend fun controlSubagent(runtimeId: String, childId: String, text: String?, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): io.github.anshuman2401.hermesmobile.data.subagent.SubagentControl =
        io.github.anshuman2401.hermesmobile.data.subagent.SubagentControl.UNAVAILABLE

    suspend fun sessionContextBreakdown(
        runtimeId: String, profile: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long
    ): GatewayResult<io.github.anshuman2401.hermesmobile.data.context.ContextBreakdown> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Context breakdown unavailable"))

    suspend fun sessionUsage(
        runtimeId: String, profile: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long
    ): GatewayResult<io.github.anshuman2401.hermesmobile.data.context.SessionUsage> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Session usage unavailable"))

    /**
     * Observable connection lifecycle state.
     */
    val connectionStatus: StateFlow<ConnectionStatus>

    /**
     * Hot stream of normalized, deduplicated inbound gateway events.
     */
    val events: Flow<GatewayEvent>

    /**
     * Dispatched when sequence gaps, buffer truncation, or epoch changes require
     * transcript replacement via authoritative session hydration.
     */
    val historyFallbackSignals: Flow<HistoryFallbackSignal>

    /**
     * Current server replay epoch announced in gateway.ready.
     */
    val currentReplayEpoch: String?

    /**
     * Initiates connection to the gateway using the supplied origin and fresh single-use ticket.
     * Enforces the gateway.ready and gateway.capabilities gate before declaring CONNECTED.
     */
    suspend fun connect(
        origin: String,
        ticket: GatewayTicket,
        useQueryFallback: Boolean = false
    ): Boolean

    /**
     * Cleanly terminates the active gateway transport connection.
     */
    fun disconnect()

    /**
     * Retrieves authoritative session list summaries.
     */
    suspend fun listSessions(limit: Int = 100): GatewayResult<SessionListResult>

    /**
     * Retrieves currently active sessions from the gateway process.
     */
    suspend fun listActiveSessions(): GatewayResult<ActiveSessionListResult>

    /**
     * Creates a new session draft.
     *
     * When [profile] is non-blank, it is sent verbatim after trimming as the `profile`
     * parameter in the `session.create` RPC, including literal `default`, routing the
     * session to the Android-app-selected workspace without changing the host's sticky
     * default profile. A null or blank value omits the field.
     *
     * [model] and [reasoningEffort] are explicit per-session overrides. They are
     * sent verbatim when non-blank (`reasoning_effort: "none"` disables
     * reasoning) and omitted entirely when null/blank. Ordinary repository
     * creates pass both as null so the host profile default remains authoritative;
     * direct adapter callers may still use explicit overrides.
     * `provider` is never sent (SEC-004); `session.resume` never sends
     * overrides (existing conversations keep running what they started).
     */
    suspend fun createSession(
        title: String? = null,
        source: String = "android",
        closeOnDisconnect: Boolean = false,
        profile: String? = null,
        model: String? = null,
        reasoningEffort: String? = null
    ): GatewayResult<SessionCreateResult>

    /**
     * Hydrates a session from server state. Handles both unpersisted draft and persisted database schemas.
     *
     * When [profile] is non-blank and not "default" (case-insensitive), it is sent as the
     * `profile` parameter in the `session.resume` RPC, routing the resume to the
     * Android-app-selected workspace without changing the host's sticky default profile.
     * A null, blank, or normalized "default" value omits the field for backward compatibility.
     */
    suspend fun resumeSession(sessionId: String, profile: String? = null): GatewayResult<SessionResumeResult>

    /** Explicit captured profile/origin/socket ownership; unsupported adapters fail closed. */
    suspend fun resumeSessionScoped(
        sessionId: String, profile: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin,
        generation: Long
    ): GatewayResult<SessionResumeResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Scoped resume unavailable"))

    fun isScopedConnectionCurrent(origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): Boolean = false


    /**
     * Submits a plain text prompt to an active runtime session.
     * Any write/result ambiguity produces a SUBMISSION_UNKNOWN failure.
     */
    suspend fun submitEditScoped(sessionId: String, text: String, rowId: Long,
        origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<PromptSubmitResult> =
        GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Message editing unavailable"))
    suspend fun interruptEditScoped(sessionId: String,
        origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<InterruptSessionResult> =
        GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Message editing unavailable"))

    suspend fun submitPrompt(sessionId: String, text: String): GatewayResult<PromptSubmitResult>

    suspend fun slashCatalog(runtimeId: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<io.github.anshuman2401.hermesmobile.data.commands.SlashCatalog> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Slash commands unavailable"))
    suspend fun slashComplete(runtimeId: String, text: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<io.github.anshuman2401.hermesmobile.data.commands.SlashCompletion> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Slash commands unavailable"))
    suspend fun slashExecute(runtimeId: String, command: io.github.anshuman2401.hermesmobile.data.commands.SlashInvocation, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<io.github.anshuman2401.hermesmobile.data.commands.SlashDirective> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Slash commands unavailable"))

    /** Stages image bytes in the live session's server-side attachment queue. */
    suspend fun attachImageBytes(
        sessionId: String,
        contentBase64: String,
        filename: String? = null,
        ext: String? = null
    ): GatewayResult<AttachImageResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Image attachment unsupported by this adapter"))

    /** Uploads a client-selected file and returns the host's prompt reference. */
    suspend fun attachFile(sessionId: String, dataUrl: String, name: String): GatewayResult<AttachFileResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("File attachment unsupported by this adapter"))

    /** Removes a queued image by the path returned from [attachImageBytes]. */
    suspend fun detachImage(sessionId: String, path: String): GatewayResult<DetachImageResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Image detach unsupported by this adapter"))

    /**
     * Asks the host to interrupt the active turn on a live runtime session
     * (JSON-RPC `session.interrupt`, params `{ "session_id": ... }`).
     *
     * [sessionId] is the LIVE runtime session id — the same addressing id
     * [submitPrompt] uses for the session. Request correlation, ack handling,
     * and the connected/capability gate run through the shared RPC path used
     * by every other session method. [GatewayResult.Success] means the host
     * acknowledged the interrupt; [GatewayResult.Failure] carries the typed
     * transport/RPC failure. Callers that stop locally treat delivery as
     * best-effort and must not gate local state on this result.
     */
    suspend fun interruptSession(sessionId: String): GatewayResult<InterruptSessionResult>

    /**
     * CHAT-011 S1 (TASK-003, SEC-001): reads the profile-default reasoning
     * effort from the host via `config.get`, structurally restricted to the
     * single key `reasoning` — this method has NO key parameter, so no call
     * site can request any other key (`full` returns the entire config with
     * expanded secrets and is banned host-side; every other config key is
     * likewise unreachable).
     *
     * When [profile] is non-blank and not "default" (case-insensitive), it is
     * sent as the `profile` parameter — the same workspace routing convention
     * as [createSession]/[resumeSession] (omitted when blank/"default" for
     * backward compatibility). The result decodes through
     * `GatewayRedactor.redactMap` at the adapter boundary; the client consumes
     * `value` only and maps a blank value to a null effort. [GatewayResult.Success]
     * means the host answered the config probe; [GatewayResult.Failure] means
     * the probe did not resolve (offline, timeout, RPC error) and the caller
     * must leave any cached default untouched.
     */
    suspend fun getReasoningDefault(profile: String? = null): GatewayResult<ReasoningDefaultResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Reasoning default lookup not supported by this adapter"))

    /**
     * Reads only the profile-scoped reasoning default through a captured origin and
     * connection generation. Additive implementations must fail closed when unsupported.
     */
    suspend fun getProfileReasoningDefault(
        profile: String,
        expectedOrigin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin,
        expectedGeneration: Long
    ): GatewayResult<ReasoningDefaultResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Profile reasoning unavailable"))

    /** Read-only connected-host roster and optional avatar; unsupported adapters fail closed. */
    suspend fun listBotProfiles(
        expectedOrigin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, expectedGeneration: Long
    ): GatewayResult<List<BotProfile>> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Bots unavailable"))

    suspend fun getBotAvatar(
        name: String, expectedOrigin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, expectedGeneration: Long
    ): BotAvatar? = null

    suspend fun listSessionModelOptionsScoped(runtime: String, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<List<SessionModelOption>> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Scoped picker unavailable"))
    suspend fun setSessionPickerScoped(runtime: String, option: SessionModelOption?, effort: String?, origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long): GatewayResult<SessionPickerSetResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Scoped picker unavailable"))

    suspend fun listSessionModelOptions(runtimeSessionId: String): GatewayResult<List<SessionModelOption>> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Model picker unsupported"))
    suspend fun setSessionModel(runtimeSessionId: String, option: SessionModelOption): GatewayResult<SessionPickerSetResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Model picker unsupported"))
    suspend fun setSessionReasoning(runtimeSessionId: String, effort: String): GatewayResult<SessionPickerSetResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Reasoning picker unsupported"))

    /**
     * Requests buffered events since a given sequence watermark.
     * Evaluates epoch matching and truncation, triggering history fallback when required.
     */
    suspend fun replayEventsSince(
        sessionId: String,
        lastSeen: Long,
        durableSessionId: String? = null
    ): GatewayResult<ReplayEventsResult>

    suspend fun replayEventsSinceScoped(
        sessionId: String, lastSeen: Long, durableSessionId: String?,
        origin: io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin, generation: Long,
        isCurrent: suspend () -> Boolean
    ): GatewayResult<ReplayEventsResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Scoped replay unavailable"))

    /**
     * Queries gateway capability flags.
     */
    suspend fun getCapabilities(): GatewayResult<GatewayCapabilities>

    /**
     * Sends keepalive heartbeat ping.
     */
    suspend fun ping(): GatewayResult<GatewayPingResult>

    /**
     * Sequence watermark queries and controls.
     */
    fun getLastSeenSeq(sessionId: String): Long?
    fun setLastSeenSeq(sessionId: String, seq: Long)
    fun clearWatermark(sessionId: String)

    /**
     * Marks that authoritative session history has been rehydrated (e.g. via session.resume),
     * clearing the awaiting-rehydration gate and permitting replay batches to be accepted.
     */
    fun markHistoryRehydrated(sessionId: String)

    /**
     * Active connection generation. Incremented monotonically on each connection attempt.
     */
    val connectionGeneration: Long get() = 0L

    /**
     * Suspends active heartbeat timer (e.g. on background suspension).
     */
    fun suspendHeartbeat() {}

    /**
     * Resumes active heartbeat timer if advertised by the gateway (e.g. on foregrounding).
     */
    fun resumeHeartbeat() {}

    /**
     * Observable terminal protocol/policy failure, if any.
     * When non-null and terminal, reconnect attempts must abort without retry.
     */
    val terminalFailure: StateFlow<GatewayFailure?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)
}

private val MutableEmptyApprovalInbox: StateFlow<io.github.anshuman2401.hermesmobile.data.approval.ApprovalInbox> =
    kotlinx.coroutines.flow.MutableStateFlow(io.github.anshuman2401.hermesmobile.data.approval.ApprovalInbox())
