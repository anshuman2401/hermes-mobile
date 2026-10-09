package io.github.anshuman2401.hermesmobile.data.local

/** Device-only Sessions display choices, bound to the same owner as local pins. */
data class SessionFilters(
    val scope: SessionPinScope,
    val pinnedOnly: Boolean = false,
    /** Null includes every source, including sources not yet observed. */
    val selectedSources: Set<String>? = null
)
