package io.github.anshuman2401.hermesmobile.navigation

/**
 * PR-7 (REQ-06, plan TEST-05) — the reconnect sheet gate: pure, allocation-free
 * decisions with no time/network/randomness and no held state (GUD-001
 * precedent: `SessionsReconnectPolicy`, `ConnectLandingPolicy`). The policy
 * ONLY gates presentation reloads — it decides nothing else, fetches nothing,
 * mutates nothing, and never authorizes anything: trace and snapshot
 * authorization (TraceUiState.allowed, restore-time allowlist) are consumed
 * elsewhere and are never touched or implied by these functions, and no
 * decision here creates frames or arms a runtime (SEC-05). Chat's edge
 * deliberately does NOT import this policy — the collector-local edge
 * variable REQ-01 extends keeps `feature/chat -> navigation` free of a new
 * import (plan REQ-06 note); this file serves the shell's `ConnectState`
 * vocabulary through plain booleans.
 */
internal object CacheReconnectGatePolicy {

    /**
     * True ONLY for a hard edge INTO connected: a non-null [previous] that
     * was not connected and a [current] of connected. First observation
     * (`previous == null`) is never an edge, so every downstream reload
     * consumer fires at most once per real reconnection — never on a
     * repeat, never on a drop away from connected.
     */
    fun isEdgeToConnected(previous: Boolean?, current: Boolean): Boolean =
        previous != null && !previous && current

    /**
     * Reconnect reload of the CACHED Workspaces roster (plan REQ-05). Fires
     * when ALL gates align: a hard edge into connected (previous -> current),
     * the sheet is open ([sheetOpen]), the sheet presents PR-2's CACHED
     * roster ([presentingCachedRoster] — LIVE rosters already hold server
     * truth), and no optimistic workspace switch is in flight
     * ([optimisticSwitchInFlight] — A-08: PR-2's final `Loaded` shape exposes
     * no in-flight state, so the consumer passes its own try/finally-guarded
     * shell flag, mirroring the switch's in-flight window). Closed-sheet
     * edges do nothing: the open effect fetches on the next open, so state
     * observed while closed is never stale on presentation. The consumer
     * must pass retainOnError = true — a failed reload leaves
     * workspaceSheetState untouched (the cached caption persists),
     * success/Empty replace unconditionally (server truth clears a stale
     * caption). GET-only: getProfiles, never selectProfile.
     */
    fun shouldReloadSheet(
        wasConnected: Boolean?,
        isConnected: Boolean,
        sheetOpen: Boolean,
        presentingCachedRoster: Boolean,
        optimisticSwitchInFlight: Boolean = false,
    ): Boolean =
        isEdgeToConnected(wasConnected, isConnected) &&
            sheetOpen &&
            presentingCachedRoster &&
            !optimisticSwitchInFlight
}
