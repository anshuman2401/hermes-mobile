package com.hermes.bot.navigation

import com.hermes.bot.feature.connect.ConnectState
import com.hermes.bot.feature.connect.StartupPresentation
import org.junit.Assert.*
import org.junit.Test

class StartupLandingPolicyTest {
    @Test fun savedLoginStartsOnSessionsBeforeConnectivity() {
        assertEquals(ShellScreen.SESSIONS, StartupLandingPolicy.initialScreen(StartupPresentation.SESSIONS))
        StartupPresentation.entries.filter { it != StartupPresentation.SESSIONS }.forEach {
            assertEquals(ShellScreen.CONNECT, StartupLandingPolicy.initialScreen(it))
        }
    }
    @Test fun progressAndNetworkFailuresNeverRequirePassword() {
        ConnectState.entries.filter { it != ConnectState.AUTH_REQUIRED }.forEach {
            assertFalse(StartupLandingPolicy.requiresSignIn(StartupPresentation.SESSIONS, it))
        }
        assertTrue(StartupLandingPolicy.requiresSignIn(StartupPresentation.SIGN_IN, ConnectState.FIRST_LAUNCH))
        assertFalse(StartupLandingPolicy.requiresSignIn(StartupPresentation.SESSIONS, ConnectState.AUTH_REQUIRED))
    }
    @Test fun onlyCompatibleConnectionEnablesRemoteActions() {
        ConnectState.entries.forEach {
            assertEquals(it == ConnectState.CONNECTED_COMPATIBLE, StartupLandingPolicy.remoteActionsAllowed(it))
        }
    }
}
