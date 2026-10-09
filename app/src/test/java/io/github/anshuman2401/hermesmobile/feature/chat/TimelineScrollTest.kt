package io.github.anshuman2401.hermesmobile.feature.chat

import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TimelineScrollTest {
    @Test fun gestureCancellationLeavesObserverAliveForNextCorrection() = runTest {
        var correction: Job? = null
        var nextCorrection = false
        val observer = launch {
            runTimelineScroll({ correction = it }) { awaitCancellation() }
            runTimelineScroll({ correction = it }) { nextCorrection = true }
        }
        runCurrent()
        correction!!.cancel()
        runCurrent()
        assertTrue(nextCorrection)
        assertFalse(observer.isCancelled)
        assertNull(correction)
    }

    @Test fun disposalCancelsCorrectionAndClearsHandle() = runTest {
        var correction: Job? = null
        val observer = launch { runTimelineScroll({ correction = it }) { awaitCancellation() } }
        runCurrent()
        val active = correction!!
        observer.cancel()
        runCurrent()
        assertTrue(active.isCancelled)
        assertNull(correction)
    }
}
