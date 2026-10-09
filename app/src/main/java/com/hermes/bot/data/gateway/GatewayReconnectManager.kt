package com.hermes.bot.data.gateway

import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.data.diagnostics.DiagnosticEvent
import com.hermes.bot.data.diagnostics.DiagnosticRing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

sealed interface ReconnectState {
    data object Idle : ReconnectState
    data object Connecting : ReconnectState
    data class WaitingBackoff(val attempt: Int, val maxAttempts: Int, val delayMs: Long) : ReconnectState
    data object BudgetExhausted : ReconnectState
    data class Failed(val message: String) : ReconnectState
}

/**
 * Reconnection manager enforcing bounded foreground reconnect and single-use ticket lifecycles
 * per ARCH-001 (session-and-turn-state-machine.md) and RPR-004.
 *
 * Rules:
 * - Exponential backoff with full jitter
 * - Bounded attempt budget (defaults to 5)
 * - Pauses retries and suspends heartbeat while app is backgrounded
 * - Mints a fresh WebSocket ticket for every connection attempt
 * - Never reuses single-use tickets
 * - Terminal auth (401/403/4401) and capability policy (4403) failures abort retries immediately
 * - Serializes connect attempts with Mutex
 */
class GatewayReconnectManager(
    private val gatewayAdapter: GatewayProtocolAdapter,
    private val authRepository: AuthRepository,
    private val scope: CoroutineScope,
    private val maxAttempts: Int = 5,
    private val baseDelayMs: Long = 1000L,
    private val maxDelayMs: Long = 16000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnosticRing: DiagnosticRing? = null,
    private val recovery: com.hermes.bot.data.auth.ConnectionRecoveryCoordinator? = null
) {
    private val _reconnectState = MutableStateFlow<ReconnectState>(ReconnectState.Idle)
    val reconnectState: StateFlow<ReconnectState> = _reconnectState.asStateFlow()

    private val reconnectMutex = Mutex()
    private var currentAttempt = 0
    private var scheduledRetryJob: Job? = null
    private var isForeground = true
    private var configuredOrigin: CanonicalOrigin? = null

    init {
        scope.launch(ioDispatcher) {
            gatewayAdapter.connectionStatus.collect { status ->
                if (recovery != null) {
                    if (recovery.phase.value == com.hermes.bot.data.auth.RecoveryPhase.CONNECTING) return@collect
                    when (status) {
                        ConnectionStatus.CONNECTED -> { currentAttempt = 0; cancelScheduledRetry(); _reconnectState.value = ReconnectState.Idle }
                        ConnectionStatus.AUTHENTICATION_REQUIRED -> if (isForeground && configuredOrigin != null) reconnectNow()
                        ConnectionStatus.OFFLINE, ConnectionStatus.FAILED -> if (isForeground && configuredOrigin != null &&
                            _reconnectState.value !is ReconnectState.WaitingBackoff) scheduleNextReconnect()
                        else -> Unit
                    }
                    return@collect
                }
                val terminal = gatewayAdapter.terminalFailure.value
                if (recovery == null && terminal != null && terminal.isTerminal) {
                    cancelScheduledRetry()
                    currentAttempt = 0
                    _reconnectState.value = ReconnectState.Failed(formatTerminalMessage(terminal))
                    return@collect
                }

                when (status) {
                    ConnectionStatus.CONNECTED -> {
                        currentAttempt = 0
                        cancelScheduledRetry()
                        _reconnectState.value = ReconnectState.Idle
                    }

                    ConnectionStatus.AUTHENTICATION_REQUIRED -> {
                        // Terminal auth close/rejection: abort retry immediately per RPR-004
                        cancelScheduledRetry()
                        currentAttempt = 0
                        _reconnectState.value = ReconnectState.Failed("Authentication required (401/4401); reconnect aborted")
                    }

                    ConnectionStatus.FAILED -> {
                        if (isForeground && configuredOrigin != null && _reconnectState.value !is ReconnectState.WaitingBackoff) {
                            scheduleNextReconnect()
                        }
                    }

                    ConnectionStatus.OFFLINE -> {
                        if (isForeground && configuredOrigin != null && _reconnectState.value !is ReconnectState.WaitingBackoff) {
                            scheduleNextReconnect()
                        }
                    }

                    else -> {
                        // In-flight states
                    }
                }
            }
        }

        if (recovery != null) scope.launch(ioDispatcher) {
            authRepository.sessionRejections.collect {
                if (isForeground && configuredOrigin != null &&
                    recovery.phase.value != com.hermes.bot.data.auth.RecoveryPhase.CONNECTING) reconnectNow()
            }
        }
        scope.launch(ioDispatcher) {
            gatewayAdapter.terminalFailure.collect { terminal ->
                if (recovery == null && terminal != null && terminal.isTerminal) {
                    cancelScheduledRetry()
                    currentAttempt = 0
                    _reconnectState.value = ReconnectState.Failed(formatTerminalMessage(terminal))
                }
            }
        }
    }

    private fun formatTerminalMessage(failure: GatewayFailure): String {
        return when (failure) {
            is GatewayFailure.Transport -> {
                val codeStr = failure.statusCode?.let { " ($it)" } ?: ""
                "Terminal transport rejection$codeStr: ${failure.message}"
            }
            is GatewayFailure.IncompatibleProtocol -> "Terminal protocol incompatibility: ${failure.reason}"
            is GatewayFailure.Rpc -> "Terminal RPC rejection (${failure.code}): ${failure.message}"
            is GatewayFailure.AuthenticationRequired -> "Authentication required: ${failure.message}"
            else -> "Terminal failure: $failure"
        }
    }

    fun configureOrigin(origin: CanonicalOrigin) {
        configuredOrigin = origin
    }

    /** Compatibility entry point that refuses unchecked origins. */
    fun configureOrigin(origin: String) {
        CanonicalOrigin.parse(origin).getOrNull()?.let(::configureOrigin)
    }

    /**
     * BUG-012: clears the configured origin when the user explicitly disconnects.
     * Automatic reconnect must never fire without a user-established connection,
     * so sign-out/disconnect also stops any scheduled retry.
     */
    fun clearOrigin() {
        configuredOrigin = null
        recovery?.invalidate()
        cancelScheduledRetry()
    }

    fun onAppForegrounded() {
        diagnosticRing?.record(DiagnosticEvent.App.ForegroundEntered)
        isForeground = true
        gatewayAdapter.resumeHeartbeat()
        // Restart budget on user foregrounding
        currentAttempt = 0
        if (gatewayAdapter.connectionStatus.value != ConnectionStatus.CONNECTED && configuredOrigin != null) {
            reconnectNow()
        }
    }

    fun onAppBackgrounded() {
        diagnosticRing?.record(DiagnosticEvent.App.BackgroundEntered)
        isForeground = false
        if (recovery?.phase?.value == com.hermes.bot.data.auth.RecoveryPhase.CONNECTING) recovery.invalidate()
        gatewayAdapter.suspendHeartbeat()
        cancelScheduledRetry()
        _reconnectState.value = ReconnectState.Idle
    }

    fun reconnectNow() {
        cancelScheduledRetry()
        val origin = configuredOrigin ?: return
        scope.launch(ioDispatcher) {
            performConnectAttempt(origin)
        }
    }

    private fun scheduleNextReconnect() {
        val origin = configuredOrigin ?: return
        val terminal = gatewayAdapter.terminalFailure.value
        if (terminal != null && terminal.isTerminal && recovery == null) {
            cancelScheduledRetry()
            _reconnectState.value = ReconnectState.Failed(formatTerminalMessage(terminal))
            return
        }

        if (currentAttempt >= maxAttempts) {
            _reconnectState.value = ReconnectState.BudgetExhausted
            return
        }

        currentAttempt++
        // Full jitter backoff: random between 0 and min(maxDelay, baseDelay * 2^(attempt-1))
        val exponentialCap = (baseDelayMs * (1L shl (currentAttempt - 1))).coerceAtMost(maxDelayMs)
        val jitteredDelay = Random.nextLong(0, exponentialCap + 1)
        diagnosticRing?.record(DiagnosticEvent.WebSocket.ReconnectScheduled(attempt = currentAttempt, delayMs = jitteredDelay))

        _reconnectState.value = ReconnectState.WaitingBackoff(
            attempt = currentAttempt,
            maxAttempts = maxAttempts,
            delayMs = jitteredDelay
        )

        scheduledRetryJob?.cancel()
        scheduledRetryJob = scope.launch(ioDispatcher) {
            delay(jitteredDelay)
            if (isForeground) {
                performConnectAttempt(origin)
            }
        }
    }

    private suspend fun performConnectAttempt(origin: CanonicalOrigin) {
        reconnectMutex.withLock {
            if (configuredOrigin != origin || !isForeground) return@withLock
            _reconnectState.value = ReconnectState.Connecting
            if (recovery != null) {
                val connected = recovery.connect(origin, allowPassword = currentAttempt == 0 ||
                    (currentAttempt == 1 && recovery.phase.value == com.hermes.bot.data.auth.RecoveryPhase.READY))
                if (configuredOrigin != origin || !isForeground) return@withLock
                if (connected) { currentAttempt = 0; _reconnectState.value = ReconnectState.Idle }
                else if (recovery.phase.value == com.hermes.bot.data.auth.RecoveryPhase.OFFLINE) scheduleNextReconnect()
                else _reconnectState.value = ReconnectState.Failed("Sign in or connection settings required")
                return@withLock
            }

            // 1. Mint a fresh single-use ticket
            val ticketResult = authRepository.mintWebSocketTicket(origin)
            if (ticketResult.isFailure) {
                val ex = ticketResult.exceptionOrNull()
                val rawMsg = ex?.message ?: "Failed to mint WebSocket ticket"
                val safeMsg = GatewayRedactor.redactString(rawMsg)
                val causeClass = ex?.javaClass?.simpleName ?: "UnknownException"
                val isTerminalAuth = rawMsg.contains("401") || rawMsg.contains("403") ||
                    rawMsg.contains("unauthorized", ignoreCase = true) ||
                    rawMsg.contains("forbidden", ignoreCase = true)
                if (isTerminalAuth) {
                    cancelScheduledRetry()
                    _reconnectState.value = ReconnectState.Failed("Authentication rejected during ticket mint [$causeClass]: $safeMsg")
                    return
                }
                _reconnectState.value = ReconnectState.Failed("Ticket mint failure [$causeClass]: $safeMsg")
                scheduleNextReconnect()
                return
            }

            val freshTicket = ticketResult.getOrThrow()

            // 2. Connect gateway with fresh ticket
            val success = try {
                gatewayAdapter.connect(origin.originUrl, freshTicket)
            } catch (e: Exception) {
                false
            }

            if (success) {
                currentAttempt = 0
                _reconnectState.value = ReconnectState.Idle
            } else {
                val terminal = gatewayAdapter.terminalFailure.value
                val status = gatewayAdapter.connectionStatus.value
                if (status == ConnectionStatus.AUTHENTICATION_REQUIRED) {
                    cancelScheduledRetry()
                    _reconnectState.value = ReconnectState.Failed("Authentication required during connect (401/4401); reconnect aborted")
                } else if (terminal != null && terminal.isTerminal) {
                    cancelScheduledRetry()
                    _reconnectState.value = ReconnectState.Failed(formatTerminalMessage(terminal))
                } else {
                    scheduleNextReconnect()
                }
            }
        }
    }

    fun cancelScheduledRetry() {
        scheduledRetryJob?.cancel()
        scheduledRetryJob = null
    }
}
