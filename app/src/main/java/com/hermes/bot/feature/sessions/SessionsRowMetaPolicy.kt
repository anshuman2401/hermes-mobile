package com.hermes.bot.feature.sessions

/**
 * Policy for session list row metadata (message count and source chips).
 * Pure and JVM-deterministic: no Compose, no network, no Android framework dependencies.
 */
object SessionsRowMetaPolicy {

    /**
     * Formats the message count into a user-facing label.
     * Singular: "1 message", Plural / Zero: "N messages" (e.g. "0 messages", "12 messages").
     */
    fun messageLabel(count: Int): String {
        return if (count == 1) {
            "1 message"
        } else {
            "$count messages"
        }
    }

    /**
     * Formats the session source into a user-facing label if present and non-blank.
     * Returns "Source: <source>" when non-blank, else null.
     */
    fun sourceLabel(source: String?): String? {
        val trimmed = source?.trim()
        return if (trimmed.isNullOrEmpty()) {
            null
        } else {
            "Source: $trimmed"
        }
    }

    /**
     * Resolves the model label for a session row.
     * Returns the model string only for an exact key match with non-blank value, else null.
     * Null represents unknown and is rendered as "—" by the UI.
     */
    fun modelLabel(
        count: Int,
        activeModels: Map<String, String>,
        sessionId: String
    ): String? {
        val model = activeModels[sessionId]?.trim()
        return if (model.isNullOrEmpty()) null else model
    }

    fun modelLabel(
        activeModels: Map<String, String>,
        sessionId: String
    ): String? = modelLabel(0, activeModels, sessionId)
}
