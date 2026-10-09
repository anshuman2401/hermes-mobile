package io.github.anshuman2401.hermesmobile.navigation

import io.github.anshuman2401.hermesmobile.data.context.ContextProfileErrorReason
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSwitchConfirmationTest {
    private class Ui {
        var sheetOpen = true
        var dialogOpen = true
        var dispatched = false
        var error: String? = null
        var rows = ContextProfileSheetState.Loaded(
            listOf(
                ContextProfileRowState("default", false, true),
                ContextProfileRowState("synthetic-workspace", false, false)
            ),
            "default"
        )
        var completeFailure: (() -> Unit)? = null

        fun confirm(streaming: Boolean = false, repositoryAvailable: Boolean = true) {
            ContextProfileSwitcherPolicy.confirmSwitch(
                dismissDialog = { dialogOpen = false },
                dispatchSwitch = {
                    assertFalse("Dialog dismisses before dispatch", dialogOpen)
                    if (!ContextProfileSwitcherPolicy.isSwitchingAllowed(streaming)) {
                        error = ContextProfileSwitcherPolicy.SWITCH_DISABLED_STREAMING_EXPLANATION
                        false
                    } else if (!repositoryAvailable) {
                        false
                    } else {
                        dispatched = true
                        rows = ContextProfileSwitcherPolicy.reduceOptimisticSwitch(rows, "synthetic-workspace")
                        completeFailure = {
                            rows = ContextProfileSwitcherPolicy.reduceRollbackSwitch(rows, "default")
                            error = ContextProfileSwitcherPolicy.activationErrorMessageFor(ContextProfileErrorReason.UNAVAILABLE)
                        }
                        true
                    }
                },
                closeSheet = { sheetOpen = false }
            )
        }
    }

    @Test fun confirmDispatchClosesSheetAndDialogBeforeResult() {
        val ui = Ui()
        ui.confirm()
        assertTrue(ui.dispatched)
        assertFalse(ui.dialogOpen)
        assertFalse("Sheet closes without waiting for activation result", ui.sheetOpen)
        assertNull(ui.error)
        assertNotNull(ui.completeFailure)
    }

    @Test fun streamingStartedAfterDialogOpenedKeepsSheetOpen() {
        val ui = Ui()
        ui.confirm(streaming = true)
        assertFalse(ui.dispatched)
        assertFalse(ui.dialogOpen)
        assertTrue(ui.sheetOpen)
        assertEquals(ContextProfileSwitcherPolicy.SWITCH_DISABLED_STREAMING_EXPLANATION, ui.error)
    }

    @Test fun failedActivationRollsBackWithoutReopeningOverUnderlyingError() {
        val ui = Ui()
        ui.confirm()
        ui.completeFailure!!.invoke()
        assertEquals("default", ui.rows.activeProfileName)
        assertTrue(ui.rows.profiles.first().isActive)
        assertFalse(ui.dialogOpen)
        assertFalse("Rollback must leave the underlying screen visible", ui.sheetOpen)
        assertEquals("Failed to switch workspace", ui.error)
    }

    @Test fun noDispatchKeepsSheetOpen() {
        val ui = Ui()
        ui.confirm(repositoryAvailable = false)
        assertFalse(ui.dispatched)
        assertFalse(ui.dialogOpen)
        assertTrue(ui.sheetOpen)
    }
}
