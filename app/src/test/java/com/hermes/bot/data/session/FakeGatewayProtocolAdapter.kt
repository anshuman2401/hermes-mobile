package com.hermes.bot.data.session

import com.hermes.bot.data.gateway.GatewayCapabilities
import com.hermes.bot.data.gateway.AttachFileResult
import com.hermes.bot.data.gateway.AttachImageResult
import com.hermes.bot.data.gateway.DetachImageResult
import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.GatewayPingResult
import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.GatewayTicket
import com.hermes.bot.data.gateway.HistoryFallbackSignal
import com.hermes.bot.data.gateway.InterruptSessionResult
import com.hermes.bot.data.gateway.PromptSubmitResult
import com.hermes.bot.data.gateway.ReplayEventsResult
import com.hermes.bot.data.gateway.SessionCreateResult
import com.hermes.bot.data.gateway.SessionListResult
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Deterministic test double for GatewayProtocolAdapter simulating session operations.
 */
class FakeGatewayProtocolAdapter : GatewayProtocolAdapter {
    // Explicitly unsupported unless an approval-specific test supplies its typed seam.
    override val approvalInbox = MutableStateFlow(com.hermes.bot.data.approval.ApprovalInbox())


    override val connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTED)
    override var connectionGeneration: Long = 0L
    var scopedOrigin: String? = null
    override fun isScopedConnectionCurrent(origin: com.hermes.bot.data.origin.CanonicalOrigin, generation: Long) =
        scopedOrigin == origin.originUrl && generation == connectionGeneration && connectionStatus.value == ConnectionStatus.CONNECTED
    var onListSessions: (suspend () -> Unit)? = null
    var onListActiveSessions: (suspend () -> Unit)? = null
    var onSubmitPrompt: (suspend () -> Unit)? = null
    var listSessionsCallCount = 0
    var submitPromptCallCount = 0
    val eventFlow = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
    override val events: Flow<GatewayEvent> = eventFlow
    override val historyFallbackSignals: Flow<HistoryFallbackSignal> = MutableSharedFlow()
    override var currentReplayEpoch: String? = "test-epoch-1"

    var listSessionsResult: GatewayResult<SessionListResult> = GatewayResult.Success(
        SessionListResult(
            sessions = listOf(
                SessionSummary("sess-01", "Session 1", "Preview 1", 1000.0, 2, "android"),
                SessionSummary("sess-02", "Session 2", "Preview 2", 2000.0, 5, "tui")
            )
        )
    )

    var createSessionResult: GatewayResult<SessionCreateResult> = GatewayResult.Success(
        SessionCreateResult(
            runtimeSessionId = "runtime-sid-created-1",
            durableSessionId = "durable-key-created-1",
            messageCount = 0,
            messages = emptyList()
        )
    )

    // CHAT-015: sequenced create results consumed one per createSession() call
    // before falling back to [createSessionResult] — lets tests script a
    // first-call failure plus a distinct retry outcome.
    val createSessionResults = ArrayDeque<GatewayResult<SessionCreateResult>>()

    var resumeSessionResult: GatewayResult<SessionResumeResult> = GatewayResult.Success(
        SessionResumeResult(
            runtimeSessionId = "runtime-sid-resumed-1",
            durableSessionId = "durable-key-resumed-1",
            messages = emptyList(),
            messageCount = 0
        )
    )

    // CHAT-011 S3 (TEST-15): record every create/resume call's arguments so
    // repository wiring tests can assert which overrides were threaded.
    data class CreateCall(
        val title: String?,
        val source: String,
        val closeOnDisconnect: Boolean,
        val profile: String?,
        val model: String?,
        val reasoningEffort: String?
    )

    data class ResumeCall(val sessionId: String, val profile: String?)

    val createCalls = mutableListOf<CreateCall>()
    val resumeCalls = mutableListOf<ResumeCall>()

    val rehydratedSessions = mutableListOf<String>()

    // F007-S3 test seams: replay-watermark seed behavior on resume. Mirrors the
    // production adapter's commit semantics: a clean (non-truncated) batch commits
    // each returned event's seq into the watermark; a truncated batch or RPC
    // failure clears it — never moving a watermark backwards.
    val watermarkBySession = HashMap<String, Long>()
    val replayCalls = mutableListOf<ReplayCall>()
    var replayEventsResultFactory: (ReplayCall) -> GatewayResult<ReplayEventsResult> = {
        GatewayResult.Success(
            ReplayEventsResult(events = emptyList(), count = 0, epoch = null, latestSeq = 0, truncated = false)
        )
    }

    data class ReplayCall(val sessionId: String, val lastSeen: Long, val durableSessionId: String?)

    override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean = true
    override fun disconnect() {}

    var listActiveSessionsResult: GatewayResult<com.hermes.bot.data.gateway.ActiveSessionListResult> = GatewayResult.Success(
        com.hermes.bot.data.gateway.ActiveSessionListResult(sessions = emptyList())
    )

    override suspend fun listSessions(limit: Int): GatewayResult<SessionListResult> {
        listSessionsCallCount++
        val captured = listSessionsResult
        onListSessions?.invoke()
        return captured
    }
    override suspend fun listActiveSessions(): GatewayResult<com.hermes.bot.data.gateway.ActiveSessionListResult> {
        val captured = listActiveSessionsResult
        onListActiveSessions?.invoke()
        return captured
    }
    override suspend fun createSession(title: String?, source: String, closeOnDisconnect: Boolean, profile: String?, model: String?, reasoningEffort: String?): GatewayResult<SessionCreateResult> {
        createCalls.add(CreateCall(title, source, closeOnDisconnect, profile, model, reasoningEffort))
        return if (createSessionResults.isNotEmpty()) createSessionResults.removeFirst() else createSessionResult
    }
    override suspend fun resumeSession(sessionId: String, profile: String?): GatewayResult<SessionResumeResult> {
        resumeCalls.add(ResumeCall(sessionId, profile))
        return resumeSessionResult
    }

    var submitPromptResult: GatewayResult<PromptSubmitResult> =
        GatewayResult.Success(PromptSubmitResult(status = "streaming"))
    var lastSubmittedSessionId: String? = null
    var lastSubmittedText: String? = null

    override suspend fun submitPrompt(sessionId: String, text: String): GatewayResult<PromptSubmitResult> {
        submitPromptCallCount++
        lastSubmittedSessionId = sessionId
        lastSubmittedText = text
        onSubmitPrompt?.invoke()
        return submitPromptResult
    }

    var attachImageResult: GatewayResult<AttachImageResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Image attachment result not configured"))
    var attachFileResult: GatewayResult<AttachFileResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("File attachment result not configured"))
    var detachImageResult: GatewayResult<DetachImageResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected("Image detach result not configured"))

    data class AttachImageCall(val sessionId: String, val contentBase64: String, val filename: String?, val ext: String?)
    val attachImageCalls = mutableListOf<AttachImageCall>()
    data class AttachFileCall(val sessionId: String, val dataUrl: String, val name: String)
    val attachFileCalls = mutableListOf<AttachFileCall>()
    val detachImageCalls = mutableListOf<Pair<String, String>>()

    override suspend fun attachImageBytes(sessionId: String, contentBase64: String, filename: String?, ext: String?): GatewayResult<AttachImageResult> {
        attachImageCalls += AttachImageCall(sessionId, contentBase64, filename, ext)
        return attachImageResult
    }

    override suspend fun attachFile(sessionId: String, dataUrl: String, name: String): GatewayResult<AttachFileResult> {
        attachFileCalls += AttachFileCall(sessionId, dataUrl, name)
        return attachFileResult
    }

    override suspend fun detachImage(sessionId: String, path: String): GatewayResult<DetachImageResult> {
        detachImageCalls += sessionId to path
        return detachImageResult
    }

    var interruptResult: GatewayResult<InterruptSessionResult> =
        GatewayResult.Success(InterruptSessionResult(ok = true))
    var interruptException: Exception? = null
    val interruptedSessions = mutableListOf<String>()

    override suspend fun interruptSession(sessionId: String): GatewayResult<InterruptSessionResult> {
        interruptedSessions.add(sessionId)
        interruptException?.let { throw it }
        return interruptResult
    }

    override suspend fun replayEventsSince(sessionId: String, lastSeen: Long, durableSessionId: String?): GatewayResult<ReplayEventsResult> {
        val call = ReplayCall(sessionId, lastSeen, durableSessionId)
        replayCalls.add(call)
        return replayEventsResultFactory(call).also { result ->
            // Mirror the production adapter: commit per-event watermark on clean
            // batches (reducer reduceReplayBatch with commitWatermark=true through
            // emitEventObservably); clear on truncated batch or RPC failure.
            when (result) {
                is GatewayResult.Success -> {
                    if (result.value.truncated) {
                        watermarkBySession.remove(sessionId)
                    } else {
                        result.value.events.forEach { event ->
                            val seq = event.seq
                            if (seq != null && seq > 0L) {
                                watermarkBySession[sessionId] = maxOf(watermarkBySession[sessionId] ?: 0L, seq)
                            }
                        }
                    }
                }
                is GatewayResult.Failure -> watermarkBySession.remove(sessionId)
            }
        }
    }

    override suspend fun getCapabilities(): GatewayResult<GatewayCapabilities> =
        GatewayResult.Success(GatewayCapabilities(perSessionExclusiveSubmit = true))

    override suspend fun ping(): GatewayResult<GatewayPingResult> =
        GatewayResult.Success(GatewayPingResult(ok = true))

    override fun getLastSeenSeq(sessionId: String): Long? = watermarkBySession[sessionId]
    override fun setLastSeenSeq(sessionId: String, seq: Long) {
        watermarkBySession[sessionId] = seq
    }
    override fun clearWatermark(sessionId: String) {
        watermarkBySession.remove(sessionId)
    }

    override fun markHistoryRehydrated(sessionId: String) {
        rehydratedSessions.add(sessionId)
    }
}
