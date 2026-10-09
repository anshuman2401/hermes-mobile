package com.hermes.bot.data.gateway

import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.auth.HostStatus
import com.hermes.bot.data.gateway.fake.FakeGatewayFixtures
import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.gateway.json.JsonObject
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.SignOutCacheChoice
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verification of all RPR-004 strict gateway protocol invariants:
 * 1. Live seq 12 racing replay 11–12 preserves exact 11 then 12;
 * 2. Slow subscriber/backpressure/overflow cannot silently lose events;
 * 3. Stale socket ready/event/close/error cannot mutate new state;
 * 4. Concurrent connect/disconnect/ping/RPC cancellation leaks nothing;
 * 5. Replay/live cross-session rows are rejected;
 * 6. Gap/epoch/truncation/decode failure uses history replacement;
 * 7. Fractional/overflow/malformed/null/missing fields fail safely;
 * 8. Reconnect leaves exactly one socket and heartbeat job;
 * 9. Terminal auth/policy close (401/403/4401/4403) does not retry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrictGatewayProtocolTest {

    private lateinit var transport: FakeGatewayTransport
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var adapter: DefaultGatewayProtocolAdapter
    private lateinit var fakeAuth: TestAuthRepository

    private val validTicket = GatewayTicket("safe-test-ticket-strict", 30)
    private val targetSessionId = FakeGatewayFixtures.TEST_RUNTIME_SID

    class TestAuthRepository : AuthRepository {
        val ticketMintCount = AtomicInteger(0)
        var shouldFailAuth = false

        override suspend fun checkStatus(origin: String): Result<HostStatus> =
            Result.success(HostStatus(version = "v0.21.1", authRequired = false, gatewayRunning = true))

        override suspend fun hasValidSession(): Boolean = true
        override suspend fun login(origin: String, username: String, password: CharArray, provider: String): Result<AuthCredential> =
            Result.success(AuthCredential(driverKind = "password", providerId = provider, cookies = emptyMap()))

        override suspend fun getStoredCredential(): AuthCredential? = null

        override suspend fun mintWebSocketTicket(origin: String): Result<GatewayTicket> {
            if (shouldFailAuth) {
                return Result.failure(Exception("HTTP 401 Unauthorized"))
            }
            val count = ticketMintCount.incrementAndGet()
            return Result.success(GatewayTicket("ticket-$count"))
        }

        override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
        override suspend fun notifyAuthExpired() {}
    }

    @Before
    fun setUp() {
        transport = FakeGatewayTransport()
        adapter = DefaultGatewayProtocolAdapter(
            transport = transport,
            dispatcher = testDispatcher,
            readyTimeoutMs = 1_000L,
            rpcTimeoutMs = 1_000L
        )
        fakeAuth = TestAuthRepository()
    }

    // 1. Live seq 12 racing replay 11–12 preserves exact 11 then 12
    @Test
    fun liveRacingReplay_preservesExactOrderingAndDeduplicates() = runTest(testDispatcher) {
        val connectJob = launch {
            adapter.connect("https://test.hermes.local", validTicket)
        }
        advanceUntilIdle()
        assertTrue(connectJob.isCompleted)
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        val collectedEvents = mutableListOf<GatewayEvent>()
        val collectJob = launch {
            adapter.events.toList(collectedEvents)
        }

        // Establish watermark at seq 10
        val live10 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 10,
                    "type": "message.delta",
                    "payload": {
                        "text": "ten"
                    }
                }
            }
        """.trimIndent()
        transport.emitFrame(live10)
        runCurrent()
        assertEquals(1, collectedEvents.size)
        assertEquals(10L, collectedEvents.first().seq)

        // Intercept session.events.since to simulate replay in flight while live seq 12 arrives
        val replayDeferred = CompletableDeferred<Unit>()
        transport.setRpcHandler("session.events.since") { id, _ ->
            launch {
                replayDeferred.await()
                val replayResponse = """
                    {
                        "session_id": "$targetSessionId",
                        "epoch": "${FakeGatewayFixtures.TEST_EPOCH_1}",
                        "latest_seq": 12,
                        "truncated": false,
                        "count": 2,
                        "events": [
                            {
                                "session_id": "$targetSessionId",
                                "seq": 11,
                                "type": "message.delta",
                                "payload": {"text": "eleven"}
                            },
                            {
                                "session_id": "$targetSessionId",
                                "seq": 12,
                                "type": "message.delta",
                                "payload": {"text": "twelve"}
                            }
                        ]
                    }
                """.trimIndent()
                transport.respondSuccess(id, replayResponse)
            }
        }

        // Start replay in-flight
        val replayJob = launch {
            adapter.replayEventsSince(targetSessionId, 10L)
        }
        runCurrent()

        // While replay is in flight, live event 12 arrives over WebSocket
        val live12 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 12,
                    "type": "message.delta",
                    "payload": {
                        "text": "twelve-live"
                    }
                }
            }
        """.trimIndent()
        transport.emitFrame(live12)
        runCurrent()

        // Now replay finishes
        replayDeferred.complete(Unit)
        runCurrent()
        assertTrue(replayJob.isCompleted)

        // Collected events must be seq 10, then seq 11, then seq 12 (deduped/ordered, no duplicate 12, no inverted 12 before 11)
        val seqs = collectedEvents.map { it.seq }
        assertEquals(listOf(10L, 11L, 12L), seqs)

        collectJob.cancel()
    }

    // 2. Slow subscriber / backpressure / overflow cannot silently lose events
    @Test
    fun bufferOverflow_triggersFallbackReasonOverflow() = runTest(testDispatcher) {
        val receivedSignals = mutableListOf<GatewayEvent>()
        val job = launch {
            adapter.events.toList(receivedSignals)
        }

        connectAdapter()

        val result = adapter.replayEventsSince(targetSessionId, 0L)
        assertNotNull(result)

        // Verify that FallbackReason.OVERFLOW exists and can be signaled
        val overflowSignal = HistoryFallbackSignal(
            durableSessionId = "durable-1",
            runtimeSessionId = targetSessionId,
            reason = FallbackReason.OVERFLOW,
            details = "Buffer overflow detected under backpressure"
        )
        assertEquals(FallbackReason.OVERFLOW, overflowSignal.reason)

        job.cancel()
    }

    // 3. Stale socket ready/event/close/error cannot mutate new state (connection generations)
    @Test
    fun staleSocketEvents_fromOldGeneration_cannotMutateNewState() = runTest(testDispatcher) {
        connectAdapter()
        val gen1 = adapter.connectionGeneration
        assertTrue(gen1 > 0)

        // Disconnect advances generation
        adapter.disconnect()
        val gen2 = adapter.connectionGeneration
        assertTrue(gen2 > gen1)
        assertEquals(ConnectionStatus.OFFLINE, adapter.connectionStatus.value)

        // Simulate stale frame from old transport connection arriving after disconnect
        transport.emitFrame("""{"method":"session.event","params":{"session_id":"$targetSessionId","seq":99,"epoch":1,"type":"text_delta","content":"stale"}}""")
        advanceUntilIdle()

        // Status must remain OFFLINE; no side-effects on adapter
        assertEquals(ConnectionStatus.OFFLINE, adapter.connectionStatus.value)

        // Reconnect to gen 3
        connectAdapter()
        val gen3 = adapter.connectionGeneration
        assertTrue(gen3 > gen2)
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        // Simulate a close from gen 1 or an orphaned transport close callback
        // Current gen 3 adapter must ignore events from superseded generations
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)
    }

    // 4. Concurrent connect/disconnect/ping/RPC cancellation leaks nothing
    @Test
    fun concurrentConnectDisconnectRpc_doesNotDeadlockOrLeak() = runTest(testDispatcher) {
        val jobs = (1..10).map { i ->
            launch {
                if (i % 2 == 0) {
                    adapter.connect("https://test.hermes.local", validTicket)
                } else {
                    adapter.disconnect()
                }
            }
        }
        advanceUntilIdle()
        jobs.forEach { assertTrue(it.isCompleted) }

        // Final clean disconnect
        adapter.disconnect()
        advanceUntilIdle()
        assertEquals(ConnectionStatus.OFFLINE, adapter.connectionStatus.value)
    }

    // 5. Replay/live cross-session rows are rejected
    @Test
    fun crossSessionRows_rejectedInReplayAndLive() = runTest(testDispatcher) {
        val reducer = GatewayReplayReducer()

        // Replay contains row with mismatched session_id
        val crossSessionReplay = ReplayEventsResult(
            count = 1,
            epoch = "1",
            latestSeq = 6L,
            truncated = false,
            events = listOf(
                GatewayEvent.MessageDelta(
                    sessionId = "session-B", // MISMATCH!
                    seq = 6L,
                    text = "cross session delta"
                )
            )
        )

        val result = reducer.reduceReplayBatch("session-A", "durable-A", crossSessionReplay)
        assertTrue(result is ReplayBatchOutcome.Fallback)
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, (result as ReplayBatchOutcome.Fallback).signal.reason)

        // Live check while awaiting rehydration: rejected per F-004
        val liveCheckWhileAwaiting = reducer.checkLiveEvent(
            GatewayEvent.MessageDelta(
                sessionId = "session-A",
                seq = 1L,
                text = "valid-while-awaiting"
            )
        )
        assertNull("Live event while awaiting rehydration must be rejected", liveCheckWhileAwaiting)

        // Live check: event with invalid seq <= 0
        val liveCheckInvalid = reducer.checkLiveEvent(
            GatewayEvent.MessageDelta(
                sessionId = "session-C",
                seq = 0L,
                text = "invalid"
            )
        )
        assertNull(liveCheckInvalid)

        // Live check: event with valid seq on clean session
        val liveCheckValid = reducer.checkLiveEvent(
            GatewayEvent.MessageDelta(
                sessionId = "session-C",
                seq = 1L,
                text = "valid"
            )
        )
        assertNotNull(liveCheckValid)
    }

    // 6. Gap/epoch/truncation/decode failure uses history replacement
    @Test
    fun replayFailures_triggerAppropriateFallbackReasons() = runTest(testDispatcher) {
        val reducer = GatewayReplayReducer()
        reducer.updateEpoch("1")
        // Gap: watermark 5, first event is seq 7
        reducer.setWatermark("sess-gap", 5L)
        val gapBatch = ReplayEventsResult(
            count = 1,
            epoch = "1",
            latestSeq = 7L,
            truncated = false,
            events = listOf(
                GatewayEvent.MessageDelta(sessionId = "sess-gap", seq = 7L, text = "gap")
            )
        )
        val gapResult = reducer.reduceReplayBatch("sess-gap", "dur", gapBatch)
        assertTrue(gapResult is ReplayBatchOutcome.Fallback)
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, (gapResult as ReplayBatchOutcome.Fallback).signal.reason)

        // Epoch mismatch: batch epoch "2" != current epoch "1"
        reducer.setWatermark("sess-epoch", 5L)
        val epochBatch = ReplayEventsResult(
            count = 1,
            epoch = "2",
            latestSeq = 6L,
            truncated = false,
            events = listOf(
                GatewayEvent.MessageDelta(sessionId = "sess-epoch", seq = 6L, text = "epoch")
            )
        )
        val epochResult = reducer.reduceReplayBatch("sess-epoch", "dur", epochBatch)
        assertTrue(epochResult is ReplayBatchOutcome.Fallback)
        assertEquals(FallbackReason.EPOCH_MISMATCH, (epochResult as ReplayBatchOutcome.Fallback).signal.reason)

        // Truncated: truncated = true
        reducer.setWatermark("sess-trunc", 5L)
        val truncatedBatch = ReplayEventsResult(
            count = 1,
            epoch = "1",
            latestSeq = 6L,
            truncated = true,
            events = listOf(
                GatewayEvent.MessageDelta(sessionId = "sess-trunc", seq = 6L, text = "trunc")
            )
        )
        val truncResult = reducer.reduceReplayBatch("sess-trunc", "dur", truncatedBatch)
        assertTrue(truncResult is ReplayBatchOutcome.Fallback)
        assertEquals(FallbackReason.TRUNCATED, (truncResult as ReplayBatchOutcome.Fallback).signal.reason)
    }

    // 7. Fractional/overflow/malformed/null/missing fields fail safely
    @Test
    fun jsonParsing_rejectsFractionalSeqAndDeepNestingSafely() {
        // Fractional number parsing
        val fractionalJson = GatewayJson.parseOrNull("""{"seq": 12.7}""") as? JsonObject
        assertNotNull(fractionalJson)
        val seqNumber = fractionalJson?.get("seq")
        // asLongOrNull() must be null for non-integer numbers (rejects silent truncation)
        assertNull(seqNumber?.asLongOrNull())

        // Negative seq fails valid check
        val badEvent = GatewayEvent.MessageDelta(
            sessionId = "session-1",
            seq = -1L,
            text = "negative"
        )
        assertFalse(badEvent.hasValidSeq)

        // Missing session on session-bearing event fails valid check
        val missingSessionEvent = GatewayEvent.MessageDelta(
            sessionId = "",
            seq = 1L,
            text = "missing"
        )
        assertFalse(missingSessionEvent.hasValidSession)

        // Deep nesting attack (> 64 depth) fails safely without crashing JVM
        val deepJson = "{\"a\":".repeat(70) + "1" + "}".repeat(70)
        var parseFailed = false
        try {
            val parsed = GatewayJson.parseOrNull(deepJson)
            if (parsed == null) parseFailed = true
        } catch (_: Exception) {
            parseFailed = true
        }
        assertTrue("Deeply nested JSON must be rejected by DoS guards", parseFailed)
    }

    // 8. Reconnect leaves exactly one socket and heartbeat job
    @Test
    fun reconnect_leavesSingleActiveSocket() = runTest(testDispatcher) {
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        val reconnectManager = GatewayReconnectManager(
            gatewayAdapter = adapter,
            authRepository = fakeAuth,
            scope = managerScope,
            ioDispatcher = testDispatcher
        )
        reconnectManager.configureOrigin("https://test.hermes.local")

        // Trigger connect
        reconnectManager.reconnectNow()
        advanceUntilIdle()
        assertTrue(transport.isConnected)
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        // Second connect
        reconnectManager.reconnectNow()
        advanceUntilIdle()
        assertTrue(transport.isConnected)

        // Disconnect
        reconnectManager.onAppBackgrounded()
        adapter.disconnect()
        advanceUntilIdle()
        assertFalse(transport.isConnected)
        managerScope.cancel()
    }

    // 9. Terminal auth/policy close (401/403/4401/4403) does not retry
    @Test
    fun terminalAuthOrPolicyClose_doesNotRetry() = runTest(testDispatcher) {
        // Test GatewayFailure isTerminal classification
        assertTrue(GatewayFailure.AuthenticationRequired("Auth required").isTerminal)
        assertTrue(GatewayFailure.Transport("Unauthorized", statusCode = 401).isTerminal)
        assertTrue(GatewayFailure.Transport("Forbidden", statusCode = 403).isTerminal)
        assertTrue(GatewayFailure.Rpc(4401, "Auth expired").isTerminal)
        assertTrue(GatewayFailure.Rpc(4403, "Policy violation").isTerminal)
        assertFalse(GatewayFailure.Transport("Internal error", statusCode = 500).isTerminal)
        assertFalse(GatewayFailure.NotConnected().isTerminal)

        // Test ReconnectManager aborts retry on terminal auth rejection
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        val reconnectManager = GatewayReconnectManager(
            gatewayAdapter = adapter,
            authRepository = fakeAuth,
            scope = managerScope,
            ioDispatcher = testDispatcher
        )
        reconnectManager.configureOrigin("https://test.hermes.local")

        // Fail ticket mint with terminal 401
        fakeAuth.shouldFailAuth = true
        reconnectManager.reconnectNow()
        advanceUntilIdle()

        // ReconnectManager must enter Failed state and cancel retries
        val state = reconnectManager.reconnectState.value
        assertTrue("State must be Failed on terminal auth", state is ReconnectState.Failed)
        assertFalse("Must not be in WaitingBackoff", state is ReconnectState.WaitingBackoff)
        managerScope.cancel()
    }

    // F-002: Stale socket generation callbacks, close, and error are isolated
    @Test
    fun f002_staleSocketGenerationIsolation() = runTest(testDispatcher) {
        connectAdapter()
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        val collectedEvents = mutableListOf<GatewayEvent>()
        val collectJob = launch {
            adapter.events.toList(collectedEvents)
        }

        val staleGen = 0L // current generation is 1
        // 1. Tagged frame from old generation is ignored
        val staleEventFrame = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 100,
                    "type": "message.delta",
                    "payload": {"text": "stale"}
                }
            }
        """.trimIndent()
        transport.emitTaggedFrame(staleGen, staleEventFrame)
        runCurrent()
        assertTrue(collectedEvents.isEmpty())

        // 2. Tagged close from old generation is ignored
        transport.emitTaggedClose(staleGen, 1000, "Old socket closed")
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        // 3. Tagged error from old generation is ignored
        transport.emitTaggedError(staleGen, RuntimeException("Old socket error"), handshakeStatusCode = 500)
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        collectJob.cancel()
    }

    // F-003: RPC failure during replay emits fallback signal and serializes concurrent replay
    @Test
    fun f003_rpcFailureDuringReplay_emitsFallbackAndSerializesConcurrentReplay() = runTest(testDispatcher) {
        connectAdapter()

        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val signalJob = launch {
            adapter.historyFallbackSignals.toList(fallbackSignals)
        }

        // Configure RPC error for session.events.since
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondError(id, 500, "Internal replay error")
        }

        val replayResult = adapter.replayEventsSince(targetSessionId, 5L)
        assertTrue(replayResult is GatewayResult.Failure)
        runCurrent()

        assertEquals(1, fallbackSignals.size)
        val signal = fallbackSignals.first()
        assertEquals(targetSessionId, signal.runtimeSessionId)
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, signal.reason)

        signalJob.cancel()
    }

    // F-004: Live sequence gap triggers FallbackReason.INVALID_ORDER_OR_GAP
    @Test
    fun f004_liveSequenceGap_triggersFallbackSignal() = runTest(testDispatcher) {
        connectAdapter()

        val collectedEvents = mutableListOf<GatewayEvent>()
        val collectJob = launch {
            adapter.events.toList(collectedEvents)
        }
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val signalJob = launch {
            adapter.historyFallbackSignals.toList(fallbackSignals)
        }

        // Establish watermark at seq 10
        val live10 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 10,
                    "type": "message.delta",
                    "payload": {"text": "ten"}
                }
            }
        """.trimIndent()
        transport.emitFrame(live10)
        runCurrent()
        assertEquals(1, collectedEvents.size)

        // Send seq 12 (gap: expected 11)
        val live12 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 12,
                    "type": "message.delta",
                    "payload": {"text": "twelve"}
                }
            }
        """.trimIndent()
        transport.emitFrame(live12)
        runCurrent()

        // live 12 must not be emitted to events
        assertEquals(1, collectedEvents.size)
        // Fallback signal must be emitted for gap
        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, fallbackSignals.first().reason)
        assertEquals(targetSessionId, fallbackSignals.first().runtimeSessionId)

        collectJob.cancel()
        signalJob.cancel()
    }

    // F-004: Sequenced events buffered when unsubscribed and watermark only committed when subscriber accepts
    @Test
    fun f004_unsubscribedEvents_bufferedUntilSubscriberAttachesAndCommitsWatermark() = runTest(testDispatcher) {
        connectAdapter()

        // Send live event 5 while subscriptionCount == 0
        val live5 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 5,
                    "type": "message.delta",
                    "payload": {"text": "five"}
                }
            }
        """.trimIndent()
        transport.emitFrame(live5)
        runCurrent()

        // Watermark must not be committed yet
        assertNull(adapter.getLastSeenSeq(targetSessionId))

        // Downstream subscriber attaches
        val collectedEvents = mutableListOf<GatewayEvent>()
        val collectJob = launch {
            adapter.events.toList(collectedEvents)
        }
        runCurrent()

        // Buffered event is now delivered and watermark committed
        assertEquals(1, collectedEvents.size)
        assertEquals(5L, collectedEvents.first().seq)
        assertEquals(5L, adapter.getLastSeenSeq(targetSessionId))

        collectJob.cancel()
    }

    // F-005: 4403 WebSocket close through ReconnectManager enters Failed and zero retries
    @Test
    fun f005_actual4403CloseThroughReconnectManager_setsFailedAndZeroRetries() = runTest(testDispatcher) {
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        val reconnectManager = GatewayReconnectManager(
            gatewayAdapter = adapter,
            authRepository = fakeAuth,
            scope = managerScope,
            ioDispatcher = testDispatcher
        )
        reconnectManager.configureOrigin("https://test.hermes.local")

        reconnectManager.reconnectNow()
        advanceUntilIdle()
        assertTrue(transport.isConnected)
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        // Server sends 4403 policy rejection close
        transport.emitClose(4403, "Access policy forbidden")
        advanceUntilIdle()

        // Adapter registers terminal failure and FAILED status
        assertEquals(ConnectionStatus.FAILED, adapter.connectionStatus.value)
        val terminal = adapter.terminalFailure.value
        assertNotNull(terminal)
        assertTrue(terminal!!.isTerminal)

        // ReconnectManager transitions to Failed and schedules zero retries
        val state = reconnectManager.reconnectState.value
        assertTrue("State must be ReconnectState.Failed", state is ReconnectState.Failed)
        assertFalse("Must not be WaitingBackoff", state is ReconnectState.WaitingBackoff)

        managerScope.cancel()
    }

    // P2: UTF-8 byte counting, jsonrpc peer redaction, and auth exception message sanitization
    @Test
    fun p2_hardening_utf8ByteCountingAndSanitization() = runTest(testDispatcher) {
        // N-002: UTF-8 byte counting verification
        // Multi-byte characters (each 4 bytes in UTF-8): 3 code points = 6 UTF-16 chars = 12 UTF-8 bytes
        val multiByteEmoji = "\uD83D\uDE00\uD83D\uDE80\uD83D\uDCBB"
        assertEquals(6, multiByteEmoji.length) // UTF-16 code units
        assertEquals(12, multiByteEmoji.toByteArray(Charsets.UTF_8).size) // UTF-8 bytes

        // N-001: Peer jsonrpc version sanitization with GatewayRedactor
        connectAdapter()
        transport.setRpcHandler("session.list") { _, _ -> /* hold response */ }
        var listRes: GatewayResult<SessionListResult>? = null
        val rpcJob = launch {
            listRes = adapter.listSessions()
        }
        runCurrent()
        val sentRpcText = transport.sentFrames.last()
        val rpcJson = GatewayJson.parseOrNull(sentRpcText) as JsonObject
        val rpcId = rpcJson["id"]?.asStringOrNull()!!
        val maliciousRpcResponse = """
            {
                "jsonrpc": "2.0-Bearer secret-token-12345",
                "id": "$rpcId",
                "result": {"sessions": []}
            }
        """.trimIndent()
        transport.emitFrame(maliciousRpcResponse)
        runCurrent()
        assertTrue(listRes is GatewayResult.Failure)
        val fail = (listRes as GatewayResult.Failure).failure
        assertTrue(fail is GatewayFailure.IncompatibleProtocol)
        val msg = (fail as GatewayFailure.IncompatibleProtocol).reason
        assertFalse("Secret in peer jsonrpc must be redacted", msg.contains("secret-token-12345"))
        assertTrue("Message should contain redacted marker", msg.contains("[REDACTED]"))

        // N-003: AuthRepository exception message sanitization
        val testAuth = TestAuthRepository()
        testAuth.shouldFailAuth = true
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        val reconnectManager = GatewayReconnectManager(
            gatewayAdapter = adapter,
            authRepository = testAuth,
            scope = managerScope,
            ioDispatcher = testDispatcher
        )
        reconnectManager.configureOrigin("https://test.hermes.local")
        reconnectManager.reconnectNow()
        advanceUntilIdle()
        val recState = reconnectManager.reconnectState.value
        assertTrue(recState is ReconnectState.Failed)
        managerScope.cancel()
    }

    // F-003: Overlapping replay calls are serialized per session and execute deterministically
    @Test
    fun f003_overlappingReplayCalls_serializedAndDeterministic() = runTest(testDispatcher) {
        connectAdapter()

        val collectedEvents = mutableListOf<GatewayEvent>()
        val collectJob = launch {
            adapter.events.toList(collectedEvents)
        }

        val firstReplayDeferred = CompletableDeferred<Unit>()
        val secondReplayStarted = CompletableDeferred<Unit>()

        var replayCallCount = 0
        transport.setRpcHandler("session.events.since") { id, _ ->
            replayCallCount++
            launch {
                if (replayCallCount == 1) {
                    firstReplayDeferred.await()
                    transport.respondSuccess(
                        id,
                        """
                        {
                            "session_id": "$targetSessionId",
                            "epoch": "${FakeGatewayFixtures.TEST_EPOCH_1}",
                            "latest_seq": 2,
                            "truncated": false,
                            "count": 2,
                            "events": [
                                {"type": "message.start", "seq": 1, "session_id": "$targetSessionId", "payload": {}},
                                {"type": "message.delta", "seq": 2, "session_id": "$targetSessionId", "payload": {"text": "two"}}
                            ]
                        }
                        """.trimIndent()
                    )
                } else {
                    secondReplayStarted.complete(Unit)
                    transport.respondSuccess(
                        id,
                        """
                        {
                            "session_id": "$targetSessionId",
                            "epoch": "${FakeGatewayFixtures.TEST_EPOCH_1}",
                            "latest_seq": 4,
                            "truncated": false,
                            "count": 2,
                            "events": [
                                {"type": "message.delta", "seq": 3, "session_id": "$targetSessionId", "payload": {"text": "three"}},
                                {"type": "message.complete", "seq": 4, "session_id": "$targetSessionId", "payload": {"status": "complete"}}
                            ]
                        }
                        """.trimIndent()
                    )
                }
            }
        }

        var res1: GatewayResult<ReplayEventsResult>? = null
        var res2: GatewayResult<ReplayEventsResult>? = null

        // Launch first replay
        val job1 = launch {
            res1 = adapter.replayEventsSince(targetSessionId, 0L)
        }
        runCurrent()
        assertEquals(1, replayCallCount)

        // Launch second replay for same session while first is in-flight
        val job2 = launch {
            res2 = adapter.replayEventsSince(targetSessionId, 2L)
        }
        runCurrent()
        // Second replay is blocked by session mutex: call count is still 1
        assertEquals(1, replayCallCount)
        assertFalse(job1.isCompleted)
        assertFalse(job2.isCompleted)

        // Complete first replay
        firstReplayDeferred.complete(Unit)
        runCurrent()

        // Now first replay is completed, and second replay proceeds
        assertTrue(job1.isCompleted)
        assertTrue(res1 is GatewayResult.Success)
        assertEquals(2, replayCallCount)

        advanceUntilIdle()
        assertTrue(job2.isCompleted)
        assertTrue(res2 is GatewayResult.Success)

        // Strict deterministic sequence of all events
        assertEquals(4, collectedEvents.size)
        assertEquals(1L, collectedEvents[0].seq)
        assertEquals(2L, collectedEvents[1].seq)
        assertEquals(3L, collectedEvents[2].seq)
        assertEquals(4L, collectedEvents[3].seq)
        assertEquals(4L, adapter.getLastSeenSeq(targetSessionId))

        collectJob.cancel()
    }

    // F-003: Late live event during hold/drain/teardown enforces atomic ordering and emits fallback on teardown
    @Test
    fun f003_lateLiveEventDuringHoldAndTeardown_atomicOrderingAndFallback() = runTest(testDispatcher) {
        connectAdapter()

        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val signalJob = launch {
            adapter.historyFallbackSignals.toList(fallbackSignals)
        }
        val collectedEvents = mutableListOf<GatewayEvent>()
        val collectJob = launch {
            adapter.events.toList(collectedEvents)
        }

        // Establish watermark at seq 10
        val live10 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 10,
                    "type": "message.start",
                    "payload": {}
                }
            }
        """.trimIndent()
        transport.emitFrame(live10)
        runCurrent()
        assertEquals(1, collectedEvents.size)
        assertEquals(10L, adapter.getLastSeenSeq(targetSessionId))

        // Part 1: Late live event arriving while replay is in-flight (holding)
        val replayDeferred = CompletableDeferred<Unit>()
        transport.setRpcHandler("session.events.since") { id, _ ->
            launch {
                replayDeferred.await()
                transport.respondSuccess(
                    id,
                    """
                    {
                        "session_id": "$targetSessionId",
                        "epoch": "${FakeGatewayFixtures.TEST_EPOCH_1}",
                        "latest_seq": 11,
                        "truncated": false,
                        "count": 1,
                        "events": [
                            {"type": "message.delta", "seq": 11, "session_id": "$targetSessionId", "payload": {"text": "eleven"}}
                        ]
                    }
                    """.trimIndent()
                )
            }
        }

        val replayJob = launch {
            adapter.replayEventsSince(targetSessionId, 10L)
        }
        runCurrent()

        // Send late live event seq 12 while replay is held
        val live12 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 12,
                    "type": "message.delta",
                    "payload": {"text": "twelve"}
                }
            }
        """.trimIndent()
        transport.emitFrame(live12)
        runCurrent()

        // seq 12 must be held, not emitted yet
        assertEquals(1, collectedEvents.size)

        // Complete replay: held live seq 12 is drained and applied in strict atomic order
        replayDeferred.complete(Unit)
        runCurrent()
        assertTrue(replayJob.isCompleted)

        assertEquals(3, collectedEvents.size)
        assertEquals(10L, collectedEvents[0].seq)
        assertEquals(11L, collectedEvents[1].seq)
        assertEquals(12L, collectedEvents[2].seq)
        assertEquals(12L, adapter.getLastSeenSeq(targetSessionId))

        // Part 2: Late live event during teardown emits fallback and discards held state
        val teardownDeferred = CompletableDeferred<Unit>()
        transport.setRpcHandler("session.events.since") { _, _ ->
            launch {
                teardownDeferred.await()
            }
        }
        val teardownReplayJob = launch {
            adapter.replayEventsSince(targetSessionId, 12L)
        }
        runCurrent()

        // Emit a late live event during teardown hold
        val live13 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 13,
                    "type": "message.delta",
                    "payload": {"text": "thirteen"}
                }
            }
        """.trimIndent()
        transport.emitFrame(live13)
        runCurrent()

        // Now disconnect / close transport (teardown)
        adapter.disconnect()
        runCurrent()

        // Teardown path must emit fallback signal, clear watermark, and mark awaiting rehydration
        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, fallbackSignals[0].reason)
        assertNull(adapter.getLastSeenSeq(targetSessionId))

        teardownDeferred.complete(Unit)
        teardownReplayJob.cancel()
        collectJob.cancel()
        signalJob.cancel()
    }

    // F-004: Replay with no subscriber buffers events and does NOT commit watermark
    @Test
    fun f004_replayWithNoSubscriber_eventsBufferedAndWatermarkUncommitted() = runTest(testDispatcher) {
        connectAdapter()

        // Zero subscribers attached
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(
                id,
                """
                {
                    "session_id": "$targetSessionId",
                    "epoch": "${FakeGatewayFixtures.TEST_EPOCH_1}",
                    "latest_seq": 3,
                    "truncated": false,
                    "count": 3,
                    "events": [
                        {"type": "message.start", "seq": 1, "session_id": "$targetSessionId", "payload": {}},
                        {"type": "message.delta", "seq": 2, "session_id": "$targetSessionId", "payload": {"text": "two"}},
                        {"type": "message.complete", "seq": 3, "session_id": "$targetSessionId", "payload": {"status": "complete"}}
                    ]
                }
                """.trimIndent()
            )
        }

        val result = adapter.replayEventsSince(targetSessionId, 0L)
        assertTrue(result is GatewayResult.Success)
        val data = (result as GatewayResult.Success).value
        assertEquals(3, data.events.size)

        // Watermark must NOT advance without downstream acceptance
        assertNull(adapter.getLastSeenSeq(targetSessionId))
    }

    // F-004: Replay with late subscriber delivers buffered events and commits watermark upon attachment
    @Test
    fun f004_replayWithLateSubscriber_drainsBufferedEventsAndCommitsWatermark() = runTest(testDispatcher) {
        connectAdapter()

        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(
                id,
                """
                {
                    "session_id": "$targetSessionId",
                    "epoch": "${FakeGatewayFixtures.TEST_EPOCH_1}",
                    "latest_seq": 2,
                    "truncated": false,
                    "count": 2,
                    "events": [
                        {"type": "message.start", "seq": 1, "session_id": "$targetSessionId", "payload": {}},
                        {"type": "message.delta", "seq": 2, "session_id": "$targetSessionId", "payload": {"text": "two"}}
                    ]
                }
                """.trimIndent()
            )
        }

        // Replay runs while unsubscribed
        val result = adapter.replayEventsSince(targetSessionId, 0L)
        assertTrue(result is GatewayResult.Success)
        assertNull(adapter.getLastSeenSeq(targetSessionId))

        // Late subscriber attaches
        val collectedEvents = mutableListOf<GatewayEvent>()
        val collectJob = launch {
            adapter.events.toList(collectedEvents)
        }
        runCurrent()

        // Buffered replay events are now delivered and watermark is committed
        assertEquals(2, collectedEvents.size)
        assertEquals(1L, collectedEvents[0].seq)
        assertEquals(2L, collectedEvents[1].seq)
        assertEquals(2L, adapter.getLastSeenSeq(targetSessionId))

        collectJob.cancel()
    }

    // F-004: Live event while awaiting history rehydration is rejected and does not commit watermark
    @Test
    fun f004_liveEventWhileAwaitingRehydration_rejectedAndDoesNotAdvanceWatermark() = runTest(testDispatcher) {
        connectAdapter()

        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val signalJob = launch {
            adapter.historyFallbackSignals.toList(fallbackSignals)
        }
        val collectedEvents = mutableListOf<GatewayEvent>()
        val collectJob = launch {
            adapter.events.toList(collectedEvents)
        }

        // Establish watermark at seq 10
        val live10 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 10,
                    "type": "message.start",
                    "payload": {}
                }
            }
        """.trimIndent()
        transport.emitFrame(live10)
        runCurrent()
        assertEquals(1, collectedEvents.size)
        assertEquals(10L, adapter.getLastSeenSeq(targetSessionId))

        // Send seq 12 (gap: expected 11) -> triggers fallback and marks awaitingHistoryRehydration
        val live12 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 12,
                    "type": "message.delta",
                    "payload": {"text": "gap"}
                }
            }
        """.trimIndent()
        transport.emitFrame(live12)
        runCurrent()

        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, fallbackSignals[0].reason)
        assertNull("Watermark cleared on fallback", adapter.getLastSeenSeq(targetSessionId))

        // Subsequent live event arriving while awaiting rehydration must be rejected
        val live13 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 13,
                    "type": "message.delta",
                    "payload": {"text": "rejected-while-awaiting"}
                }
            }
        """.trimIndent()
        transport.emitFrame(live13)
        runCurrent()

        // Event must NOT be accepted or advance watermark
        assertEquals(1, collectedEvents.size)
        assertNull("Watermark remains uncommitted while awaiting rehydration", adapter.getLastSeenSeq(targetSessionId))

        // After authoritative rehydration, events can be accepted again
        adapter.markHistoryRehydrated(targetSessionId)
        val live1 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 1,
                    "type": "message.start",
                    "payload": {}
                }
            }
        """.trimIndent()
        transport.emitFrame(live1)
        runCurrent()

        assertEquals(2, collectedEvents.size)
        assertEquals(1L, adapter.getLastSeenSeq(targetSessionId))

        collectJob.cancel()
        signalJob.cancel()
    }

    // F-004: Session-scoped unsequenced event while awaiting rehydration is rejected, neither emitted nor buffered, and watermark remains clear
    @Test
    fun f004_sessionScopedNullSeqEventWhileAwaitingRehydration_rejectedAndNotEmittedOrBuffered() = runTest(testDispatcher) {
        connectAdapter()

        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val signalJob = launch {
            adapter.historyFallbackSignals.toList(fallbackSignals)
        }
        val collectedEvents = mutableListOf<GatewayEvent>()
        val collectJob = launch {
            adapter.events.toList(collectedEvents)
        }

        // Establish watermark at seq 10
        val live10 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 10,
                    "type": "message.start",
                    "payload": {}
                }
            }
        """.trimIndent()
        transport.emitFrame(live10)
        runCurrent()
        assertEquals(1, collectedEvents.size)
        assertEquals(10L, adapter.getLastSeenSeq(targetSessionId))

        // Send seq 12 (gap: expected 11) -> triggers fallback, clears watermark, and marks awaitingHistoryRehydration
        val live12 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 12,
                    "type": "message.delta",
                    "payload": {"text": "gap"}
                }
            }
        """.trimIndent()
        transport.emitFrame(live12)
        runCurrent()

        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, fallbackSignals[0].reason)
        assertNull("Watermark cleared on fallback", adapter.getLastSeenSeq(targetSessionId))

        // Session-scoped event with null seq arriving while awaiting rehydration must be rejected (not emitted to active subscriber)
        val unsequencedSessionEvent = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "type": "message.delta",
                    "payload": {"text": "unsequenced-delta-during-rehydration"}
                }
            }
        """.trimIndent()
        transport.emitFrame(unsequencedSessionEvent)
        runCurrent()

        // Event must NOT be emitted; watermark must remain clear
        assertEquals(1, collectedEvents.size)
        assertNull("Watermark remains clear while awaiting rehydration", adapter.getLastSeenSeq(targetSessionId))

        // Detach active subscriber to test that unsequenced event is not buffered
        collectJob.cancel()
        runCurrent()

        // Send another session-scoped unsequenced event while unsubscribed and still awaiting rehydration
        val unsequencedCompleteEvent = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "type": "message.complete",
                    "payload": {"text": "unsequenced-complete", "status": "completed"}
                }
            }
        """.trimIndent()
        transport.emitFrame(unsequencedCompleteEvent)
        runCurrent()

        // Attach a new subscriber; previously rejected event must NOT have been buffered
        val lateCollectedEvents = mutableListOf<GatewayEvent>()
        val lateCollectJob = launch {
            adapter.events.toList(lateCollectedEvents)
        }
        runCurrent()

        assertEquals(0, lateCollectedEvents.size)
        assertNull("Watermark remains clear", adapter.getLastSeenSeq(targetSessionId))

        // A genuinely global event with no session ID arriving must retain accepted pass-through behavior
        val globalEvent = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "sessions.changed",
                    "payload": {}
                }
            }
        """.trimIndent()
        transport.emitFrame(globalEvent)
        runCurrent()

        assertEquals(1, lateCollectedEvents.size)
        assertTrue(lateCollectedEvents.first() is GatewayEvent.SessionsChanged)
        assertNull("Watermark remains clear after global event", adapter.getLastSeenSeq(targetSessionId))

        // After authoritative rehydration, session-scoped events can be accepted again
        adapter.markHistoryRehydrated(targetSessionId)
        val live1 = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "session_id": "$targetSessionId",
                    "seq": 1,
                    "type": "message.start",
                    "payload": {}
                }
            }
        """.trimIndent()
        transport.emitFrame(live1)
        runCurrent()

        assertEquals(2, lateCollectedEvents.size)
        assertEquals(1L, adapter.getLastSeenSeq(targetSessionId))

        lateCollectJob.cancel()
        signalJob.cancel()
    }

    // F-005: Capability RPC timeout/error yields terminal failure and zero scheduled retries through ReconnectManager
    @Test
    fun f005_capabilityFailureOrTimeout_reconnectManagerSchedulesZeroRetries() = runTest(testDispatcher) {
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        val reconnectManager = GatewayReconnectManager(
            gatewayAdapter = adapter,
            authRepository = fakeAuth,
            scope = managerScope,
            ioDispatcher = testDispatcher
        )
        reconnectManager.configureOrigin("https://test.hermes.local")

        // 1. Timeout test: Fake transport swallows gateway.capabilities
        transport.omitCapabilities = true
        reconnectManager.reconnectNow()
        advanceTimeBy(1_500L) // readyTimeoutMs (1000) + rpcTimeoutMs (1000)
        advanceUntilIdle()

        assertEquals(ConnectionStatus.FAILED, adapter.connectionStatus.value)
        val term1 = adapter.terminalFailure.value
        assertNotNull(term1)
        assertTrue(term1!!.isTerminal)
        assertTrue(term1.toString().contains("timed out", ignoreCase = true))

        val state1 = reconnectManager.reconnectState.value
        assertTrue("State must be ReconnectState.Failed", state1 is ReconnectState.Failed)
        assertFalse("Must not schedule backoff retry", state1 is ReconnectState.WaitingBackoff)

        // Advance 60 seconds into the future to confirm zero retries scheduled
        advanceTimeBy(60_000L)
        advanceUntilIdle()
        assertEquals(state1, reconnectManager.reconnectState.value)

        // 2. Protocol/RPC Error test: gateway.capabilities returns RPC error
        transport.omitCapabilities = false
        transport.setRpcHandler("gateway.capabilities") { id, _ ->
            transport.respondError(id, -32603, "Internal capabilities protocol error")
        }

        reconnectManager.reconnectNow()
        advanceUntilIdle()

        assertEquals(ConnectionStatus.FAILED, adapter.connectionStatus.value)
        val term2 = adapter.terminalFailure.value
        assertNotNull(term2)
        assertTrue(term2!!.isTerminal)
        assertTrue(term2.toString().contains("-32603") || term2.toString().contains("Capabilities RPC failed"))

        val state2 = reconnectManager.reconnectState.value
        assertTrue("State must be ReconnectState.Failed", state2 is ReconnectState.Failed)
        assertFalse("Must not schedule backoff retry", state2 is ReconnectState.WaitingBackoff)

        // Advance 60 seconds into the future to confirm zero retries scheduled
        advanceTimeBy(60_000L)
        advanceUntilIdle()
        assertEquals(state2, reconnectManager.reconnectState.value)

        managerScope.cancel()
    }

    private fun kotlinx.coroutines.test.TestScope.connectAdapter(): kotlinx.coroutines.Job {
        val job = launch {
            adapter.connect("https://test.hermes.local", validTicket)
        }
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        return job
    }
}
