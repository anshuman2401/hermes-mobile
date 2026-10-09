package io.github.anshuman2401.hermesmobile.data.turn

import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.local.PendingEditMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Preparation may read repeatedly; each ledger attempt can perform only one write. */
internal class MessageEditSubmission(private val ledger: PendingTurnLedger, private val mutex: Mutex) {
    suspend fun submit(storedId: String, runtimeId: String, rowId: Long, text: String,
        access: MessageEditAccess, current: () -> Boolean, phase: (EditPhase) -> Unit): MessageEditOutcome {
        if (!mutex.tryLock()) return MessageEditOutcome.Refused("Another message is being sent.")
        var attempted: String? = null
        try {
            if (!MessageEditPolicy.plainText(text) || rowId <= 0)
                return MessageEditOutcome.Refused("Only complete plain-text messages can be edited.")
            suspend fun owned() = current() && access.isCurrent()
            if (!owned()) return MessageEditOutcome.Refused("Refresh this conversation before editing.")
            if (ledger.getAllPendingTurns().any { it.storedSessionId == storedId && it.edit?.ownerKey == access.ownerKey &&
                    it.state in setOf(io.github.anshuman2401.hermesmobile.data.local.PendingTurnState.UNKNOWN, io.github.anshuman2401.hermesmobile.data.local.PendingTurnState.WRITE_ATTEMPTED) })
                return MessageEditOutcome.Refused("Review the earlier uncertain edit before trying again.")
            phase(EditPhase.CHECKING)
            var snapshot = (access.snapshot() as? GatewayResult.Success)?.value
                ?: return MessageEditOutcome.Refused("Couldn't check conversation history.")
            if (!owned()) return MessageEditOutcome.Refused("Conversation changed. Review the edit again.")
            if (snapshot.messages.none { it.role == "user" && (it.rowId ?: it.editable?.rowId) == rowId })
                return MessageEditOutcome.Refused("This message is no longer available to edit. Refresh the conversation.")
            if (snapshot.activityEvidence == ResumeActivityEvidence.UNKNOWN)
                return MessageEditOutcome.Refused("Couldn't verify whether the response has stopped.")
            if (snapshot.activityEvidence == ResumeActivityEvidence.RUNNING) {
                phase(EditPhase.STOPPING)
                if ((access.interrupt() as? GatewayResult.Success)?.value?.ok != true || !owned())
                    return MessageEditOutcome.Refused("Couldn't stop the response. Your edit is still here.")
                val idle = withTimeoutOrNull(10_000) {
                    while (true) {
                        delay(500)
                        if (!owned()) return@withTimeoutOrNull null
                        val next = (access.snapshot() as? GatewayResult.Success)?.value ?: return@withTimeoutOrNull null
                        if (next.activityEvidence == ResumeActivityEvidence.NON_RUNNING) return@withTimeoutOrNull next
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
                snapshot = idle ?: return MessageEditOutcome.Refused("Response has not stopped. Try again after checking history.")
            }
            if (!owned()) return MessageEditOutcome.Refused("Conversation changed. Review the edit again.")
            val target = snapshot.messages.singleOrNull { it.role == "user" && (it.rowId ?: it.editable?.rowId) == rowId }
                ?: return MessageEditOutcome.Refused("This message is no longer available to edit. Refresh the conversation.")
            if (target.content.trim() == text.trim()) return MessageEditOutcome.Refused("Change the message before resending.")
            val pending = ledger.prepareTurn(storedId, text, runtimeId, PendingEditMetadata(rowId, access.ownerKey))
            if (!owned()) { ledger.markResolved(pending.pendingTurnId); return MessageEditOutcome.Refused("Conversation changed.") }
            phase(EditPhase.UPDATING)
            ledger.markWriteAttempted(pending.pendingTurnId)
            attempted = pending.pendingTurnId
            if (!owned()) { ledger.markResolved(pending.pendingTurnId); attempted = null; return MessageEditOutcome.Refused("Conversation changed.") }
            return when (val result = access.submit(text, rowId)) {
                is GatewayResult.Success -> {
                    ledger.markConfirmedAdmitted(pending.pendingTurnId)
                    MessageEditOutcome.Admitted(pending.pendingTurnId, result.value.userRowId)
                }
                is GatewayResult.Failure -> {
                    val code = (result.failure as? GatewayFailure.Rpc)?.code
                    // Only errors known to precede the cut prove rejection. Others may follow a persisted rewind.
                    if (code in setOf(4009, 4018, 4028, 4029, 4030, -32601, -32602)) {
                        ledger.markResolved(pending.pendingTurnId)
                        MessageEditOutcome.Refused("Edit was not accepted. Check history and try again.")
                    } else {
                        ledger.markUnknown(pending.pendingTurnId)
                        MessageEditOutcome.Unknown(pending.pendingTurnId)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            attempted?.let { id -> withContext(NonCancellable) { ledger.markUnknown(id) } }
            throw cancelled
        } catch (_: Exception) {
            val id = attempted
            if (id != null) {
                withContext(NonCancellable) { ledger.markUnknown(id) }
                return MessageEditOutcome.Unknown(id)
            }
            return MessageEditOutcome.Refused("Couldn't prepare the edit. Your message has not been resent.")
        } finally { mutex.unlock() }
    }
}
