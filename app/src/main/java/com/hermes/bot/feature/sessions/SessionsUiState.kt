package com.hermes.bot.feature.sessions

import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.session.SessionCreateFailedException
import com.hermes.bot.domain.SessionError
import com.hermes.bot.domain.SessionHandle
import com.hermes.bot.feature.chat.ChatStreamingStatus

/**
 * Presentation state for Sessions screen.
 * Distinctly models offline cache and error variants (remote not found vs local cache).
 */
data class SessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val pinnedSessionIds: Set<String> = emptySet(),
    val showPinnedOnly: Boolean = false,
    /** Null includes all current/future sources; empty explicitly selects none. */
    val selectedSources: Set<String>? = null,
    /** Saved filter preference is being read before list rows may publish. */
    val filtersRestoring: Boolean = false,
    val filterFailure: String? = null,
    val pinsAvailable: Boolean = false,
    val savingPinIds: Set<String> = emptySet(),
    val pinFailure: String? = null,
    val isLoading: Boolean = false,
    val isOfflineCache: Boolean = false,
    val selectedSession: SessionHandle? = null,
    val activeMessages: List<SessionMessage> = emptyList(),
    val error: SessionError? = null,
    /** HOME-S3: streaming state of the app's active session (in-app semantics only). */
    val activeTurnStreaming: ChatStreamingStatus = ChatStreamingStatus(),
    /** HOME-S7: client-side search/filter query over [sessions]; blank = no filter. */
    val searchQuery: String = "",
    val activeModels: Map<String, String> = emptyMap(),
    val selectedProfileName: String? = null,
    /**
     * Scoped-session 404 self-heal: fixed sanitized notice
     * (ScopedSessionsResult.PROFILE_GONE_NOTICE) surfaced exactly when this
     * load recovered from a stale selected workspace. Never carries
     * host-controlled text.
     */
    val staleProfileNotice: String? = null,
    /**
     * CHAT-015 B2: dedicated create-in-progress state for the new-chat entry
     * points. Never feeds the list-refresh `isLoading` (pull-to-refresh must
     * stay bound to list refresh only).
     */
    val isCreatingSession: Boolean = false,
    /**
     * CHAT-015 B2: dedicated, dismissible create-failure state (redacted copy).
     * Never routed through the [error] channel — the global connection-status
     * banner suppression (owner decision 2026-09-24) must stay intact.
     */
    val newChatFailure: NewChatCreateFailure? = null,
    /**
     * CHAT-015 B2: degraded-default notice riding a successful create
     * ([SessionHandle.savedDefaultModelApplied] == false). Carries the session
     * id it belongs to so the ChatScreen surface auto-clears when the active
     * session changes.
     */
    val newChatDegradedNotice: NewChatCreateNotice? = null
)

/**
 * CHAT-015 B2: redacted create-failure presentation. [message] is fixed
 * app-authored copy plus, at most, the failure category and the
 * `GatewayFailure` code/message (credential-free by contract). Host-controlled
 * `Rpc.data` (model/provider/suggestions strings) is NEVER rendered verbatim.
 */
data class NewChatCreateFailure(val message: String) {
    companion object {
        const val FIXED_COPY: String = "Couldn't start a new chat"

        fun redactedFrom(error: Throwable): NewChatCreateFailure {
            val failure = (error as? SessionCreateFailedException)?.failure
            val detail = when (failure) {
                is GatewayFailure.Rpc ->
                    "rpc ${failure.code}: ${failure.message}"
                is GatewayFailure.Transport ->
                    "transport: ${failure.message}"
                is GatewayFailure.NotConnected -> "not connected"
                is GatewayFailure.AuthenticationRequired ->
                    "authentication required"
                is GatewayFailure.SubmissionUnknown ->
                    "submission unknown"
                is GatewayFailure.IncompatibleProtocol ->
                    "incompatible protocol"
                null -> null
            }
            return NewChatCreateFailure(
                if (detail == null) FIXED_COPY else "$FIXED_COPY ($detail)"
            )
        }
    }
}

/**
 * CHAT-015 B2: degraded-default notice bound to the session it describes.
 */
data class NewChatCreateNotice(val sessionId: String, val message: String)

/**
 * CHAT-015 (controller decision 2026-09-26): the fixed contract phrase
 * followed by " — chat created with the host default." Nothing else.
 */
const val DEGRADED_DEFAULT_NOTICE_COPY: String =
    "saved default model not applied — chat created with the host default."
