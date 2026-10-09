package com.hermes.bot.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-7 REQ-05 (plan TEST-05) — [CacheReconnectGatePolicy] is the ONE
 * place sheet reload gating decisions live: pure, JVM-deterministic, no
 * Android types, and it authorizes nothing (trace/snapshot authorization
 * stays where it was).
 *
 * Edge semantics (chat's collector-local edge shares this rule):
 * - first observation (previous == null) is NEVER an edge;
 * - only a change INTO connected is;
 * - a repeat of the current connected value is NEVER an edge.
 */
class CacheReconnectGatePolicyTest {

    // --- isEdgeToConnected (Boolean form — shell vocabulary) ---

    @Test
    fun edge_sheetBooleanForm_firstObservationAndRepeatsNeverEdge() {
        assertFalse("first observation is never an edge", CacheReconnectGatePolicy.isEdgeToConnected(null, true))
        assertFalse("repeat of connected is never an edge", CacheReconnectGatePolicy.isEdgeToConnected(true, true))
        assertFalse("no transition is never an edge", CacheReconnectGatePolicy.isEdgeToConnected(false, false))
        assertFalse("dropping away is never an edge", CacheReconnectGatePolicy.isEdgeToConnected(true, false))
    }

    @Test
    fun edge_sheetBooleanForm_notConnectedToConnectedIsTheEdge() {
        assertTrue(CacheReconnectGatePolicy.isEdgeToConnected(false, true))
    }

    // --- shouldReloadSheet (plan TEST-05 reload matrix) ---

    @Test
    fun shouldReloadSheet_allGatesAlign_reloads() {
        assertTrue(
            CacheReconnectGatePolicy.shouldReloadSheet(
                wasConnected = false,
                isConnected = true,
                sheetOpen = true,
                presentingCachedRoster = true,
                optimisticSwitchInFlight = false,
            )
        )
    }

    @Test
    fun shouldReloadSheet_firstObservation_nullWasConnected_neverReloads() {
        assertFalse(
            CacheReconnectGatePolicy.shouldReloadSheet(
                wasConnected = null,
                isConnected = true,
                sheetOpen = true,
                presentingCachedRoster = true,
                optimisticSwitchInFlight = false,
            )
        )
    }

    @Test
    fun shouldReloadSheet_alreadyConnectedRepeat_neverReloads() {
        assertFalse(
            CacheReconnectGatePolicy.shouldReloadSheet(
                wasConnected = true,
                isConnected = true,
                sheetOpen = true,
                presentingCachedRoster = true,
                optimisticSwitchInFlight = false,
            )
        )
    }

    @Test
    fun shouldReloadSheet_sheetClosed_neverReloads() {
        assertFalse(
            CacheReconnectGatePolicy.shouldReloadSheet(
                wasConnected = false,
                isConnected = true,
                sheetOpen = false,
                presentingCachedRoster = true,
                optimisticSwitchInFlight = false,
            )
        )
    }

    @Test
    fun shouldReloadSheet_presentingLiveRoster_neverReloads() {
        assertFalse(
            CacheReconnectGatePolicy.shouldReloadSheet(
                wasConnected = false,
                isConnected = true,
                sheetOpen = true,
                presentingCachedRoster = false,
                optimisticSwitchInFlight = false,
            )
        )
    }

    @Test
    fun shouldReloadSheet_optimisticSwitchInFlight_neverReloads() {
        assertFalse(
            CacheReconnectGatePolicy.shouldReloadSheet(
                wasConnected = false,
                isConnected = true,
                sheetOpen = true,
                presentingCachedRoster = true,
                optimisticSwitchInFlight = true,
            )
        )
    }
}
