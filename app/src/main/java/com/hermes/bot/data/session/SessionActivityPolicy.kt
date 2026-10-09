package com.hermes.bot.data.session

import com.hermes.bot.data.gateway.SessionSummary

/**
 * Known finite activity leads, newest first. Stable sorting preserves ties;
 * unknown activity keeps its input-relative order, without a creation fallback.
 * A wholly unknown page retains the host or cached order exactly.
 */
internal fun List<SessionSummary>.sortedByActivity(): List<SessionSummary> {
    val (known, unknown) = partition { it.lastActive?.isFinite() == true }
    if (known.isEmpty()) return this
    return known.sortedByDescending { it.lastActive } + unknown
}

internal fun newerActivity(vararg values: Double?): Double? = values.filterNotNull().filter { it.isFinite() }.maxOrNull()

internal fun List<SessionSummary>.withActivityFloor(floors: Map<String, Double>): List<SessionSummary> =
    map { row ->
        val floor = floors[row.id]
        val activity = newerActivity(row.lastActive, floor)
        if (floor == null || activity == row.lastActive) row else row.copy(lastActive = activity)
    }.sortedByActivity()
