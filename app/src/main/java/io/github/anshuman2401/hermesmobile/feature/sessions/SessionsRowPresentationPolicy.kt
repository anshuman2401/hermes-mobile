package io.github.anshuman2401.hermesmobile.feature.sessions

import java.util.Locale

/** Pure presentation decisions shared by the consumer session row and JVM tests. */
object SessionsRowPresentationPolicy {
    fun pinAccessibilityLabel(pinned: Boolean): String = if (pinned) "Unpin conversation" else "Pin conversation"

    fun displayedTitle(title: String?, sessionId: String): String {
        val normalizedTitle = title?.trim().orEmpty()
        if (normalizedTitle.isNotEmpty()) return normalizedTitle

        val normalizedId = sessionId.trim().ifEmpty { "conversation" }
        return "Session $normalizedId"
    }

    fun titleInitial(title: String?, sessionId: String): String =
        initialForDisplayedTitle(displayedTitle(title, sessionId))

    fun initialForDisplayedTitle(displayedTitle: String): String {
        val normalizedTitle = displayedTitle.trim()
        if (normalizedTitle.isEmpty()) return "?"

        val codePoint = normalizedTitle.codePointAt(0)
        if (!Character.isLetterOrDigit(codePoint)) return "?"

        val uppercased = String(Character.toChars(codePoint)).uppercase(Locale.ROOT)
        val initialCodePoint = uppercased.codePointAt(0)
        return String(Character.toChars(initialCodePoint))
    }
}
