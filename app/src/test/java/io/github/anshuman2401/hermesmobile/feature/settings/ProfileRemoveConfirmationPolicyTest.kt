package io.github.anshuman2401.hermesmobile.feature.settings

import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for ProfileRemoveConfirmationPolicy (MEDIUM-3).
 */
class ProfileRemoveConfirmationPolicyTest {

    @Test
    fun confirmationTitle_returnsExpected() {
        assertEquals("Remove connection", ProfileRemoveConfirmationPolicy.confirmationTitle())
    }

    @Test
    fun confirmationMessage_formatsCorrectly() {
        val message = ProfileRemoveConfirmationPolicy.confirmationMessage("Work Server")
        assertEquals(
            "Remove connection Work Server? This deletes its saved sign-in.",
            message
        )
    }

    @Test
    fun displayLabel_prefersDisplayLabel_fallsBackToNormalizedOrigin() {
        val withLabel = ConnectionProfile(
            localId = "p1",
            normalizedOrigin = "https://hermes.local:8443",
            displayLabel = "Work"
        )
        assertEquals("Work", ProfileRemoveConfirmationPolicy.displayLabel(withLabel))

        val withoutLabel = ConnectionProfile(
            localId = "p2",
            normalizedOrigin = "https://hermes.local:8443",
            displayLabel = ""
        )
        assertEquals("https://hermes.local:8443", ProfileRemoveConfirmationPolicy.displayLabel(withoutLabel))

        val blankLabel = ConnectionProfile(
            localId = "p3",
            normalizedOrigin = "https://hermes.local:8443",
            displayLabel = "   "
        )
        assertEquals("https://hermes.local:8443", ProfileRemoveConfirmationPolicy.displayLabel(blankLabel))
    }

    @Test
    fun canRemove_allowedOnlyWhenMoreThanOneProfile() {
        assertFalse(ProfileRemoveConfirmationPolicy.canRemove(0))
        assertFalse(ProfileRemoveConfirmationPolicy.canRemove(1))
        assertTrue(ProfileRemoveConfirmationPolicy.canRemove(2))
        assertTrue(ProfileRemoveConfirmationPolicy.canRemove(5))
    }
}
