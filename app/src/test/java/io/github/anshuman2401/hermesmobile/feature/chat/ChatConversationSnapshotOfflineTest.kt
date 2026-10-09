package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheReadResult
import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheRepository
import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheWriteResult
import io.github.anshuman2401.hermesmobile.data.cache.VaultConversationCacheRepository
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
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
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
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
 * Charter T-10 (+ T-01/T-02/T-05 conversation implications), plan §9 row (e):
 * the offline conversation snapshot end-to-end — cache-first gate with ZERO
 * adapter calls, replace semantics on authoritative resume, retention of a
 * fresh cache on failure, rotation/credential/workspace isolation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatConversationSnapshotOfflineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var cacheRepo: ConversationCacheRepository
    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: CountingSessionRepository
    private val origin = "http://127.0.0.1:9711"

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
        storageDir = tempFolder.newFolder("snapshot_offline_test")
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

    private fun vm(cacheRepository: ConversationCacheRepository? = cacheRepo) = ChatViewModel(
        sessionId = "sess-100",
        sessionRepository = fakeRepo,
        gatewayProtocolAdapter = fakeAdapter,
        pendingTurnLedger = PendingTurnLedger(vault),
        vaultStorage = vault,
        conversationCacheRepository = cacheRepository
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

    // Plan §6 title chain (repair finding 3): the OFFLINE serve path falls
    // back state title -> ACTIVE-HANDLE title -> friendly label, never the
    // durable id — the handle source must not be skipped on the snapshot path.
    @Test
    fun offlineServe_titleFallsBackToActiveHandleTitle() = runTest {
        seed()
        seedSnapshot()
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("must not resume offline"))
        fakeRepo._activeSession.value =
            SessionHandle(storedSessionId = "sess-100", runtimeSessionId = null, title = "Handle Title Sentinel")

        val viewModel = vm()
        viewModel.loadSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("timeline serves from cache", 2, state.timeline.size)
        assertEquals(
            "§6 chain: no real state title => active-handle title, got '${state.sessionTitle}'",
            "Handle Title Sentinel",
            state.sessionTitle
        )
    }

    // (a) T-10: offline gate serves the snapshot with ZERO adapter/prompt calls.
    @Test
    fun offlineGateway_servesSnapshot_withZeroAdapterCalls_runtimeIdNull_composerDisabled() = runTest {
        seed()
        val capturedAt = seedSnapshot()
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("must not resume offline"))

        val viewModel = vm()
        viewModel.loadSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value

        // Zero-call proof (SEC-01/SEC-04).
        assertEquals("repo resume must never run", 0, fakeRepo.resumeCalls)
        assertEquals("repo history fetch must never run", 0, fakeRepo.fetchHistoryCalls)
        assertTrue("no replay calls", fakeAdapter.replayCalls.isEmpty())
        assertNull("no prompt frame", fakeAdapter.lastSubmittedText)
        assertTrue("no interrupts", fakeAdapter.interruptedSessions.isEmpty())

        // Runtime/composer/strip invariants (REQ-09, RISK-03).
        assertNull("cached runtime id is never restored", state.runtimeSessionId)
        assertFalse("composer disabled offline", state.isComposerReady)
        assertNotNull("strip flag set; state=$state", state.savedSnapshotCapturedAtMillis)
        assertEquals(capturedAt, state.savedSnapshotCapturedAtMillis)
        assertNull(
            "snapshot path must NEVER arm NO_RUNTIME (RISK-03/OQ-03); state=$state",
            state.submitBlockedReason
        )
        assertFalse(state.isInitialLoading)

        // Rows rendered via the same history mapper (stable hist- ids).
        assertEquals("timeline=$state", 2, state.timeline.size)
        assertEquals("hist-user-0", state.timeline[0].id)
        assertEquals("hist-asst-1", state.timeline[1].id)
        assertTrue(state.timeline[0] is UserMessage)
        assertEquals("Cached question", (state.timeline[0] as UserMessage).text)
        // Title never the durable id (T-10 proof).
        assertNotNull(state.sessionTitle)
        assertNotEqualsImpl("sess-100", state.sessionTitle)
    }

    // (b) Successful resume REPLACES the snapshot — no merge — and refreshes capture.
    @Test
    fun successfulResume_replacesSnapshotAtomically_clearsStrip_restoresRuntimeId() = runTest {
        seed()
        val firstCapture = seedSnapshot()
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE

        val viewModel = vm()
        viewModel.loadSession()
        advanceUntilIdle()
        assertEquals(
            "first offline load must serve the cached timeline; state=${viewModel.uiState.value}",
            2,
            viewModel.uiState.value.timeline.size
        )

        // Hermes authoritative: reconnect with DIFFERENT history.
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-200",
                durableSessionId = "sess-100",
                messageCount = 2,
                messages = listOf(
                    SessionMessage(role = "user", content = "Fresh question"),
                    SessionMessage(role = "assistant", content = "Fresh answer")
                )
            )
        )
        viewModel.loadSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.timeline.size)
        assertEquals("Fresh question", (state.timeline[0] as UserMessage).text)
        assertFalse("no merged cached rows", state.timeline.any { it is UserMessage && it.text == "Cached question" })
        assertEquals("runtime-200", state.runtimeSessionId)
        assertNull("strip clears on live resume", state.savedSnapshotCapturedAtMillis)

        // Stored record replaced too (revision/capturedAt refreshed, rows = fresh).
        val read = cacheRepo.load("sess-100")
        assertTrue(read is ConversationCacheReadResult.Snapshot)
        val snap = (read as ConversationCacheReadResult.Snapshot).value
        assertEquals("Fresh question", snap.rows.first().content)
        assertTrue("capturedAt refreshed", snap.capturedAtMillis >= firstCapture)
    }

    // (c) Failed CONNECTED resume retains the fresh labelled cache byte-identically.
    @Test
    fun failedResume_retainsFreshLabelledCache_byteIdentical() = runTest {
        seed()
        seedSnapshot()
        val bytesBefore = contentFile().readBytes()
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE

        val viewModel = vm()
        viewModel.loadSession()
        advanceUntilIdle()

        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        fakeRepo.resumeOutcome = Result.failure(java.io.IOException("upstream down"))
        viewModel.loadSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // PR-7 (REQ-01): resumeCalls == 2 — this test's own OFFLINE→CONNECTED
        // step (line 307) is now a real reconnect edge, so PR-7's labelled-
        // strip consumer performs its sanctioned reconnect-replace (exactly
        // once for the edge) in ADDITION to the explicit loadSession() below.
        // PR-3's contract under test is unchanged: the failed resume retains
        // the fresh labelled cache byte-identically, with the strip set and
        // NO_RUNTIME never armed (asserts below).
        assertEquals(2, fakeRepo.resumeCalls)
        // Fallback serves the labelled cache — never NO_RUNTIME, never empty.
        assertNotNull("strip set; state=$state", state.savedSnapshotCapturedAtMillis)
        assertNull("failed resume must not arm NO_RUNTIME when cache is fresh", state.submitBlockedReason)
        assertEquals(2, state.timeline.size)
        assertEquals("Cached question", (state.timeline[0] as UserMessage).text)
        assertNull(state.runtimeSessionId)

        val bytesAfter = contentFile().readBytes()
        assertTrue("cache bytes unchanged", bytesBefore.contentEquals(bytesAfter))
    }

    // (d) T-01: sign-out/rotation ⇒ load Unavailable and old bytes unreachable.
    @Test
    fun rotationOldBytesUnreachable_stateAbsentFailsClosed() = kotlinx.coroutines.runBlocking {
        seed()
        seedSnapshot()

        // Access state removed (sign-out) ⇒ fail closed, distinct reason.
        val stateFile = File(
            File(File(storageDir, "cache"), sha256Hex("conn-1")),
            sha256Hex("conn-1:${sha256Hex(origin)}")
        ).listFiles()!!.first { it.name.startsWith("cache_access_") }
        assertTrue(stateFile.delete())
        assertTrue(
            "Sign-out must fail closed",
            cacheRepo.load("sess-100") is ConversationCacheReadResult.Unavailable
        )

        // Origin rotation ⇒ old subtree purged; old bytes unreachable.
        seed()
        val oldFile = contentFile()
        vault.saveConnectionProfile(ConnectionProfile("conn-1", "http://127.0.0.1:9999", "Server"))
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok2"),
                canonicalOrigin = "http://127.0.0.1:9999"
            ),
            profileLocalId = "conn-1"
        )
        assertFalse("Old-origin bytes purged by layout", oldFile.exists())
        assertTrue(
            "Old snapshot unreachable after rotation",
            cacheRepo.load("sess-100") is ConversationCacheReadResult.Miss
        )
    }

    // (e) T-02: credential replacement ⇒ old-epoch record discarded (AEAD/identity fail).
    @Test
    fun credentialReplacement_oldEpochRecordDiscarded() = kotlinx.coroutines.runBlocking {
        seed()
        val before = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        seedSnapshot()

        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "rotated-token"),
                canonicalOrigin = origin
            ),
            profileLocalId = "conn-1"
        )
        val after = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        assertTrue("Credential replacement rotates the cache epoch", after.cacheEpoch != before.cacheEpoch)
        assertTrue(
            "Old-epoch record must not resolve",
            cacheRepo.load("sess-100") is ConversationCacheReadResult.Miss
        )
    }

    // (f) T-05: same durable id across workspaces ⇒ distinct scopes, no unscoped fallback,
    // selection never mutated by a load.
    @Test
    fun workspaceScopeSwitch_servesOnlyResolvedScope_noUnscopedFallback_selectionUnchanged() = kotlinx.coroutines.runBlocking {
        seed()
        // Workspace A.
        vault.saveSelectedContextProfile("ws-a")
        seedSnapshot(user = "Question in A", assistant = "Answer in A")
        val inA = cacheRepo.load("sess-100")
        assertTrue("Workspace A serves its own record: $inA", inA is ConversationCacheReadResult.Snapshot)

        // Workspace B: same durable id, different partition ⇒ no fallback.
        vault.saveSelectedContextProfile("ws-b")
        val inB = cacheRepo.load("sess-100")
        assertTrue(
            "No unscoped fallback across workspaces: $inB",
            inB is ConversationCacheReadResult.Miss
        )
        // Loading must never mutate the selection (no self-heal).
        assertEquals("ws-b", vault.getSelectedContextProfile())

        // B writes its OWN record for the same sid; A stays unreachable from B.
        seedSnapshot(user = "Question in B", assistant = "Answer in B")
        val bSnap = cacheRepo.load("sess-100")
        assertTrue(bSnap is ConversationCacheReadResult.Snapshot)
        assertEquals("Question in B", (bSnap as ConversationCacheReadResult.Snapshot).value.rows.first().content)

        vault.saveSelectedContextProfile("ws-a")
        val backToA = cacheRepo.load("sess-100")
        assertTrue(backToA is ConversationCacheReadResult.Snapshot)
        assertEquals("Question in A", (backToA as ConversationCacheReadResult.Snapshot).value.rows.first().content)
        assertEquals("ws-a", vault.getSelectedContextProfile())
    }

    private fun assertNotEqualsImpl(unexpected: String, actual: String?) {
        assertTrue("Title must never be the durable id, got '$actual'", actual != unexpected)
    }

    // CHAT-018 PR-A: snapshot roundtrip is notes-free (documented live-only
    // persistence limitation, plan §A1) — the snapshot schema is
    // CachedRemoteHistoryRow(role, content, …) with no note row type, and the
    // serve path reuses the SAME history mapper as live hydration, so no
    // AgentNoteEntry can ever surface from a snapshot. Assert the contract at
    // the cache seam: a resume capture (replaceRemoteSnapshot over a resume
    // whose messages map 1:1 to rows) plus the VM's offline serve produce a
    // notes-free timeline with the reply retained.
    @Test
    fun snapshotRoundtrip_dropsAgentNoteEntry_replyRetained() = runTest {
        seed()
        seedSnapshot(user = "q", assistant = "kept")
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("must not resume offline"))

        val viewModel = vm()
        viewModel.loadSession()
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        // The note row type simply does not exist in the snapshot schema.
        assertTrue(
            "snapshot rows must never surface as AgentNoteEntry: $timeline",
            timeline.filterIsInstance<AgentNoteEntry>().isEmpty()
        )
        assertEquals(
            "user + reply rows survive hydration",
            listOf("hist-user-0", "hist-asst-1"),
            timeline.map { it.id }
        )
        assertEquals("kept", (timeline[1] as AssistantMessage).text)
    }
}
