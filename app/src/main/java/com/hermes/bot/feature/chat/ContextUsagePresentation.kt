package com.hermes.bot.feature.chat

import com.hermes.bot.data.context.ContextUsageSnapshot

enum class CompressionPhase { IDLE, REQUESTING, PENDING, UNKNOWN, RECONCILING }

data class ContextUsagePresentation(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val snapshot: ContextUsageSnapshot? = null,
    val error: String? = null,
    val live: com.hermes.bot.data.context.LiveUsageSummary? = null,
    val detailsStale: Boolean = false,
    val expanded: Boolean = false
)

/** Only ownership/readiness changes invalidate a read; rendering its result never refetches. */
internal data class ContextReadKey(
    val visible: Boolean, val stored: String?, val runtime: String?, val idle: Boolean, val connected: Boolean
)
