package io.github.anshuman2401.hermesmobile.navigation

import io.github.anshuman2401.hermesmobile.feature.connect.ConnectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSwitchPolicyTest {

    @Test
    fun switchAllowed_fromEveryConnectState() {
        val available = listOf("prof-active", "prof-target")
        for (state in ConnectState.entries) {
            val decision = ProfileSwitchPolicy.canSwitch(
                currentConnectState = state,
                targetLocalId = "prof-target",
                activeLocalId = "prof-active",
                availableProfileIds = available
            )
            assertEquals("Switch must be allowed from state $state", ProfileSwitchDecision.Allowed, decision)
        }
    }

    @Test
    fun switchBlocked_sameProfile() {
        for (state in ConnectState.entries) {
            val decision = ProfileSwitchPolicy.canSwitch(
                currentConnectState = state,
                targetLocalId = "prof-active",
                activeLocalId = "prof-active",
                availableProfileIds = listOf("prof-active", "prof-other")
            )
            assertEquals("Switching to currently active profile must be blocked as SameProfile",
                ProfileSwitchDecision.Blocked.SameProfile, decision)
        }
    }

    @Test
    fun switchBlocked_missingProfile() {
        val available = listOf("prof-1", "prof-2")
        val decisionMissing = ProfileSwitchPolicy.canSwitch(
            currentConnectState = ConnectState.CONNECTED_COMPATIBLE,
            targetLocalId = "prof-nonexistent",
            activeLocalId = "prof-1",
            availableProfileIds = available
        )
        assertEquals(ProfileSwitchDecision.Blocked.MissingProfile, decisionMissing)

        val decisionBlank = ProfileSwitchPolicy.canSwitch(
            currentConnectState = ConnectState.CONNECTED_COMPATIBLE,
            targetLocalId = "",
            activeLocalId = "prof-1",
            availableProfileIds = available
        )
        assertEquals(ProfileSwitchDecision.Blocked.MissingProfile, decisionBlank)
    }
}
