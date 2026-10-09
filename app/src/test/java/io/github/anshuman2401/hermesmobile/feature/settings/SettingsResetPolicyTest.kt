package io.github.anshuman2401.hermesmobile.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan CACHE-PR5 TEST-01 (FILE-12) — policy first, before any UI code.
 * Asserts the exact confirmation-copy bindings (plan Interfaces, line 76),
 * an exhaustive per-choice mapping, and the no-shared-mutable-copy guarantee
 * (each call returns a fresh immutable ConfirmationCopy).
 */
class SettingsResetPolicyTest {

    @Test
    fun clearCacheDialog_isExactlyBoundAndStatesWhatSurvives() {
        val copy = SettingsResetPolicy.copyFor(SettingsResetChoice.CLEAR_OFFLINE_CACHE)
        assertEquals("Clear offline cache?", copy.title)
        assertEquals(
            "Saved lists, workspace rosters, and conversations stored on this " +
                "device will be removed. Drafts, pending turns, sign-in, and your " +
                "Hermes sessions on the server are not affected. New data is " +
                "saved again as you use the app.",
            copy.message
        )
        assertEquals("Clear offline cache", copy.confirmLabel)
        assertEquals("Cancel", copy.cancelLabel)
        // TEST-01: its message states credentials/sign-in, drafts, and pending
        // turns are NOT affected.
        assertTrue(copy.message.contains("Drafts, pending turns, sign-in"))
        assertTrue(copy.message.contains("not affected"))
    }

    @Test
    fun clearAllDialog_isExactlyBoundAndStatesRecoverabilityPlusNoRemoteDelete() {
        val copy = SettingsResetPolicy.copyFor(SettingsResetChoice.CLEAR_ALL_LOCAL_DATA)
        assertEquals("Clear all local app data?", copy.title)
        assertEquals(
            "Erases everything this app stored on this device, including the " +
                "offline cache (saved lists, workspace rosters, and " +
                "conversations), drafts, pending turns, saved connections, and " +
                "sign-in. Your Hermes sessions on the server are not deleted. " +
                "You will set up the connection again.",
            copy.message
        )
        assertEquals("Erase all", copy.confirmLabel)
        assertEquals("Cancel", copy.cancelLabel)
        // TEST-01: cache + drafts + pending turns + sign-in recoverable, and
        // other sessions are NOT deleted.
        assertTrue(copy.message.contains("offline cache"))
        assertTrue(copy.message.contains("drafts, pending turns, saved connections, and sign-in"))
        assertTrue(copy.message.contains("You will set up the connection again"))
        assertTrue(copy.message.contains("are not deleted"))
    }

    @Test
    fun choicesAreExhaustiveDistinctAndCopiesAreNotShared() {
        val cache = SettingsResetPolicy.copyFor(SettingsResetChoice.CLEAR_OFFLINE_CACHE)
        val all = SettingsResetPolicy.copyFor(SettingsResetChoice.CLEAR_ALL_LOCAL_DATA)
        // Exhaustive member function over both enum entries: both resolve.
        assertTrue(cache.title != all.title)
        assertTrue(cache.message != all.message)
        assertTrue(cache.confirmLabel != all.confirmLabel)
        // No shared mutable copy: distinct instances per call.
        assertNotSame(cache, all)
        assertNotSame(cache, SettingsResetPolicy.copyFor(SettingsResetChoice.CLEAR_OFFLINE_CACHE))
        // Cancel is representable only as "Cancel" (the decision lives in the
        // screen: cancel -> back to IDLE, zero mutation, no callback fires).
        assertEquals("Cancel", cache.cancelLabel)
        assertEquals("Cancel", all.cancelLabel)
    }
}
