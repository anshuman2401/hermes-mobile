package com.hermes.bot.domain

/**
 * Domain model representing durable vs runtime session identity
 * as specified in architecture_plan.md and session-and-turn-state-machine.md.
 */
data class SessionHandle(
    val storedSessionId: String,
    val runtimeSessionId: String? = null,
    val title: String? = null,
    val isDraft: Boolean = false,
    /**
     * CHAT-015 B1: create-outcome indication. `true` = the create succeeded with
     * the saved default model/reasoning overrides as sent; `false` = the create
     * was minted via the degraded-default fallback (host refused the overrides
     * with `-32602`, retried once with host defaults). `null` = not a create
     * outcome (every existing handle construction stays compiling).
     */
    val savedDefaultModelApplied: Boolean? = null
)

enum class ConnectionStatus {
    OFFLINE,
    CONNECTING,
    AUTHENTICATION_REQUIRED,
    CONNECTED,
    FAILED
}

sealed class SessionError {
    data class RemoteNotFound(
        val storedSessionId: String,
        val message: String = "Session was not found on the remote server (code 4007)."
    ) : SessionError()

    data class LocalCacheFailure(
        val message: String = "Local session cache is unavailable or corrupted."
    ) : SessionError()

    data class NetworkFailure(
        val message: String
    ) : SessionError()
}

class SessionNotFoundException(
    val storedSessionId: String,
    message: String = "Session not found on remote host: $storedSessionId"
) : Exception(message)

class LocalCacheException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
