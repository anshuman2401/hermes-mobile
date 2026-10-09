package com.hermes.bot.navigation

import com.hermes.bot.data.context.ContextProfile
import com.hermes.bot.data.context.ContextProfileErrorReason
import com.hermes.bot.data.context.RosterSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-B5 / B2: Deterministic JVM tests for ContextProfileSwitcherPolicy.
 * Verifies:
 * - 48dp minimum touch-target constant.
 * - Exact accessibility strings and content descriptions.
 * - Non-switching explanation string and disabled selection on rows.
 * - Safe error strings for all failure modes (unauthorized, unavailable, malformed, empty).
 * - Row state mapping and active workspace marking.
 */
class ContextProfileSwitcherPolicyTest {

    @Test
    fun minTouchTarget_is48dp() {
        assertEquals(48, ContextProfileSwitcherPolicy.MIN_TOUCH_TARGET_DP)
    }

    @Test
    fun exactAccessibilityStrings_matchContract() {
        assertEquals("Workspace profiles", ContextProfileSwitcherPolicy.TRIGGER_CONTENT_DESCRIPTION)
        assertEquals("Close workspace profiles", ContextProfileSwitcherPolicy.CLOSE_SHEET_CONTENT_DESCRIPTION)
        assertEquals("Active workspace", ContextProfileSwitcherPolicy.ACTIVE_BADGE_CONTENT_DESCRIPTION)
        assertEquals("Running", ContextProfileSwitcherPolicy.RUNNING_STATUS_CONTENT_DESCRIPTION)
    }

    @Test
    fun nonSwitchingExplanation_isFixedAndSafe() {
        assertEquals("Workspace switching is not yet supported", ContextProfileSwitcherPolicy.SWITCH_DISABLED_EXPLANATION)
        assertEquals("Workspace switching is not yet supported", ContextProfileSwitcherPolicy.onRowClicked())
    }

    @Test
    fun safeErrorStrings_matchContract() {
        assertEquals("Sign in to view workspaces", ContextProfileSwitcherPolicy.errorMessageFor(ContextProfileErrorReason.UNAUTHORIZED))
        assertEquals("Workspace list unavailable", ContextProfileSwitcherPolicy.errorMessageFor(ContextProfileErrorReason.UNAVAILABLE))
        assertEquals("Workspace list format not recognized", ContextProfileSwitcherPolicy.errorMessageFor(ContextProfileErrorReason.MALFORMED))
        assertEquals("No active connection", ContextProfileSwitcherPolicy.errorMessageFor(ContextProfileErrorReason.NO_ACTIVE_CONNECTION))
        assertEquals("No workspaces found", ContextProfileSwitcherPolicy.ERROR_EMPTY)
        assertEquals("Loading workspaces...", ContextProfileSwitcherPolicy.LOADING_MESSAGE)
    }

    @Test
    fun rowStateMapping_marksActiveCorrectly_andDisablesSelection() {
        val profiles = listOf(
            ContextProfile(name = "default", isRunning = true, isActive = true),
            ContextProfile(name = "ca", isRunning = false, isActive = false),
            ContextProfile(name = "test-ws", isRunning = true, isActive = false)
        )

        val rowStates = ContextProfileSwitcherPolicy.toRowStates(profiles)
        assertEquals(3, rowStates.size)

        // Row 0: default, running, active
        assertEquals("default", rowStates[0].name)
        assertTrue("default must be active", rowStates[0].isActive)
        assertTrue("default must be running", rowStates[0].isRunning)
        assertFalse("Selection must be disabled (read-only)", rowStates[0].isSelectable)
        assertEquals("default, active, running", ContextProfileSwitcherPolicy.rowContentDescription(rowStates[0]))

        // Row 1: ca, not running, inactive
        assertEquals("ca", rowStates[1].name)
        assertFalse("ca must not be active", rowStates[1].isActive)
        assertFalse("ca must not be running", rowStates[1].isRunning)
        assertFalse("Selection must be disabled (read-only)", rowStates[1].isSelectable)
        assertEquals("ca", ContextProfileSwitcherPolicy.rowContentDescription(rowStates[1]))

        // Row 2: test-ws, running, inactive
        assertEquals("test-ws", rowStates[2].name)
        assertFalse("test-ws must not be active", rowStates[2].isActive)
        assertTrue("test-ws must be running", rowStates[2].isRunning)
        assertFalse("Selection must be disabled (read-only)", rowStates[2].isSelectable)
        assertEquals("test-ws, running", ContextProfileSwitcherPolicy.rowContentDescription(rowStates[2]))
    }

    @Test
    fun dialogPolicy_activeRow_neverShowsConfirm_andNeverSwitches() {
        val activeRow = ContextProfileRowState(name = "default", isRunning = true, isActive = true)
        assertFalse(ContextProfileSwitcherPolicy.shouldShowConfirmDialog(activeRow))
        val action = ContextProfileSwitcherPolicy.handleRowClick(activeRow)
        assertEquals(ContextProfileClickAction.NoOp, action)
    }

    @Test
    fun dialogPolicy_inactiveRow_showsConfirm_neverAutoSwitches() {
        val inactiveRow = ContextProfileRowState(name = "architect", isRunning = false, isActive = false)
        assertTrue(ContextProfileSwitcherPolicy.shouldShowConfirmDialog(inactiveRow))
        val action = ContextProfileSwitcherPolicy.handleRowClick(inactiveRow)
        assertTrue("Tapping inactive row requests confirm rather than auto-switching", action is ContextProfileClickAction.RequestConfirm)
        assertEquals("architect", (action as ContextProfileClickAction.RequestConfirm).profileName)
    }

    @Test
    fun dialogPolicy_confirmDialog_workspaceNameOnly() {
        assertEquals("Switch workspace", ContextProfileSwitcherPolicy.CONFIRM_DIALOG_TITLE)
        assertEquals("Switch", ContextProfileSwitcherPolicy.CONFIRM_BUTTON_LABEL)
        assertEquals("Cancel", ContextProfileSwitcherPolicy.DISMISS_BUTTON_LABEL)
        assertEquals("architect", ContextProfileSwitcherPolicy.confirmDialogMessage("architect"))
    }

    @Test
    fun optimisticReducer_marksTargetActive_andOthersInactive() {
        val rows = listOf(
            ContextProfileRowState(name = "default", isRunning = true, isActive = true),
            ContextProfileRowState(name = "ca", isRunning = false, isActive = false),
            ContextProfileRowState(name = "architect", isRunning = true, isActive = false)
        )
        val loadedState = ContextProfileSheetState.Loaded(profiles = rows, activeProfileName = "default")

        val updated = ContextProfileSwitcherPolicy.reduceOptimisticSwitch(loadedState, "architect")
        assertEquals("architect", updated.activeProfileName)
        assertEquals(3, updated.profiles.size)

        assertFalse(updated.profiles[0].isActive) // default
        assertFalse(updated.profiles[1].isActive) // ca
        assertTrue(updated.profiles[2].isActive)  // architect
    }

    @Test
    fun rollbackReducer_restoresPreviousActive_andMarksTargetInactive() {
        val optimisticRows = listOf(
            ContextProfileRowState(name = "default", isRunning = true, isActive = false),
            ContextProfileRowState(name = "ca", isRunning = false, isActive = false),
            ContextProfileRowState(name = "architect", isRunning = true, isActive = true)
        )
        val optimisticState = ContextProfileSheetState.Loaded(profiles = optimisticRows, activeProfileName = "architect")

        val rolledBack = ContextProfileSwitcherPolicy.reduceRollbackSwitch(optimisticState, "default")
        assertEquals("default", rolledBack.activeProfileName)
        assertEquals(3, rolledBack.profiles.size)

        assertTrue(rolledBack.profiles[0].isActive)  // default
        assertFalse(rolledBack.profiles[1].isActive) // ca
        assertFalse(rolledBack.profiles[2].isActive) // architect
    }

    @Test
    fun activationErrorMessageFor_mapsSafeMessages() {
        // Activation failures must show the dedicated activation string, not
        // the workspace-list error strings.
        assertEquals("Failed to switch workspace", ContextProfileSwitcherPolicy.activationErrorMessageFor(ContextProfileErrorReason.UNAUTHORIZED))
        assertEquals("Failed to switch workspace", ContextProfileSwitcherPolicy.activationErrorMessageFor(ContextProfileErrorReason.UNAVAILABLE))
        assertEquals("Failed to switch workspace", ContextProfileSwitcherPolicy.activationErrorMessageFor(ContextProfileErrorReason.MALFORMED))
        assertEquals("Failed to switch workspace", ContextProfileSwitcherPolicy.activationErrorMessageFor(ContextProfileErrorReason.NO_ACTIVE_CONNECTION))
    }

    @Test
    fun midTurnSafetyPolicy_disablesSwitchingWhenStreaming() {
        assertTrue(ContextProfileSwitcherPolicy.isSwitchingAllowed(isStreaming = false))
        assertFalse(ContextProfileSwitcherPolicy.isSwitchingAllowed(isStreaming = true))
    }

    @Test
    fun midTurnSafetyPolicy_canSwitchTo_requiresNotActiveAndNotStreaming() {
        val activeRow = ContextProfileRowState(name = "default", isRunning = true, isActive = true)
        val inactiveRow = ContextProfileRowState(name = "architect", isRunning = false, isActive = false)

        assertFalse("Cannot switch to active row even if not streaming", ContextProfileSwitcherPolicy.canSwitchTo(activeRow, isStreaming = false))
        assertFalse("Cannot switch to active row when streaming", ContextProfileSwitcherPolicy.canSwitchTo(activeRow, isStreaming = true))
        assertTrue("Can switch to inactive row when not streaming", ContextProfileSwitcherPolicy.canSwitchTo(inactiveRow, isStreaming = false))
        assertFalse("Cannot switch to inactive row when streaming", ContextProfileSwitcherPolicy.canSwitchTo(inactiveRow, isStreaming = true))
    }

    @Test
    fun midTurnSafetyPolicy_rowClickReturnsDisabledWhenStreaming() {
        val inactiveRow = ContextProfileRowState(name = "architect", isRunning = false, isActive = false)
        val action = ContextProfileSwitcherPolicy.handleRowClick(inactiveRow, isStreaming = true)
        assertTrue(action is ContextProfileClickAction.Disabled)
        assertEquals(
            ContextProfileSwitcherPolicy.SWITCH_DISABLED_STREAMING_EXPLANATION,
            (action as ContextProfileClickAction.Disabled).message
        )
    }

    // ---- PR-2 TEST-08: offline roster provenance caption ----

    @Test
    fun offlineCaption_liveIsNeverLabelled() {
        assertNull(
            "Live results carry no offline caption",
            ContextProfileSwitcherPolicy.offlineRosterCaption(
                RosterSource.LIVE, 1_700_000_000_000L, 1_700_000_001_000L
            )
        )
        assertNull(
            "Live results carry no caption even without capture time",
            ContextProfileSwitcherPolicy.offlineRosterCaption(RosterSource.LIVE, null, 1_700_000_001_000L)
        )
    }

    @Test
    fun offlineCaption_cachedNow_readsExactJustNowString() {
        val now = 1_700_000_000_000L
        assertEquals(
            "Offline cache (saved just now)",
            ContextProfileSwitcherPolicy.offlineRosterCaption(RosterSource.CACHED, now, now)
        )
    }

    @Test
    fun offlineCaption_cachedWithoutCaptureTime_readsExactLabel() {
        assertEquals(
            "Offline cache",
            ContextProfileSwitcherPolicy.offlineRosterCaption(RosterSource.CACHED, null, 1_700_000_000_000L)
        )
    }

    @Test
    fun offlineCaption_cachedOlderCapture_usesRelativeVocabulary() {
        val now = 1_700_000_000_000L
        assertEquals(
            "Offline cache (saved 5h ago)",
            ContextProfileSwitcherPolicy.offlineRosterCaption(
                RosterSource.CACHED, now - 5L * 60 * 60 * 1000, now
            )
        )
        assertEquals(
            "Offline cache (saved Yesterday)",
            ContextProfileSwitcherPolicy.offlineRosterCaption(
                RosterSource.CACHED, now - 30L * 60 * 60 * 1000, now
            )
        )
    }
}
