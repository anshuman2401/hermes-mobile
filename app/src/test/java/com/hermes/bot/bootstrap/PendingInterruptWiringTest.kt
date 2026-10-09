package com.hermes.bot.bootstrap

import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.ledger.PendingInterruptQueue
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * T3 PR-2 wiring tests (plan TASK-004 / TEST-002 / TEST-004 / TEST-005 /
 * TEST-006 / TEST-007): the production flush observer must deliver holds once
 * per observed transition into `ConnectionStatus.CONNECTED`, through T1's own
 * `interruptSession` seam, honoring the queue's per-generation single-flight
 * and 60 s TTL — and, per the FD-001 gate (RISK-004), only ever against a
 * LIVE runtime id resolved from session state, never an enqueue-time snapshot
 * restored after process death.
 *
 * Behavior tests carry the proof over a real [PendingInterruptQueue] on a
 * real (test-key) [EncryptedVault]; the final source-seam test guards the
 * BUG-012 rule 1 composition wiring (the observer must be armed where the
 * container is built, not reachable only through a first-use path).
 * This JVM suite uses no live hosts; device checks are separate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingInterruptWiringTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var vault: EncryptedVault
    private lateinit var fake: FakeGatewayProtocolAdapter
    private lateinit var adapter: GatewayProtocolAdapter
    private val generation = AtomicLong(1L)
    private var now = 1_700_000_000_000L
    private lateinit var queue: PendingInterruptQueue
    private lateinit var scope: CoroutineScope

    /**
     * The live session-state seam the observer resolves through (FD-001):
     * stored session id -> LIVE runtime id. Tests register a mapping to model
     * a same-process live session; leaving it empty models post-death state
     * where no live id exists yet.
     */
    private val liveSessions = mutableMapOf<String, String>()

    /**
     * The shared fake's connectionGeneration is fixed at the interface
     * default (0); the wiring needs a generation that can advance, exactly
     * like DefaultGatewayProtocolAdapter's ++per connect attempt.
     */
    private class GenerationAwareAdapter(
        private val delegate: FakeGatewayProtocolAdapter,
        private val generation: AtomicLong
    ) : GatewayProtocolAdapter by delegate {
        override val connectionGeneration: Long get() = generation.get()
    }

    @Before
    fun setUp() {
        vault = EncryptedVault(
            storageDir = tempFolder.newFolder("flush_wiring_vault"),
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = testDispatcher
        )
        fake = FakeGatewayProtocolAdapter()
        adapter = GenerationAwareAdapter(fake, generation)
        queue = PendingInterruptQueue(vault) { now }
        // The observer must latch transitions, never a pre-existing CONNECTED.
        fake.connectionStatus.value = ConnectionStatus.OFFLINE
        scope = CoroutineScope(testDispatcher)
    }

    @After
    fun tearDown() {
        // BUG-012 rule 6: cancel the component scope, never one sharing the
        // TestScope job.
        scope.cancel()
    }

    /** Arms the production observer exactly like DefaultAppContainer does. */
    private fun startObserver() {
        PendingInterruptFlushObserver(
            queue = queue,
            adapter = adapter,
            liveRuntimeIdFor = { storedId -> liveSessions[storedId] },
            scope = scope
        )
    }

    @Test
    fun flushOnConnectedTransition_deliversHoldOnce_thenAcksItAway() = runTest(testDispatcher) {
        assertTrue(queue.enqueue("sess-flush", runtimeSessionId = "rt-flush"))
        // Same-process live session: the resolver knows the live id for this
        // stored id, so the first flush must deliver (no over-blocking).
        liveSessions["sess-flush"] = "rt-flush"
        startObserver()
        advanceUntilIdle() // collector attaches while OFFLINE: no flush yet

        fake.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()

        assertEquals("Exactly one delivery via the T1 seam", listOf("rt-flush"), fake.interruptedSessions)
        assertEquals("Success must drop the hold", 0, queue.getPendingHolds().size)

        // A later CONNECTED with no new hold sends nothing (TEST-002).
        fake.connectionStatus.value = ConnectionStatus.OFFLINE
        advanceUntilIdle()
        fake.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        assertEquals("No hold -> no repeat interrupt", 1, fake.interruptedSessions.size)
    }

    @Test
    fun flushFailure_reHolds_singleFlightPerGeneration_thenDeliversOnNextGeneration() = runTest(testDispatcher) {
        fake.interruptResult = GatewayResult.Failure(GatewayFailure.NotConnected("dropped mid-flush"))
        assertTrue(queue.enqueue("sess-rehold", runtimeSessionId = "rt-rehold"))
        liveSessions["sess-rehold"] = "rt-rehold" // same-process live session
        startObserver()
        advanceUntilIdle()

        fake.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        assertEquals(1, fake.interruptedSessions.size)
        assertEquals("Failure must re-hold (FD-004)", 1, queue.getPendingHolds().size)

        // FAILED -> CONNECTED flap on the SAME generation: no second attempt
        // (wiring-level single-flight, TEST-005: never a storm).
        fake.connectionStatus.value = ConnectionStatus.FAILED
        advanceUntilIdle()
        fake.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        assertEquals("One attempt per connection generation", 1, fake.interruptedSessions.size)

        // A fresh connection generation retries the re-held stop exactly once.
        generation.incrementAndGet()
        fake.connectionStatus.value = ConnectionStatus.FAILED
        advanceUntilIdle()
        fake.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        assertEquals("Next generation delivers the re-hold", 2, fake.interruptedSessions.size)
        assertEquals("Still failing -> held again for a later generation", 1, queue.getPendingHolds().size)
    }

    @Test
    fun expiredHold_isPurgedAtFlush_andNeverSent() = runTest(testDispatcher) {
        assertTrue(queue.enqueue("sess-expiry", runtimeSessionId = "rt-expiry"))
        now += 60_001L // strictly past the 60 s TTL (FD-003)
        startObserver()
        advanceUntilIdle()

        fake.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()

        assertTrue("A hold past TTL must never reach the adapter", fake.interruptedSessions.isEmpty())
        assertEquals("Expiry must purge the vault record", 0, vault.getPendingInterrupts().size)
        assertEquals(0, queue.getPendingHolds().size)
    }

    /**
     * TEST-006 (restore-after-death): a hold restored by a NEW queue
     * instance over the same vault must be held back until a LIVE runtime id
     * for that stored id exists, then delivered exactly once to the LIVE id —
     * never the enqueue-time snapshot (FD-001 cost (b), RISK-004).
     */
    @Test
    fun restoredHold_waitsForLiveRuntimeId_thenDeliversToLiveId() = runTest(testDispatcher) {
        // Pre-death capture: queue A records the hold with its snapshot.
        assertTrue(queue.enqueue("sess-restore", runtimeSessionId = "rt-snapshot"))
        // True restore: a fresh queue instance over the same vault directory.
        val restoredQueue = PendingInterruptQueue(vault) { now }
        var liveId: String? = null // after restart: no live session state yet
        PendingInterruptFlushObserver(
            queue = restoredQueue,
            adapter = adapter,
            liveRuntimeIdFor = { liveId },
            scope = scope
        )
        advanceUntilIdle()

        fake.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()

        assertTrue(
            "TEST-006: restored snapshot must never be delivered without a live id",
            fake.interruptedSessions.isEmpty()
        )
        assertEquals("Hold survives for a later live id", 1, restoredQueue.getPendingHolds().size)

        // The session is resumed: a LIVE runtime id now exists for the stored
        // id. A new connection generation (resume re-connects) flushes again.
        liveId = "rt-live-after-restore"
        generation.incrementAndGet()
        fake.connectionStatus.value = ConnectionStatus.FAILED
        advanceUntilIdle()
        fake.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()

        assertEquals(
            "Delivered exactly once, to the LIVE id — never the snapshot",
            listOf("rt-live-after-restore"),
            fake.interruptedSessions
        )
        assertEquals("Success acks the restored hold away", 0, restoredQueue.getPendingHolds().size)
    }

    @Test
    fun holdWithoutLiveRuntimeId_isNeverDelivered() = runTest(testDispatcher) {
        // Queue-level guard only: production capture never enqueues a null
        // snapshot — the post-death restore shape carries a snapshot, and it
        // is gated by the live-id resolver (TEST-006 above, FD-001/RISK-004).
        assertTrue(queue.enqueue("sess-no-rt", runtimeSessionId = null))
        startObserver()
        advanceUntilIdle()

        fake.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()

        assertTrue(fake.interruptedSessions.isEmpty())
        assertEquals("Held back until TTL drops it (FD-001)", 1, queue.getPendingHolds().size)
    }

    @Test
    fun pendingInterrupt_wiringSeamsExistInProductionSources() {
        // Supplemental source assertions (BUG-012 rule 1) — the behavior
        // tests above prove the observer itself; this proves production
        // composition arms it and threads the capture seam.
        val bootstrapDir = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .map { it.resolve("com/hermes/bot/bootstrap") }
            .first { it.resolve("AppContainer.kt").exists() }

        val appContainerSrc = bootstrapDir.resolve("AppContainer.kt").readText()
        assertTrue(
            "T3 PR-2: DefaultAppContainer must construct the flush observer at composition time",
            appContainerSrc.contains("PendingInterruptFlushObserver(")
        )
        assertTrue(
            "T3 PR-2: the observer must be armed with the production queue and adapter (BUG-012 rule 1)",
            appContainerSrc.contains("queue = pendingInterruptQueue")
        )
        assertTrue(
            "T3 PR-2: the FD-001 live-id resolver must be wired at composition time (never the snapshot)",
            appContainerSrc.contains("liveRuntimeIdFor")
        )
        assertTrue(
            "T3 PR-2: createChatViewModel must inject the queue for the capture path",
            appContainerSrc.contains("pendingInterruptQueue = pendingInterruptQueue")
        )

        val chatDir = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .map { it.resolve("com/hermes/bot/feature/chat") }
            .first { it.resolve("ChatViewModel.kt").exists() }
        val chatSrc = chatDir.resolve("ChatViewModel.kt").readText()
        assertTrue(
            "T3 PR-2: stopStreaming must capture the T1 result into the queue (TASK-005)",
            chatSrc.contains("pendingInterruptQueue") && chatSrc.contains(".enqueue(")
        )
    }
}
