package com.hermes.bot.data.auth

import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.GatewayTicket
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Shared owner for startup and reconnect. Never retries application requests or prompts. */
enum class RecoveryPhase { IDLE, CONNECTING, READY, SIGN_IN_REQUIRED, OFFLINE, POLICY_FAILURE }
class ConnectionRecoveryCoordinator(
    private val auth: AuthRepository,
    private val gateway: GatewayProtocolAdapter
) {
    private val mutex = Mutex()
    private val mutablePhase = MutableStateFlow(RecoveryPhase.IDLE)
    val phase = mutablePhase.asStateFlow()
    private val generation = AtomicLong()
    @Volatile private var completedAttempt = 0L
    private var lastOrigin: CanonicalOrigin? = null
    private var lastSucceeded = false
    private var connectedOrigin: CanonicalOrigin? = null

    fun invalidate() {
        generation.incrementAndGet()
        auth.invalidatePendingAuthentication()
        mutablePhase.value = RecoveryPhase.IDLE
    }

    suspend fun connect(origin: CanonicalOrigin, allowPassword: Boolean = true, retryPolicy: Boolean = false): Boolean {
        val owner = generation.get()
        val observedAttempt = completedAttempt
        return mutex.withLock {
            if (owner != generation.get()) return@withLock false
            // Waiters share failures as well as successes; a mutex alone would repeat failed passwords.
            if (observedAttempt != completedAttempt && lastOrigin == origin) return@withLock lastSucceeded
            var connected = false
            try {
                if (connectedOrigin == origin && gateway.connectionStatus.value == ConnectionStatus.CONNECTED &&
                    auth.hasValidSession(origin)) {
                    mutablePhase.value = RecoveryPhase.READY
                    connected = true
                } else connected = performConnect(origin, owner, allowPassword, retryPolicy)
                connected
            } catch (cancelled: CancellationException) {
                if (owner == generation.get()) mutablePhase.value = RecoveryPhase.OFFLINE
                throw cancelled
            } catch (_: Exception) {
                if (owner == generation.get()) mutablePhase.value = RecoveryPhase.OFFLINE
                false
            } finally {
                lastOrigin = origin
                lastSucceeded = connected && owner == generation.get()
                completedAttempt++
            }
        }
    }

    private suspend fun performConnect(origin: CanonicalOrigin, owner: Long, allowPassword: Boolean, retryPolicy: Boolean): Boolean {
        fun current() = owner == generation.get()
        fun policyFailure(): Boolean {
            val terminal = gateway.terminalFailure.value
            return (terminal is GatewayFailure.Transport && terminal.statusCode == 4403) ||
                terminal is GatewayFailure.IncompatibleProtocol
        }
        if (policyFailure() && (!retryPolicy || !auth.hasValidSession(origin))) {
            mutablePhase.value = RecoveryPhase.POLICY_FAILURE
            return false
        }
        mutablePhase.value = RecoveryPhase.CONNECTING
        if (gateway.connectionStatus.value == ConnectionStatus.CONNECTED) gateway.disconnect()
        var passwordUsed = false
        suspend fun recover(): Boolean {
            if (!allowPassword || passwordUsed) {
                mutablePhase.value = if (!allowPassword) RecoveryPhase.OFFLINE else RecoveryPhase.SIGN_IN_REQUIRED
                return false
            }
            passwordUsed = true
            val result = auth.recoverSession(origin)
            if (!current()) return false
            if (result.isSuccess) return true
            mutablePhase.value = classify(result.exceptionOrNull())
            return false
        }
        if (!auth.hasValidSession(origin) && !recover()) return false
        if (!current()) return false
        var ticket = auth.mintWebSocketTicket(origin)
        if (ticket.exceptionOrNull() is AuthSessionRejectedException && recover()) {
            if (!current()) return false
            ticket = auth.mintWebSocketTicket(origin)
        }
        suspend fun open(fresh: Result<GatewayTicket>): Boolean {
            if (!current()) return false
            if (fresh.isFailure) {
                if (mutablePhase.value == RecoveryPhase.CONNECTING) mutablePhase.value = classify(fresh.exceptionOrNull())
                return false
            }
            val connected = gateway.connect(origin.originUrl, fresh.getOrThrow())
            if (!current()) { gateway.disconnect(); return false }
            return connected
        }
        var connected = open(ticket)
        if (!current()) return false
        if (!connected && mutablePhase.value == RecoveryPhase.CONNECTING && !policyFailure() &&
            gateway.connectionStatus.value == ConnectionStatus.AUTHENTICATION_REQUIRED && recover()) {
            if (!current()) return false
            connected = open(auth.mintWebSocketTicket(origin))
        }
        if (!current()) return false
        if (connected) {
            connectedOrigin = origin
            mutablePhase.value = RecoveryPhase.READY
        } else if (mutablePhase.value == RecoveryPhase.CONNECTING) {
            mutablePhase.value = when {
                policyFailure() -> RecoveryPhase.POLICY_FAILURE
                gateway.connectionStatus.value == ConnectionStatus.AUTHENTICATION_REQUIRED -> RecoveryPhase.SIGN_IN_REQUIRED
                else -> RecoveryPhase.OFFLINE
            }
        }
        return connected
    }

    private fun classify(error: Throwable?): RecoveryPhase = when {
        error is AuthSessionRejectedException || error is SavedPasswordRejectedException -> RecoveryPhase.SIGN_IN_REQUIRED
        error is AuthOriginPolicyRejectedException || error?.cause is AuthOriginPolicyRejectedException -> RecoveryPhase.POLICY_FAILURE
        else -> RecoveryPhase.OFFLINE
    }
}
