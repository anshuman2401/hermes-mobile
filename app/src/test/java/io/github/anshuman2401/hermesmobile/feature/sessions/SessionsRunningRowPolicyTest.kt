package io.github.anshuman2401.hermesmobile.feature.sessions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsRunningRowPolicyTest {
    private fun marked(
        rowId: String = "stored-a",
        streamingId: String? = "stored-a",
        isStreaming: Boolean = true,
        isOfflineCache: Boolean = false,
        hasNetworkFailure: Boolean = false,
        hasViewModel: Boolean = true
    ) = SessionsRunningRowPolicy.showRunning(
        rowId, streamingId, isStreaming, isOfflineCache, hasNetworkFailure, hasViewModel
    )

    @Test fun exactNonblankStoredIdMarksOnlyItsRow() {
        assertTrue(marked())
        assertFalse(marked(rowId = "stored-b"))
        assertFalse(marked(rowId = "Stored-a"))
    }

    @Test fun missingOrBlankIdentitiesNeverMark() {
        assertFalse(marked(streamingId = null))
        assertFalse(marked(streamingId = ""))
        assertFalse(marked(streamingId = "  "))
        assertFalse(marked(rowId = "" , streamingId = ""))
        assertFalse(marked(rowId = " ", streamingId = " "))
    }

    @Test fun streamingStopClearsMark() {
        assertTrue(marked())
        assertFalse(marked(isStreaming = false))
        assertTrue(marked())
    }

    @Test fun offlineCacheAndNetworkFailureIndependentlyClearMark() {
        assertFalse(marked(isOfflineCache = true))
        assertFalse(marked(hasNetworkFailure = true))
        assertFalse(marked(isOfflineCache = true, hasNetworkFailure = true))
        assertTrue(marked(isOfflineCache = false, hasNetworkFailure = false))
    }

    @Test fun previewOnlyCannotMarkEvenWithMatchingId() {
        assertFalse(marked(hasViewModel = false))
    }
}
