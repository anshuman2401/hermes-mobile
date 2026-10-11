package com.hermes.bot.data.turn

import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.PromptSubmitResult

/** Captured, memory-only write authority. The repository binds owner, origin and socket generation. */
interface PromptSubmissionAccess {
    suspend fun isCurrent(): Boolean
    suspend fun submit(prompt: String): GatewayResult<PromptSubmitResult>
}
