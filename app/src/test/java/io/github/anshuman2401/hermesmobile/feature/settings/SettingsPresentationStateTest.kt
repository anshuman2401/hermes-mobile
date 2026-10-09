package io.github.anshuman2401.hermesmobile.feature.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsPresentationStateTest {
    @Test fun resetCannotBeArmedFromHomeOrAnUnrelatedSheet() {
        val home = SettingsPresentationState()
        val appearance = home.openSheet(SettingsSheet.APPEARANCE)
        for (choice in SettingsResetChoice.entries) {
            assertEquals(home, home.requestReset(choice))
            assertEquals(appearance, appearance.requestReset(choice))
            assertNull(home.confirmReset().second)
        }
    }

    @Test fun cancellationDismissesConfirmationBeforeThePrivacySheet() {
        val privacy = SettingsPresentationState().openSheet(SettingsSheet.PRIVACY)
        for (choice in SettingsResetChoice.entries) {
            val pending = privacy.requestReset(choice)
            assertEquals(choice, pending.pendingResetChoice)
            assertEquals(privacy, pending.dismiss())
            assertNull(pending.dismiss().confirmReset().second)
            assertEquals(SettingsPresentationState(), pending.dismiss().dismiss())
        }
    }

    @Test fun eachResetConfirmationProducesItsIntentOnce() {
        for (choice in SettingsResetChoice.entries) {
            var state = SettingsPresentationState().openSheet(SettingsSheet.PRIVACY).requestReset(choice)
            val emitted = mutableListOf<SettingsResetChoice>()
            repeat(2) {
                val (next, effect) = state.confirmReset()
                state = next
                effect?.let(emitted::add)
            }
            assertEquals(listOf(choice), emitted)
            assertEquals(SettingsSheet.PRIVACY, state.sheet)
            assertNull(state.pendingResetChoice)
        }
    }

    @Test fun removalRequiresConnectionsAndMultipleSavedConnections() {
        val home = SettingsPresentationState()
        assertNull(home.requestRemoval("example-a", 2).pendingRemoveProfileId)
        val connections = home.openSheet(SettingsSheet.CONNECTIONS)
        assertNull(connections.requestRemoval("example-a", 1).pendingRemoveProfileId)
        assertNull(connections.requestRemoval("", 2).pendingRemoveProfileId)
        val pending = connections.requestRemoval("example-a", 2)
        assertEquals(connections, pending.dismiss())
        val (next, removed) = pending.confirmRemoval(listOf("example-a", "example-b"))
        assertEquals("example-a", removed)
        assertNull(next.confirmRemoval(listOf("example-a", "example-b")).second)
    }

    @Test fun removalRevalidatesTheRosterBeforeEmittingAnIntent() {
        val pending = SettingsPresentationState().openSheet(SettingsSheet.CONNECTIONS).requestRemoval("example-a", 2)
        assertNull(pending.confirmRemoval(listOf("example-a")).second)
        assertNull(pending.confirmRemoval(listOf("example-b", "example-c")).second)
    }

    @Test fun openingAnotherSheetDiscardsPendingConfirmation() {
        val pending = SettingsPresentationState().openSheet(SettingsSheet.PRIVACY)
            .requestReset(SettingsResetChoice.CLEAR_ALL_LOCAL_DATA)
        assertEquals(SettingsPresentationState(sheet = SettingsSheet.APPEARANCE),
            pending.openSheet(SettingsSheet.APPEARANCE))
    }
}
