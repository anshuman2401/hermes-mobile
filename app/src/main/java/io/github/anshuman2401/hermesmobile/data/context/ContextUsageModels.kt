package io.github.anshuman2401.hermesmobile.data.context

import io.github.anshuman2401.hermesmobile.data.session.SessionActivityOwner

/** Memory-only host projections; never diagnostics or persisted history. */
class ContextCategory(val id: String, val label: String, val tokens: Long) {
    override fun toString() = "ContextCategory([redacted])"
}
class ContextBreakdown(
    val used: Long, val capacity: Long, val percent: Double, val estimated: Boolean,
    val model: String?, val categories: List<ContextCategory>
) {
    val remaining: Long get() = (capacity - used).coerceAtLeast(0)
    val categoryTotal: Double get() = categories.sumOf { it.tokens.toDouble() }
    override fun toString() = "ContextBreakdown([redacted])"
}
class AllowanceWindow(val label: String, val remainingPercent: Int?, val resetText: String?, val detail: String?) {
    override fun toString() = "AllowanceWindow([redacted])"
}
class AccountAllowance(
    val provider: String?, val windows: List<AllowanceWindow>, val bankedResets: Int?, val lines: List<String>
) { override fun toString() = "AccountAllowance([redacted])" }
class SessionUsage(val total: Long?, val account: AccountAllowance) {
    override fun toString() = "SessionUsage([redacted])"
}
class ContextUsageSnapshot(val context: ContextBreakdown, val usage: SessionUsage) {
    override fun toString() = "ContextUsageSnapshot([redacted])"
}
class ContextUsageOwner internal constructor(
    internal val storedId: String, internal val runtimeId: String,
    internal val activity: SessionActivityOwner? = null
) { override fun toString() = "ContextUsageOwner([redacted])" }

enum class ContextUsageSignalKind { INVALIDATED, USAGE_UPDATED, COMPACTED, READY, STOPPED, FAILED }
class ContextUsageSignal(val kind: ContextUsageSignalKind, internal val runtimeId: String? = null, val usage: LiveUsageSummary? = null) {
    override fun toString() = "ContextUsageSignal([redacted])"
}

/** Validated, partial host report. Never retained in transcript or disk caches. */
class LiveUsageSummary(val context: ContextBreakdown? = null, val total: Long? = null, val model: String? = null) {
    fun merge(update: LiveUsageSummary) = LiveUsageSummary(
        update.context ?: context, update.total ?: total, update.model ?: model)
    override fun toString() = "LiveUsageSummary([redacted])"
}
