package com.hermes.bot.feature.chat

import com.hermes.bot.data.cache.ConversationCacheReadResult
import com.hermes.bot.data.cache.ConversationCacheRepository
import com.hermes.bot.data.cache.ConversationCacheWriteResult
import com.hermes.bot.data.cache.VaultConversationCacheRepository
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.CacheAccessPartition
import com.hermes.bot.data.local.CacheAccessPartitionResult
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.domain.SessionHandle
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * PR-7 (plan TEST-01..TEST-04) — offline/reconnect gating at the chat edge
 * consumer, end-to-end against the vault cache:
 *
 *  (TEST-01) the collector's FIRST observation and every NON-connected status
 *   change are never an edge: zero reconnect loads, strip/draft/bytes intact;
 *   the first real OFFLINE->CONNECTED edge loads EXACTLY once (remote truth
 *   replaces the cached projection), and a later edge with no strip on screen
 *   never loads again.
 *  (TEST-02) construction on an already-CONNECTED adapter never fires an
 *   edge load; only the init load runs.
 *  (TEST-03) a failed replace NEVER drops the labelled strip: a fresh cache
 *   is re-served byte-identically without arming NO_RUNTIME (TEST-03a); a
 *   dead cache keeps the strip beside an honestly armed NO_RUNTIME
 *   (TEST-03b) — the one state where both consumer lanes' preconditions hold.
 *  (TEST-04) exactly one load per edge: single-flight while a load is in
 *   flight (04a), exactly ONE lane fires when both are eligible (04b), and
 *   neither lane ever loads for `new-session` (04c).
 *
 * No test may ever mint a prompt or replay frame or restore a cached runtime
 * id; the gate policy never authorizes trace/snapshot rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSnapshotReconnectTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var cacheRepo: ConversationCacheRepository
    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: CountingSessionRepository
    private val origin = "http://127.0.0.1:9747"

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

        /** PR-7 TEST-04a: suspends resumeSession until completed — the
         *  in-flight window during which a second edge must be dropped. */
        var resumeGate: CompletableDeferred<Unit>? = null

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-draft-1", "runtime-draft-1", isDraft = true))

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> {
            resumeCalls++
            resumeGate?.await()
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
        storageDir = tempFolder.newFolder("snapshot_reconnect_test")
        // Vault I/O must join the test scheduler — with the default real
        // Dispatchers.IO, runTest's advanceUntilIdle drains before reads/writes
        // finish and every cache-first assertion races the I/O.
        vault = EncryptedVault(
            storageDir,
            AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        cacheRepo = VaultConversationCacheRepository(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = CountingSessionRepository()
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun seed() {
        vault.saveConnectionProfile(ConnectionProfile("conn-1", origin, "Server"))
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

    private suspend fun seedSnapshot(
        sid: String = "sess-100",
        user: String = "Cached question",
        assistant: String = "Cached answer"
    ): Long {
        val capturedAt = System.currentTimeMillis()
        val result = cacheRepo.replaceRemoteSnapshot(
            storedSessionId = sid,
            resume = SessionResumeResult(
                runtimeSessionId = "runtime-stored",
                durableSessionId = sid,
                messageCount = 2,
                messages = listOf(
                    SessionMessage(role = "user", content = user),
                    SessionMessage(role = "assistant", content = assistant)
                )
            ),
            nowMillis = capturedAt
        )
        assertTrue("Seed write must succeed: $result", result is ConversationCacheWriteResult.Written)
        return capturedAt
    }

    private fun vm(sessionId: String = "sess-100") = ChatViewModel(
        sessionId = sessionId,
        sessionRepository = fakeRepo,
        gatewayProtocolAdapter = fakeAdapter,
        pendingTurnLedger = PendingTurnLedger(vault),
        vaultStorage = vault,
        conversationCacheRepository = cacheRepo
    )

    private fun contentFile(): File {
        val p = runBlockingPartition()
        val dir = File(
            File(File(storageDir, "cache"), sha256Hex(p.profileLocalId)),
            sha256Hex("${p.profileLocalId}:${p.originSha256}")
        )
        return dir.listFiles()!!.first { !it.name.startsWith("cache_access_") }
    }

    private fun runBlockingPartition(): CacheAccessPartition =
        kotlinx.coroutines.runBlocking { partition() }

    /** Deletes the PR-4 access-state file so subsequent reads fail closed
     *  (ConversationCacheReadResult.Unavailable) — the dead-cache shape. */
    private fun killCacheAccess() {
        val p = runBlockingPartition()
        val dir = File(
            File(File(storageDir, "cache"), sha256Hex(p.profileLocalId)),
            sha256Hex("${p.profileLocalId}:${p.originSha256}")
        )
        val stateFile = dir.listFiles()!!.first { it.name.startsWith("cache_access_") }
        assertTrue("access state file must exist", stateFile.delete())
    }

    private fun remoteResume(
        runtimeId: String,
        user: String = "Fresh question",
        assistant: String = "Fresh answer"
    ): Result<SessionResumeResult> = Result.success(
        SessionResumeResult(
            runtimeSessionId = runtimeId,
            durableSessionId = "sess-100",
            messageCount = 2,
            messages = listOf(
                SessionMessage(role = "user", content = user),
                SessionMessage(role = "assistant", content = assistant)
            )
        )
    )

    /** The PR-7 invariant on every edge: gating never mints frames. */
    private fun assertNoFrames(label: String) {
        assertNull("no prompt frame may be minted ($label)", fakeAdapter.lastSubmittedText)
        assertTrue("no replay frame may be minted ($label)", fakeAdapter.replayCalls.isEmpty())
        assertTrue("no interrupts ($label)", fakeAdapter.interruptedSessions.isEmpty())
    }

    // TEST-01: initial collector observation + every non-connected status are
    // never an edge; the first real edge loads exactly once; a later edge
    // with no strip never loads.
    @Test
    fun initialObservationAndNonConnectedEdges_neverTriggerAReconnectLoad() = runTest {
        seed()
        seedSnapshot()
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val viewModel = vm()
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertNotNull("offline construction serves the labelled snapshot", state.savedSnapshotCapturedAtMillis)
        assertEquals("no remote call before any edge", 0, fakeRepo.resumeCalls)
        assertEquals("cached projection served", 2, state.timeline.size)
        val stripBefore = requireNotNull(state.savedSnapshotCapturedAtMillis)

        viewModel.updateDraft("half-typed draft")
        advanceUntilIdle()
        val bytesBefore = contentFile().readBytes()

        for (status in listOf(
            ConnectionStatus.FAILED,
            ConnectionStatus.CONNECTING,
            ConnectionStatus.AUTHENTICATION_REQUIRED,
            ConnectionStatus.OFFLINE,
        )) {
            fakeAdapter.connectionStatus.value = status
            advanceUntilIdle()
            state = viewModel.uiState.value
            assertEquals("no reconnect load on $status", 0, fakeRepo.resumeCalls)
            assertEquals("strip survives $status", stripBefore, state.savedSnapshotCapturedAtMillis)
            assertEquals("draft survives $status", "half-typed draft", state.composerDraft)
            assertTrue(
                "snapshot bytes untouched by $status",
                contentFile().readBytes().contentEquals(bytesBefore)
            )
        }
        assertNoFrames("non-connected churn")

        // First real edge: previous emission OFFLINE -> CONNECTED.
        fakeRepo.resumeOutcome = remoteResume("runtime-100")
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals("exactly one load per edge", 1, fakeRepo.resumeCalls)
        assertNull("remote replace clears the strip", state.savedSnapshotCapturedAtMillis)
        assertEquals("runtime-100", state.runtimeSessionId)
        assertEquals("half-typed draft", state.composerDraft)

        // With no strip on screen a later OFFLINE->CONNECTED edge never loads
        // (remote truth must not be re-served from local cache).
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        advanceUntilIdle()
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        assertEquals("no load without a strip on screen", 1, fakeRepo.resumeCalls)
        assertNull(viewModel.uiState.value.savedSnapshotCapturedAtMillis)
        assertNoFrames("post-replace edges")
    }

    // TEST-01/02: construction on an already-CONNECTED adapter — the collector's
    // first observation (previous == null) is not an edge; only the init load runs.
    @Test
    fun constructionOnAlreadyConnectedAdapter_neverFiresAnEdgeLoad() = runTest {
        seed()
        seedSnapshot()
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        fakeRepo.resumeOutcome = remoteResume("runtime-500", user = "Live question", assistant = "Live answer")

        val viewModel = vm()
        advanceUntilIdle()
        assertEquals("init load only", 1, fakeRepo.resumeCalls)
        val state = viewModel.uiState.value
        assertNull("live path never presents the snapshot", state.savedSnapshotCapturedAtMillis)
        assertEquals("runtime-500", state.runtimeSessionId)

        advanceUntilIdle()
        assertEquals("first observation is not an edge", 1, fakeRepo.resumeCalls)
        assertNoFrames("already-connected construction")
    }

    // TEST-02: a successful reconnect replace — remote truth wins: runtime
    // restored, strip cleared, composer re-armed, draft byte-identical, the
    // stored snapshot replaced, no frames.
    @Test
    fun reconnectEdge_replaceOk_replacesCachedWithRemote_clearsStrip_reArmsComposer() = runTest {
        seed()
        val seedCapture = seedSnapshot()
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val viewModel = vm()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.savedSnapshotCapturedAtMillis)

        viewModel.updateDraft("half-typed draft")
        advanceUntilIdle()
        assertFalse(
            "composer not ready while the strip is shown",
            viewModel.uiState.value.isComposerReady
        )
        val bytesBefore = contentFile().readBytes()

        fakeRepo.resumeOutcome = remoteResume("runtime-200")
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("one load for the edge", 1, fakeRepo.resumeCalls)
        assertEquals("runtime-200", state.runtimeSessionId)
        assertNull("strip clears on the successful replace", state.savedSnapshotCapturedAtMillis)
        assertEquals("fresh remote rows", 2, state.timeline.size)
        assertEquals("Fresh question", (state.timeline[0] as UserMessage).text)
        assertFalse(
            "no cached row survives the replace",
            state.timeline.any { it is UserMessage && it.text == "Cached question" }
        )
        assertEquals("half-typed draft", state.composerDraft)
        assertTrue("composer re-arms on the restored runtime", state.isComposerReady)
        assertFalse(state.isInitialLoading)
        assertNull(state.submitBlockedReason)

        // The STORED snapshot is replaced too (remote reconnect data always
        // replaces cached; plan REQ-02).
        val read = cacheRepo.load("sess-100")
        assertTrue("read=$read", read is ConversationCacheReadResult.Snapshot)
        val snap = (read as ConversationCacheReadResult.Snapshot).value
        assertEquals("Fresh question", snap.rows.first().content)
        assertTrue("capturedAt refreshed", snap.capturedAtMillis >= seedCapture)
        assertTrue(
            "record bytes rewritten by the replace",
            !contentFile().readBytes().contentEquals(bytesBefore)
        )
        assertNoFrames("replace-ok edge")
    }

    // TEST-03a: replace FAILED over a FRESH cache — the PR-3 failure fallback
    // re-serves the labelled snapshot: the strip is never dropped, the cache
    // stays byte-identical, NO_RUNTIME is never armed, cached runtime id never
    // restored, zero frames.
    @Test
    fun reconnectEdge_replaceFailed_freshCache_retainsStrip_byteIdenticalCache_neverArmsNoRuntime() = runTest {
        seed()
        seedSnapshot()
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val viewModel = vm()
        advanceUntilIdle()
        val stripBefore = requireNotNull(viewModel.uiState.value.savedSnapshotCapturedAtMillis)

        viewModel.updateDraft("half-typed draft")
        advanceUntilIdle()
        val bytesBefore = contentFile().readBytes()
        val replayBefore = fakeAdapter.replayCalls.size

        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("gateway down"))
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("the reconnect load ran exactly once", 1, fakeRepo.resumeCalls)
        assertNotNull("strip must survive the failed replace", state.savedSnapshotCapturedAtMillis)
        assertEquals("strip keeps its capture time", stripBefore, state.savedSnapshotCapturedAtMillis)
        assertNull(
            "fresh-cache failure NEVER arms NO_RUNTIME (RISK-03)",
            state.submitBlockedReason
        )
        assertNull("cached runtime id is never restored", state.runtimeSessionId)
        assertFalse(state.isInitialLoading)
        assertEquals("rows remain the PR-3 cached projection", 2, state.timeline.size)
        assertEquals("Cached question", (state.timeline[0] as UserMessage).text)
        assertEquals("half-typed draft", state.composerDraft)
        assertTrue(
            "cache bytes byte-identical after the failed replace",
            contentFile().readBytes().contentEquals(bytesBefore)
        )
        assertEquals("no replay frames", replayBefore, fakeAdapter.replayCalls.size)
        assertNull(fakeAdapter.lastSubmittedText)
        assertTrue(fakeAdapter.interruptedSessions.isEmpty())
    }

    // TEST-03b: replace FAILED over a DEAD cache — the strip is NEVER dropped;
    // NO_RUNTIME is armed honestly beside it (the labelled-cached-plus-blocked
    // state), zero frames, zero restored runtime.
    @Test
    fun reconnectEdge_replaceFailed_deadCache_keepsStripAndArmsErrorTogether() = runTest {
        seed()
        seedSnapshot()
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val viewModel = vm()
        advanceUntilIdle()
        val stripBefore = requireNotNull(viewModel.uiState.value.savedSnapshotCapturedAtMillis)

        killCacheAccess()
        assertTrue(
            "cache is now dead",
            cacheRepo.load("sess-100") is ConversationCacheReadResult.Unavailable
        )
        viewModel.updateDraft("half-typed draft")
        advanceUntilIdle()

        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("gateway down"))
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("one load for the edge", 1, fakeRepo.resumeCalls)
        assertEquals(
            "the strip is never dropped for a dead cache",
            stripBefore,
            state.savedSnapshotCapturedAtMillis
        )
        assertEquals(
            "PR-7's lane arms NO_RUNTIME when there is nothing left to serve",
            SubmitBlockedReason.NO_RUNTIME,
            state.submitBlockedReason
        )
        assertNotNull("error surfaced", state.errorMessage)
        assertNull("no restored runtime", state.runtimeSessionId)
        assertFalse(state.isInitialLoading)
        assertEquals("cached rows still rendered under the strip", 2, state.timeline.size)
        assertEquals("half-typed draft", state.composerDraft)
        assertNoFrames("dead-cache edge")
    }

    // TEST-04a: single-flight — a second edge while the first load is still in
    // flight (resume gated) is dropped; exactly one resume total.
    @Test
    fun reconnectEdge_whileALoadIsInFlight_isSingleFlight() = runTest {
        seed()
        seedSnapshot()
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val viewModel = vm()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.savedSnapshotCapturedAtMillis)
        viewModel.updateDraft("half-typed draft")
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        fakeRepo.resumeGate = gate
        fakeRepo.resumeOutcome = remoteResume("runtime-300")

        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        assertEquals("first edge starts the load", 1, fakeRepo.resumeCalls)
        assertTrue("load is in flight", viewModel.uiState.value.isInitialLoading)

        // Flap back and edge again while the first load still runs — the edge
        // condition is met a second time, but single-flight must drop it.
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        advanceUntilIdle()
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        assertEquals("single-flight: no second load while one is running", 1, fakeRepo.resumeCalls)
        assertTrue(viewModel.uiState.value.isInitialLoading)

        gate.complete(Unit)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals("exactly one load across the whole flap", 1, fakeRepo.resumeCalls)
        assertEquals("runtime-300", state.runtimeSessionId)
        assertNull(state.savedSnapshotCapturedAtMillis)
        assertFalse(state.isInitialLoading)
        assertEquals("half-typed draft", state.composerDraft)
        assertNoFrames("single-flight flap")
    }

    // TEST-04b: dead-cache failure leaves BOTH lanes' preconditions true
    // (strip set + NO_RUNTIME). The next edge must be served by exactly ONE
    // lane — the pre-existing CHAT-T7 self-heal — never both: one load per
    // edge, total resumes == number of edges.
    @Test
    fun bothLanesPresent_exactlyOneLoadPerEdge_neverDoubleFires() = runTest {
        seed()
        seedSnapshot()
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val viewModel = vm()
        advanceUntilIdle()
        val stripBefore = requireNotNull(viewModel.uiState.value.savedSnapshotCapturedAtMillis)
        viewModel.updateDraft("half-typed draft")
        advanceUntilIdle()

        // Dead cache + failed replace: strip kept AND NO_RUNTIME armed.
        killCacheAccess()
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("gateway down"))

        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        var state = viewModel.uiState.value
        assertEquals("lane 2 (reconnect replace) fired first", 1, fakeRepo.resumeCalls)
        assertEquals("strip kept", stripBefore, state.savedSnapshotCapturedAtMillis)
        assertEquals("NO_RUNTIME armed beside it", SubmitBlockedReason.NO_RUNTIME, state.submitBlockedReason)

        // Second edge: now lane 1's precondition (NO_RUNTIME) also holds —
        // mutual exclusion must yield exactly one more load (lane 1).
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        advanceUntilIdle()
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(
            "exactly one load on the second edge — no double fire",
            2,
            fakeRepo.resumeCalls
        )
        assertEquals(SubmitBlockedReason.NO_RUNTIME, state.submitBlockedReason)
        assertNull(
            "lane 1's plain load cleared the strip (its contract)",
            state.savedSnapshotCapturedAtMillis
        )
        assertEquals("half-typed draft", state.composerDraft)
        assertNoFrames("two-lane edges")
    }

    // TEST-04c: `new-session` — neither lane may load: lane 1 is gated by the
    // stored-session check (even with NO_RUNTIME armed via the submit guard),
    // lane 2 by the strip/served-store guards (a new session never serves).
    @Test
    fun newSessionEdge_neverLoadsFromEitherLane() = runTest {
        seed()
        seedSnapshot()
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val viewModel = vm(sessionId = "new-session")
        advanceUntilIdle()

        // Block via the submit runtime guard — lane 1's precondition — so the
        // new-session check is the only thing that can hold it back.
        viewModel.updateDraft("hello there")
        viewModel.submitPrompt()
        advanceUntilIdle()
        assertEquals(
            "submit guard armed NO_RUNTIME",
            SubmitBlockedReason.NO_RUNTIME,
            viewModel.uiState.value.submitBlockedReason
        )
        assertEquals("new-session never resumes", 0, fakeRepo.resumeCalls)
        assertNull(
            "new-session never serves a snapshot",
            viewModel.uiState.value.savedSnapshotCapturedAtMillis
        )

        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        assertEquals("neither lane may load for new-session", 0, fakeRepo.resumeCalls)
        assertNull(viewModel.uiState.value.savedSnapshotCapturedAtMillis)
        assertNoFrames("new-session edge")
    }
}
