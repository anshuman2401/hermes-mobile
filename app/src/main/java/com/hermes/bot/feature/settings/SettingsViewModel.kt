package com.hermes.bot.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.local.LocalDataResetCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Plan CACHE-PR5 FILE-03: Settings gets its first view model purely to own
 * deferred flow execution (FGU-004: view models own deferred collection/
 * dispatch wiring) for the two confirmation-gated local resets.
 *
 * Contract: fire-and-forget in [viewModelScope]; any thrown error converts
 * into a no-crash no-op (REQ-02/OQ-02 — the action never fails the user, and
 * no retry storm can start from a UI tap). This class references ONLY the
 * coordinator seam — no storage engine, no network adapter, no wire-level
 * vocabulary, no logout path (SEC-04).
 */
class SettingsViewModel(
    private val resetCoordinator: LocalDataResetCoordinator,
) : ViewModel() {

    /** Runs the seven-phase offline-cache clear (plan REQ-02). */
    fun clearOfflineCache() {
        viewModelScope.launch {
            try {
                resetCoordinator.clearOfflineCache()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Swallowed by contract: best-effort reset, residue already
                // representable via CacheClearResult when the flow returned.
            }
        }
    }

    /** Runs the full local wipe (plan REQ-04); the caller performs teardown first. */
    fun clearAllLocalData() {
        viewModelScope.launch {
            try {
                resetCoordinator.clearAllLocalData()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Same contract as clearOfflineCache: never fail the action.
            }
        }
    }
}
