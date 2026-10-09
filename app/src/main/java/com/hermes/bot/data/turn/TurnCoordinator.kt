package com.hermes.bot.data.turn

import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.PendingTurnRecord
import com.hermes.bot.data.local.PendingTurnState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import com.hermes.bot.data.session.SessionRepository

/**
 * Turn state lifecycle according to session-and-turn-state-machine.md.
 */
enum class TurnLifecycleState {
    IDLE,
    COMPOSING,
    PREPARED,
    SUBMITTING,
    ADMITTED,
    STREAMING,
    RECONCILING,
    SUBMISSION_UNKNOWN,
    REQUIRES_USER_DECISION,
    TERMINAL_COMPLETE,
    TERMINAL_INTERRUPTED,
    TERMINAL_FAILED
}

sealed interface TurnSubmissionOutcome {
    data class Admitted(val pendingTurnId: String, val prompt: String, val userRowId: Long? = null) : TurnSubmissionOutcome
    data class AmbiguousUnknown(val pendingTurnId: String, val prompt: String, val reason: String) : TurnSubmissionOutcome
    data class RejectedBusy(val reason: String) : TurnSubmissionOutcome
    data class ValidationFailed(val reason: String) : TurnSubmissionOutcome
}

sealed interface ReconciliationOutcome {
    data class ProvenByHistory(val pendingTurnId: String, val matchedMessage: SessionMessage) : ReconciliationOutcome
    data class Unproven(val pendingTurnId: String, val reason: String) : ReconciliationOutcome
    data object NoPendingTurn : ReconciliationOutcome
}

/**
 * Coordinator enforcing prompt-submission mutual exclusion, single-flight writes,
 * outbox ledger audit trails, and history reconciliation per ARCH-001 / BLD-007.
 *
 * Invariant P0: At most ONE write attempt per pendingTurnId. Any write ambiguity
 * strictly transitions the turn to UNKNOWN and prohibits automatic resends.
 */
class TurnCoordinator(
    private val gatewayAdapter: GatewayProtocolAdapter,
    private val pendingTurnLedger: PendingTurnLedger,
    private val sessionRepository: SessionRepository? = null
) {
    private val submitMutex = Mutex()

    /**
     * Executes a single-flight prompt submission.
     * Enforces the outbox policy: PREPARED -> WRITE_ATTEMPTED -> CONFIRMED_ADMITTED or UNKNOWN.
     */
    suspend fun submitPrompt(
        storedSessionId: String,
        runtimeSessionId: String,
        prompt: String
    ): TurnSubmissionOutcome = submitMutex.withLock {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return TurnSubmissionOutcome.ValidationFailed("Prompt cannot be empty")
        }

        // Capture the original binding before any ledger IO can suspend or switch its owner.
        val activityOwner = try {
            sessionRepository?.captureSendActivityOwner(storedSessionId, runtimeSessionId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

        // 1. Prepare turn in pending ledger
        val turnRecord = pendingTurnLedger.prepareTurn(
            storedSessionId = storedSessionId,
            promptContent = trimmedPrompt,
            runtimeSessionId = runtimeSessionId
        )
        val turnId = turnRecord.pendingTurnId

        // 2. Mark write attempted BEFORE network write
        pendingTurnLedger.markWriteAttempted(turnId)

        if (activityOwner != null) {
            try { sessionRepository?.recordSendActivity(activityOwner) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Metadata cannot prevent the actual prompt write. */ }
        }

        // 3. Issue prompt.submit frame
        val result = try {
            gatewayAdapter.submitPrompt(runtimeSessionId, trimmedPrompt)
        } catch (e: Exception) {
            GatewayResult.Failure(
                com.hermes.bot.data.gateway.GatewayFailure.Transport(
                    e.message ?: "Transport error during prompt write"
                )
            )
        }

        return when (result) {
            is GatewayResult.Success -> {
                // Confirmed admission
                pendingTurnLedger.markConfirmedAdmitted(turnId)
                TurnSubmissionOutcome.Admitted(turnId, trimmedPrompt, result.value.userRowId)
            }

            is GatewayResult.Failure -> {
                // Ambiguous or failed write: mark UNKNOWN and strictly forbid auto-send
                pendingTurnLedger.markUnknown(turnId)
                val failureMessage = when (val f = result.failure) {
                    is com.hermes.bot.data.gateway.GatewayFailure.Rpc -> f.message
                    is com.hermes.bot.data.gateway.GatewayFailure.Transport -> f.message
                    is com.hermes.bot.data.gateway.GatewayFailure.IncompatibleProtocol -> f.reason
                    is com.hermes.bot.data.gateway.GatewayFailure.SubmissionUnknown -> f.message
                    is com.hermes.bot.data.gateway.GatewayFailure.NotConnected -> f.message
                    is com.hermes.bot.data.gateway.GatewayFailure.AuthenticationRequired -> f.message
                }
                TurnSubmissionOutcome.AmbiguousUnknown(
                    pendingTurnId = turnId,
                    prompt = trimmedPrompt,
                    reason = failureMessage
                )
            }
        }
    }

    /**
     * Reconciles any active or ambiguous pending turns against server-authoritative history.
     * If the prompt content appears in server messages, the turn is proven admitted and resolved.
     * If not found, the turn remains unproven (UNKNOWN).
     */
    suspend fun reconcilePendingTurnWithHistory(
        storedSessionId: String,
        serverMessages: List<SessionMessage>
    ): ReconciliationOutcome {
        val pendingTurns = pendingTurnLedger.getAllPendingTurns()
            .filter { it.storedSessionId == storedSessionId }
            .filter { it.state == PendingTurnState.UNKNOWN || it.state == PendingTurnState.WRITE_ATTEMPTED || it.state == PendingTurnState.CONFIRMED_ADMITTED }

        if (pendingTurns.isEmpty()) {
            return ReconciliationOutcome.NoPendingTurn
        }

        val targetTurn = pendingTurns.maxByOrNull { it.createdAt } ?: return ReconciliationOutcome.NoPendingTurn

        if (targetTurn.edit != null) return ReconciliationOutcome.Unproven(targetTurn.pendingTurnId,
            "Edit outcome requires history review")

        // Look for matching user prompt in authoritative server transcript
        val matchedUserMessage = serverMessages.find { msg ->
            msg.role.equals("user", ignoreCase = true) &&
                msg.content.trim() == targetTurn.promptContent.trim()
        }

        // Matching text alone cannot distinguish this uncertain write from an older
        // identical user message. Only an already-confirmed admission may be closed
        // by history without a host-provided per-attempt correlation identity.
        if (targetTurn.state != PendingTurnState.CONFIRMED_ADMITTED) {
            return ReconciliationOutcome.Unproven(
                pendingTurnId = targetTurn.pendingTurnId,
                reason = if (matchedUserMessage != null) "Matching text cannot identify this write"
                    else "Prompt was not found in server history"
            )
        }

        return if (matchedUserMessage != null) {
            // Proven admitted and incorporated into server transcript!
            pendingTurnLedger.markResolved(targetTurn.pendingTurnId)
            ReconciliationOutcome.ProvenByHistory(targetTurn.pendingTurnId, matchedUserMessage)
        } else {
            // Not proven; must remain visible as unknown
            ReconciliationOutcome.Unproven(
                pendingTurnId = targetTurn.pendingTurnId,
                reason = "Prompt was not found in server history"
            )
        }
    }

    /**
     * Explicit user-initiated retry for an ambiguous prompt.
     * Strictly creates a new attempt with a fresh pendingTurnId; never reuses the old attempt.
     */
    suspend fun retryAmbiguousTurn(
        oldPendingTurnId: String,
        runtimeSessionId: String
    ): TurnSubmissionOutcome {
        val newRecord = pendingTurnLedger.createNewAttemptFromAmbiguous(oldPendingTurnId)
            ?: return TurnSubmissionOutcome.ValidationFailed("Old pending turn not found: $oldPendingTurnId")

        return submitPrompt(
            storedSessionId = newRecord.storedSessionId,
            runtimeSessionId = runtimeSessionId,
            prompt = newRecord.promptContent
        )
    }

    suspend fun pendingEditForReview(storedId: String, runtimeId: String): PendingTurnRecord? {
        val access = captureEditAccess(storedId, runtimeId) ?: return null
        return pendingTurnLedger.getAllPendingTurns().filter { it.storedSessionId == storedId &&
            it.edit?.ownerKey == access.ownerKey && it.state in setOf(PendingTurnState.UNKNOWN, PendingTurnState.WRITE_ATTEMPTED) }
            .maxByOrNull { it.createdAt }?.takeIf { access.isCurrent() }
    }

    suspend fun captureEditAccess(storedId: String, runtimeId: String): MessageEditAccess? =
        sessionRepository?.captureMessageEditAccess(storedId, runtimeId)

    suspend fun editMessage(storedId: String, runtimeId: String, rowId: Long, text: String,
        access: MessageEditAccess, current: () -> Boolean = { true }, phase: (EditPhase) -> Unit = {}): MessageEditOutcome =
        MessageEditSubmission(pendingTurnLedger, submitMutex).submit(storedId, runtimeId, rowId, text, access, current, phase)

    suspend fun getPendingTurn(pendingTurnId: String): PendingTurnRecord? {
        return pendingTurnLedger.getPendingTurn(pendingTurnId)
    }

    suspend fun markResolved(pendingTurnId: String) {
        pendingTurnLedger.markResolved(pendingTurnId)
    }
}
