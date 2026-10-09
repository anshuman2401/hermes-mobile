package io.github.anshuman2401.hermesmobile.feature.sessions

/** Gate for the single stored-session row's app-observed streaming mark. */
internal object SessionsRunningRowPolicy {
    fun showRunning(
        rowId: String,
        streamingId: String?,
        isStreaming: Boolean,
        isOfflineCache: Boolean,
        hasNetworkFailure: Boolean,
        hasViewModel: Boolean
    ): Boolean = hasViewModel && isStreaming && !isOfflineCache && !hasNetworkFailure &&
        !streamingId.isNullOrBlank() && rowId.isNotBlank() && streamingId == rowId
}
