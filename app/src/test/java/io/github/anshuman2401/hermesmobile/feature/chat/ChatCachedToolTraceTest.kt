package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheReadResult
import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheRepository
import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheWriteResult
import io.github.anshuman2401.hermesmobile.data.cache.VaultConversationCacheRepository
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartition
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartitionResult
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.feature.chat.components.CACHED_TOOL_TRACE_SECTION_TITLE
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * PR-4 (plan CACHE-PR4 §9 T-13): the cached tool-trace timeline projection
 * end-to-end — exact-runtime admission with ZERO prompt/adapter calls,
 * hydration/cache-first/fallback projection with the byte-exact provenance
 * label, server-exact timeline (never re-authored), observed-order rows with
 * no fabrication and no same-name collapse, CON-01 trace retention across a
 * successful resume, loadSession-entry clearing (stale group never
 * survives), and CON-02 composer/session-field invariance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatCachedToolTraceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var cacheRepo: CountingCacheRepository
    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: CountingSessionRepository
    private val origin = "http://127.0.0.1:9714"
    private var fixedNow = 10_000_000_000_000L

    private val engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())

    private class CountingCacheRepository(
        private val delegate: ConversationCacheRepository
    ) : ConversationCacheRepository {
        var loadCalls = 0
        var recordCalls = 0
        var lastStoredId: String? = null
        var lastRuntimeId: String? = null
        var lastEvent: GatewayEvent? = null
        var lastObservedAt: Long? = null

        override suspend fun load(storedSessionId: String): ConversationCacheReadResult {
            loadCalls++
            return delegate.load(storedSessionId)
        }

        override suspend fun replaceRemoteSnapshot(
            storedSessionId: String,
            resume: SessionResumeResult,
            nowMillis: Long
        ): ConversationCacheWriteResult =
            delegate.replaceRemoteSnapshot(storedSessionId, resume, nowMillis)

        override suspend fun recordObservedToolEvent(
            storedSessionId: String,
            runtimeSessionId: String,
            event: GatewayEvent,
            observedAtMillis: Long
        ): ConversationCacheWriteResult {
            recordCalls++
            lastStoredId = storedSessionId
            lastRuntimeId = runtimeSessionId
            lastEvent = event
            lastObservedAt = observedAtMillis
            return delegate.recordObservedToolEvent(storedSessionId, runtimeSessionId, event, observedAtMillis)
        }
    }

    private class CountingSessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

        var resumeCalls = 0
        var fetchHistoryCalls = 0
        var resumeOutcome: Result<SessionResumeResult> =
            Result.failure(IllegalStateException("offline — must never be reached on the cache path"))

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-draft-1", "runtime-draft-1", isDraft = true))

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> {
            resumeCalls++
            return resumeOutcome
        }

        override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> {
            fetchHistoryCalls++
            return resumeOutcome
        }

        override suspend fun restoreSelectedSession(): SessionHandle? = null
        override suspend fun clearActiveSession() {}
        override suspend fun getActiveSession(): SessionHandle? = _activeSession.value
    }

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("cached_tool_trace_test")
        fixedNow = 10_000_000_000_000L
        vault = EncryptedVault(
            storageDir,
            engine,
            ioDispatcher = mainDispatcherRule.testDispatcher,
            wallClockMillis = { fixedNow }
        )
        cacheRepo = CountingCacheRepository(VaultConversationCacheRepository(vault))
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = CountingSessionRepository()
    }

    private fun vm() = ChatViewModel(
        sessionId = "sess-100",
        sessionRepository = fakeRepo,
        gatewayProtocolAdapter = fakeAdapter,
        pendingTurnLedger = PendingTurnLedger(vault),
        vaultStorage = vault,
        conversationCacheRepository = cacheRepo,
        // The record TTL is anchored to the fixture clock — a real wall clock
        // would make the cached record look expired on the very next read.
        clock = { fixedNow }
    )

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun seed() {
        vault.saveConnectionProfile(
            ConnectionProfile(localId = "conn-1", normalizedOrigin = origin, displayLabel = "Test Server")
        )
        vault.setActiveProfileId("conn-1")
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok"),
                canonicalOrigin = origin
            ),
            profileLocalId = "conn-1"
        )
    }

    private suspend fun partition(): CacheAccessPartition =
        (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition

    /** Seed the cached server snapshot rows through the real repository. */
    private suspend fun seedSnapshot(
        messages: List<String>,
        runtimeId: String = "runtime-from-cache"
    ): Long {
        val capturedAt = fixedNow
        val result = VaultConversationCacheRepository(vault).replaceRemoteSnapshot(
            storedSessionId = "sess-100",
            resume = SessionResumeResult(
                runtimeSessionId = runtimeId,
                durableSessionId = "sess-100",
                messageCount = messages.size,
                messages = messages.mapIndexed { i, m ->
                    SessionMessage(role = if (i % 2 == 0) "user" else "assistant", content = m)
                }
            ),
            nowMillis = capturedAt
        )
        assertTrue("seed write must succeed: $result", result is ConversationCacheWriteResult.Written)
        return capturedAt
    }

    /** Append an observed tool event straight to the delegate (seed side). */
    private suspend fun appendTrace(event: GatewayEvent): ConversationCacheWriteResult =
        VaultConversationCacheRepository(vault).recordObservedToolEvent(
            storedSessionId = "sess-100",
            runtimeSessionId = "runtime-from-cache",
            event = event,
            observedAtMillis = fixedNow + 1_000L
        )

    private fun cachedTrace(): List<CachedToolTraceEntry> =
        runBlocking {
            (VaultConversationCacheRepository(vault).load("sess-100") as ConversationCacheReadResult.Snapshot)
                .value.localTrace
                .let { ExecutionTracePolicy.buildCachedToolTraceEntryList(it) }
        }

    private fun startEvent(runtime: String, seq: Long, toolId: String, name: String) =
        GatewayEvent.ToolStart(
            sessionId = runtime, seq = seq, toolId = toolId, name = name,
            context = "synthetic context"
        )

    // ---------------------------------------------------------------------
    // T-13: exact-runtime admission. No write before ids exist, no write on
    // a runtime mismatch, one write on exact match — and ZERO prompt frames /
    // adapter calls from any of it.
    // ---------------------------------------------------------------------
    @Test
    fun exactRuntimeAdmission_zeroPromptAndAdapterCalls() = runTest {
        seed()
        seedSnapshot(listOf("cached question", "cached answer"))

        val viewModel = vm()

        // (1) No hydration yet: durable and runtime ids are both absent —
        // the observation is dropped and nothing reaches the store.
        fakeAdapter.eventFlow.tryEmit(startEvent("runtime-pre-hydration", 1L, "tool-pre", "tool_pre"))
        advanceUntilIdle()
        assertEquals("no ids ⇒ no write", 0, cacheRepo.recordCalls)

        // Hydration succeeds → runtime/durable ids resolved; hydration itself
        // never writes a trace.
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-live-1",
                durableSessionId = "sess-100",
                messageCount = 2,
                messages = listOf(
                    SessionMessage(role = "user", content = "live question"),
                    SessionMessage(role = "assistant", content = "live answer")
                )
            )
        )
        viewModel.loadSession()
        advanceUntilIdle()
        assertEquals("hydration makes no trace writes", 0, cacheRepo.recordCalls)
        val hydrated = viewModel.uiState.value
        assertEquals("runtime-live-1", hydrated.runtimeSessionId)
        assertEquals("sess-100", hydrated.durableSessionId)

        // (2) Runtime mismatch ⇒ exact-runtime gate drops it: no write.
        fakeAdapter.eventFlow.tryEmit(startEvent("runtime-WRONG", 2L, "tool-mismatch", "tool_mm"))
        advanceUntilIdle()
        assertEquals("mismatched runtime ⇒ no write", 0, cacheRepo.recordCalls)

        // (3) Exact match ⇒ exactly one write, bound to the stored id.
        fakeAdapter.eventFlow.tryEmit(startEvent("runtime-live-1", 3L, "tool-m1", "tool_m1"))
        advanceUntilIdle()
        assertEquals("exact match ⇒ one write", 1, cacheRepo.recordCalls)
        assertEquals("sess-100", cacheRepo.lastStoredId)
        assertEquals("runtime-live-1", cacheRepo.lastRuntimeId)
        assertNotNull("observation time recorded", cacheRepo.lastObservedAt)
        assertEquals(GatewayEvent.ToolStart::class, cacheRepo.lastEvent!!::class)

        // Zero prompt frames / adapter calls from trace writes (SEC-01/SEC-04).
        assertNull("no prompt submitted", fakeAdapter.lastSubmittedText)
        assertNull("no prompt session", fakeAdapter.lastSubmittedSessionId)
        assertTrue("no replay calls", fakeAdapter.replayCalls.isEmpty())
        assertTrue("no interrupts", fakeAdapter.interruptedSessions.isEmpty())

        // No fabricated prompt frames: the admitted ToolStart legitimately
        // adds ONE live ToolActivity row on top of the 2 server rows — no
        // prompt-like rows are ever minted (SEC-01/SEC-04).
        val state = viewModel.uiState.value
        assertEquals("timeline server-exact: $state", 3, state.timeline.size)
        assertEquals("live question", (state.timeline[0] as UserMessage).text)
        assertEquals("live answer", (state.timeline[1] as AssistantMessage).text)
        assertEquals("live tool row, never a prompt frame", "tool-m1", (state.timeline[2] as ToolActivity).toolId)
        // The trace projection from that write is now visible.
        assertEquals(1, state.cachedToolTrace.size)
        assertEquals("tool_m1", state.cachedToolTrace.single().name)
    }

    // ---------------------------------------------------------------------
    // T-13: hydration success projection — server-exact timeline, byte-exact
    // provenance, group absent (state byte-identical to baseline) when the
    // trace is empty.
    // ---------------------------------------------------------------------
    @Test
    fun hydrationSuccess_projectsTrace_provenanceByteExact_groupAbsentWhenEmpty() = runTest {
        seed()
        seedSnapshot(listOf("cached question", "cached answer"))
        val written = appendTrace(startEvent("runtime-from-cache", 1L, "tool-1", "tool_alpha"))
        assertTrue("seed trace: $written", written is ConversationCacheWriteResult.Written)

        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-live-2",
                durableSessionId = "sess-100",
                messageCount = 2,
                messages = listOf(
                    SessionMessage(role = "user", content = "live question"),
                    SessionMessage(role = "assistant", content = "live answer")
                )
            )
        )
        val viewModel = vm()
        viewModel.loadSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Projection present with observed fields only.
        assertEquals(1, state.cachedToolTrace.size)
        val entry = state.cachedToolTrace.single()
        assertEquals("tool_alpha", entry.name)
        assertEquals(CachedToolTraceStatus.RUNNING, entry.status)
        assertNull("duration never fabricated", entry.durationSeconds)
        assertTrue(entry.observedAtMillis > 0L)

        // Timeline stays SERVER-exact — the cached rows are never re-authored.
        assertEquals(2, state.timeline.size)
        assertEquals("live question", (state.timeline[0] as UserMessage).text)
        assertEquals("live answer", (state.timeline[1] as AssistantMessage).text)

        // Byte-exact provenance copy (single source: the ChatScreen constant).
        assertEquals(
            "Captured on this device; not confirmed by server history",
            CACHED_TOOL_TRACE_PROVENANCE
        )

        // The group renders into the display list, appended after the timeline.
        val withGroup = ExecutionTracePolicy.buildBlockDisplayEntries(
            state.timeline, state.trace, state.cachedToolTrace
        )
        assertTrue(
            "group appended after timeline",
            withGroup.isNotEmpty() && withGroup.last() is TraceDisplayEntry.CachedTrace
        )

        // Empty trace ⇒ no group, byte-identical to the baseline projection.
        val baseline = ExecutionTracePolicy.buildBlockDisplayEntries(state.timeline, state.trace)
        assertEquals(
            "empty trace must be byte-identical to baseline",
            baseline,
            ExecutionTracePolicy.buildBlockDisplayEntries(state.timeline, state.trace, emptyList())
        )
        assertTrue(
            "no cached group in baseline",
            baseline.none { it is TraceDisplayEntry.CachedTrace }
        )
    }

    // ---------------------------------------------------------------------
    // CON-01: a subsequent successful resume replaces snapshot rows but
    // carries the phone-captured trace forward (replace-on-resume does NOT
    // delete it).
    // ---------------------------------------------------------------------
    @Test
    fun successfulResume_retainsTrace_replacesRows() = runTest {
        seed()
        seedSnapshot(listOf("cached question", "cached answer"))
        val written = appendTrace(startEvent("runtime-from-cache", 1L, "tool-1", "tool_alpha"))
        assertTrue("seed trace: $written", written is ConversationCacheWriteResult.Written)

        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-live-3",
                durableSessionId = "sess-100",
                messageCount = 3,
                messages = listOf(
                    SessionMessage(role = "user", content = "resume question"),
                    SessionMessage(role = "assistant", content = "resume answer"),
                    SessionMessage(role = "user", content = "resume followup")
                )
            )
        )
        val viewModel = vm()
        viewModel.loadSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("rows replaced by the resume capture", 3, state.timeline.size)
        assertEquals("resume question", (state.timeline[0] as UserMessage).text)
        assertEquals("resume followup", (state.timeline[2] as UserMessage).text)
        assertEquals("trace projection still served", 1, state.cachedToolTrace.size)

        // And the store really retained it through replace (CON-01).
        val stored = VaultConversationCacheRepository(vault).load("sess-100")
        val snapshot = (stored as ConversationCacheReadResult.Snapshot).value
        assertEquals(3, snapshot.rows.size)
        assertEquals(1, snapshot.localTrace.size)
        assertEquals("tool_alpha", snapshot.localTrace.single().name)
    }

    // ---------------------------------------------------------------------
    // T-13 rendering: observed order (completion observed before start stays
    // first, never fabricated), same-name concurrency never collapses,
    // RUNNING wording is pinned, remote omission keeps timeline server-exact,
    // and CON-02: composer/runtime/blocked-reason are unaffected by the trace.
    // ---------------------------------------------------------------------
    @Test
    fun observedOrder_noCollapse_runningWording_serverOmission_con2Invariance() = runTest {
        seed()
        seedSnapshot(listOf("cached question", "cached answer"))

        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("offline — cache path"))

        // Baseline run: record exists with NO trace — capture CON-02 fields.
        val baselineVm = vm()
        baselineVm.loadSession()
        advanceUntilIdle()
        val baseline = baselineVm.uiState.value
        assertEquals("baseline trace empty", 0, baseline.cachedToolTrace.size)
        assertTrue("baseline serves cache", baseline.timeline.size == 2)

        // Now capture events — completion observed BEFORE any start, plus two
        // concurrent same-name tools under distinct toolIds.
        val completeFirst = appendTrace(
            GatewayEvent.ToolComplete(
                sessionId = "runtime-from-cache", seq = 1L, toolId = "tool-c1",
                name = "tool_alpha", durationSeconds = 2.5
            )
        )
        assertTrue("complete-first: $completeFirst", completeFirst is ConversationCacheWriteResult.Written)
        val startP1 = appendTrace(startEvent("runtime-from-cache", 2L, "tool-p1", "parallel_tool"))
        assertTrue("p1: $startP1", startP1 is ConversationCacheWriteResult.Written)
        val startP2 = appendTrace(startEvent("runtime-from-cache", 3L, "tool-p2", "parallel_tool"))
        assertTrue("p2: $startP2", startP2 is ConversationCacheWriteResult.Written)

        val viewModel = vm()
        viewModel.loadSession()
        advanceUntilIdle()
        val state = viewModel.uiState.value

        // Observed order preserved; completion-first never gains a fabricated start row.
        assertEquals(3, state.cachedToolTrace.size)
        val first = state.cachedToolTrace[0]
        assertEquals("tool_alpha", first.name)
        assertEquals(CachedToolTraceStatus.COMPLETE, first.status)
        assertEquals(2.5, first.durationSeconds!!, 0.0001)

        // Same-name concurrency: two tools, never collapsed.
        val parallel = state.cachedToolTrace.filter { it.name == "parallel_tool" }
        assertEquals("same-name tools never collapse", 2, parallel.size)
        assertEquals(2, parallel.map { it.id }.toSet().size)
        parallel.forEach { e -> assertEquals(CachedToolTraceStatus.RUNNING, e.status) }

        // RUNNING wording is "not captured", never a fabricated completion.
        assertEquals(
            "Completion not captured",
            ExecutionTracePolicy.cachedTraceStatusText(CachedToolTraceStatus.RUNNING, null)
        )

        // Remote omission: the timeline still shows ONLY the server rows —
        // no timeline tool rows are fabricated from the trace.
        assertEquals("timeline server-exact despite 3 trace events", 2, state.timeline.size)
        assertTrue(state.timeline.none { it is ToolActivity })

        // CON-02: trace state never alters composer/session fields.
        assertEquals(
            "isComposerReady unaffected by trace",
            baseline.isComposerReady, state.isComposerReady
        )
        assertEquals(
            "submitBlockedReason unaffected by trace",
            baseline.submitBlockedReason, state.submitBlockedReason
        )
        assertEquals(
            "runtimeSessionId unaffected by trace",
            baseline.runtimeSessionId, state.runtimeSessionId
        )
        assertNull("snapshot path never arms NO_RUNTIME", state.submitBlockedReason)
        assertFalse(state.isInitialLoading)
        assertNotNull(state.savedSnapshotCapturedAtMillis)
    }

    // ---------------------------------------------------------------------
    // loadSession entry clears the projection: a stale group never survives
    // into a session whose record no longer carries one.
    // ---------------------------------------------------------------------
    @Test
    fun loadSessionEntry_clearsStaleTraceGroup() = runTest {
        seed()
        seedSnapshot(listOf("cached question", "cached answer"))
        val written = appendTrace(startEvent("runtime-from-cache", 1L, "tool-1", "tool_alpha"))
        assertTrue("seed trace: $written", written is ConversationCacheWriteResult.Written)
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("offline — cache path"))

        val viewModel = vm()
        viewModel.loadSession()
        advanceUntilIdle()
        assertEquals("trace served from cache", 1, viewModel.uiState.value.cachedToolTrace.size)

        // The record disappears (rotation-equivalent loss) → next loadSession
        // must clear the stale group, not keep the previous projection.
        kotlinx.coroutines.runBlocking {
            vault.deleteConversationSnapshot(partition(), "sess-100")
        }
        viewModel.loadSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("stale group never survives loadSession entry", 0, state.cachedToolTrace.size)
        // Cache miss + offline resume never MINTS rows: the previously
        // served timeline stays untouched (no fabricated content).
        assertEquals("no fabricated rows on cache miss", 2, state.timeline.size)
        assertTrue(state.timeline.none { it is ToolActivity })
    }

    // ---------------------------------------------------------------------
    // Review F3: the card's section title must be the plan-frozen literal
    // (CACHE-PR4 §5.6 / §6 / ASSUMPTION-07) — byte-exact, single source: the
    // ExecutionTraceSurface constant the card renders.
    // ---------------------------------------------------------------------
    @Test
    fun sectionTitle_isPlanFrozenLiteral() {
        assertEquals(
            "Tool activity captured on this phone",
            CACHED_TOOL_TRACE_SECTION_TITLE
        )
    }
}
