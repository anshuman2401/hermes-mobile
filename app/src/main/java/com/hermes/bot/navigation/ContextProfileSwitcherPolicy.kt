package com.hermes.bot.navigation

import com.hermes.bot.data.context.ContextProfile
import com.hermes.bot.data.context.ContextProfileErrorReason
import com.hermes.bot.data.context.RosterSource
import com.hermes.bot.feature.sessions.relativeActivityLabel
import java.time.Instant

/**
 * Pure JVM policy governing the Context Profile (workspace) bottom sheet UI,
 * row state mappings, non-switching explanation message, and accessibility strings.
 */
object ContextProfileSwitcherPolicy {

    const val MIN_TOUCH_TARGET_DP: Int = 48

    const val TRIGGER_CONTENT_DESCRIPTION: String = "Workspace profiles"
    const val CLOSE_SHEET_CONTENT_DESCRIPTION: String = "Close workspace profiles"
    const val ACTIVE_BADGE_CONTENT_DESCRIPTION: String = "Active workspace"
    const val RUNNING_STATUS_CONTENT_DESCRIPTION: String = "Running"

    const val TITLE: String = "Workspaces"
    const val SWITCH_DISABLED_EXPLANATION: String = "Workspace switching is not yet supported"

    const val CONFIRM_DIALOG_TITLE: String = "Switch workspace"
    const val CONFIRM_BUTTON_LABEL: String = "Switch"
    const val DISMISS_BUTTON_LABEL: String = "Cancel"

    const val ERROR_UNAUTHORIZED: String = "Sign in to view workspaces"
    const val ERROR_UNAVAILABLE: String = "Workspace list unavailable"
    const val ERROR_MALFORMED: String = "Workspace list format not recognized"
    const val ERROR_NO_CONNECTION: String = "No active connection"
    const val ERROR_EMPTY: String = "No workspaces found"
    const val LOADING_MESSAGE: String = "Loading workspaces..."

    const val ERROR_ACTIVATION_FAILED: String = "Failed to switch workspace"
    const val SWITCH_DISABLED_STREAMING_EXPLANATION: String = "Workspace switching is unavailable while a response is generating"

    /** PR-2 REQ-05: offline roster provenance copy — the sole owner (CON-04). */
    const val OFFLINE_ROSTER_LABEL: String = "Offline cache"
    const val OFFLINE_ROSTER_CAPTION_PREFIX: String = "Offline cache (saved "
    const val OFFLINE_ROSTER_CAPTION_SUFFIX: String = ")"

    /** Applies confirmation UI effects; dispatch reports whether the switch was started. */
    fun confirmSwitch(
        dismissDialog: () -> Unit,
        dispatchSwitch: () -> Boolean,
        closeSheet: () -> Unit
    ) {
        dismissDialog()
        if (dispatchSwitch()) closeSheet()
    }

    fun isSwitchingAllowed(isStreaming: Boolean): Boolean = !isStreaming

    fun canSwitchTo(row: ContextProfileRowState, isStreaming: Boolean = false): Boolean =
        !row.isActive && isSwitchingAllowed(isStreaming)

    fun confirmDialogMessage(workspaceName: String): String = workspaceName

    fun shouldShowConfirmDialog(row: ContextProfileRowState, isStreaming: Boolean = false): Boolean =
        canSwitchTo(row, isStreaming)

    fun handleRowClick(
        row: ContextProfileRowState,
        isStreaming: Boolean = false
    ): ContextProfileClickAction {
        if (row.isActive) return ContextProfileClickAction.NoOp
        if (!isSwitchingAllowed(isStreaming)) {
            return ContextProfileClickAction.Disabled(SWITCH_DISABLED_STREAMING_EXPLANATION)
        }
        return ContextProfileClickAction.RequestConfirm(row.name)
    }

    fun reduceOptimisticSwitch(
        rows: List<ContextProfileRowState>,
        targetName: String
    ): List<ContextProfileRowState> =
        rows.map { row ->
            row.copy(isActive = (row.name == targetName))
        }

    fun reduceOptimisticSwitch(
        state: ContextProfileSheetState.Loaded,
        targetName: String
    ): ContextProfileSheetState.Loaded =
        state.copy(
            profiles = reduceOptimisticSwitch(state.profiles, targetName),
            activeProfileName = targetName
        )

    fun reduceRollbackSwitch(
        rows: List<ContextProfileRowState>,
        previousActiveName: String?
    ): List<ContextProfileRowState> =
        rows.map { row ->
            row.copy(isActive = (row.name == previousActiveName))
        }

    fun reduceRollbackSwitch(
        state: ContextProfileSheetState.Loaded,
        previousActiveName: String?
    ): ContextProfileSheetState.Loaded =
        state.copy(
            profiles = reduceRollbackSwitch(state.profiles, previousActiveName),
            activeProfileName = previousActiveName
        )

    fun errorMessageFor(reason: ContextProfileErrorReason): String = when (reason) {
        ContextProfileErrorReason.UNAUTHORIZED -> ERROR_UNAUTHORIZED
        ContextProfileErrorReason.UNAVAILABLE -> ERROR_UNAVAILABLE
        ContextProfileErrorReason.MALFORMED -> ERROR_MALFORMED
        ContextProfileErrorReason.NO_ACTIVE_CONNECTION -> ERROR_NO_CONNECTION
    }

    fun activationErrorMessageFor(reason: ContextProfileErrorReason): String = ERROR_ACTIVATION_FAILED

    /**
     * PR-2 REQ-05: exact provenance caption for the Workspaces sheet — null for
     * live data (never labelled offline), relative capture age for a cached
     * roster, or [OFFLINE_ROSTER_LABEL] when no capture time was carried.
     * Pure (CON-04): [nowMillis] is injected; wording reuses the sessions'
     * `relativeActivityLabel` vocabulary.
     */
    fun offlineRosterCaption(
        source: RosterSource,
        capturedAtMillis: Long?,
        nowMillis: Long
    ): String? = when (source) {
        RosterSource.LIVE -> null
        RosterSource.CACHED ->
            if (capturedAtMillis == null) {
                OFFLINE_ROSTER_LABEL
            } else {
                OFFLINE_ROSTER_CAPTION_PREFIX +
                    relativeActivityLabel(
                        Instant.ofEpochMilli(nowMillis),
                        Instant.ofEpochMilli(capturedAtMillis)
                    ) +
                    OFFLINE_ROSTER_CAPTION_SUFFIX
            }
    }

    fun toRowStates(profiles: List<ContextProfile>): List<ContextProfileRowState> =
        profiles.map { profile ->
            ContextProfileRowState(
                name = profile.name,
                isRunning = profile.isRunning,
                isActive = profile.isActive,
                isSelectable = false
            )
        }

    fun rowContentDescription(row: ContextProfileRowState): String {
        val parts = mutableListOf(row.name)
        if (row.isActive) parts.add("active")
        if (row.isRunning) parts.add("running")
        return parts.joinToString(", ")
    }

    fun onRowClicked(): String = SWITCH_DISABLED_EXPLANATION
}

/**
 * Presentation model for an individual Context Profile row in the bottom sheet.
 * Always has isSelectable = false because workspace activation is uncertified and disallowed.
 */
data class ContextProfileRowState(
    val name: String,
    val isRunning: Boolean,
    val isActive: Boolean,
    val isSelectable: Boolean = false
)

/**
 * UI states for the Context Profile bottom sheet.
 */
sealed interface ContextProfileSheetState {
    object Loading : ContextProfileSheetState
    data class Loaded(
        val profiles: List<ContextProfileRowState>,
        val activeProfileName: String?,
        /** PR-2: provenance of the served roster (default keeps fixtures compiling). */
        val source: RosterSource = RosterSource.LIVE,
        val capturedAtMillis: Long? = null
    ) : ContextProfileSheetState
    data class Error(val message: String) : ContextProfileSheetState
    object Empty : ContextProfileSheetState
}

sealed interface ContextProfileClickAction {
    data class RequestConfirm(val profileName: String) : ContextProfileClickAction
    data class Disabled(val message: String) : ContextProfileClickAction
    object NoOp : ContextProfileClickAction
}
