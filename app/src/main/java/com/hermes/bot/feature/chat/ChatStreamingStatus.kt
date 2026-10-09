package com.hermes.bot.feature.chat

/**
 * HOME-S3: per-turn streaming broadcast for the active session.
 * The mark semantics are IN-APP ONLY (this app's active session, its local
 * turn). Never presented as host-wide session presence.
 */
data class ChatStreamingStatus(
    val storedSessionId: String? = null,
    val isStreaming: Boolean = false
)
