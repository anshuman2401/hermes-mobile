package io.github.anshuman2401.hermesmobile.feature.sessions

import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary

/** Pure projection over the complete loaded list, including offline rows. */
object SessionsSourceFilterPolicy {
    // Blank is the absent-source key, distinct from a literal "Unspecified" source.
    fun key(source: String?): String = source?.trim().orEmpty()

    data class Option(val key: String, val count: Int) {
        val label: String get() = key.ifEmpty { "Unspecified" }
    }

    fun options(sessions: List<SessionSummary>): List<Option> =
        sessions.groupingBy { key(it.source) }.eachCount().entries
            .sortedWith(compareBy({ it.key.isEmpty() }, { it.key }))
            .map { Option(it.key, it.value) }

    /** Null is All (also includes future sources); empty is an explicit no-selection. */
    fun filter(sessions: List<SessionSummary>, selected: Set<String>?): List<SessionSummary> =
        if (selected == null) sessions else sessions.filter { key(it.source) in selected }

    fun toggle(selected: Set<String>?, source: String, options: List<Option>): Set<String>? {
        val available = options.map { it.key }.toSet()
        if (source !in available) return selected
        val current = selected ?: available
        val next = if (source in current) current - source else current + source
        return if (next == available) null else next
    }
}
