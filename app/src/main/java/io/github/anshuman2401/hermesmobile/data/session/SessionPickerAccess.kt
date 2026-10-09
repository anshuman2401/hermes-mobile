package io.github.anshuman2401.hermesmobile.data.session

import io.github.anshuman2401.hermesmobile.data.gateway.*

/** Captured authority; implementations must revalidate before and after every suspension. */
interface SessionPickerAccess {
    suspend fun isCurrent(): Boolean
    /** Same authenticated workspace and runtime, allowing a replaced socket. */
    suspend fun isSameContext(): Boolean = isCurrent()
    suspend fun options(): GatewayResult<List<SessionModelOption>>
    suspend fun setModel(option: SessionModelOption): GatewayResult<SessionPickerSetResult>
    suspend fun setReasoning(effort: String): GatewayResult<SessionPickerSetResult>
}
