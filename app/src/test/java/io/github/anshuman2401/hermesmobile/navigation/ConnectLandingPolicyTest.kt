package io.github.anshuman2401.hermesmobile.navigation

import io.github.anshuman2401.hermesmobile.feature.connect.ConnectState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHG-007-S1 — JVM decision-table tests for ConnectLandingPolicy.
 *
 * The shell must land on SESSIONS only when a connect becomes
 * CONNECTED_COMPATIBLE while it is parked on the CONNECT screen
 * (launch auto-connect, manual form connect, process-death restore).
 * Every other state/screen pair stays put (REQ-002/REQ-003/REQ-004).
 */
class ConnectLandingPolicyTest {

    // (a) the only true case
    @Test
    fun connectedCompatible_fromConnect_landsOnSessions() {
        assertTrue(
            ConnectLandingPolicy.shouldLandOnSessions(
                state = ConnectState.CONNECTED_COMPATIBLE,
                currentScreen = ShellScreen.CONNECT
            )
        )
    }

    // (b) REQ-004: never redirect when the user already left CONNECT
    @Test
    fun connectedCompatible_fromAnyNonConnectScreen_doesNotLand() {
        for (screen in ShellScreen.entries - ShellScreen.CONNECT) {
            assertFalse(
                "screen=$screen",
                ConnectLandingPolicy.shouldLandOnSessions(
                    state = ConnectState.CONNECTED_COMPATIBLE,
                    currentScreen = screen
                )
            )
        }
    }

    // (c) REQ-002/REQ-003: every non-connected state stays on CONNECT
    @Test
    fun nonConnectedStates_fromConnect_neverLand() {
        for (state in ConnectState.entries - ConnectState.CONNECTED_COMPATIBLE) {
            assertFalse(
                "state=$state",
                ConnectLandingPolicy.shouldLandOnSessions(
                    state = state,
                    currentScreen = ShellScreen.CONNECT
                )
            )
        }
    }

    // (d) full cross-product: exactly one true pair (state=CONNECTED_COMPATIBLE, screen=CONNECT)
    @Test
    fun fullDecisionTable_exactlyOneTruePair() {
        var truePairs = 0
        for (state in ConnectState.entries) {
            for (screen in ShellScreen.entries) {
                val decision = ConnectLandingPolicy.shouldLandOnSessions(state, screen)
                val expected = state == ConnectState.CONNECTED_COMPATIBLE &&
                    screen == ShellScreen.CONNECT
                assertTrue(
                    "state=$state screen=$screen expected=$expected",
                    decision == expected
                )
                if (decision) truePairs++
            }
        }
        assertTrue(truePairs == 1)
    }

    @Test
    fun connectedCompatible_withSwitchPending_fromChatOrSettings_landsOnSessions() {
        assertTrue(
            "switch pending from CHAT should land on SESSIONS",
            ConnectLandingPolicy.shouldLandOnSessions(
                state = ConnectState.CONNECTED_COMPATIBLE,
                currentScreen = ShellScreen.CHAT,
                isSwitchPending = true
            )
        )
        assertTrue(
            "switch pending from SETTINGS should land on SESSIONS",
            ConnectLandingPolicy.shouldLandOnSessions(
                state = ConnectState.CONNECTED_COMPATIBLE,
                currentScreen = ShellScreen.SETTINGS,
                isSwitchPending = true
            )
        )
        assertFalse(
            "switch pending from SESSIONS stays on SESSIONS",
            ConnectLandingPolicy.shouldLandOnSessions(
                state = ConnectState.CONNECTED_COMPATIBLE,
                currentScreen = ShellScreen.SESSIONS,
                isSwitchPending = true
            )
        )
    }

    @Test
    fun nonConnectedCompatible_withSwitchPending_neverLandsOnSessions() {
        for (state in ConnectState.entries - ConnectState.CONNECTED_COMPATIBLE) {
            for (screen in listOf(ShellScreen.CHAT, ShellScreen.SETTINGS, ShellScreen.CONNECT)) {
                assertFalse(
                    "state=$state screen=$screen",
                    ConnectLandingPolicy.shouldLandOnSessions(
                        state = state,
                        currentScreen = screen,
                        isSwitchPending = true
                    )
                )
            }
        }
    }
}
