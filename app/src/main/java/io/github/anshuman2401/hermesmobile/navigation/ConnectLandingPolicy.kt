package io.github.anshuman2401.hermesmobile.navigation

import io.github.anshuman2401.hermesmobile.feature.connect.ConnectState

/**
 * Shell-level connect-landing policy, extracted from HermesNavShell so the
 * landing decision is unit-tested (ConnectLandingPolicyTest). The
 * LaunchedEffect executes this decision; it holds no policy of its own.
 *
 * Semantics (CHG-007-S1): the shell lands on SESSIONS only when the connect
 * state is CONNECTED_COMPATIBLE AND the shell is currently parked on the
 * CONNECT screen. This covers the launch auto-connect with saved
 * credentials, a manual form connect, and a process-death restore — while
 * every non-connected state and every already-left screen never triggers a
 * redirect (REQ-002/REQ-003/REQ-004).
 */
internal object ConnectLandingPolicy {
    fun shouldLandOnSessions(
        state: ConnectState,
        currentScreen: ShellScreen,
        isSwitchPending: Boolean = false
    ): Boolean {
        if (state != ConnectState.CONNECTED_COMPATIBLE) return false
        return currentScreen == ShellScreen.CONNECT ||
            (isSwitchPending && (currentScreen == ShellScreen.CHAT || currentScreen == ShellScreen.SETTINGS))
    }
}
