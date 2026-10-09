package com.hermes.bot.data.subagent

import com.hermes.bot.data.session.SessionActivityOwner

/** Transient projections only: opaque identities must never reach logs or caches. */
enum class SubagentStatus { QUEUED, RUNNING, COMPLETED, FAILED, INTERRUPTED, DISPATCHED, UNKNOWN;
    val live get() = this == QUEUED || this == RUNNING
    val terminal get() = this == COMPLETED || this == FAILED || this == INTERRUPTED
}
data class Subagent(
    val id: String, val goal: String, val status: SubagentStatus,
    val parentId: String? = null, val delegationId: String? = null, val taskIndex: Int? = null,
    val model: String? = null, val startedAtMillis: Long? = null, val durationSeconds: Double? = null,
    val toolCount: Long? = null, val tokens: Long? = null, val acceptingSteer: Boolean? = null,
    val activity: List<String> = emptyList(), val observed: Boolean = true,
    val finished: Boolean = status.terminal, val historical: Boolean = false, val sourceChildId: String? = null, val projectionTruncated: Boolean = false
) { override fun toString() = "Subagent([redacted])" }
class SubagentOwner internal constructor(val storedId: String, val runtimeId: String, internal val activity: SessionActivityOwner) {
    override fun toString() = "SubagentOwner([redacted])"
}
class SubagentRoster(val children: List<Subagent>, val failures: List<Subagent> = emptyList(), val truncated: Boolean = false) {
    override fun toString() = "SubagentRoster([redacted])"
}
class SubagentTail(val available: Boolean, val text: String, val truncated: Boolean) {
    override fun toString() = "SubagentTail([redacted])"
}
enum class SubagentControl { QUEUED, STOP_REQUESTED, REJECTED, UNKNOWN, UNAVAILABLE }
