package io.github.anshuman2401.hermesmobile.navigation

import io.github.anshuman2401.hermesmobile.feature.connect.ConnectState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [SessionsReconnectPolicy].
 * Verifies decision table for reconnect reactions on the Sessions screen:
 * - Refresh sessions when reaching CONNECTED_COMPATIBLE while presenting offline cache.
 * - Surface visible error when reconnect fails or auth is required.
 * - Suppress actions when not on the SESSIONS screen or during intermediate/fresh states.
 */
class SessionsReconnectPolicyTest {

    @Test
    fun decide_connectedCompatible_withOfflineCache_onSessions_returnsRefreshSessions() {
        val action = SessionsReconnectPolicy.decide(
            screen = ShellScreen.SESSIONS,
            connectState = ConnectState.CONNECTED_COMPATIBLE,
            isOfflineCache = true,
            hasLocalCacheError = false
        )
        assertEquals(SessionsReconnectAction.REFRESH_SESSIONS, action)
    }

    @Test
    fun decide_connectedCompatible_withoutOfflineCache_onSessions_returnsNone() {
        val action = SessionsReconnectPolicy.decide(
            screen = ShellScreen.SESSIONS,
            connectState = ConnectState.CONNECTED_COMPATIBLE,
            isOfflineCache = false,
            hasLocalCacheError = false
        )
        assertEquals(SessionsReconnectAction.NONE, action)
    }

    @Test
    fun decide_connectedCompatible_withLocalCacheError_withoutOfflineCache_onSessions_returnsRefreshSessions() {
        val action = SessionsReconnectPolicy.decide(
            screen = ShellScreen.SESSIONS,
            connectState = ConnectState.CONNECTED_COMPATIBLE,
            isOfflineCache = false,
            hasLocalCacheError = true
        )
        assertEquals(SessionsReconnectAction.REFRESH_SESSIONS, action)
    }

    @Test
    fun decide_connectedCompatible_withLocalCacheError_withOfflineCache_onSessions_returnsRefreshSessions() {
        val action = SessionsReconnectPolicy.decide(
            screen = ShellScreen.SESSIONS,
            connectState = ConnectState.CONNECTED_COMPATIBLE,
            isOfflineCache = true,
            hasLocalCacheError = true
        )
        assertEquals(SessionsReconnectAction.REFRESH_SESSIONS, action)
    }

    @Test
    fun decide_errorStates_onSessions_returnsSurfaceError() {
        val errorStates = listOf(
            ConnectState.HOST_UNREACHABLE,
            ConnectState.ORIGIN_POLICY_REJECTED,
            ConnectState.PROTOCOL_INCOMPATIBLE,
            ConnectState.AUTH_REQUIRED
        )

        for (state in errorStates) {
            for (isOffline in listOf(true, false)) {
                for (hasError in listOf(true, false)) {
                    val action = SessionsReconnectPolicy.decide(
                        screen = ShellScreen.SESSIONS,
                        connectState = state,
                        isOfflineCache = isOffline,
                        hasLocalCacheError = hasError
                    )
                    assertEquals(
                        "State $state with isOfflineCache=$isOffline, hasLocalCacheError=$hasError must trigger SURFACE_ERROR",
                        SessionsReconnectAction.SURFACE_ERROR,
                        action
                    )
                }
            }
        }
    }

    @Test
    fun decide_intermediateStates_onSessions_returnsNone() {
        val intermediateStates = listOf(
            ConnectState.FIRST_LAUNCH,
            ConnectState.PREFLIGHT_LOADING
        )

        for (state in intermediateStates) {
            for (isOffline in listOf(true, false)) {
                for (hasError in listOf(true, false)) {
                    val action = SessionsReconnectPolicy.decide(
                        screen = ShellScreen.SESSIONS,
                        connectState = state,
                        isOfflineCache = isOffline,
                        hasLocalCacheError = hasError
                    )
                    assertEquals(
                        "Intermediate state $state with isOfflineCache=$isOffline, hasLocalCacheError=$hasError must return NONE",
                        SessionsReconnectAction.NONE,
                        action
                    )
                }
            }
        }
    }

    @Test
    fun decide_nonSessionsScreens_alwaysReturnsNone() {
        val nonSessionsScreens = ShellScreen.entries - ShellScreen.SESSIONS

        for (screen in nonSessionsScreens) {
            for (state in ConnectState.entries) {
                for (isOffline in listOf(true, false)) {
                    for (hasError in listOf(true, false)) {
                        val action = SessionsReconnectPolicy.decide(
                            screen = screen,
                            connectState = state,
                            isOfflineCache = isOffline,
                            hasLocalCacheError = hasError
                        )
                        assertEquals(
                            "Screen $screen with state $state, isOfflineCache=$isOffline, hasLocalCacheError=$hasError must return NONE",
                            SessionsReconnectAction.NONE,
                            action
                        )
                    }
                }
            }
        }
    }

    @Test
    fun errorMessage_preservesNonBlankErrorMessage() {
        val customError = "Custom error from host"
        val message = SessionsReconnectPolicy.errorMessage(ConnectState.HOST_UNREACHABLE, customError)
        assertEquals(customError, message)
    }

    @Test
    fun errorMessage_fallsBackToStateDefaultsWhenNullOrBlank() {
        val hostMsg = SessionsReconnectPolicy.errorMessage(ConnectState.HOST_UNREACHABLE, null)
        assertEquals("Host unreachable. Check your network or Tailscale connection.", hostMsg)

        val authMsg = SessionsReconnectPolicy.errorMessage(ConnectState.AUTH_REQUIRED, "   ")
        assertEquals("Authentication required. Re-authentication needed.", authMsg)

        val policyMsg = SessionsReconnectPolicy.errorMessage(ConnectState.ORIGIN_POLICY_REJECTED, null)
        assertEquals("Connection rejected by address, redirect, or Android network policy.", policyMsg)

        val protoMsg = SessionsReconnectPolicy.errorMessage(ConnectState.PROTOCOL_INCOMPATIBLE, null)
        assertEquals("Protocol version mismatch: required hermes-gateway-v1.", protoMsg)
    }

    // PR-7 TEST-06 (additive characterization; decide() unchanged): the FULL
    // cross product — every screen x every connect state x every flag pair —
    // must resolve to exactly the current truth table and one of only three
    // declared actions. REFRESH_SESSIONS fires ONLY on SESSIONS +
    // CONNECTED_COMPATIBLE with a presenting flag (the label is cleared by
    // the owning slice's remote read, never by decide()); SURFACE_ERROR on
    // SESSIONS with an error/auth state (flag-independent); everything else —
    // including intermediate FIRST_LAUNCH/PREFLIGHT_LOADING with a label
    // presented — returns NONE (never refresh, never overwrite the label
    // with an error mid-flight).
    @Test
    fun decide_pr7FullCrossProduct_matchesCurrentTruthTableOnlyThreeActions() {
        val declared = listOf(
            SessionsReconnectAction.NONE,
            SessionsReconnectAction.REFRESH_SESSIONS,
            SessionsReconnectAction.SURFACE_ERROR,
        )
        for (screen in ShellScreen.entries) {
            for (state in ConnectState.entries) {
                for (isOffline in listOf(true, false)) {
                    for (hasError in listOf(true, false)) {
                        val action = SessionsReconnectPolicy.decide(screen, state, isOffline, hasError)
                        assertEquals(
                            "only the three declared actions exist: $action (screen=$screen state=$state)",
                            true,
                            action in declared,
                        )
                        val expected = when {
                            screen != ShellScreen.SESSIONS -> SessionsReconnectAction.NONE
                            state == ConnectState.CONNECTED_COMPATIBLE && (isOffline || hasError) ->
                                SessionsReconnectAction.REFRESH_SESSIONS
                            state.isErrorState || state == ConnectState.AUTH_REQUIRED ->
                                SessionsReconnectAction.SURFACE_ERROR
                            else -> SessionsReconnectAction.NONE
                        }
                        assertEquals(
                            "truth table (screen=$screen state=$state isOffline=$isOffline hasError=$hasError)",
                            expected,
                            action,
                        )
                    }
                }
            }
        }
    }
}
