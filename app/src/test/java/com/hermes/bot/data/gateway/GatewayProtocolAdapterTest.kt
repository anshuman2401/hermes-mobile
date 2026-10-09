package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.fake.FakeGatewayFixtures
import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayProtocolAdapterTest {

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

    @Test
    fun connect_successfulReadyAndCapabilities_transitionsToConnected() = runTest(testDispatcher) {
        val connectJob = launch {
            adapter.connect("https://test.hermes.local", validTicket)
        }
        advanceUntilIdle()
        assertTrue(connectJob.isCompleted)
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)
        assertEquals(FakeGatewayFixtures.TEST_EPOCH_1, adapter.currentReplayEpoch)
    }

    @Test
    fun connect_http403AtHandshake_classifiedAsAuthRequired() = runTest(testDispatcher) {
        transport.handshakeStatusCode = 403

        val connectResult = adapter.connect("https://test.hermes.local", validTicket)
        advanceUntilIdle()

        assertFalse(connectResult)
        assertEquals(ConnectionStatus.AUTHENTICATION_REQUIRED, adapter.connectionStatus.value)
    }

    @Test
    fun close_4401_classifiedAsAuthRequired() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        transport.emitClose(4401, "Ticket expired or reused")
        advanceUntilIdle()

        assertEquals(ConnectionStatus.AUTHENTICATION_REQUIRED, adapter.connectionStatus.value)
    }

    @Test
    fun close_4403_distinctPolicyRejection_transitionsToFailed() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        transport.emitClose(4403, "Disabled or origin policy rejection")
        advanceUntilIdle()

        assertEquals(ConnectionStatus.FAILED, adapter.connectionStatus.value)
    }

    @Test
    fun connect_readyAbsence_blocksUsableAndTransitionsToFailed() = runTest(testDispatcher) {
        transport.autoEmitReady = false

        val connectJob = launch {
            adapter.connect("https://test.hermes.local", validTicket)
        }
        advanceUntilIdle()

        assertTrue(connectJob.isCompleted)
        assertEquals(ConnectionStatus.FAILED, adapter.connectionStatus.value)
    }

    @Test
    fun connect_capabilityMissingOrFalse_blocksUsable() = runTest(testDispatcher) {
        transport.capabilitiesExclusiveSubmit = false

        val connectJob = launch {
            adapter.connect("https://test.hermes.local", validTicket)
        }
        advanceUntilIdle()

        assertTrue(connectJob.isCompleted)
        assertEquals(ConnectionStatus.FAILED, adapter.connectionStatus.value)
    }

    @Test
    fun jsonrpc_correlation_matchesResultByExactId() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        val listResult = adapter.listSessions(limit = 5)
        assertTrue(listResult is GatewayResult.Success)
        val sessions = (listResult as GatewayResult.Success).value.sessions
        assertEquals(2, sessions.size)
        assertEquals("<redacted-session-id-1>", sessions[0].id)
        assertEquals("android-int-001-test", sessions[0].title)
    }

    @Test
    fun jsonrpc_correlation_matchesErrorByExactId() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        transport.setRpcHandler("session.resume") { id, _ ->
            transport.respondError(id, 4007, "session not found")
        }

        val resumeResult = adapter.resumeSession("nonexistent-sid")
        assertTrue(resumeResult is GatewayResult.Failure)
        val failure = (resumeResult as GatewayResult.Failure).failure
        assertTrue(failure is GatewayFailure.Rpc)
        val rpcFailure = failure as GatewayFailure.Rpc
        assertEquals(4007, rpcFailure.code)
        assertEquals("session not found", rpcFailure.message)
    }

    @Test
    fun attachmentRpcs_sendDocumentedParamsAndParseServerFields() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        transport.setRpcHandler("image.attach_bytes") { id, params ->
            assertEquals("<session>", params["session_id"]?.asStringOrNull())
            assertEquals("YQ==", params["content_base64"]?.asStringOrNull())
            assertEquals("photo.png", params["filename"]?.asStringOrNull())
            assertEquals("png", params["ext"]?.asStringOrNull())
            transport.respondSuccess(id, """{"attached":true,"path":"<image-path>","count":1,"text":"<image-text>","bytes":1,"image_meta":{"ignored":true}}""")
        }
        assertEquals(
            AttachImageResult("<image-path>", 1, "<image-text>", 1),
            (adapter.attachImageBytes("<session>", "YQ==", "photo.png", "png") as GatewayResult.Success).value
        )

        transport.setRpcHandler("file.attach") { id, params ->
            assertEquals("<session>", params["session_id"]?.asStringOrNull())
            assertEquals("data:text/plain;base64,YQ==", params["data_url"]?.asStringOrNull())
            assertEquals("note.txt", params["name"]?.asStringOrNull())
            assertNull(params["path"])
            transport.respondSuccess(id, """{"attached":true,"name":"note.txt","path":"<file-path>","ref_path":"<ref-path>","ref_text":"@file:<ref>","uploaded":true,"extra":"ignored"}""")
        }
        assertEquals(
            AttachFileResult("note.txt", "<file-path>", "<ref-path>", "@file:<ref>", true),
            (adapter.attachFile("<session>", "data:text/plain;base64,YQ==", "note.txt") as GatewayResult.Success).value
        )

        transport.setRpcHandler("image.detach") { id, params ->
            assertEquals("<session>", params["session_id"]?.asStringOrNull())
            assertEquals("<image-path>", params["path"]?.asStringOrNull())
            transport.respondSuccess(id, """{"detached":true,"count":0,"extra":"ignored"}""")
        }
        assertEquals(
            DetachImageResult(true, 0),
            (adapter.detachImage("<session>", "<image-path>") as GatewayResult.Success).value
        )
    }

    @Test
    fun attachmentRpcs_mapValidationAndHostErrorsAndPreserveUnknownMethod() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        data class Case(val method: String, val code: Int, val expectedMessage: String)
        val cases = listOf(
            Case("image.attach_bytes", 4015, "Attachment request is missing required data"),
            Case("image.attach_bytes", 4016, "Image type is unsupported"),
            Case("image.attach_bytes", 4017, "Image data is invalid"),
            Case("image.attach_bytes", 4018, "Image exceeds the host size limit"),
            Case("image.attach_bytes", 5027, "Host could not store the image"),
            Case("file.attach", 4015, "Attachment request is missing required data"),
            Case("file.attach", 5028, "Host could not store the file"),
            Case("image.detach", 4015, "Attachment request is missing required data")
        )
        for (case in cases) {
            transport.setRpcHandler(case.method) { id, _ ->
                transport.respondError(id, case.code, "<server-detail>")
            }
            val result = when (case.method) {
                "image.attach_bytes" -> adapter.attachImageBytes("<session>", "YQ==")
                "file.attach" -> adapter.attachFile("<session>", "data:text/plain;base64,YQ==", "note.txt")
                else -> adapter.detachImage("<session>", "<image-path>")
            }
            val failure = (result as GatewayResult.Failure).failure as GatewayFailure.Rpc
            assertEquals(case.code, failure.code)
            assertEquals(case.expectedMessage, failure.message)
            assertFalse(failure.isTerminal)
        }

        for (method in listOf("image.attach_bytes", "file.attach", "image.detach")) {
            transport.setRpcHandler(method) { id, _ ->
                transport.respondError(id, -32601, "Method not found")
            }
            val result = when (method) {
                "image.attach_bytes" -> adapter.attachImageBytes("<session>", "YQ==")
                "file.attach" -> adapter.attachFile("<session>", "data:text/plain;base64,YQ==", "note.txt")
                else -> adapter.detachImage("<session>", "<image-path>")
            }
            val failure = (result as GatewayResult.Failure).failure as GatewayFailure.Rpc
            assertEquals(-32601, failure.code)
            assertEquals("Method not found", failure.message)
            assertTrue(failure.isTerminal)
        }
    }

    @Test
    fun attachmentRpcs_rejectIncompleteSuccessEnvelopes() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        transport.setRpcHandler("image.attach_bytes") { id, _ ->
            transport.respondSuccess(id, """{"attached":false,"path":"<image-path>","count":1,"text":"<image-text>","bytes":1}""")
        }
        val image = adapter.attachImageBytes("<session>", "YQ==")
        assertTrue((image as GatewayResult.Failure).failure is GatewayFailure.IncompatibleProtocol)

        transport.setRpcHandler("file.attach") { id, _ ->
            transport.respondSuccess(id, """{"attached":true,"name":"note.txt","path":"<file-path>","ref_path":"<ref-path>","uploaded":true}""")
        }
        val file = adapter.attachFile("<session>", "data:text/plain;base64,YQ==", "note.txt")
        assertTrue((file as GatewayResult.Failure).failure is GatewayFailure.IncompatibleProtocol)

        transport.setRpcHandler("image.detach") { id, _ ->
            transport.respondSuccess(id, """{"count":0}""")
        }
        val detach = adapter.detachImage("<session>", "<image-path>")
        assertTrue((detach as GatewayResult.Failure).failure is GatewayFailure.IncompatibleProtocol)
    }

    @Test
    fun jsonrpc_unsolicitedResultOrError_ignoredSafelyWithoutMutation() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Inject unsolicited result frame with random ID
        transport.emitFrame("""{"jsonrpc":"2.0","id":"unsolicited-999","result":{"random":true}}""")
        // Inject unsolicited error frame
        transport.emitFrame("""{"jsonrpc":"2.0","id":"unsolicited-888","error":{"code":-32600,"message":"Invalid"}}""")
        advanceUntilIdle()

        // Adapter remains connected and stable
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)
    }

    @Test
    fun malformedAndUnknownFrames_handledSafelyWithoutCrashing() = runTest(testDispatcher) {
        val collectedEvents = mutableListOf<GatewayEvent>()
        val eventsJob = launch {
            adapter.events.toList(collectedEvents)
        }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Send broken JSON
        transport.emitFrame("NOT_JSON_AT_ALL")
        transport.emitFrame("{unclosed json")
        transport.emitFrame("[1, 2, 3]")

        // Send unknown event frame
        transport.emitFrame("""
            {
              "jsonrpc": "2.0",
              "method": "event",
              "params": {
                "type": "future.experimental.event",
                "session_id": "sid-test-1",
                "seq": 100,
                "payload": {
                  "experimental_data": "opaque"
                }
              }
            }
        """.trimIndent())
        advanceUntilIdle()

        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)
        val unknownEvent = collectedEvents.filterIsInstance<GatewayEvent.Unknown>().firstOrNull()
        assertNotNull(unknownEvent)
        assertEquals("future.experimental.event", unknownEvent?.type)
        assertEquals("sid-test-1", unknownEvent?.sessionId)
        assertEquals(100L, unknownEvent?.seq)
        assertEquals("opaque", unknownEvent?.payload?.get("experimental_data"))

        eventsJob.cancel()
    }

    @Test
    fun sessionResume_parsesDualSchemas_unpersistedAndPersisted() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Variant A: Live unpersisted draft (stored_session_id, missing status/running)
        transport.setRpcHandler("session.resume") { id, _ ->
            transport.respondSuccess(id, FakeGatewayFixtures.SESSION_RESUME_UNPERSISTED)
        }
        val unpersistedRes = adapter.resumeSession("sid-draft-query")
        assertTrue(unpersistedRes is GatewayResult.Success)
        val draft = (unpersistedRes as GatewayResult.Success).value
        assertEquals("TEST-RUNTIME-SID-1", draft.runtimeSessionId)
        assertEquals("TEST-STORED-SESSION-KEY-1", draft.durableSessionId)
        assertEquals("idle", draft.status) // Default when omitted
        assertFalse(draft.running) // Default when omitted
        assertEquals(0, draft.messageCount)

        // Variant B: Persisted session (session_key, status, running, started_at)
        transport.setRpcHandler("session.resume") { id, _ ->
            transport.respondSuccess(id, FakeGatewayFixtures.SESSION_RESUME_PERSISTED)
        }
        val persistedRes = adapter.resumeSession("sid-persisted-query")
        assertTrue(persistedRes is GatewayResult.Success)
        val persisted = (persistedRes as GatewayResult.Success).value
        assertEquals("TEST-RUNTIME-SID-1", persisted.runtimeSessionId)
        assertEquals("TEST-STORED-SESSION-KEY-1", persisted.durableSessionId)
        assertEquals("idle", persisted.status)
        assertFalse(persisted.running)
        assertEquals(2, persisted.messageCount)
        assertEquals("Echo the single word: PONG", persisted.messages[0].content)
        assertEquals("PONG", persisted.messages[1].content)
    }

    @Test
    fun promptSubmit_confirmedAdmissionAndWriteFailureAmbiguity() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Normal successful submission (streaming status)
        val submitRes = adapter.submitPrompt("sid-1", "Hello")
        assertTrue(submitRes is GatewayResult.Success)
        assertEquals("streaming", (submitRes as GatewayResult.Success).value.status)

        // Write/result ambiguity: socket closes before confirmation
        transport.setRpcHandler("prompt.submit") { _, _ ->
            transport.emitClose(1006, "Abrupt drop")
        }

        val failedSubmit = adapter.submitPrompt("sid-1", "Hello again")
        assertTrue(failedSubmit is GatewayResult.Failure)
        val failure = (failedSubmit as GatewayResult.Failure).failure
        assertTrue("Expected SUBMISSION_UNKNOWN on write ambiguity", failure is GatewayFailure.SubmissionUnknown)
    }

    @Test
    fun replayEventsSince_bareParamsParsed_andFallbackOnTruncatedOrEpochMismatch() = runTest(testDispatcher) {
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val fallbackJob = launch {
            adapter.historyFallbackSignals.toList(fallbackSignals)
        }
        val eventsJob = launch { adapter.events.collect {} }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Replay with bare params (normal)
        // With watermark 1L, seq 1 is deduped; returned normalized events are [2, 3]
        val testSid = FakeGatewayFixtures.TEST_RUNTIME_SID
        adapter.setLastSeenSeq(testSid, 1L)
        val normalReplay = adapter.replayEventsSince(testSid, 1L, "durable-1")
        assertTrue(normalReplay is GatewayResult.Success)
        val replayData = (normalReplay as GatewayResult.Success).value
        assertEquals(3, replayData.count)
        assertEquals(3L, replayData.latestSeq)
        assertEquals(2, replayData.events.size)
        assertTrue(replayData.events[0] is GatewayEvent.MessageDelta)
        assertEquals(2L, replayData.events[0].seq)
        assertTrue(replayData.events[1] is GatewayEvent.MessageComplete)
        assertEquals(3L, replayData.events[1].seq)
        assertEquals(3L, adapter.getLastSeenSeq(testSid))
        assertTrue(fallbackSignals.isEmpty())

        // Replay with truncated: true -> fallback emitted, events list empty
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(id, FakeGatewayFixtures.REPLAY_TRUNCATED_RESPONSE)
        }
        val truncatedReplay = adapter.replayEventsSince(testSid, 3L, "durable-1")
        assertTrue(truncatedReplay is GatewayResult.Success)
        assertEquals(0, (truncatedReplay as GatewayResult.Success).value.events.size)
        advanceUntilIdle()
        assertNull(adapter.getLastSeenSeq(testSid))
        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.TRUNCATED, fallbackSignals[0].reason)

        // Replay with epoch mismatch -> fallback emitted, events list empty
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(id, FakeGatewayFixtures.REPLAY_EPOCH_MISMATCH_RESPONSE)
        }
        val epochMismatchReplay = adapter.replayEventsSince(testSid, 0L, "durable-1")
        assertTrue(epochMismatchReplay is GatewayResult.Success)
        assertEquals(0, (epochMismatchReplay as GatewayResult.Success).value.events.size)
        advanceUntilIdle()
        assertEquals(2, fallbackSignals.size)
        assertEquals(FallbackReason.EPOCH_MISMATCH, fallbackSignals[1].reason)

        fallbackJob.cancel()
        eventsJob.cancel()
    }

    @Test
    fun keepalive_gatewayPingRespondsOk() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        val pingResult = adapter.ping()
        assertTrue(pingResult is GatewayResult.Success)
        assertTrue((pingResult as GatewayResult.Success).value.ok)
    }

    @Test
    fun toolLifecycle_optionalGeneratingAndProgressAreNonFatal() = runTest(testDispatcher) {
        val collectedEvents = mutableListOf<GatewayEvent>()
        val eventsJob = launch {
            adapter.events.toList(collectedEvents)
        }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // tool.generating (name only, no tool_id)
        transport.emitFrame("""
            {"jsonrpc":"2.0","method":"event","params":{"session_id":"sid-1","seq":10,"type":"tool.generating","payload":{"name":"terminal"}}}
        """.trimIndent())

        // tool.start (tool_id, name, args)
        transport.emitFrame("""
            {"jsonrpc":"2.0","method":"event","params":{"session_id":"sid-1","seq":11,"type":"tool.start","payload":{"tool_id":"call-1","name":"terminal","args":{"cmd":"ls"}}}}
        """.trimIndent())

        // tool.progress variant (e.g. reasoning.available)
        transport.emitFrame("""
            {"jsonrpc":"2.0","method":"event","params":{"session_id":"sid-1","seq":12,"type":"reasoning.available","payload":{"text":"planning"}}}
        """.trimIndent())

        // tool.complete (tool_id, result, duration)
        transport.emitFrame("""
            {"jsonrpc":"2.0","method":"event","params":{"session_id":"sid-1","seq":13,"type":"tool.complete","payload":{"tool_id":"call-1","name":"terminal","duration_s":0.05,"summary":"done"}}}
        """.trimIndent())
        advanceUntilIdle()

        val toolEvents = collectedEvents.filterNot { it is GatewayEvent.Ready }
        assertEquals(4, toolEvents.size)
        val generating = toolEvents[0] as GatewayEvent.ToolGenerating
        assertEquals("terminal", generating.name)

        val start = toolEvents[1] as GatewayEvent.ToolStart
        assertEquals("call-1", start.toolId)

        val progress = toolEvents[2] as GatewayEvent.ToolProgress
        assertEquals("reasoning.available", progress.type)

        val complete = toolEvents[3] as GatewayEvent.ToolComplete
        assertEquals("call-1", complete.toolId)
        assertEquals("done", complete.summary)

        eventsJob.cancel()
    }

    @Test
    fun ticketRedaction_ticketValueNeverExposedInToStringOrDiagnostics() {
        val secretValue = "super-secret-raw-ticket-token-998877"
        val ticket = GatewayTicket(secretValue, 30)

        // toString() must redact
        assertFalse(ticket.toString().contains(secretValue))
        assertTrue(ticket.toString().contains("[REDACTED]"))

        // ConnectConfig toString() must redact
        val configPreferred = GatewayConnectConfig(
            url = "wss://test.hermes.local/api/ws",
            subprotocols = listOf("hermes-gateway-v1", "hermes-gateway-ticket.$secretValue")
        )
        assertFalse(configPreferred.toString().contains(secretValue))
        assertTrue(configPreferred.toString().contains("[REDACTED]"))

        val configQuery = GatewayConnectConfig(
            url = "wss://test.hermes.local/api/ws?ticket=$secretValue",
            subprotocols = listOf("hermes-gateway-v1")
        )
        assertFalse(configQuery.toString().contains(secretValue))
        assertTrue(configQuery.toString().contains("[REDACTED]"))
    }

    @Test
    fun connectionGeneration_incrementsOnEachConnectAttempt() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()
        adapter.ping()
        advanceUntilIdle()

        val firstPingFrame = transport.sentFrames.last()
        assertTrue(firstPingFrame.contains("\"id\":\"android-1-"))

        // Reconnect
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()
        adapter.ping()
        advanceUntilIdle()

        val secondPingFrame = transport.sentFrames.last()
        assertTrue(secondPingFrame.contains("\"id\":\"android-2-"))
    }

    // =========================================================================
    // F-001 Tests: Replay Gap Detection, Decode Errors, and Normalized Events
    // =========================================================================

    @Test
    fun replayEventsSince_gapFromNonzeroWatermark_triggersFallbackAndClearsWatermark() = runTest(testDispatcher) {
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val fallbackJob = launch { adapter.historyFallbackSignals.toList(fallbackSignals) }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.setLastSeenSeq("sid-gap-1", 1L)
        // Watermark 1; batch [2, 4] skips seq 3
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(
                id,
                """
                {
                  "count": 2,
                  "epoch": "${FakeGatewayFixtures.TEST_EPOCH_1}",
                  "latest_seq": 4,
                  "truncated": false,
                  "events": [
                    {"type":"message.delta","seq":2,"session_id":"sid-gap-1","payload":{"text":"two"}},
                    {"type":"message.delta","seq":4,"session_id":"sid-gap-1","payload":{"text":"four"}}
                  ]
                }
                """.trimIndent()
            )
        }

        val result = adapter.replayEventsSince("sid-gap-1", 1L, "durable-1")
        assertTrue(result is GatewayResult.Success)
        val data = (result as GatewayResult.Success).value
        assertEquals(0, data.events.size) // Empty on fallback
        advanceUntilIdle()

        assertNull(adapter.getLastSeenSeq("sid-gap-1")) // Watermark cleared
        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, fallbackSignals[0].reason)

        fallbackJob.cancel()
    }

    @Test
    fun replayEventsSince_initialGapWithNoWatermark_triggersFallback() = runTest(testDispatcher) {
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val fallbackJob = launch { adapter.historyFallbackSignals.toList(fallbackSignals) }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // No watermark set; batch starts at seq 3 (missing 1, 2)
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(
                id,
                """
                {
                  "count": 1,
                  "epoch": "${FakeGatewayFixtures.TEST_EPOCH_1}",
                  "latest_seq": 3,
                  "truncated": false,
                  "events": [
                    {"type":"message.delta","seq":3,"session_id":"sid-init-gap","payload":{"text":"three"}}
                  ]
                }
                """.trimIndent()
            )
        }

        val result = adapter.replayEventsSince("sid-init-gap", 0L, "durable-1")
        assertTrue(result is GatewayResult.Success)
        advanceUntilIdle()

        assertNull(adapter.getLastSeenSeq("sid-init-gap"))
        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, fallbackSignals[0].reason)

        fallbackJob.cancel()
    }

    @Test
    fun replayEventsSince_malformedReplayRows_triggersDecodeErrorFallback() = runTest(testDispatcher) {
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val fallbackJob = launch { adapter.historyFallbackSignals.toList(fallbackSignals) }

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.setLastSeenSeq("sid-malformed", 1L)

        // Case 1: non-object row (scalar in events array)
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(
                id,
                """
                {
                  "count": 1,
                  "epoch": "${FakeGatewayFixtures.TEST_EPOCH_1}",
                  "latest_seq": 2,
                  "truncated": false,
                  "events": ["not an object"]
                }
                """.trimIndent()
            )
        }
        val res1 = adapter.replayEventsSince("sid-malformed", 1L)
        assertTrue(res1 is GatewayResult.Success)
        advanceUntilIdle()
        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.DECODE_ERROR, fallbackSignals[0].reason)
        assertNull(adapter.getLastSeenSeq("sid-malformed"))

        adapter.markHistoryRehydrated("sid-malformed")
        adapter.setLastSeenSeq("sid-malformed", 1L)

        // Case 2: row missing type
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(
                id,
                """
                {
                  "count": 1,
                  "epoch": "${FakeGatewayFixtures.TEST_EPOCH_1}",
                  "latest_seq": 2,
                  "truncated": false,
                  "events": [{"seq":2,"session_id":"sid-malformed"}]
                }
                """.trimIndent()
            )
        }
        val res2 = adapter.replayEventsSince("sid-malformed", 1L)
        assertTrue(res2 is GatewayResult.Success)
        advanceUntilIdle()
        assertEquals(2, fallbackSignals.size)
        assertEquals(FallbackReason.DECODE_ERROR, fallbackSignals[1].reason)

        adapter.markHistoryRehydrated("sid-malformed")
        adapter.setLastSeenSeq("sid-malformed", 1L)

        // Case 3: row with seq as string
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(
                id,
                """
                {
                  "count": 1,
                  "epoch": "${FakeGatewayFixtures.TEST_EPOCH_1}",
                  "latest_seq": 2,
                  "truncated": false,
                  "events": [{"type":"message.delta","seq":"2","session_id":"sid-malformed","payload":{"text":"str-seq"}}]
                }
                """.trimIndent()
            )
        }
        val res3 = adapter.replayEventsSince("sid-malformed", 1L)
        assertTrue(res3 is GatewayResult.Success)
        advanceUntilIdle()
        assertEquals(3, fallbackSignals.size)
        assertEquals(FallbackReason.DECODE_ERROR, fallbackSignals[2].reason)

        fallbackJob.cancel()
    }

    @Test
    fun replayEventsSince_wireOutOfOrderAndDuplicate_returnedNormalizedAscendingDeduped() = runTest(testDispatcher) {
        val eventsJob = launch { adapter.events.collect {} }
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.setLastSeenSeq("sid-norm", 1L)

        // Wire contains duplicate seq 1 and out-of-order seq 3 before seq 2
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(
                id,
                """
                {
                  "count": 3,
                  "epoch": "${FakeGatewayFixtures.TEST_EPOCH_1}",
                  "latest_seq": 3,
                  "truncated": false,
                  "events": [
                    {"type":"message.delta","seq":3,"session_id":"sid-norm","payload":{"text":"three"}},
                    {"type":"message.delta","seq":1,"session_id":"sid-norm","payload":{"text":"one-dup"}},
                    {"type":"message.delta","seq":2,"session_id":"sid-norm","payload":{"text":"two"}}
                  ]
                }
                """.trimIndent()
            )
        }

        val result = adapter.replayEventsSince("sid-norm", 1L)
        assertTrue(result is GatewayResult.Success)
        val data = (result as GatewayResult.Success).value

        // Duplicate seq 1 deduped; returned events strictly sorted ascending: [2, 3]
        assertEquals(2, data.events.size)
        assertEquals(2L, data.events[0].seq)
        assertEquals(3L, data.events[1].seq)
        assertEquals(3L, adapter.getLastSeenSeq("sid-norm"))

        eventsJob.cancel()
    }

    // =========================================================================
    // F-002 Tests: HTTPS-Only Validated Origin Boundary
    // =========================================================================

    @Test
    fun connect_acceptedHttpsWithAndWithoutPathPrefix_configuresTransportCorrectly() = runTest(testDispatcher) {
        // 1. Bare HTTPS origin
        val res1 = adapter.connect("https://test.hermes.local", validTicket)
        advanceUntilIdle()
        assertTrue(res1)
        assertEquals("wss://test.hermes.local/api/ws", transport.lastConnectConfig?.url)

        // 2. HTTPS origin with path prefix
        val res2 = adapter.connect("https://test.hermes.local/prefix/v1", validTicket)
        advanceUntilIdle()
        assertTrue(res2)
        assertEquals("wss://test.hermes.local/prefix/v1/api/ws", transport.lastConnectConfig?.url)

        // 3. HTTPS origin with trailing slashes
        val res3 = adapter.connect("https://test.hermes.local/custom/app///", validTicket)
        advanceUntilIdle()
        assertTrue(res3)
        assertEquals("wss://test.hermes.local/custom/app/api/ws", transport.lastConnectConfig?.url)
    }

    @Test
    fun connect_rejectedOriginClasses_returnsFalseWithoutTransportInvocation() = runTest(testDispatcher) {
        val rejectedOrigins = listOf(
            "ws://test.hermes.local", // rejected class: ws scheme
            "wss://test.hermes.local", // rejected class: wss scheme
            "https://user:pass@test.hermes.local", // rejected class: userinfo
            "https://test.hermes.local?query=1", // rejected class: query
            "https://test.hermes.local#frag", // rejected class: fragment
            "", // rejected class: empty
            "   ", // rejected class: whitespace
            "ftp://test.hermes.local", // rejected class: unsupported scheme
            "https://invalid host.local/", // rejected class: space in host
            "https://" // rejected class: missing host
        )

        for (rejected in rejectedOrigins) {
            val freshTransport = FakeGatewayTransport()
            val freshAdapter = DefaultGatewayProtocolAdapter(freshTransport, testDispatcher)

            val success = freshAdapter.connect(rejected, validTicket)
            advanceUntilIdle()

            assertFalse("Expected connect to return false for: $rejected", success)
            assertNull("Transport should not have been called for: $rejected", freshTransport.lastConnectConfig)
            assertEquals(ConnectionStatus.FAILED, freshAdapter.connectionStatus.value)
        }
    }

    // =========================================================================
    // F-003 Tests: Secret-Safe Failure and Event Diagnostics
    // =========================================================================

    @Test
    fun secretSafety_poisonValuesNeverLeakedInFailuresEventsOrDiagnostics() = runTest(testDispatcher) {
        val poison = "CRITICAL-POISON-SECRET-998877"

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // 1. JSON-RPC Error with poison in message, data, ticket params, and ticket subprotocols
        transport.setRpcHandler("session.resume") { id, _ ->
            transport.respondError(
                id = id,
                code = 4001,
                message = "Auth failed for hermes-gateway-ticket.$poison and ?ticket=$poison",
                dataRawJson = """{"ticket":"$poison","token":"$poison","nested":{"secret":"$poison"}}"""
            )
        }

        val rpcResult = adapter.resumeSession("sid-poison-check")
        assertTrue(rpcResult is GatewayResult.Failure)
        val rpcFailure = (rpcResult as GatewayResult.Failure).failure as GatewayFailure.Rpc
        assertFalse(rpcFailure.message.contains(poison))
        assertTrue(rpcFailure.message.contains("[REDACTED]"))
        assertNotNull(rpcFailure.data)
        assertFalse(rpcFailure.data!!.contains(poison))
        assertTrue(rpcFailure.data!!.contains("[REDACTED]"))
        assertFalse(rpcFailure.toString().contains(poison))
        assertTrue(rpcFailure.toString().contains("[REDACTED]"))

        // 2. Transport exception containing poison in message aborting in-flight RPC
        transport.setRpcHandler("session.list") { _, _ ->
            // Keep in-flight without immediate response
        }
        var listFailure: GatewayFailure? = null
        val listJob = launch {
            val res = adapter.listSessions()
            if (res is GatewayResult.Failure) {
                listFailure = res.failure
            }
        }
        advanceTimeBy(10)
        transport.emitError(RuntimeException("Connection drop while resolving ticket=$poison"))
        advanceUntilIdle()

        assertTrue(listJob.isCompleted)
        assertNotNull(listFailure)
        assertTrue(listFailure is GatewayFailure.Transport)
        val transportFailure = listFailure as GatewayFailure.Transport
        assertFalse(transportFailure.message.contains(poison))
        assertTrue(transportFailure.message.contains("[REDACTED]"))
        assertFalse(transportFailure.toString().contains(poison))
        assertTrue(transportFailure.toString().contains("[REDACTED]"))

        // 3. Unknown event and tool events with credential keys
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        val collectedEvents = mutableListOf<GatewayEvent>()
        val eventsJob = launch { adapter.events.toList(collectedEvents) }

        transport.emitFrame("""
            {
              "jsonrpc": "2.0",
              "method": "event",
              "params": {
                "type": "custom.security.event",
                "session_id": "sid-sec",
                "seq": 50,
                "payload": {
                  "ticket": "$poison",
                  "token": "$poison",
                  "password": "$poison",
                  "secret": "$poison",
                  "cookie": "$poison",
                  "authorization": "Bearer $poison",
                  "safe_field": "safe-value"
                }
              }
            }
        """.trimIndent())
        advanceUntilIdle()

        val unknownEvent = collectedEvents.filterIsInstance<GatewayEvent.Unknown>().firstOrNull()
        assertNotNull(unknownEvent)
        assertFalse(unknownEvent.toString().contains(poison))
        assertEquals("[REDACTED]", unknownEvent?.payload?.get("ticket"))
        assertEquals("[REDACTED]", unknownEvent?.payload?.get("token"))
        assertEquals("[REDACTED]", unknownEvent?.payload?.get("password"))
        assertEquals("[REDACTED]", unknownEvent?.payload?.get("secret"))
        assertEquals("[REDACTED]", unknownEvent?.payload?.get("cookie"))
        assertEquals("[REDACTED]", unknownEvent?.payload?.get("authorization"))
        assertEquals("safe-value", unknownEvent?.payload?.get("safe_field"))

        eventsJob.cancel()
    }

    // =========================================================================
    // F-004 Tests: Ready Epoch Changes and History Rehydration Gating
    // =========================================================================

    @Test
    fun reconnect_readyEpochChanged_emitsFallbackSignalAndRequiresRehydration() = runTest(testDispatcher) {
        val fallbackSignals = mutableListOf<HistoryFallbackSignal>()
        val fallbackJob = launch { adapter.historyFallbackSignals.toList(fallbackSignals) }

        // Initial connect in epoch 1
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()
        assertEquals(FakeGatewayFixtures.TEST_EPOCH_1, adapter.currentReplayEpoch)

        adapter.setLastSeenSeq("sid-epoch-test", 1L)

        // Disconnect
        adapter.disconnect()
        advanceUntilIdle()

        // Reconnect with Epoch 2
        val readyFrameEpoch2 = """
        {
          "jsonrpc": "2.0",
          "method": "event",
          "params": {
            "type": "gateway.ready",
            "payload": {
              "change_events": true,
              "heartbeat": true,
              "replay_epoch": "${FakeGatewayFixtures.TEST_EPOCH_2}"
            }
          }
        }
        """.trimIndent()
        transport.readyFrameOverride = readyFrameEpoch2

        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()
        assertEquals(FakeGatewayFixtures.TEST_EPOCH_2, adapter.currentReplayEpoch)

        // Assert fallback signal was emitted during reconnect
        assertEquals(1, fallbackSignals.size)
        assertEquals(FallbackReason.EPOCH_CHANGED_READY, fallbackSignals[0].reason)
        assertEquals("sid-epoch-test", fallbackSignals[0].runtimeSessionId)
        assertNull(adapter.getLastSeenSeq("sid-epoch-test"))

        // Attempting replay in Epoch 2 before rehydration must return Fallback EPOCH_CHANGED_READY
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(
                id,
                """
                {
                  "count": 1,
                  "epoch": "${FakeGatewayFixtures.TEST_EPOCH_2}",
                  "latest_seq": 1,
                  "truncated": false,
                  "events": [
                    {"type":"message.start","seq":1,"session_id":"sid-epoch-test","payload":{}}
                  ]
                }
                """.trimIndent()
            )
        }

        val unhydratedReplay = adapter.replayEventsSince("sid-epoch-test", 0L)
        assertTrue(unhydratedReplay is GatewayResult.Success)
        advanceUntilIdle()
        assertEquals(2, fallbackSignals.size)
        assertEquals(FallbackReason.EPOCH_CHANGED_READY, fallbackSignals[1].reason)

        // Mark history rehydrated (e.g. after session.resume)
        adapter.markHistoryRehydrated("sid-epoch-test")

        val eventsJob = launch { adapter.events.collect {} }
        // Subsequent replay in Epoch 2 is accepted
        val hydratedReplay = adapter.replayEventsSince("sid-epoch-test", 0L)
        assertTrue(hydratedReplay is GatewayResult.Success)
        val data = (hydratedReplay as GatewayResult.Success).value
        assertEquals(1, data.events.size)
        assertEquals(1L, adapter.getLastSeenSeq("sid-epoch-test"))

        fallbackJob.cancel()
        eventsJob.cancel()
    }

    // =========================================================================
    // F-005 Tests: Raw ID-less Ping Ack Prompt Completion
    // =========================================================================

    @Test
    fun ping_rawAckOnly_completesPromptlyWithoutWaitingForTimeout() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        transport.pingRawAckOnly = true

        var pingResult: GatewayResult<GatewayPingResult>? = null
        val pingJob = launch {
            pingResult = adapter.ping()
        }

        // Advance by only 10ms; must complete without waiting for 1,000ms timeout
        advanceTimeBy(10)
        advanceUntilIdle()

        assertTrue(pingJob.isCompleted)
        assertNotNull(pingResult)
        assertTrue(pingResult is GatewayResult.Success)
        assertTrue((pingResult as GatewayResult.Success).value.ok)
    }

    // =========================================================================
    // F-006 Tests: Strict Correlated Frame Admission and Prompt Status
    // =========================================================================

    @Test
    fun jsonrpc_strictEnvelopeAdmission_rejectsMissingOrDoubleEnvelopesOrBadVersion() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // 1. Frame with ID but neither result nor error
        transport.setRpcHandler("session.list") { id, _ ->
            transport.emitFrame("""{"jsonrpc":"2.0","id":"$id","status":"ok"}""")
        }
        val res1 = adapter.listSessions()
        assertTrue(res1 is GatewayResult.Failure)
        assertTrue((res1 as GatewayResult.Failure).failure is GatewayFailure.IncompatibleProtocol)

        // 2. Frame with both result and error
        transport.setRpcHandler("session.list") { id, _ ->
            transport.emitFrame("""{"jsonrpc":"2.0","id":"$id","result":{},"error":{"code":-32600,"message":"oops"}}""")
        }
        val res2 = adapter.listSessions()
        assertTrue(res2 is GatewayResult.Failure)
        assertTrue((res2 as GatewayResult.Failure).failure is GatewayFailure.IncompatibleProtocol)

        // 3. Frame with non-2.0 jsonrpc
        transport.setRpcHandler("session.list") { id, _ ->
            transport.emitFrame("""{"jsonrpc":"1.0","id":"$id","result":{"sessions":[]}}""")
        }
        val res3 = adapter.listSessions()
        assertTrue(res3 is GatewayResult.Failure)
        assertTrue((res3 as GatewayResult.Failure).failure is GatewayFailure.IncompatibleProtocol)
    }

    @Test
    fun promptSubmit_strictStatusValidation_onlyStreamingConfirmed() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // 1. Ambiguous status "accepted" -> SubmissionUnknown
        transport.setRpcHandler("prompt.submit") { id, _ ->
            transport.respondSuccess(id, """{"status":"accepted"}""")
        }
        val res1 = adapter.submitPrompt("sid-1", "Test")
        assertTrue(res1 is GatewayResult.Failure)
        assertTrue((res1 as GatewayResult.Failure).failure is GatewayFailure.SubmissionUnknown)

        // 2. Missing status -> SubmissionUnknown
        transport.setRpcHandler("prompt.submit") { id, _ ->
            transport.respondSuccess(id, """{}""")
        }
        val res2 = adapter.submitPrompt("sid-1", "Test")
        assertTrue(res2 is GatewayResult.Failure)
        assertTrue((res2 as GatewayResult.Failure).failure is GatewayFailure.SubmissionUnknown)

        // 3. Confirmed status "streaming" -> Success
        transport.setRpcHandler("prompt.submit") { id, _ ->
            transport.respondSuccess(id, """{"status":"streaming"}""")
        }
        val res3 = adapter.submitPrompt("sid-1", "Test")
        assertTrue(res3 is GatewayResult.Success)
        assertEquals("streaming", (res3 as GatewayResult.Success).value.status)
    }

    // =========================================================================
    // F-007 Tests: Handshake Close/Error Terminates Ready Wait Promptly
    // =========================================================================

    @Test
    fun connect_closeDuringReadyGate_terminatesPromptlyPreservingClassification() = runTest(testDispatcher) {
        // Case 1: Close 4403 during ready wait
        transport.autoEmitReady = false
        var connectResult: Boolean? = null
        val job1 = launch {
            connectResult = adapter.connect("https://test.hermes.local", validTicket)
        }
        advanceTimeBy(10)
        transport.emitClose(4403, "Origin policy violation")
        advanceTimeBy(10)
        advanceUntilIdle()

        assertTrue(job1.isCompleted)
        assertEquals(false, connectResult)
        assertEquals(ConnectionStatus.FAILED, adapter.connectionStatus.value)

        // Case 2: Close 4401 during ready wait
        val freshTransport = FakeGatewayTransport()
        freshTransport.autoEmitReady = false
        val freshAdapter = DefaultGatewayProtocolAdapter(freshTransport, testDispatcher, readyTimeoutMs = 10_000L)
        var connectResult2: Boolean? = null
        val job2 = launch {
            connectResult2 = freshAdapter.connect("https://test.hermes.local", validTicket)
        }
        advanceTimeBy(10)
        freshTransport.emitClose(4401, "Ticket expired")
        advanceTimeBy(10)
        advanceUntilIdle()

        assertTrue(job2.isCompleted)
        assertEquals(false, connectResult2)
        assertEquals(ConnectionStatus.AUTHENTICATION_REQUIRED, freshAdapter.connectionStatus.value)

        // Case 3: Error during ready wait
        val errorTransport = FakeGatewayTransport()
        errorTransport.autoEmitReady = false
        val errorAdapter = DefaultGatewayProtocolAdapter(errorTransport, testDispatcher, readyTimeoutMs = 10_000L)
        var connectResult3: Boolean? = null
        val job3 = launch {
            connectResult3 = errorAdapter.connect("https://test.hermes.local", validTicket)
        }
        advanceTimeBy(10)
        errorTransport.emitError(RuntimeException("Handshake rejected"), 403)
        advanceTimeBy(10)
        advanceUntilIdle()

        assertTrue(job3.isCompleted)
        assertEquals(false, connectResult3)
        assertEquals(ConnectionStatus.AUTHENTICATION_REQUIRED, errorAdapter.connectionStatus.value)
    }

    // =========================================================================
    // F007-S2: live reasoning decode (reasoning.delta / thinking.delta)
    // =========================================================================

    @Test
    fun decodeReasoningDelta_typedReasoningEvent() {
        // F007-S2: the host emits reasoning.delta {text, verbose?} live on every
        // reasoning-bearing stream (agent_callbacks.py:96-98); it must decode to
        // a typed ReasoningDelta with kind=reasoning, not fall through to
        // Unknown. parseEventForTest uses the same decodeEvent used for live
        // frames (no connection needed); it passes no seq by seam design, so
        // only sessionId/kind/text are asserted here.
        val event = adapter.parseEventForTest(
            """{"type":"reasoning.delta","session_id":"sid-1","seq":7,"payload":{"text":"thinking about X"}}"""
        )
        val reasoning = event as GatewayEvent.ReasoningDelta
        assertEquals("sid-1", reasoning.sessionId)
        assertEquals(ReasoningKind.REASONING, reasoning.kind)
        assertEquals("thinking about X", reasoning.text)
    }

    @Test
    fun decodeThinkingDelta_typedReasoningEventThinkingKind() {
        // F007-S2: thinking.delta {text} (agent_callbacks.py:93) decodes with
        // kind=thinking so the UI can label the block distinctly.
        val event = adapter.parseEventForTest(
            """{"type":"thinking.delta","session_id":"sid-1","seq":8,"payload":{"text":"wait commentary"}}"""
        )
        val reasoning = event as GatewayEvent.ReasoningDelta
        assertEquals("sid-1", reasoning.sessionId)
        assertEquals(ReasoningKind.THINKING, reasoning.kind)
        assertEquals("wait commentary", reasoning.text)
    }

    @Test
    fun decodeReasoningDelta_malformedPayload_safeEmptyNoCrash() {
        // F007-S2: non-string / absent / malformed text payloads decode safely
        // to empty text — no crash, no fabricated text (plan step 2).
        val nonString = adapter.parseEventForTest(
            """{"type":"reasoning.delta","session_id":"sid-1","seq":1,"payload":{"text":42}}"""
        )
        assertTrue(nonString is GatewayEvent.ReasoningDelta)
        assertEquals("", (nonString as GatewayEvent.ReasoningDelta).text)
        assertEquals(ReasoningKind.REASONING, (nonString as GatewayEvent.ReasoningDelta).kind)

        val absent = adapter.parseEventForTest(
            """{"type":"reasoning.delta","session_id":"sid-1","seq":2,"payload":{}}"""
        )
        assertTrue(absent is GatewayEvent.ReasoningDelta)
        assertEquals("", (absent as GatewayEvent.ReasoningDelta).text)

        val malformedPayload = adapter.parseEventForTest(
            """{"type":"thinking.delta","session_id":"sid-1","seq":3,"payload":{"text":[1,2]}}"""
        )
        assertTrue(malformedPayload is GatewayEvent.ReasoningDelta)
        assertEquals("", (malformedPayload as GatewayEvent.ReasoningDelta).text)
        assertEquals(ReasoningKind.THINKING, (malformedPayload as GatewayEvent.ReasoningDelta).kind)

        // Degenerate envelopes (payload not an object at all) also stay safe.
        val badPayloadShape = adapter.parseEventForTest(
            """{"type":"reasoning.delta","session_id":"sid-1","seq":4,"payload":"oops"}"""
        )
        assertTrue(badPayloadShape is GatewayEvent.ReasoningDelta)
        assertEquals("", (badPayloadShape as GatewayEvent.ReasoningDelta).text)
    }

    // =========================================================================
    // CHAT-010: session.info boundary redaction parity (model + reasoning
    // effort survive; credential-keyed entries do not)
    // =========================================================================

    @Test
    fun decodeSessionInfo_preservesModelAndReasoningEffort() {
        // CHAT-010: session.info {model, reasoning_effort, ...} must decode
        // typed with the consumed keys intact — the Chat reducer's label
        // sources. parseEventForTest uses the same decodeEvent used for
        // live frames (no connection needed).
        val event = adapter.parseEventForTest(
            """{"type":"session.info","session_id":"sid-1","seq":9,"payload":{"model":"gpt-5.6-luna","provider":"openai","reasoning_effort":"high"}}"""
        )
        val info = (event as GatewayEvent.SessionInfo).info
        assertEquals("gpt-5.6-luna", info["model"])
        assertEquals("high", info["reasoning_effort"])
    }

    @Test
    fun decodeSessionInfo_redactsCredentialKeyedEntries() {
        // CHAT-010 (T8): session.info now meets the same boundary rule as
        // the create/resume info maps — credential-keyed entries (any key
        // matching the ticket/token/session/etc regex) become "[REDACTED]".
        // The consumed keys (model, reasoning_effort) are not credential
        // keys and must survive redaction untouched.
        val event = adapter.parseEventForTest(
            """{"type":"session.info","session_id":"sid-1","seq":9,"payload":{"model":"gpt-5.6-luna","reasoning_effort":"none","stored_session_id":"opaque-session-id-12345"}}"""
        )
        val info = (event as GatewayEvent.SessionInfo).info
        assertEquals("gpt-5.6-luna", info["model"])
        assertEquals("none", info["reasoning_effort"])
        assertEquals("[REDACTED]", info["stored_session_id"])
    }

    @Test
    fun decodeReasoningAvailable_mappingUnchanged_stillToolProgress() {
        // F007-S2 guard: this slice does NOT touch the reasoning.available
        // mapping (stays ToolProgress per plan step 2 / debug report §2).
        val event = adapter.parseEventForTest(
            """{"type":"reasoning.available","session_id":"sid-1","seq":9,"payload":{"text":"preview"}}"""
        )
        assertTrue(event is GatewayEvent.ToolProgress)
        val progress = event as GatewayEvent.ToolProgress
        assertEquals("reasoning.available", progress.type)
        assertEquals("preview", progress.payload["text"])
    }

    // =========================================================================
    // F007-S4: message.complete carries the host's final reasoning
    // =========================================================================

    @Test
    fun decodeMessageComplete_stringReasoning_preserved() {
        // F007-S4: _complete_turn_payload (prompt_turn.py:620-631) ships the
        // last reasoning inside message.complete payload.reasoning; the
        // adapter must preserve it instead of dropping it at the boundary.
        val event = adapter.parseEventForTest(
            """{"type":"message.complete","session_id":"sid-1","seq":11,"payload":{"text":"done","status":"complete","reasoning":"final chain of thought"}}"""
        )
        val complete = event as GatewayEvent.MessageComplete
        assertEquals("final chain of thought", complete.reasoning)
        assertEquals("done", complete.text)
    }

    @Test
    fun decodeMessageComplete_nonStringOrAbsentReasoning_nullNoCrash() {
        // F007-S4: non-string / absent reasoning decodes to null — no crash,
        // no fabricated text. Degenerate envelopes stay safe.
        val nonString = adapter.parseEventForTest(
            """{"type":"message.complete","session_id":"sid-1","seq":1,"payload":{"text":"done","reasoning":42}}"""
        )
        assertTrue(nonString is GatewayEvent.MessageComplete)
        assertNull((nonString as GatewayEvent.MessageComplete).reasoning)

        val absent = adapter.parseEventForTest(
            """{"type":"message.complete","session_id":"sid-1","seq":2,"payload":{"text":"done"}}"""
        )
        assertTrue(absent is GatewayEvent.MessageComplete)
        assertNull((absent as GatewayEvent.MessageComplete).reasoning)

        val badPayloadShape = adapter.parseEventForTest(
            """{"type":"message.complete","session_id":"sid-1","seq":3,"payload":"oops"}"""
        )
        assertTrue(badPayloadShape is GatewayEvent.MessageComplete)
        assertNull((badPayloadShape as GatewayEvent.MessageComplete).reasoning)
    }
}
