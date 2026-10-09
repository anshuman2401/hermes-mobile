package com.hermes.bot.navigation

import com.hermes.bot.feature.connect.ConnectState

internal enum class SessionsReconnectAction {
    NONE,
    REFRESH_SESSIONS,
    SURFACE_ERROR
}

/**
 * Shell-level policy for reconnect reactions on the Sessions screen.
 *
 * Ensures:
 * 1. When the gateway reaches CONNECTED_COMPATIBLE while on the SESSIONS screen
 *    with an active offline-cache presentation (isOfflineCache = true) or an active
 *    local-cache failure (hasLocalCacheError = true), refreshSessions() is triggered
 *    to reconcile with server truth and clear the offline badge or error state.
 * 2. When reconnect fails or requires authentication while on the SESSIONS screen,
 *    an error is surfaced to SessionsViewModel so the Sessions screen renders SessionErrorBanner
 *    rather than swallowing the failure silently.
 */
internal object SessionsReconnectPolicy {

    fun decide(
        screen: ShellScreen,
        connectState: ConnectState,
        isOfflineCache: Boolean,
        hasLocalCacheError: Boolean
    ): SessionsReconnectAction {
        if (screen != ShellScreen.SESSIONS) return SessionsReconnectAction.NONE
        return when {
            connectState == ConnectState.CONNECTED_COMPATIBLE && (isOfflineCache || hasLocalCacheError) ->
                SessionsReconnectAction.REFRESH_SESSIONS
            connectState.isErrorState ->
                SessionsReconnectAction.SURFACE_ERROR
            connectState == ConnectState.AUTH_REQUIRED ->
                SessionsReconnectAction.SURFACE_ERROR
            else -> SessionsReconnectAction.NONE
        }
    }

    fun errorMessage(connectState: ConnectState, connectErrorMessage: String?): String {
        return connectErrorMessage?.takeIf { it.isNotBlank() }
            ?: when (connectState) {
                ConnectState.AUTH_REQUIRED -> "Authentication required. Re-authentication needed."
                ConnectState.HOST_UNREACHABLE -> "Host unreachable. Check your network or Tailscale connection."
                ConnectState.ORIGIN_POLICY_REJECTED -> "Connection rejected by address, redirect, or Android network policy."
                ConnectState.PROTOCOL_INCOMPATIBLE -> "Protocol version mismatch: required hermes-gateway-v1."
                else -> "Connection failed."
            }
    }
}
