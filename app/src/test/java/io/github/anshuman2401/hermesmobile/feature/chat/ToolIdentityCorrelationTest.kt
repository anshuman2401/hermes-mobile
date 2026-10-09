package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayFixtures
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonArray
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BLD-006 S7 — M-04 tool identity rows, anchored to the certified fixture
 * `fixtures/v0.21.1/tool-lifecycle.json` (certified field shapes: tool_id, name,
 * context, args, duration_s, result, summary).
 *
 * Invariants under test:
 * - Correlation is ONLY by validated nonblank tool_id (no name/adjacency fallback).
 * - tool.generating is an independent "preparing" row, never attached to a keyed card.
 * - Uncertified progress variants are independent generic rows.
 * - tool.complete before tool.start yields ONE correct terminal card keyed by the ID.
 * - Two concurrent same-name tools with distinct IDs stay separate.
 * - Blank/malformed tool_id becomes a bounded generic row, never a keyed card.
 * - Unknown future event renders one bounded generic row and mutates no keyed card.
 *
 * Provenance note (controller default 2026-09-17): the complete-before-start and
 * blank-ID rows are SYNTHETIC scenarios the certified corpus lacks; they reuse the
 * certified field shapes from tool-lifecycle.json verbatim and contain zero real
 * host literals.
 */
class ToolIdentityCorrelationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: FakeIdentitySessionRepository

    class FakeIdentitySessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-draft-1", "runtime-draft-1", isDraft = true))

        override suspend fun resumeSession(storedSessionId: String): Result<io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult> =
            Result.success(
                io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult(
                    runtimeSessionId = "runtime-100",
                    durableSessionId = "sess-100",
                    messageCount = 0,
                    messages = emptyList()
                )
            )

        override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult> =
            Result.success(
                io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult(
                    runtimeSessionId = "runtime-snap",
                    durableSessionId = durableSessionId,
                    messageCount = 0,
                    messages = emptyList()
                )
            )

        override suspend fun restoreSelectedSession(): SessionHandle? = null
        override suspend fun clearActiveSession() {}
        override suspend fun getActiveSession(): SessionHandle? = null
    }

    @Before
    fun setUp() {
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = FakeIdentitySessionRepository()
    }

    private fun viewModel(): ChatViewModel = ChatViewModel(
        sessionId = "sess-100",
        sessionRepository = fakeRepo,
        gatewayProtocolAdapter = fakeAdapter,
        pendingTurnLedger = null
    )

    private fun toolLifecycleEvents(): List<GatewayEvent> {
        val json = FakeGatewayFixtures.loadFixture("tool-lifecycle.json")
        val parsed = GatewayJson.parse(json) as JsonObject
        val events = parsed["events"] as JsonArray
        return events.list.map { eventObj ->
            val obj = eventObj as JsonObject
            val params = obj["params"] as JsonObject
            val type = params["type"]?.asStringOrNull()!!
            val payload = params["payload"] as JsonObject
            // The certified fixture's runtime sid constant (TEST-RUNTIME-SID-1) is
            // remapped to the VM-under-test's runtime id so the S1 runtime-ID-only
            // admission guard admits them; payload shapes remain certified verbatim.
            val sessionId = "runtime-100"
            val seq = params["seq"]?.asLongOrNull()
            when (type) {
                "tool.generating" -> GatewayEvent.ToolGenerating(
                    sessionId = sessionId,
                    seq = seq,
                    name = payload["name"]?.asStringOrNull() ?: ""
                )
                "tool.start" -> GatewayEvent.ToolStart(
                    sessionId = sessionId,
                    seq = seq,
                    toolId = payload["tool_id"]?.asStringOrNull() ?: "",
                    name = payload["name"]?.asStringOrNull() ?: "",
                    context = payload["context"]?.asStringOrNull(),
                    args = payload["args"]?.toAny()?.let { it as? Map<String, Any?> } ?: emptyMap()
                )
                "tool.complete" -> GatewayEvent.ToolComplete(
                    sessionId = sessionId,
                    seq = seq,
                    toolId = payload["tool_id"]?.asStringOrNull() ?: "",
                    name = payload["name"]?.asStringOrNull() ?: "",
                    durationSeconds = payload["duration_s"]?.asDoubleOrNull(),
                    result = payload["result"]?.toAny(),
                    summary = payload["summary"]?.asStringOrNull()
                )
                "reasoning.available" -> GatewayEvent.ToolProgress(
                    sessionId = sessionId,
                    seq = seq,
                    type = type,
                    payload = payload.toAny()?.let { it as? Map<String, Any?> } ?: emptyMap()
                )
                else -> GatewayEvent.Unknown(
                    type = type,
                    sessionId = sessionId,
                    seq = seq,
                    payload = payload.toAny()?.let { it as? Map<String, Any?> } ?: emptyMap()
                )
            }
        }
    }

    private suspend fun emit(vararg events: GatewayEvent) {
        events.forEach { fakeAdapter.eventFlow.emit(it) }
    }

    private fun toolCards(viewModel: ChatViewModel): List<ToolActivity> =
        viewModel.uiState.value.timeline.filterIsInstance<ToolActivity>()

    private fun genericRows(viewModel: ChatViewModel): List<GenericGatewayActivity> =
        viewModel.uiState.value.timeline.filterIsInstance<GenericGatewayActivity>()

    // ------------------------------------------------------------------
    // Certified fixture sequence (tool-lifecycle.json): generating -> start
    // -> reasoning.available -> complete
    // ------------------------------------------------------------------

    @Test
    fun certifiedLifecycle_preparingStaysIndependent_keyedCardTerminal() = runTest {
        val vm = viewModel()
        emit(*toolLifecycleEvents().toTypedArray())

        // tool.generating row stays independent PREPARING, toolId null.
        val preparing = toolCards(vm).first { it.status == ToolStatus.PREPARING }
        assertEquals("terminal", preparing.name)
        assertNull(preparing.toolId)

        // Keyed card correlated only by tool_id, terminal with certified fields.
        // (result takes precedence over summary in the certified payload shape.)
        val keyed = toolCards(vm).first { it.toolId == "call_test_tool_001" }
        assertEquals(ToolStatus.COMPLETED, keyed.status)
        assertEquals("terminal", keyed.name)
        assertEquals("test\n", keyed.resultSummary)
        assertEquals(0.045, keyed.durationSeconds!!, 0.001)

        // reasoning.available was an independent generic row, not attached to the card.
        val reasoning = genericRows(vm).single()
        assertEquals("tool.progress.reasoning.available", reasoning.eventType)
        assertFalse(reasoning.summary.contains("<redacted-reasoning-preview>"))
        // The sanitized certified payload passes through as inert preview text.
        assertTrue(reasoning.payloadPreview!!.contains("redacted-reasoning-preview"))
    }

    @Test
    fun generatingOnly_preparingRowNeverUpgraded() = runTest {
        val vm = viewModel()
        val generating = toolLifecycleEvents().first { it is GatewayEvent.ToolGenerating }
        emit(generating)

        val cards = toolCards(vm)
        assertEquals(1, cards.size)
        assertEquals(ToolStatus.PREPARING, cards.single().status)
        assertNull(cards.single().toolId)
        assertEquals("terminal", cards.single().name)
        assertEquals(0, genericRows(vm).size)
    }

    @Test
    fun startOnly_keyedRunningCard() = runTest {
        val vm = viewModel()
        emit(toolLifecycleEvents().first { it is GatewayEvent.ToolStart })

        val cards = toolCards(vm)
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals("call_test_tool_001", card.toolId)
        assertEquals(ToolStatus.RUNNING, card.status)
        assertEquals("echo test", card.context)
        assertEquals("terminal", card.name)
        assertEquals(0, genericRows(vm).size)
    }

    @Test
    fun completeBeforeStart_oneCorrectTerminalCard() = runTest {
        val vm = viewModel()
        // SYNTHETIC scenario (certified field shapes): completion precedes start.
        emit(
            GatewayEvent.ToolComplete(
                sessionId = "runtime-100",
                seq = 13L,
                toolId = "call_test_tool_001",
                name = "terminal",
                durationSeconds = 0.045,
                result = "test\n",
                summary = "Ran `echo test`"
            )
        )
        // Certified tool.start for the same ID arrives afterwards.
        emit(toolLifecycleEvents().first { it is GatewayEvent.ToolStart })

        val cards = toolCards(vm)
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals("call_test_tool_001", card.toolId)
        // The late start must not resurrect a completed terminal card.
        assertEquals(ToolStatus.COMPLETED, card.status)
        // result takes precedence over summary in the certified payload shape.
        assertEquals("test\n", card.resultSummary)
        assertEquals(0.045, card.durationSeconds!!, 0.001)
    }

    @Test
    fun completion_sealsRunningCard_andPreservesItsContext() = runTest {
        // Polish PR D+E (P-5): finalizeToolComplete copies the existing RUNNING
        // card, so the start's bounded context must survive the seal — the
        // renderer now shows context on COMPLETED rows too. If the completion
        // ever rebuilt the card instead of copying it, the context would drop
        // exactly where this test pins it.
        val vm = viewModel()
        val start = toolLifecycleEvents().first { it is GatewayEvent.ToolStart } as GatewayEvent.ToolStart
        emit(start)
        emit(
            GatewayEvent.ToolComplete(
                sessionId = "runtime-100",
                seq = (start.seq ?: 0L) + 1L,
                toolId = start.toolId,
                name = start.name,
                durationSeconds = 0.045,
                result = "test\n",
                summary = "Ran `echo test`"
            )
        )

        val cards = toolCards(vm)
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(ToolStatus.COMPLETED, card.status)
        assertEquals(start.context, card.context)
    }


    @Test
    fun twoConcurrentSameName_distinctIds_staySeparate() = runTest {
        val vm = viewModel()
        val base = toolLifecycleEvents().first { it is GatewayEvent.ToolStart } as GatewayEvent.ToolStart
        // SYNTHETIC scenario (certified field shapes): two same-name tools, distinct IDs.
        emit(
            base.copy(seq = 20L, toolId = "call_test_tool_001"),
            base.copy(seq = 21L, toolId = "call_test_tool_002"),
            base.copy(seq = 22L, toolId = "call_test_tool_001", context = "second invocation")
        )

        val cards = toolCards(vm)
        assertEquals(2, cards.size)
        assertEquals(
            setOf("call_test_tool_001", "call_test_tool_002"),
            cards.map { it.toolId }.toSet()
        )
        // The re-start updated only its lawful keyed card.
        val first = cards.first { it.toolId == "call_test_tool_001" }
        assertEquals("second invocation", first.context)
        val second = cards.first { it.toolId == "call_test_tool_002" }
        assertEquals("echo test", second.context)
    }

    @Test
    fun sameNameDifferentId_completeDoesNotGlueUnrelatedCards() = runTest {
        val vm = viewModel()
        val start = toolLifecycleEvents().first { it is GatewayEvent.ToolStart } as GatewayEvent.ToolStart
        emit(start.copy(seq = 30L, toolId = "call_test_tool_001"))
        // SYNTHETIC scenario (certified field shapes): completion for a DIFFERENT
        // tool with the SAME name must not attach to the running card.
        emit(
            GatewayEvent.ToolComplete(
                sessionId = "runtime-100",
                seq = 31L,
                toolId = "call_other_tool_999",
                name = start.name,
                durationSeconds = 0.1,
                result = "other result"
            )
        )

        val cards = toolCards(vm)
        assertEquals(2, cards.size)
        val running = cards.first { it.toolId == "call_test_tool_001" }
        assertEquals(ToolStatus.RUNNING, running.status)
        assertNull(running.resultSummary)
        val other = cards.first { it.toolId == "call_other_tool_999" }
        assertEquals(ToolStatus.COMPLETED, other.status)
    }

    @Test
    fun blankToolId_startBecomesBoundedGenericRow_neverKeyedCard() = runTest {
        val vm = viewModel()
        // SYNTHETIC scenario (certified field shapes): malformed blank tool_id.
        emit(
            GatewayEvent.ToolStart(
                sessionId = "runtime-100",
                seq = 40L,
                toolId = "",
                name = "terminal"
            )
        )

        assertEquals(0, toolCards(vm).size)
        val row = genericRows(vm).single()
        assertEquals("tool.start", row.eventType)
        assertTrue(row.summary.contains("terminal"))
    }

    @Test
    fun whitespaceToolId_completeBecomesBoundedGenericRow_neverKeyedCard() = runTest {
        val vm = viewModel()
        emit(
            GatewayEvent.ToolComplete(
                sessionId = "runtime-100",
                seq = 41L,
                toolId = "   ",
                name = "terminal",
                result = "test\n"
            )
        )

        assertEquals(0, toolCards(vm).size)
        assertEquals(1, genericRows(vm).size)
        assertEquals("tool.complete", genericRows(vm).single().eventType)
    }

    @Test
    fun uncertifiedProgressVariants_independentGenericRows() = runTest {
        val vm = viewModel()
        emit(
            GatewayEvent.ToolProgress(
                sessionId = "runtime-100",
                seq = 50L,
                type = "tool.progress",
                payload = mapOf("step" to 1)
            ),
            GatewayEvent.ToolProgress(
                sessionId = "runtime-100",
                seq = 51L,
                type = "moa.progress",
                payload = mapOf("stage" to "mix")
            ),
            GatewayEvent.ToolProgress(
                sessionId = "runtime-100",
                seq = 52L,
                type = "subagent.progress",
                payload = mapOf("agent" to "a1")
            )
        )

        assertEquals(0, toolCards(vm).size)
        assertEquals(3, genericRows(vm).size)
        assertEquals(
            setOf("tool.progress.tool.progress", "tool.progress.moa.progress", "tool.progress.subagent.progress"),
            genericRows(vm).map { it.eventType }.toSet()
        )
    }

    @Test
    fun unknownFutureEvent_oneBoundedGenericRow_noKeyedCardMutation() = runTest {
        val vm = viewModel()
        val start = toolLifecycleEvents().first { it is GatewayEvent.ToolStart } as GatewayEvent.ToolStart
        emit(start.copy(seq = 60L))
        val before = toolCards(vm).single()

        emit(
            GatewayEvent.Unknown(
                type = "agent.custom_future_notice",
                sessionId = "runtime-100",
                seq = 61L,
                payload = mapOf("details" to "non-critical server alert")
            )
        )

        // Exactly one new bounded generic row.
        assertEquals(1, genericRows(vm).size)
        assertEquals("agent.custom_future_notice", genericRows(vm).single().eventType)

        // The keyed card was not mutated by the unknown event.
        val after = toolCards(vm).single()
        assertEquals(before, after)
    }

    private fun assertNull(actual: String?) {
        org.junit.Assert.assertNull(actual)
    }
}
