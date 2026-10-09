package com.hermes.bot.feature.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * BLD-006 S5 (TASK-024 / D-04 / PAT-004): deterministic virtual-time probes for
 * the 32 ms coalescing publication scheduler. Every frozen axis is probed on
 * BOTH count and content: publications land at a bounded cadence (count) while
 * the concatenation of every publication equals the admitted stream exactly
 * once, in order (size/content). No real-time waits.
 *
 * Every test installs [MainDispatcherRule] so the production
 * `Dispatchers.Main.immediate` default resolves to the test scheduler, and
 * constructs each scheduler with a fresh [UnconfinedTestDispatcher] driven by
 * the same virtual clock, so admission and the publication loop share one
 * deterministic timeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeltaPublicationSchedulerTest {

    @get:Rule
    val mainDispatcherRule = com.hermes.bot.test.MainDispatcherRule()

    private class PublicationProbe {
        val texts = mutableListOf<String>()
        fun onPublish(text: String) {
            texts.add(text)
        }

        val aggregate: String get() = texts.joinToString("")
        val count: Int get() = texts.size
    }

    private fun newScheduler(): Pair<DeltaPublicationScheduler, PublicationProbe> {
        val probe = PublicationProbe()
        val scheduler = DeltaPublicationScheduler(
            onPublish = probe::onPublish,
            schedulerContext = UnconfinedTestDispatcher()
        )
        return Pair(scheduler, probe)
    }

    // ------------------------------------------------------------------
    // Cadence: coalescing reduces publication count (count axis)
    // ------------------------------------------------------------------

    @Test
    fun admit_manyFragmentsWithinOneWindow_publishesOnceAfterWindow() = runTest {
        val (scheduler, probe) = newScheduler()

        repeat(100) { i -> scheduler.admit("frag$i|") }
        advanceUntilIdle()

        // Coalesced into exactly one publication for the whole window.
        assertEquals(1, probe.count)
        assertEquals((0..99).joinToString("") { "frag$it|" }, probe.aggregate)
        scheduler.dispose()
    }

    @Test
    fun admit_duringPendingWindow_coalescesIntoNextPublication() = runTest {
        val (scheduler, probe) = newScheduler()

        scheduler.admit("A")
        advanceUntilIdle()
        assertEquals(1, probe.count)
        assertEquals("A", probe.aggregate)

        // Fragments admitted after the first publication coalesce into the
        // next window, never token-rate.
        scheduler.admit("B")
        scheduler.admit("C")
        advanceUntilIdle()
        assertEquals(2, probe.count)
        assertEquals("ABC", probe.aggregate)
        scheduler.dispose()
    }

    @Test
    fun cadence_defaultConstant_isFrozen32ms() {
        assertEquals(
            32L,
            DeltaPublicationScheduler.DEFAULT_PUBLICATION_INTERVAL_MS
        )
    }

    // ------------------------------------------------------------------
    // Losslessness: every admitted scalar exactly once, in order (size axis)
    // ------------------------------------------------------------------

    @Test
    fun admit_thousandsOfFragments_everyScalarExactlyOnceInOrder_zeroLoss() = runTest {
        val (scheduler, probe) = newScheduler()

        val fragments = (1..2000).map { "f$it|" }
        fragments.forEach { scheduler.admit(it) }
        advanceUntilIdle()

        val expected = fragments.joinToString("")
        assertEquals(expected, probe.aggregate)
        // Publication count is decoupled from fragment count: 2000 fragments
        // coalesce into a handful of publications, far below token rate.
        assertTrue(probe.count < 100)
        scheduler.dispose()
    }

    @Test
    fun admit_zeroAndEmptyFragments_produceNoPublication() = runTest {
        val (scheduler, probe) = newScheduler()

        scheduler.admit("")
        advanceUntilIdle()
        assertEquals(0, probe.count)
        scheduler.dispose()
    }

    @Test
    fun admit_afterDispose_isRejected_neverClaimedAsRetained() = runTest {
        val (scheduler, probe) = newScheduler()

        scheduler.admit("before")
        advanceUntilIdle()
        scheduler.dispose()
        val countAfterDispose = probe.count

        scheduler.admit("after")
        advanceUntilIdle()
        assertEquals(countAfterDispose, probe.count)
        assertEquals("before", probe.aggregate)
    }

    // ------------------------------------------------------------------
    // TASK-023 flush rules: boundary events flush pending text first
    // ------------------------------------------------------------------

    @Test
    fun flush_beforeBoundaryEvent_publishesPendingSynchronously_noWindowDelay() = runTest {
        val (scheduler, probe) = newScheduler()

        scheduler.admit("pending text")
        // No advanceUntilIdle: the window has not elapsed, nothing published.
        assertEquals(0, probe.count)

        // A tool/generic/error/terminal boundary flushes before publishing.
        scheduler.flush()
        assertEquals(1, probe.count)
        assertEquals("pending text", probe.aggregate)

        // The flush drains the buffer: a second flush is a no-op (no dup).
        scheduler.flush()
        assertEquals(1, probe.count)
        scheduler.dispose()
    }

    @Test
    fun flush_withNothingPending_isNoOp() = runTest {
        val (scheduler, probe) = newScheduler()

        scheduler.flush()
        assertEquals(0, probe.count)

        scheduler.admit("x")
        advanceUntilIdle()
        scheduler.flush()
        assertEquals(1, probe.count)
        scheduler.dispose()
    }

    // ------------------------------------------------------------------
    // Race probes (RISK-003): no admitted text silently lost
    // ------------------------------------------------------------------

    @Test
    fun dispose_flushesAdmittedPendingText_beforeStopping() = runTest {
        val (scheduler, probe) = newScheduler()

        scheduler.admit("admitted before disposal")
        // The window has not elapsed; disposal must still flush (TASK-023:
        // cancellation/session-switch/clear flush when locally possible).
        scheduler.dispose()

        assertEquals(1, probe.count)
        assertEquals("admitted before disposal", probe.aggregate)
    }

    @Test
    fun interleave_admit_flush_admit_publishesEveryScalarExactlyOnceInOrder() = runTest {
        val (scheduler, probe) = newScheduler()

        scheduler.admit("A")
        scheduler.admit("B")
        scheduler.flush() // boundary 1: tool row
        scheduler.admit("C")
        advanceUntilIdle()
        scheduler.flush() // boundary 2: terminal event
        scheduler.dispose()

        assertEquals(listOf("AB", "C"), probe.texts)
        assertEquals("ABC", probe.aggregate)
    }

    @Test
    fun interleave_terminalDuringPendingWindow_flushKeepsEveryScalar() = runTest {
        val (scheduler, probe) = newScheduler()

        scheduler.admit("first half")
        // Terminal arrives before the window elapses — RISK-003 probe.
        scheduler.flush()
        scheduler.admit("second half")
        scheduler.flush()
        scheduler.dispose()

        assertEquals(2, probe.count)
        assertEquals("first halfsecond half", probe.aggregate)
    }

    @Test
    fun interleave_longStreamWithFlushes_zeroDuplicationZeroReordering() = runTest {
        val (scheduler, probe) = newScheduler()

        val expected = StringBuilder()
        repeat(50) { round ->
            repeat(10) { i -> scheduler.admit("r${round}f$i|") }
            expected.append((0..9).joinToString("") { "r${round}f$it|" })
            if (round % 5 == 4) scheduler.flush() // periodic boundary
        }
        advanceUntilIdle()
        scheduler.dispose()

        assertEquals(expected.toString(), probe.aggregate)
        // Exactly-once: publications concatenated have no duplicated or
        // reordered scalars — the aggregate equality IS the probe.
        assertTrue(probe.count < 50)
    }
}
