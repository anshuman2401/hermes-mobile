package com.hermes.bot.feature.sessions

/**
 * Label for repository-flagged cached Sessions rows.
 *
 * When `DefaultSessionRepository.listSessions` serves the wholesale vault
 * cache fallback, it sets `SessionsUiState.isOfflineCache = true` and the
 * whole list is cached data (never mixed with live rows). The section
 * derives ONLY from that repository-provided flag (CHG-003-S5 contract) —
 * no screen-local staleness inference. Pure and JVM-deterministic: no
 * time, network, or randomness inputs.
 */
object SessionsOfflineSectionPolicy {
    const val LABEL_LIVE = "Recent Conversations"
    const val LABEL_CACHED = "Saved sessions · offline"

    fun sectionLabel(isOfflineCache: Boolean): String =
        if (isOfflineCache) LABEL_CACHED else LABEL_LIVE
}
