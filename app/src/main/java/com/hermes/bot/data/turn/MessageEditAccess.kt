package com.hermes.bot.data.turn

import com.hermes.bot.data.gateway.*

/** Captured authenticated session authority; never reconstructed from persisted edit metadata. */
interface MessageEditAccess {
    val ownerKey: String
    suspend fun isCurrent(): Boolean
    suspend fun snapshot(): GatewayResult<SessionResumeResult>
    suspend fun interrupt(): GatewayResult<InterruptSessionResult>
    suspend fun submit(text: String, rowId: Long): GatewayResult<PromptSubmitResult>
}

enum class EditPhase { CHECKING, STOPPING, UPDATING }
sealed interface MessageEditOutcome {
    data class Admitted(val pendingId: String, val rowId: Long?) : MessageEditOutcome { override fun toString() = "EditAdmitted([REDACTED])" }
    data class Unknown(val pendingId: String) : MessageEditOutcome { override fun toString() = "EditUnknown([REDACTED])" }
    data class Refused(val message: String) : MessageEditOutcome
}
