package com.hermes.bot.data.session

import com.hermes.bot.data.gateway.*

/** Captured authority; implementations must revalidate before and after every suspension. */
interface SessionPickerAccess {
    suspend fun isCurrent(): Boolean
    /** Same authenticated workspace and runtime, allowing a replaced socket. */
    suspend fun isSameContext(): Boolean = isCurrent()
    suspend fun options(): GatewayResult<List<SessionModelOption>>
    suspend fun setModel(option: SessionModelOption): GatewayResult<SessionPickerSetResult>
    suspend fun setReasoning(effort: String): GatewayResult<SessionPickerSetResult>
}
