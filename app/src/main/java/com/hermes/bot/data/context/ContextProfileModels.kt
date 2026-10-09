package com.hermes.bot.data.context

import com.hermes.bot.data.gateway.SessionSummary

/**
 * Representation of a Hermes Context Profile (workspace).
 * Safe for display: contains only sanitized display name, running/gateway status,
 * and active workspace marker. Never contains host paths, provider keys, or origins.
 */
data class ContextProfile(
    val name: String,
    val isRunning: Boolean = false,
    val isActive: Boolean = false
)

enum class ContextProfileErrorReason {
    UNAUTHORIZED,
    UNAVAILABLE,
    MALFORMED,
    NO_ACTIVE_CONNECTION
}

/**
 * PR-2 REQ-05: where a Workspaces roster result came from — live server truth
 * or the encrypted offline capture (drives the provenance caption).
 */
enum class RosterSource { LIVE, CACHED }

sealed interface ContextProfilesResult {
    data class Success(
        val profiles: List<ContextProfile>,
        val activeProfileName: String?,
        /** PR-2: defaulted so every existing construction compiles unchanged. */
        val source: RosterSource = RosterSource.LIVE,
        /** Capture instant of the served roster — null for live results. */
        val capturedAtMillis: Long? = null
    ) : ContextProfilesResult

    data class Error(
        val reason: ContextProfileErrorReason,
        val safeMessage: String
    ) : ContextProfilesResult
}

/** Outcome of persisting this app's selected workspace profile locally. */
sealed interface ContextProfileSelectionResult {
    data class Success(
        val selectedProfileName: String
    ) : ContextProfileSelectionResult

    data class Error(
        val reason: ContextProfileErrorReason,
        val safeMessage: String
    ) : ContextProfileSelectionResult
}

/**
 * Outcome of a profile-scoped session fetch (GET /api/profiles/sessions).
 *
 * [StaleSelection] is returned ONLY for a 404 whose host 'detail' body
 * indicates the persisted selected profile no longer exists on the roster.
 * It is a typed self-heal signal: the caller (DefaultSessionRepository) must
 * clear the persisted preference and fall back to the unscoped session path
 * for this load. The user-visible notice is the fixed, sanitized constant
 * [PROFILE_GONE_NOTICE] — the host-controlled 'detail' text is NEVER carried
 * in this result and never surfaced raw.
 */
sealed interface ScopedSessionsResult {
    data class Success(
        val sessions: List<SessionSummary>
    ) : ScopedSessionsResult

    data class StaleSelection(
        val notice: String = PROFILE_GONE_NOTICE
    ) : ScopedSessionsResult

    data class Error(
        val exception: Exception
    ) : ScopedSessionsResult

    companion object {
        /**
         * Fixed, safe, user-visible string for the stale-workspace self-heal.
         * Deliberately host-independent: derived from the typed 404 signal,
         * never from the host's 'detail' text.
         */
        const val PROFILE_GONE_NOTICE: String =
            "Workspace no longer exists — showing all workspaces"
    }
}
