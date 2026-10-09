package com.hermes.bot.data.turn

import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.gateway.GatewayCapabilities
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
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fault-injection test harness for verifying R-01 through R-12 resilience
 * per specs/architecture/test_strategy.md.
 */
class ReconnectFaultInjectionHarness : GatewayProtocolAdapter {

    override val connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTED)
    val eventFlow = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 128)
    override val events: Flow<GatewayEvent> = eventFlow

    val fallbackSignalFlow = MutableSharedFlow<HistoryFallbackSignal>(extraBufferCapacity = 16)
    override val historyFallbackSignals: Flow<HistoryFallbackSignal> = fallbackSignalFlow

    override var currentReplayEpoch: String? = "epoch-100"

    // Prompt submit tracking
    val promptSubmitCount = AtomicInteger(0)
    val submittedPrompts = mutableListOf<Pair<String, String>>()

    // Injected behaviors
    var failConnect: Boolean = false
    var failPromptSubmitWith: GatewayFailure? = null
    var promptSubmitException: Exception? = null

    // Session states
    var serverMessages = mutableListOf<SessionMessage>()
    var durableSessionId = "durable-sess-1"
    var runtimeSessionId = "runtime-sid-1"

    override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean {
        if (failConnect) {
            connectionStatus.value = ConnectionStatus.FAILED
            return false
        }
        connectionStatus.value = ConnectionStatus.CONNECTED
        return true
    }

    override fun disconnect() {
        connectionStatus.value = ConnectionStatus.OFFLINE
    }

    override suspend fun listSessions(limit: Int): GatewayResult<SessionListResult> {
        return GatewayResult.Success(SessionListResult(emptyList()))
    }

    override suspend fun listActiveSessions(): GatewayResult<com.hermes.bot.data.gateway.ActiveSessionListResult> {
        return GatewayResult.Success(com.hermes.bot.data.gateway.ActiveSessionListResult(emptyList()))
    }

    override suspend fun createSession(title: String?, source: String, closeOnDisconnect: Boolean, profile: String?, model: String?, reasoningEffort: String?): GatewayResult<SessionCreateResult> {
        return GatewayResult.Success(
            SessionCreateResult(
                runtimeSessionId = runtimeSessionId,
                durableSessionId = durableSessionId,
                messageCount = serverMessages.size,
                messages = serverMessages
            )
        )
    }

    override suspend fun resumeSession(sessionId: String, profile: String?): GatewayResult<SessionResumeResult> {
        return GatewayResult.Success(
            SessionResumeResult(
                runtimeSessionId = runtimeSessionId,
                durableSessionId = durableSessionId,
                messageCount = serverMessages.size,
                messages = serverMessages
            )
        )
    }

    override suspend fun submitPrompt(sessionId: String, text: String): GatewayResult<PromptSubmitResult> {
        promptSubmitCount.incrementAndGet()
        submittedPrompts.add(sessionId to text)

        promptSubmitException?.let { throw it }

        failPromptSubmitWith?.let { failure ->
            return GatewayResult.Failure(failure)
        }

        return GatewayResult.Success(PromptSubmitResult(status = "streaming"))
    }

    override suspend fun interruptSession(sessionId: String): GatewayResult<InterruptSessionResult> =
        GatewayResult.Failure(GatewayFailure.NotConnected())

    override suspend fun replayEventsSince(sessionId: String, lastSeen: Long, durableSessionId: String?): GatewayResult<ReplayEventsResult> {
        return GatewayResult.Success(
            ReplayEventsResult(
                count = 0,
                epoch = currentReplayEpoch,
                latestSeq = lastSeen,
                truncated = false,
                events = emptyList()
            )
        )
    }

    override suspend fun getCapabilities(): GatewayResult<GatewayCapabilities> {
        return GatewayResult.Success(GatewayCapabilities(perSessionExclusiveSubmit = true))
    }

    override suspend fun ping(): GatewayResult<GatewayPingResult> {
        return GatewayResult.Success(GatewayPingResult(ok = true))
    }

    override fun getLastSeenSeq(sessionId: String): Long? = null
    override fun setLastSeenSeq(sessionId: String, seq: Long) {}
    override fun clearWatermark(sessionId: String) {}
    override fun markHistoryRehydrated(sessionId: String) {}
}
