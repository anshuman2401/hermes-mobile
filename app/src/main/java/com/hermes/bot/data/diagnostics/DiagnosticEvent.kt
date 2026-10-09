package com.hermes.bot.data.diagnostics

import java.time.Instant

/**
 * Structured diagnostic event types for in-app diagnostic capture.
 * Captures lifecycle and boundary events with sanitized metadata only.
 */
sealed interface DiagnosticEvent {
    val timestamp: Instant

    sealed interface Auth : DiagnosticEvent {
        data class LoggingIn(
            val provider: String,
            override val timestamp: Instant = Instant.now()
        ) : Auth

        data class Authenticated(
            val provider: String,
            override val timestamp: Instant = Instant.now()
        ) : Auth

        data class Rejected(
            val reason: String,
            override val timestamp: Instant = Instant.now()
        ) : Auth

        data class LoggedOut(
            val clearDrafts: Boolean = false,
            override val timestamp: Instant = Instant.now()
        ) : Auth

        data class Expired(
            override val timestamp: Instant = Instant.now()
        ) : Auth
    }

    sealed interface Http : DiagnosticEvent {
        data class RequestCompleted(
            val method: String,
            val urlPathShape: String,
            val statusCode: Int,
            val durationMs: Long,
            val error: String? = null,
            override val timestamp: Instant = Instant.now()
        ) : Http
    }

    sealed interface Switch : DiagnosticEvent {
        data class SheetOpened(
            override val timestamp: Instant = Instant.now()
        ) : Switch

        data class ConfirmTapped(
            val targetProfile: String,
            override val timestamp: Instant = Instant.now()
        ) : Switch {
            val target: String get() = targetProfile
        }

        data class SelectionResult(
            val targetProfile: String,
            val success: Boolean,
            val reason: String? = null,
            override val timestamp: Instant = Instant.now()
        ) : Switch

        data class PreferencePersisted(
            val profile: String,
            override val timestamp: Instant = Instant.now()
        ) : Switch

        data class PreferenceCleared(
            override val timestamp: Instant = Instant.now()
        ) : Switch

        data class ScopedFetchStarted(
            val profile: String,
            override val timestamp: Instant = Instant.now()
        ) : Switch

        data class ScopedFetchOutcome(
            val profile: String,
            val count: Int? = null,
            val isOfflineCache: Boolean = false,
            val error: String? = null,
            override val timestamp: Instant = Instant.now()
        ) : Switch
    }

    sealed interface WebSocket : DiagnosticEvent {
        data class Connecting(
            val url: String? = null,
            override val timestamp: Instant = Instant.now()
        ) : WebSocket

        data class Connected(
            val url: String? = null,
            override val timestamp: Instant = Instant.now()
        ) : WebSocket

        data class Disconnected(
            val code: Int? = null,
            val reason: String? = null,
            override val timestamp: Instant = Instant.now()
        ) : WebSocket

        data class ReconnectScheduled(
            val attempt: Int,
            val delayMs: Long,
            override val timestamp: Instant = Instant.now()
        ) : WebSocket

        data class ResumeOutcome(
            val success: Boolean,
            val error: String? = null,
            override val timestamp: Instant = Instant.now()
        ) : WebSocket
    }

    sealed interface SessionList : DiagnosticEvent {
        data class Outcome(
            val count: Int,
            val isOfflineCache: Boolean = false,
            val isScoped: Boolean = false,
            val profile: String? = null,
            val error: String? = null,
            override val timestamp: Instant = Instant.now()
        ) : SessionList
    }

    sealed interface App : DiagnosticEvent {
        data object ForegroundEntered : App {
            override val timestamp: Instant = Instant.now()
        }

        data object BackgroundEntered : App {
            override val timestamp: Instant = Instant.now()
        }

        data class Lifecycle(
            val stage: String,
            override val timestamp: Instant = Instant.now()
        ) : App
    }

    fun formatSummary(): String = when (this) {
        is Auth.LoggingIn -> "AUTH: logging in (provider=[redacted])"
        is Auth.Authenticated -> "AUTH: authenticated (provider=[redacted])"
        is Auth.Rejected -> "AUTH: rejected (reason=[redacted])"
        is Auth.LoggedOut -> "AUTH: logged out (clearDrafts=$clearDrafts)"
        is Auth.Expired -> "AUTH: session expired"
        is Http.RequestCompleted -> {
            val safeMethod = method.takeIf { it in setOf("GET", "POST", "PUT", "PATCH", "DELETE") } ?: "[redacted]"
            val safePath = urlPathShape.substringBefore('?').takeIf {
                it in setOf("/api/status", "/auth/password-login", "/api/auth/ws-ticket", "/api/profiles/sessions")
            } ?: "[redacted]"
            val err = if (error != null) " error=[redacted]" else ""
            "HTTP: $safeMethod $safePath -> $statusCode (${durationMs}ms)$err"
        }
        is Switch.SheetOpened -> "SWITCH: sheet opened"
        is Switch.ConfirmTapped -> "SWITCH: confirm tapped (target=[redacted])"
        is Switch.SelectionResult -> {
            if (success) {
                "SWITCH: selection succeeded (target=[redacted])"
            } else {
                val r = if (reason != null) ", reason=[redacted]" else ""
                "SWITCH: selection failed (target=[redacted]$r)"
            }
        }
        is Switch.PreferencePersisted -> "SWITCH: preference persisted (profile=[redacted])"
        is Switch.PreferenceCleared -> "SWITCH: preference cleared"
        is Switch.ScopedFetchStarted -> "SWITCH: scoped fetch started (profile=[redacted])"
        is Switch.ScopedFetchOutcome -> {
            if (error != null) {
                "SWITCH: scoped fetch failed (profile=[redacted], error=[redacted], offlineCache=$isOfflineCache)"
            } else {
                val cnt = if (count != null) "count=$count, " else ""
                "SWITCH: scoped fetch loaded (profile=[redacted], ${cnt}offlineCache=$isOfflineCache)"
            }
        }
        is WebSocket.Connecting -> "WS: connecting"
        is WebSocket.Connected -> "WS: connected"
        is WebSocket.Disconnected -> {
            val r = if (reason != null) " (reason=[redacted])" else ""
            "WS: disconnected$r"
        }
        is WebSocket.ReconnectScheduled -> "WS: reconnect scheduled (attempt=$attempt, delay=${delayMs}ms)"
        is WebSocket.ResumeOutcome -> {
            if (success) {
                "WS: session.resume succeeded"
            } else {
                val err = if (error != null) " (error=[redacted])" else ""
                "WS: session.resume failed$err"
            }
        }
        is SessionList.Outcome -> {
            val prof = if (profile == null) "default" else "[redacted]"
            if (error != null) {
                "SESSION_LIST: error (reason=[redacted], profile=$prof, offlineCache=$isOfflineCache)"
            } else if (count == 0) {
                "SESSION_LIST: empty (profile=$prof, offlineCache=$isOfflineCache)"
            } else {
                "SESSION_LIST: loaded count=$count (profile=$prof, offlineCache=$isOfflineCache)"
            }
        }
        is App.ForegroundEntered -> "APP: foreground entered"
        is App.BackgroundEntered -> "APP: background entered"
        is App.Lifecycle -> "APP: lifecycle stage=[redacted]"
    }
}
