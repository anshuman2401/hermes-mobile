package io.github.anshuman2401.hermesmobile.feature.settings

import io.github.anshuman2401.hermesmobile.data.local.CacheEpochRotationReason
import io.github.anshuman2401.hermesmobile.data.local.SignOutCacheChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-6 TEST-04: decision vocabulary & dialog contract
 * (REQ-01/REQ-02/REQ-06 — grounds the enum's epoch policy functions and the
 * policy object's exact literals).
 */
class SignOutCacheChoicePolicyTest {

    @Test
    fun policy_labelsExact_defaultIsKeep_cancelAborts_resolveExhaustive() {
        assertEquals("Sign out", SignOutCacheChoicePolicy.title())
        assertEquals(
            "Sign out of this connection? Choose what to do with this device's saved offline cache.",
            SignOutCacheChoicePolicy.message()
        )
        assertEquals("Keep saved cache on this device", SignOutCacheChoicePolicy.keepLabel())
        assertEquals("Clear saved cache", SignOutCacheChoicePolicy.clearLabel())
        assertEquals("Sign out", SignOutCacheChoicePolicy.confirmLabel())
        assertEquals("Cancel", SignOutCacheChoicePolicy.cancelLabel())

        // Default selection is ALWAYS Keep-cache (both entries share it).
        assertEquals(SignOutCacheChoice.KEEP, SignOutCacheChoicePolicy.defaultSelection())

        // Cancel/back/dismiss aborts sign-out entirely: no SignOutCacheChoice
        // value exists for abort, so no choice can escape without confirm —
        // the only escape hatch is resolve() fed by an explicit selection,
        // and it is exhaustive over exactly the two values.
        assertEquals(2, SignOutCacheChoice.entries.size)
        assertEquals(
            SignOutCacheChoice.KEEP,
            SignOutCacheChoicePolicy.resolve(SignOutCacheChoice.KEEP)
        )
        assertEquals(
            SignOutCacheChoice.CLEAR,
            SignOutCacheChoicePolicy.resolve(SignOutCacheChoice.CLEAR)
        )
    }

    @Test
    fun epochPolicy_rotateAlwaysIsEnumProperty_retainsPartitionExactly() {
        // REQ-01: rotationReason() is SIGNED_OUT for BOTH values —
        // ROTATE-always is a property of the enum, not of the branch.
        for (choice in SignOutCacheChoice.entries) {
            assertEquals(
                "ROTATE-always holds for $choice",
                CacheEpochRotationReason.SIGNED_OUT,
                choice.rotationReason()
            )
        }

        // REQ-02: the two predicates partition exactly — every value
        // retains XOR purges; there is no third/implicit state.
        assertTrue(SignOutCacheChoice.KEEP.retainsCiphertext())
        assertTrue(SignOutCacheChoice.CLEAR.physicallyPurges())
        for (choice in SignOutCacheChoice.entries) {
            assertTrue(
                "$choice must retain XOR purge",
                choice.retainsCiphertext() xor choice.physicallyPurges()
            )
        }

        // Req REQ-07 coupling: the physically purging value is exactly CLEAR.
        assertTrue(SignOutCacheChoice.CLEAR.physicallyPurges())
        assertTrue(!SignOutCacheChoice.KEEP.physicallyPurges())
    }
}
