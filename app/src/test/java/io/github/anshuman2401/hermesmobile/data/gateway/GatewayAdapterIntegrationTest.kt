package io.github.anshuman2401.hermesmobile.data.gateway

import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayFixtures
import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive offline integration test suite for [DefaultGatewayProtocolAdapter].
 * Covers certified host boundary shapes, query fallback, readiness edge variants,
 * ping variants, replay/resume flows, tool_id correlation, JSON-RPC envelope edge cases,
 * typed boundary encapsulation, and credential secrecy invariants.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayAdapterIntegrationTest {

    private lateinit var transport: FakeGatewayTransport
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var adapter: DefaultGatewayProtocolAdapter

    private val validTicket = GatewayTicket("safe-test-ticket-xyz", 30)

    @Before
    fun setUp() {
        transport = FakeGatewayTransport()
        adapter = DefaultGatewayProtocolAdapter(
            transport = transport,
            dispatcher = testDispatcher,
            readyTimeoutMs = 1_000L,
            rpcTimeoutMs = 1_000L
        )
    }

    // =========================================================================
    // A. Cookie login + ticket minting (shape-level, harness-only)
    // =========================================================================

    @Test
    fun connect_preferredForm_buildsExactConnectConfigWithTicketSubprotocol() = runTest(testDispatcher) {
        val ticket = GatewayTicket("tk-samp1")
        launch { adapter.connect("https://test.hermes.local/api-prefix", ticket) }
        advanceUntilIdle()

        val config = transport.lastConnectConfig
        assertNotNull("Expected connect config to be populated", config)
        assertEquals("wss://test.hermes.local/api-prefix/api/ws", config?.url)
        assertEquals(
            listOf("hermes-gateway-v1", "hermes-gateway-ticket.tk-samp1"),
            config?.subprotocols
        )
        assertFalse("Preferred form url must not contain query parameters", config!!.url.contains("?"))
    }

    @Test
    fun connect_freshSingleUseTicketPerAttempt_secondConnectUsesOnlyNewTicket() = runTest(testDispatcher) {
        // Attempt 1 with ticket t-1
        launch { adapter.connect("https://test.hermes.local", GatewayTicket("t-1")) }
        advanceUntilIdle()
        assertEquals(
            listOf("hermes-gateway-v1", "hermes-gateway-ticket.t-1"),
            transport.lastConnectConfig?.subprotocols
        )

        // Disconnect
        adapter.disconnect()
        advanceUntilIdle()

        // Attempt 2 with fresh ticket t-2
        launch { adapter.connect("https://test.hermes.local", GatewayTicket("t-2")) }
        advanceUntilIdle()
        assertEquals(
            listOf("hermes-gateway-v1", "hermes-gateway-ticket.t-2"),
            transport.lastConnectConfig?.subprotocols
        )
        assertFalse(
            "Second attempt must not contain first ticket",
            transport.lastConnectConfig?.subprotocols?.contains("hermes-gateway-ticket.t-1") ?: true
        )
    }

    @Test
    fun connect_ticketReuseHandshake403_classifiedAsAuthRequired_doesNotRetainTicket() = runTest(testDispatcher) {
        val reusedTicket = GatewayTicket("reused-ticket-bad-403")
        transport.handshakeStatusCode = 403

        val success = adapter.connect("https://test.hermes.local", reusedTicket)
        advanceUntilIdle()

        assertFalse(success)
        assertEquals(ConnectionStatus.AUTHENTICATION_REQUIRED, adapter.connectionStatus.value)

        // Adapter does not retain the failed ticket anywhere observable
        assertNoTicketLeakInAdapter(adapter, "reused-ticket-bad-403")
    }

    @Test
    fun connect_ticketExpiryClose4401_classifiedAsAuthRequired_doesNotRetainTicket() = runTest(testDispatcher) {
        val expiredTicket = GatewayTicket("expired-ticket-4401")
        transport.autoEmitReady = false

        var connectSuccess: Boolean? = null
        val connectJob = launch {
            connectSuccess = adapter.connect("https://test.hermes.local", expiredTicket)
        }
        advanceTimeBy(10)
        transport.emitClose(4401, "Ticket expired or rejected")
        advanceUntilIdle()

        assertTrue(connectJob.isCompleted)
        assertEquals(false, connectSuccess)
        assertEquals(ConnectionStatus.AUTHENTICATION_REQUIRED, adapter.connectionStatus.value)

        // Adapter does not retain the ticket anywhere observable
        assertNoTicketLeakInAdapter(adapter, "expired-ticket-4401")
    }

    @Test
    fun connect_ticketMemoryOnly_configToStringRedactsRawValueAcrossSuccessfulAndFailedConnect() = runTest(testDispatcher) {
        // Successful connect
        val successSecret = "success-ticket-secret-val-777"
        launch { adapter.connect("https://test.hermes.local", GatewayTicket(successSecret)) }
        advanceUntilIdle()

        val successConfigStr = transport.lastConnectConfig.toString()
        assertFalse("Config toString must not contain raw ticket on success", successConfigStr.contains(successSecret))
        assertTrue("Config toString must contain [REDACTED]", successConfigStr.contains("[REDACTED]"))

        // Failed connect
        adapter.disconnect()
        transport.handshakeStatusCode = 403
        val failureSecret = "failure-ticket-secret-val-888"
        val failed = adapter.connect("https://test.hermes.local", GatewayTicket(failureSecret))
        advanceUntilIdle()

        assertFalse(failed)
        val failureConfigStr = transport.lastConnectConfig.toString()
        assertFalse("Config toString must not contain raw ticket on failure", failureConfigStr.contains(failureSecret))
        assertTrue("Config toString must contain [REDACTED]", failureConfigStr.contains("[REDACTED]"))

        assertNoTicketLeakInAdapter(adapter, successSecret)
        assertNoTicketLeakInAdapter(adapter, failureSecret)
    }

    // =========================================================================
    // B. Subprotocol reflection vs query fallback
    // =========================================================================

    @Test
    fun connect_queryFallbackEndToEnd_configuresUrlWithQueryAndSingleSubprotocolAndConnects() = runTest(testDispatcher) {
        val ticket = GatewayTicket("query-fallback-token-abc")
        val connectJob = launch {
            adapter.connect("https://test.hermes.local", ticket, useQueryFallback = true)
        }
        advanceUntilIdle()

        assertTrue(connectJob.isCompleted)
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        val config = transport.lastConnectConfig
        assertNotNull(config)
        assertEquals("wss://test.hermes.local/api/ws?ticket=query-fallback-token-abc", config?.url)
        assertEquals(listOf("hermes-gateway-v1"), config?.subprotocols)
    }

    @Test
    fun connect_preferredForm_subprotocolsExactAndUrlCarriesNoQuery() = runTest(testDispatcher) {
        val ticket = GatewayTicket("tk-pref-1")
        launch { adapter.connect("https://test.hermes.local", ticket, useQueryFallback = false) }
        advanceUntilIdle()

        val config = transport.lastConnectConfig
        assertNotNull(config)
        assertFalse("Preferred url must carry NO query parameter", config!!.url.contains("?"))
        assertEquals(
            listOf("hermes-gateway-v1", "hermes-gateway-ticket.tk-pref-1"),
            config.subprotocols
        )
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)
    }

    @Test
    fun connect_pathPrefixPreservedInBothPreferredAndQueryFallbackForms() = runTest(testDispatcher) {
        val ticket = GatewayTicket("tk-pfx-12")

        // 1. Preferred form with prefix
        launch { adapter.connect("https://test.hermes.local/prefix/v2", ticket, useQueryFallback = false) }
        advanceUntilIdle()
        assertEquals("wss://test.hermes.local/prefix/v2/api/ws", transport.lastConnectConfig?.url)
        assertEquals(
            listOf("hermes-gateway-v1", "hermes-gateway-ticket.tk-pfx-12"),
            transport.lastConnectConfig?.subprotocols
        )

        adapter.disconnect()
        advanceUntilIdle()

        // 2. Query fallback with prefix
        launch { adapter.connect("https://test.hermes.local/prefix/v2", ticket, useQueryFallback = true) }
        advanceUntilIdle()
        assertEquals("wss://test.hermes.local/prefix/v2/api/ws?ticket=tk-pfx-12", transport.lastConnectConfig?.url)
        assertEquals(listOf("hermes-gateway-v1"), transport.lastConnectConfig?.subprotocols)
    }

    // =========================================================================
    // C. Readiness gating edge variants
    // =========================================================================

    @Test
    fun connect_readyWithHeartbeatFalseAndChangeEventsFalse_stillConnectsAndReadyEventCarriesFlags() = runTest(testDispatcher) {
        val collectedEvents = mutableListOf<GatewayEvent>()
        val eventsJob = launch { adapter.events.toList(collectedEvents) }

        transport.readyFrameOverride = FakeGatewayFixtures.READY_FRAME_NO_HEARTBEAT_NO_CHANGE_EVENTS

        val connectJob = launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        assertTrue(connectJob.isCompleted)
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        val readyEvent = collectedEvents.filterIsInstance<GatewayEvent.Ready>().firstOrNull()
        assertNotNull("Expected GatewayEvent.Ready event", readyEvent)
        assertFalse("heartbeat flag must be false", readyEvent!!.heartbeat)
        assertFalse("changeEvents flag must be false", readyEvent.changeEvents)
        assertEquals(FakeGatewayFixtures.TEST_EPOCH_1, readyEvent.replayEpoch)

        eventsJob.cancel()
    }

    @Test
    fun connect_capabilitiesRpcTimeout_returnsFalseAndTransitionsToFailed() = runTest(testDispatcher) {
        // Fake transport handler swallows the gateway.capabilities request
        transport.omitCapabilities = true

        var connectResult: Boolean? = null
        val connectJob = launch {
            connectResult = adapter.connect("https://test.hermes.local", validTicket)
        }
        advanceTimeBy(500)
        advanceUntilIdle()

        assertTrue(connectJob.isCompleted)
        assertEquals(false, connectResult)
        assertEquals(ConnectionStatus.FAILED, adapter.connectionStatus.value)
    }

    @Test
    fun connect_capabilitiesUnparseableEmptyResult_transitionsToFailed() = runTest(testDispatcher) {
        // Result is {} without per_session_exclusive_submit
        transport.setRpcHandler("gateway.capabilities") { id, _ ->
            transport.respondSuccess(id, "{}")
        }

        val connectJob = launch {
            adapter.connect("https://test.hermes.local", validTicket)
        }
        advanceUntilIdle()

        assertTrue(connectJob.isCompleted)
        assertEquals(ConnectionStatus.FAILED, adapter.connectionStatus.value)
    }

    @Test
    fun connect_capabilitiesUnparseableWrongType_transitionsToFailed() = runTest(testDispatcher) {
        // per_session_exclusive_submit is string instead of boolean
        transport.setRpcHandler("gateway.capabilities") { id, _ ->
            transport.respondSuccess(id, """{"per_session_exclusive_submit":"not-a-bool"}""")
        }

        val connectJob = launch {
            adapter.connect("https://test.hermes.local", validTicket)
        }
        advanceUntilIdle()

        assertTrue(connectJob.isCompleted)
        assertEquals(ConnectionStatus.FAILED, adapter.connectionStatus.value)
    }

    // =========================================================================
    // D. gateway.ping vs plain ping
    // =========================================================================

    @Test
    fun ping_inboundPlainPongFrame_completesPendingPingSuccessfullyWithoutCrashingConnection() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        transport.autoRespondPing = false

        var pingResult: GatewayResult<GatewayPingResult>? = null
        val pingJob = launch {
            pingResult = adapter.ping()
        }
        advanceTimeBy(10)

        // Emit inbound plain {"pong":true} frame (no id, no jsonrpc method)
        transport.emitFrame("""{"pong":true}""")
        advanceUntilIdle()

        assertTrue(pingJob.isCompleted)
        assertNotNull(pingResult)
        assertTrue(pingResult is GatewayResult.Success)
        assertTrue((pingResult as GatewayResult.Success).value.ok)
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)
    }

    @Test
    fun ping_idCorrelatedResponse_completesSuccessfullyAndUsedGatewayPingMethod() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        val pingResult = adapter.ping()
        assertTrue(pingResult is GatewayResult.Success)
        assertTrue((pingResult as GatewayResult.Success).value.ok)

        val pingFrame = transport.sentFrames.last()
        val parsed = GatewayJson.parseOrNull(pingFrame) as? JsonObject
        assertNotNull(parsed)
        assertEquals("gateway.ping", parsed?.get("method")?.asStringOrNull())
        assertEquals("2.0", parsed?.get("jsonrpc")?.asStringOrNull())
        assertNotNull(parsed?.get("id")?.asStringOrNull())
    }

    @Test
    fun ping_whileOffline_returnsNotConnectedFailure() = runTest(testDispatcher) {
        // Adapter has not connected yet; connectionStatus == OFFLINE
        assertEquals(ConnectionStatus.OFFLINE, adapter.connectionStatus.value)

        val pingResult = adapter.ping()
        assertTrue(pingResult is GatewayResult.Failure)
        val failure = (pingResult as GatewayResult.Failure).failure
        assertTrue("Expected GatewayFailure.NotConnected", failure is GatewayFailure.NotConnected)
    }

    @Test
    fun ping_rawAckConcurrentWithInFlightRpc_completesBothWithoutCrossCorrelation() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Intercept session.list so it stays in-flight
        var listRpcId: String? = null
        transport.setRpcHandler("session.list") { id, _ ->
            listRpcId = id
        }
        transport.autoRespondPing = false

        var listResult: GatewayResult<SessionListResult>? = null
        var pingResult: GatewayResult<GatewayPingResult>? = null

        val listJob = launch { listResult = adapter.listSessions() }
        advanceTimeBy(10)
        assertNotNull("session.list request should have been sent", listRpcId)

        val pingJob = launch { pingResult = adapter.ping() }
        advanceTimeBy(10)

        // 1. Emit raw ping ack {"ok":true}
        transport.emitFrame("""{"ok":true}""")
        advanceTimeBy(10)

        assertTrue("Ping should complete upon raw ack", pingJob.isCompleted)
        assertNotNull(pingResult)
        assertTrue(pingResult is GatewayResult.Success)
        assertTrue((pingResult as GatewayResult.Success).value.ok)

        assertFalse("session.list must still be pending", listJob.isCompleted)

        // 2. Emit correlated response for session.list
        transport.respondSuccess(listRpcId!!, FakeGatewayFixtures.SESSION_LIST_RESPONSE_PR)
        advanceUntilIdle()

        assertTrue("session.list should now complete", listJob.isCompleted)
        assertNotNull(listResult)
        assertTrue(listResult is GatewayResult.Success)
        val sessions = (listResult as GatewayResult.Success).value.sessions
        assertEquals(2, sessions.size)
        assertEquals("sess-test-001", sessions[0].id)
    }

    // =========================================================================
    // E. Replay/resume flow integration
    // =========================================================================

    @Test
    fun replayEventsSince_watermarkZero_returnsAllThreeEventsAscendingAndAdvancesWatermarkToThree() = runTest(testDispatcher) {
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val fallbackJob = launch { adapter.historyFallbackSignals.toList(fallbackSignals) }
        val eventsJob = launch { adapter.events.collect {} }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // With initial watermark 0, all 3 events in REPLAY_SINCE_RESPONSE [1, 2, 3] are accepted
        val result = adapter.replayEventsSince("runtime-sid-1", 0L, "durable-1")
        assertTrue(result is GatewayResult.Success)
        val replayData = (result as GatewayResult.Success).value

        assertEquals(3, replayData.count)
        assertEquals(3L, replayData.latestSeq)
        assertEquals(3, replayData.events.size)
        assertTrue(replayData.events[0] is GatewayEvent.MessageStart)
        assertEquals(1L, replayData.events[0].seq)
        assertTrue(replayData.events[1] is GatewayEvent.MessageDelta)
        assertEquals(2L, replayData.events[1].seq)
        assertEquals("PONG", (replayData.events[1] as GatewayEvent.MessageDelta).text)
        assertTrue(replayData.events[2] is GatewayEvent.MessageComplete)
        assertEquals(3L, replayData.events[2].seq)
        assertEquals("complete", (replayData.events[2] as GatewayEvent.MessageComplete).status)

        assertEquals(3L, adapter.getLastSeenSeq("runtime-sid-1"))
        assertTrue("No fallback signal should fire on clean replay", fallbackSignals.isEmpty())

        fallbackJob.cancel()
        eventsJob.cancel()
    }

    @Test
    fun replayEventsSince_truncatedFallbackFollowedByResumeAndRehydrate_restoresReplay() = runTest(testDispatcher) {
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val fallbackJob = launch { adapter.historyFallbackSignals.toList(fallbackSignals) }
        val eventsJob = launch { adapter.events.collect {} }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.setLastSeenSeq("runtime-sid-1", 10L)

        // 1. Truncated replay triggers TRUNCATED fallback
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(id, FakeGatewayFixtures.REPLAY_TRUNCATED_RESPONSE)
        }
        val truncatedResult = adapter.replayEventsSince("runtime-sid-1", 10L, "durable-1")
        assertTrue(truncatedResult is GatewayResult.Success)
        assertEquals(0, (truncatedResult as GatewayResult.Success).value.events.size)
        advanceUntilIdle()

        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.TRUNCATED, fallbackSignals[0].reason)
        assertNull("Watermark must be cleared on fallback", adapter.getLastSeenSeq("runtime-sid-1"))

        // 2. Integration sequence: caller hydrates via session.resume, then marks rehydrated
        val resumeResult = adapter.resumeSession("durable-1")
        assertTrue(resumeResult is GatewayResult.Success)
        adapter.markHistoryRehydrated("runtime-sid-1")

        // 3. Subsequent replay now succeeds
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(
                id,
                FakeGatewayFixtures.REPLAY_SINCE_RESPONSE.replace(FakeGatewayFixtures.TEST_RUNTIME_SID, "runtime-sid-1")
            )
        }
        val rehydratedReplay = adapter.replayEventsSince("runtime-sid-1", 0L, "durable-1")
        assertTrue(rehydratedReplay is GatewayResult.Success)
        val data = (rehydratedReplay as GatewayResult.Success).value
        assertEquals(3, data.events.size)
        assertEquals(3L, adapter.getLastSeenSeq("runtime-sid-1"))

        fallbackJob.cancel()
        eventsJob.cancel()
    }

    @Test
    fun sessionResume_4007Error_returnsRpcFailure4007() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        transport.setRpcHandler("session.resume") { id, _ ->
            transport.respondError(id, 4007, "session not found")
        }

        val res = adapter.resumeSession("missing-session-uuid")
        assertTrue(res is GatewayResult.Failure)
        val failure = (res as GatewayResult.Failure).failure
        assertTrue(failure is GatewayFailure.Rpc)
        val rpcFailure = failure as GatewayFailure.Rpc
        assertEquals(4007, rpcFailure.code)
        assertEquals("session not found", rpcFailure.message)
    }

    @Test
    fun replayEventsSince_countInconsistency_dispatchesInvalidOrderOrGapFallback() = runTest(testDispatcher) {
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val fallbackJob = launch { adapter.historyFallbackSignals.toList(fallbackSignals) }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.setLastSeenSeq("runtime-sid-1", 0L)

        // Batch specifies count=2 but only provides 1 event row
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(id, FakeGatewayFixtures.REPLAY_COUNT_INCONSISTENCY_RESPONSE)
        }

        val result = adapter.replayEventsSince("runtime-sid-1", 0L, "durable-1")
        assertTrue(result is GatewayResult.Success)
        assertEquals(0, (result as GatewayResult.Success).value.events.size)
        advanceUntilIdle()

        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, fallbackSignals[0].reason)
        assertTrue(fallbackSignals[0].details?.contains("Replay count inconsistency") == true)
        assertNull(adapter.getLastSeenSeq("runtime-sid-1"))

        fallbackJob.cancel()
    }

    @Test
    fun replayEventsSince_latestSeqInconsistency_dispatchesInvalidOrderOrGapFallback() = runTest(testDispatcher) {
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val fallbackJob = launch { adapter.historyFallbackSignals.toList(fallbackSignals) }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Batch specifies count=1, 1 event at seq 1, but latest_seq is 5
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(id, FakeGatewayFixtures.REPLAY_LATEST_SEQ_INCONSISTENCY_RESPONSE)
        }

        val result = adapter.replayEventsSince("runtime-sid-1", 0L, "durable-1")
        assertTrue(result is GatewayResult.Success)
        assertEquals(0, (result as GatewayResult.Success).value.events.size)
        advanceUntilIdle()

        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, fallbackSignals[0].reason)
        assertTrue(fallbackSignals[0].details?.contains("does not match last parsed event seq") == true)
        assertNull(adapter.getLastSeenSeq("runtime-sid-1"))

        fallbackJob.cancel()
    }

    // =========================================================================
    // F. tool_id state correlation
    // =========================================================================

    @Test
    fun resumeThenLive_liveEventAtLatestSeqPlusOne_appliesWithoutGapFallbackOrRehydration() = runTest(testDispatcher) {
        // F007-S3 behavioral contract: after resume hydration + watermark seeding,
        // a live sequenced event at latest_seq+1 must be ACCEPTED into the event
        // stream with NO gap fallback and NO re-hydration requirement. Without the
        // seed (pre-S3), this live event trips the gap detector (evaluateLiveEvent
        // with no watermark: seq=4 > 0+1) and dispatches INVALID_ORDER_OR_GAP.
        val collectedEvents = mutableListOf<GatewayEvent>()
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val eventsJob = launch { adapter.events.collect { collectedEvents.add(it) } }
        val fallbackJob = launch { adapter.historyFallbackSignals.toList(fallbackSignals) }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // 1. Resume hydration (session.resume -> runtime-sid-1)
        val resumeResult = adapter.resumeSession("durable-key-persisted-1")
        assertTrue(resumeResult is GatewayResult.Success)

        // 2. Seed the replay watermark via the production first sweep
        //    (session.events.since from 0: ring replays [1,2,3], latest_seq 3).
        val seedResult = adapter.replayEventsSince("runtime-sid-1", 0L, "durable-key-persisted-1")
        assertTrue(seedResult is GatewayResult.Success)
        assertEquals(3L, adapter.getLastSeenSeq("runtime-sid-1"))
        // SharedFlow delivery to the collector is asynchronous under the test
        // dispatcher; let the collector drain before asserting replay emission.
        advanceUntilIdle()
        assertEquals(3, collectedEvents.filterNot { it is GatewayEvent.Ready }.size)

        // 3. Live event at latest_seq+1: must be accepted, not gap-dropped.
        transport.emitFrame(
            """{"jsonrpc":"2.0","method":"event","params":{"session_id":"runtime-sid-1","seq":4,"type":"tool.start","payload":{"tool_id":"call-LIVE-1","name":"search","args":{"q":"live"}}}}"""
        )
        advanceUntilIdle()

        val nonReady = collectedEvents.filterNot { it is GatewayEvent.Ready }
        val liveStart = nonReady.filterIsInstance<GatewayEvent.ToolStart>().firstOrNull()
        assertNotNull("Live event at latest_seq+1 must reach the event stream", liveStart)
        assertEquals("call-LIVE-1", liveStart?.toolId)
        assertEquals("search", liveStart?.name)
        assertEquals(4L, adapter.getLastSeenSeq("runtime-sid-1"))

        // The core F007-S3 assertion: NO fallback (no gap drop, no re-hydration).
        assertTrue(
            "Seeded watermark must prevent gap fallback: got ${fallbackSignals.map { it.reason }}",
            fallbackSignals.isEmpty()
        )

        // 4. Continuation: the next live event also streams without any fallback.
        transport.emitFrame(
            """{"jsonrpc":"2.0","method":"event","params":{"session_id":"runtime-sid-1","seq":5,"type":"message.complete","payload":{"status":"complete","text":"done"}}}"""
        )
        advanceUntilIdle()
        assertTrue("Subsequent live events must keep streaming", fallbackSignals.isEmpty())

        eventsJob.cancel()
        fallbackJob.cancel()
    }

    @Test
    fun resumeThenLive_truncatedReplayStillRehydrates_existingSafetyPreserved() = runTest(testDispatcher) {
        // F007-S3 must not weaken the contract fallbacks: truncation during the
        // seed still clears the watermark and marks the session awaiting
        // re-hydration (loadSession() re-hydration preserved).
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val fallbackJob = launch { adapter.historyFallbackSignals.toList(fallbackSignals) }
        val eventsJob = launch { adapter.events.collect {} }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.resumeSession("durable-key-persisted-1")

        // Truncated replay response on the seed sweep.
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(id, FakeGatewayFixtures.REPLAY_TRUNCATED_RESPONSE)
        }
        val seedResult = adapter.replayEventsSince("runtime-sid-1", 0L, "durable-key-persisted-1")
        assertTrue(seedResult is GatewayResult.Success)
        advanceUntilIdle()

        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.TRUNCATED, fallbackSignals[0].reason)
        assertNull("Watermark must be cleared on truncation", adapter.getLastSeenSeq("runtime-sid-1"))

        // Re-hydration preserved: after the caller hydrates via resume and marks
        // rehydrated, the replay path accepts batches again (contract rule 4).
        adapter.resumeSession("durable-key-persisted-1")
        adapter.markHistoryRehydrated("runtime-sid-1")
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(
                id,
                FakeGatewayFixtures.REPLAY_SINCE_RESPONSE.replace(FakeGatewayFixtures.TEST_RUNTIME_SID, "runtime-sid-1")
            )
        }
        val rehydrated = adapter.replayEventsSince("runtime-sid-1", 0L, "durable-key-persisted-1")
        assertTrue(rehydrated is GatewayResult.Success)
        assertEquals(3, (rehydrated as GatewayResult.Success).value.events.size)
        assertEquals(3L, adapter.getLastSeenSeq("runtime-sid-1"))
        assertEquals("Only the truncation fallback fired", 1, fallbackSignals.size)

        fallbackJob.cancel()
        eventsJob.cancel()
    }

    @Test
    fun toolLifecycle_interleavedToolsStream_maintainsCorrectToolIdCorrelation() = runTest(testDispatcher) {
        val collectedEvents = mutableListOf<GatewayEvent>()
        val eventsJob = launch { adapter.events.toList(collectedEvents) }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // 1. tool.start call-A
        transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"session_id":"sid-1","seq":1,"type":"tool.start","payload":{"tool_id":"call-A","name":"search","args":{"q":"test"}}}}""")
        // 2. reasoning.available progress variant
        transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"session_id":"sid-1","seq":2,"type":"reasoning.available","payload":{"thought":"evaluating"}}}""")
        // 3. tool.progress variant
        transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"session_id":"sid-1","seq":3,"type":"tool.progress","payload":{"progress":0.5}}}""")
        // 4. unrelated tool.start call-B
        transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"session_id":"sid-1","seq":4,"type":"tool.start","payload":{"tool_id":"call-B","name":"read_file","args":{"path":"foo.txt"}}}}""")
        // 5. tool.complete for call-B
        transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"session_id":"sid-1","seq":5,"type":"tool.complete","payload":{"tool_id":"call-B","name":"read_file","summary":"done reading"}}}""")
        // 6. tool.complete for call-A
        transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"session_id":"sid-1","seq":6,"type":"tool.complete","payload":{"tool_id":"call-A","name":"search","summary":"found 1 result"}}}""")
        advanceUntilIdle()

        val toolEvents = collectedEvents.filterNot { it is GatewayEvent.Ready }
        assertEquals(6, toolEvents.size)

        val startA = toolEvents[0] as GatewayEvent.ToolStart
        assertEquals("call-A", startA.toolId)
        assertEquals("search", startA.name)

        val prog1 = toolEvents[1] as GatewayEvent.ToolProgress
        assertEquals("reasoning.available", prog1.type)
        assertEquals("evaluating", prog1.payload["thought"])

        val prog2 = toolEvents[2] as GatewayEvent.ToolProgress
        assertEquals("tool.progress", prog2.type)
        assertEquals(0.5, prog2.payload["progress"])

        val startB = toolEvents[3] as GatewayEvent.ToolStart
        assertEquals("call-B", startB.toolId)
        assertEquals("read_file", startB.name)

        val compB = toolEvents[4] as GatewayEvent.ToolComplete
        assertEquals("call-B", compB.toolId)
        assertEquals("read_file", compB.name)
        assertEquals("done reading", compB.summary)

        val compA = toolEvents[5] as GatewayEvent.ToolComplete
        assertEquals("call-A", compA.toolId)
        assertEquals("search", compA.name)
        assertEquals("found 1 result", compA.summary)

        eventsJob.cancel()
    }

    @Test
    fun toolLifecycle_toolCompleteArrivingWithoutPriorToolStart_parsesSafelyWithoutCrashOrFallback() = runTest(testDispatcher) {
        val collectedEvents = mutableListOf<GatewayEvent>()
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val eventsJob = launch { adapter.events.toList(collectedEvents) }
        val fallbackJob = launch { adapter.historyFallbackSignals.toList(fallbackSignals) }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // tool.complete arriving without prior tool.start
        transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"session_id":"sid-1","seq":10,"type":"tool.complete","payload":{"tool_id":"orphan-call-99","name":"terminal","duration_s":0.1,"summary":"orphan complete"}}}""")
        advanceUntilIdle()

        val complete = collectedEvents.filterIsInstance<GatewayEvent.ToolComplete>().firstOrNull()
        assertNotNull(complete)
        assertEquals("orphan-call-99", complete?.toolId)
        assertEquals("terminal", complete?.name)
        assertEquals(0.1, complete?.durationSeconds)
        assertEquals("orphan complete", complete?.summary)

        assertTrue("No fallback should be triggered by orphan tool.complete", fallbackSignals.isEmpty())
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        eventsJob.cancel()
        fallbackJob.cancel()
    }

    // =========================================================================
    // G. JSON-RPC envelope edge cases
    // =========================================================================

    @Test
    fun jsonrpc_missingJsonRpcFieldTolerated_completesPendingRpcSuccessfully() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Correlated response without "jsonrpc" field
        transport.setRpcHandler("session.list") { id, _ ->
            transport.emitFrame("""{"id":"$id","result":{"sessions":[]}}""")
        }

        val result = adapter.listSessions()
        assertTrue(result is GatewayResult.Success)
        val sessions = (result as GatewayResult.Success).value.sessions
        assertEquals(0, sessions.size)
    }

    @Test
    fun jsonrpc_errorExplicitNull_treatedAsNeitherResultNorError_failsWithIncompatibleProtocol() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Correlated response with ID matching request but error is explicit null and result absent
        transport.setRpcHandler("session.list") { id, _ ->
            transport.emitFrame("""{"jsonrpc":"2.0","id":"$id","error":null}""")
        }

        val result = adapter.listSessions()
        assertTrue(result is GatewayResult.Failure)
        val failure = (result as GatewayResult.Failure).failure
        assertTrue("Expected IncompatibleProtocol when error is null and result absent", failure is GatewayFailure.IncompatibleProtocol)
    }

    @Test
    fun jsonrpc_outboundFramesStrictFormat_assertsWireShapeAndParams() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        val submitRes = adapter.submitPrompt("runtime-sid-abc", "execute command")
        assertTrue(submitRes is GatewayResult.Success)

        // Locate prompt.submit frame in sentFrames
        val promptFrame = transport.sentFrames.firstOrNull { it.contains("prompt.submit") }
        assertNotNull("Expected prompt.submit frame in sentFrames", promptFrame)

        val json = GatewayJson.parseOrNull(promptFrame!!) as? JsonObject
        assertNotNull(json)
        assertEquals("2.0", json?.get("jsonrpc")?.asStringOrNull())
        assertEquals("prompt.submit", json?.get("method")?.asStringOrNull())

        val id = json?.get("id")?.asStringOrNull()
        assertNotNull(id)
        assertTrue("ID must follow format android-<gen>-<n>", id!!.matches(Regex("""^android-\d+-\d+$""")))

        val params = json?.get("params") as? JsonObject
        assertNotNull(params)
        assertEquals("runtime-sid-abc", params?.get("session_id")?.asStringOrNull())
        assertEquals("execute command", params?.get("text")?.asStringOrNull())
    }

    // =========================================================================
    // H. Typed-boundary assertions (transport encapsulation)
    // =========================================================================

    @Test
    fun typedBoundary_publicInterfaceExposesNoRawFrameApi() {
        val adapterInterface = GatewayProtocolAdapter::class.java
        val forbiddenRawFrameTypes = setOf(
            "io.github.anshuman2401.hermesmobile.data.gateway.json.JsonValue",
            "io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject",
            "io.github.anshuman2401.hermesmobile.data.gateway.json.JsonArray",
            "io.github.anshuman2401.hermesmobile.data.gateway.GatewayTransportFrame",
            "io.github.anshuman2401.hermesmobile.data.gateway.GatewayConnectConfig"
        )

        for (method in adapterInterface.methods) {
            val returnTypeName = method.returnType.name
            assertFalse(
                "Interface method ${method.name} must not return raw frame or JSON type $returnTypeName",
                forbiddenRawFrameTypes.contains(returnTypeName)
            )
            for (paramType in method.parameterTypes) {
                assertFalse(
                    "Interface method ${method.name} parameter ${paramType.name} must not expose raw frame or JSON type",
                    forbiddenRawFrameTypes.contains(paramType.name)
                )
            }
        }
    }

    @Test
    fun typedBoundary_fullScenarioReturnsStrictlyTypedDomainModelsAndNoRawFrames() = runTest(testDispatcher) {
        // Drive a full lifecycle scenario:
        // 1. Connect
        val connected = adapter.connect("https://test.hermes.local", validTicket)
        advanceUntilIdle()
        assertTrue(connected)

        // 2. List sessions -> SessionListResult
        val listResult = adapter.listSessions()
        assertTrue(listResult is GatewayResult.Success)
        val sessionList = (listResult as GatewayResult.Success).value
        assertEquals("SessionListResult", sessionList.javaClass.simpleName)
        assertEquals("<redacted-session-id-1>", sessionList.sessions[0].id)
        for (summary in sessionList.sessions) {
            assertEquals("SessionSummary", summary.javaClass.simpleName)
            assertFalse(summary.id.startsWith("{"))
        }

        // 3. Create session -> SessionCreateResult
        val createResult = adapter.createSession(title = "new-session")
        assertTrue(createResult is GatewayResult.Success)
        val sessionCreate = (createResult as GatewayResult.Success).value
        assertEquals("SessionCreateResult", sessionCreate.javaClass.simpleName)
        assertEquals("TEST-RUNTIME-SID-1", sessionCreate.runtimeSessionId)

        // 4. Resume session -> SessionResumeResult
        val resumeResult = adapter.resumeSession("durable-key-persisted-1")
        assertTrue(resumeResult is GatewayResult.Success)
        val sessionResume = (resumeResult as GatewayResult.Success).value
        assertEquals("SessionResumeResult", sessionResume.javaClass.simpleName)
        assertEquals("TEST-RUNTIME-SID-1", sessionResume.runtimeSessionId)

        // 5. Submit prompt -> PromptSubmitResult
        val submitResult = adapter.submitPrompt("runtime-sid-1", "ping")
        assertTrue(submitResult is GatewayResult.Success)
        val promptSubmit = (submitResult as GatewayResult.Success).value
        assertEquals("PromptSubmitResult", promptSubmit.javaClass.simpleName)
        assertEquals("streaming", promptSubmit.status)

        // 6. Replay events -> ReplayEventsResult
        val replayResult = adapter.replayEventsSince("runtime-sid-1", 0L)
        assertTrue(replayResult is GatewayResult.Success)
        val replayEvents = (replayResult as GatewayResult.Success).value
        assertEquals("ReplayEventsResult", replayEvents.javaClass.simpleName)
        for (ev in replayEvents.events) {
            assertTrue("Replay events must be GatewayEvent subtypes, not raw frames", ev.javaClass.name.startsWith("io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent"))
        }

        // 7. Ping -> GatewayPingResult
        val pingResult = adapter.ping()
        assertTrue(pingResult is GatewayResult.Success)
        val ping = (pingResult as GatewayResult.Success).value
        assertEquals("GatewayPingResult", ping.javaClass.simpleName)
        assertTrue(ping.ok)
    }

    @Test
    fun typedBoundary_unknownEventsInReplayBatch_surfaceAsGatewayEventUnknownWithPayloadMap() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(id, FakeGatewayFixtures.REPLAY_WITH_UNKNOWN_EVENT_RESPONSE)
        }

        val result = adapter.replayEventsSince("runtime-sid-1", 0L)
        assertTrue(result is GatewayResult.Success)
        val events = (result as GatewayResult.Success).value.events
        assertEquals(1, events.size)

        val ev = events[0]
        assertTrue("Unknown event must be GatewayEvent.Unknown subtype", ev is GatewayEvent.Unknown)
        val unknownEv = ev as GatewayEvent.Unknown
        assertEquals("custom.future.telemetry", unknownEv.type)
        assertEquals(1L, unknownEv.seq)
        assertEquals("runtime-sid-1", unknownEv.sessionId)
        assertEquals("latency", unknownEv.payload["metric"])
        assertEquals(42, (unknownEv.payload["val"] as? Number)?.toInt())
    }

    // =========================================================================
    // I. Secrets not persisted or logged
    // =========================================================================

    @Test
    fun secretsNotPersisted_ticketNotExposed_pendingRequestsDrainedOnClose_reconnectPingWorks() = runTest(testDispatcher) {
        val secretTicket1 = GatewayTicket("ephemeral-secret-ticket-1")
        launch { adapter.connect("https://test.hermes.local", secretTicket1) }
        advanceUntilIdle()

        // Keep an RPC in flight
        var inFlightRpcFailure: GatewayFailure? = null
        transport.setRpcHandler("session.list") { _, _ -> /* do not respond */ }
        launch {
            val res = adapter.listSessions()
            if (res is GatewayResult.Failure) {
                inFlightRpcFailure = res.failure
            }
        }
        advanceTimeBy(10)

        // Close connection abruptly
        transport.emitClose(1000, "Clean close")
        advanceUntilIdle()

        // Pending RPC must be aborted on close
        assertNotNull(inFlightRpcFailure)
        assertTrue("Aborted RPC must fail with Transport failure", inFlightRpcFailure is GatewayFailure.Transport)

        // Reconnect with new ticket
        val secretTicket2 = GatewayTicket("ephemeral-secret-ticket-2")
        launch { adapter.connect("https://test.hermes.local", secretTicket2) }
        advanceUntilIdle()
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        // Subsequent ping after reconnect succeeds
        val pingRes = adapter.ping()
        assertTrue(pingRes is GatewayResult.Success)
        assertTrue((pingRes as GatewayResult.Success).value.ok)

        // Assert adapter exposes no ticket
        assertNoTicketLeakInAdapter(adapter, "ephemeral-secret-ticket-1")
        assertNoTicketLeakInAdapter(adapter, "ephemeral-secret-ticket-2")
    }

    @Test
    fun secretsNotPersisted_ticketCopyAndEqualitySemanticsNeverLeakRawValueInToStringOrDiagnostics() {
        val rawSecret = "super-confidential-ticket-material-445566"
        val ticket1 = GatewayTicket(rawSecret, 30)
        val ticket2 = ticket1.copy(ttlSeconds = 60)
        val ticket3 = GatewayTicket(rawSecret, 30)

        // Equality compares raw values
        assertEquals(ticket1, ticket3)
        assertNotEquals(ticket1, ticket2)

        // toString redacts across original and copy
        assertFalse(ticket1.toString().contains(rawSecret))
        assertTrue(ticket1.toString().contains("[REDACTED]"))
        assertFalse(ticket2.toString().contains(rawSecret))
        assertTrue(ticket2.toString().contains("[REDACTED]"))

        // Context / diagnostics strings
        val diag = "Diagnostics: ticket1=$ticket1, ticket2=$ticket2, isEqual=${ticket1 == ticket3}"
        assertFalse("Diagnostic string must not leak raw ticket value", diag.contains(rawSecret))
        assertTrue(diag.contains("[REDACTED]"))
    }

    @Test
    fun secretsNotPersisted_comprehensivePoisonSweepAcrossAllFailureTypesAndCauseClassNames() = runTest(testDispatcher) {
        val poison = "VERY-LETHAL-POISON-TOKEN-SECRET-424242"

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // 1. Rpc failure with poison
        transport.setRpcHandler("session.list") { id, _ ->
            transport.respondError(
                id = id,
                code = -32603,
                message = "Failed for bearer $poison and ticket=$poison",
                dataRawJson = """{"secret_token":"$poison","token":"$poison"}"""
            )
        }
        val rpcRes = adapter.listSessions()
        assertTrue(rpcRes is GatewayResult.Failure)
        val rpcFailure = (rpcRes as GatewayResult.Failure).failure as GatewayFailure.Rpc
        assertFalse(rpcFailure.message.contains(poison))
        assertTrue(rpcFailure.message.contains("[REDACTED]"))
        assertNotNull(rpcFailure.data)
        assertFalse(rpcFailure.data!!.contains(poison))
        assertTrue(rpcFailure.data!!.contains("[REDACTED]"))

        // 2. Transport failure via exception with poison
        transport.setRpcHandler("session.resume") { _, _ -> /* hold */ }
        var transportFailure: GatewayFailure.Transport? = null
        launch {
            val res = adapter.resumeSession("sid-1")
            if (res is GatewayResult.Failure) {
                transportFailure = res.failure as? GatewayFailure.Transport
            }
        }
        advanceTimeBy(10)
        transport.emitError(IllegalStateException("Network dropped with authorization: Bearer $poison"))
        advanceUntilIdle()

        assertNotNull(transportFailure)
        assertFalse(transportFailure!!.message.contains(poison))
        assertTrue(transportFailure!!.message.contains("[REDACTED]"))
        assertEquals("IllegalStateException", transportFailure!!.causeClassName)
        assertFalse(transportFailure!!.causeClassName!!.contains(poison))

        // 3. SubmissionUnknown failure with poison
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()
        transport.setRpcHandler("prompt.submit") { id, _ ->
            // Respond with status containing poison
            transport.respondSuccess(id, """{"status":"unauthorized ticket=$poison"}""")
        }
        val submitRes = adapter.submitPrompt("sid-1", "hello")
        assertTrue(submitRes is GatewayResult.Failure)
        val subFailure = (submitRes as GatewayResult.Failure).failure as GatewayFailure.SubmissionUnknown
        assertFalse(subFailure.message.contains(poison))
        assertTrue(subFailure.message.contains("[REDACTED]"))

        // 4. IncompatibleProtocol failure
        transport.setRpcHandler("session.list") { id, _ ->
            transport.emitFrame("""{"jsonrpc":"2.0","id":"$id","error":null}""")
        }
        val protoRes = adapter.listSessions()
        assertTrue(protoRes is GatewayResult.Failure)
        val protoFailure = (protoRes as GatewayResult.Failure).failure as GatewayFailure.IncompatibleProtocol
        assertNotNull(protoFailure.reason)

        // 5. NotConnected failure
        adapter.disconnect()
        val notConnRes = adapter.ping()
        assertTrue(notConnRes is GatewayResult.Failure)
        val notConnFailure = (notConnRes as GatewayResult.Failure).failure as GatewayFailure.NotConnected
        assertNotNull(notConnFailure.message)
    }

    private fun assertNoTicketLeakInAdapter(adapter: GatewayProtocolAdapter, secretTicketValue: String) {
        // Check declared and public methods - none should return GatewayTicket or String containing secret
        for (method in adapter.javaClass.methods) {
            assertNotEquals("No public method should return GatewayTicket", GatewayTicket::class.java, method.returnType)
        }
        // Check all fields on adapter instance
        for (field in adapter.javaClass.declaredFields) {
            field.isAccessible = true
            val value = field.get(adapter)
            if (value is String) {
                assertFalse("Field ${field.name} leaked secret ticket value", value.contains(secretTicketValue))
            }
            if (value is GatewayTicket) {
                throw AssertionError("Adapter should never retain GatewayTicket in field ${field.name}")
            }
        }
    }
}
