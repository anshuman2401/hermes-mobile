package com.hermes.bot.data.session

import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.AttachImageResult
import com.hermes.bot.data.gateway.AttachFileResult
import com.hermes.bot.data.gateway.DetachImageResult
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.ReplayEventsResult
import com.hermes.bot.data.gateway.SessionCreateResult
import com.hermes.bot.data.gateway.SessionListResult
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.CacheAccessPartition
import com.hermes.bot.data.local.CacheAccessPartitionResult
import com.hermes.bot.data.local.CacheReadResult
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.local.SessionSelection
import com.hermes.bot.data.local.SessionSummaryCache
import com.hermes.bot.domain.LocalCacheException
import com.hermes.bot.domain.SessionNotFoundException
import kotlinx.coroutines.runBlocking
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

class SessionRepositoryTest {

    @Test
    fun documentAttachment_encodesMimeAndBytesAsDataUrl() = runBlocking {
        fakeAdapter.attachFileResult = GatewayResult.Success(
            AttachFileResult("report.pdf", "<path>", "<ref-path>", "<ref-text>", true)
        )

        val result = repository.attachFile("<runtime>", byteArrayOf(97), "application/pdf", "report.pdf")

        assertEquals("<ref-text>", (result as GatewayResult.Success).value.refText)
        assertEquals(
            FakeGatewayProtocolAdapter.AttachFileCall("<runtime>", "data:application/pdf;base64,YQ==", "report.pdf"),
            fakeAdapter.attachFileCalls.single()
        )
    }

    @Test
    fun imageAttachment_encodesBytesAndForwardsDetachThroughAdapter() = runBlocking {
        fakeAdapter.attachImageResult = GatewayResult.Success(AttachImageResult("<image-path>", 1, "<image-text>", 1))
        fakeAdapter.detachImageResult = GatewayResult.Success(DetachImageResult(true, 0))

        val attached = repository.attachImage("<runtime>", byteArrayOf(97), "photo.png", "png")
        assertEquals("<image-path>", (attached as GatewayResult.Success).value.path)
        assertEquals(1, fakeAdapter.attachImageCalls.size)
        assertEquals("<runtime>", fakeAdapter.attachImageCalls.single().sessionId)
        assertEquals("YQ==", fakeAdapter.attachImageCalls.single().contentBase64)
        assertEquals("photo.png", fakeAdapter.attachImageCalls.single().filename)
        assertEquals("png", fakeAdapter.attachImageCalls.single().ext)

        val detached = repository.detachImage("<runtime>", "<image-path>")
        assertTrue((detached as GatewayResult.Success).value.detached)
        assertEquals(listOf("<runtime>" to "<image-path>"), fakeAdapter.detachImageCalls)
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var repository: DefaultSessionRepository

    @Before
    fun setUp() = runBlocking {
        storageDir = tempFolder.newFolder("session_repo_test")
        vault = EncryptedVault(storageDir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        fakeAdapter = FakeGatewayProtocolAdapter()
        repository = DefaultSessionRepository(fakeAdapter, vault)

        // PR-1: session summary reads/writes are partition-gated on an active
        // profile with a matching credential and rotation state.
        vault.saveConnectionProfile(
            ConnectionProfile(
                localId = "conn-1",
                normalizedOrigin = "http://127.0.0.1:9119",
                displayLabel = "Test Server"
            )
        )
        vault.setActiveProfileId("conn-1")
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok"),
                canonicalOrigin = "http://127.0.0.1:9119"
            ),
            profileLocalId = "conn-1"
        )
    }

    private suspend fun partition(): CacheAccessPartition =
        (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition

    private suspend fun readCache(): SessionSummaryCache? =
        (vault.readSessionSummaryCache(partition()) as? CacheReadResult.Available)?.value

    @Test fun startupCacheReadMakesZeroNetworkCallsAndLabelsEligibleRowsOffline() = runBlocking {
        val rows = listOf(SessionSummary("synthetic-cache", "Saved", "", 100.0, 1, "android"))
        vault.saveSessionSummaryCache(SessionSummaryCache(rows, profileLocalId = "conn-1"), partition())
        val outcome = repository.loadCachedSessions().getOrThrow()!!
        assertTrue(outcome.isOfflineCache)
        assertEquals(rows, outcome.sessions)
        assertEquals(0, fakeAdapter.listSessionsCallCount)
    }

    @Test fun startupCacheAbsenceAndExpiredRecordsAreNotServerEmptyLists() = runBlocking {
        assertNull(repository.loadCachedSessions().getOrThrow())
        vault.saveSessionSummaryCache(SessionSummaryCache(emptyList(),
            syncTimeEpochSeconds = System.currentTimeMillis() / 1000 - SessionSummaryCache.MAX_RETENTION_SECONDS - 10,
            profileLocalId = "conn-1"), partition())
        assertNull(repository.loadCachedSessions().getOrThrow())
        assertEquals(0, fakeAdapter.listSessionsCallCount)
    }

    @Test fun startupCacheCannotBeReadAfterSignOut() = runBlocking {
        vault.saveSessionSummaryCache(SessionSummaryCache(emptyList(), profileLocalId = "conn-1"), partition())
        vault.clearAuthCredential("conn-1", reason = com.hermes.bot.data.local.CacheEpochRotationReason.SIGNED_OUT)
        assertNull(repository.loadCachedSessions().getOrThrow())
        assertEquals(0, fakeAdapter.listSessionsCallCount)
    }

    @Test fun startupCacheStorageFailureIsDistinctFromAbsence() = runBlocking {
        val broken = object : com.hermes.bot.data.local.VaultStorage by vault {
            override suspend fun readSessionSummaryCache(partition: CacheAccessPartition): CacheReadResult<SessionSummaryCache> =
                throw com.hermes.bot.data.local.KeyStoreUnavailableException("synthetic unavailable")
        }
        assertTrue(DefaultSessionRepository(fakeAdapter, broken).loadCachedSessions().isFailure)
        assertEquals(0, fakeAdapter.listSessionsCallCount)
    }

    @Test
    fun listSessions_networkSuccess_persistsCacheAndReturnsNotOffline() = runBlocking {
        val summaries = listOf(
            SessionSummary("s1", "Session One", "Preview One", 100.0, 2, "android"),
            SessionSummary("s2", "Session Two", "Preview Two", 200.0, 4, "tui")
        )
        fakeAdapter.listSessionsResult = GatewayResult.Success(SessionListResult(summaries))

        val result = repository.listSessions(forceRefresh = true)
        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertEquals(2, outcome.sessions.size)
        assertFalse("Server response must not be marked as offline cache", outcome.isOfflineCache)

        // Verify local vault cache was updated
        val cached = readCache()
        assertNotNull(cached)
        assertEquals(2, cached!!.sessions.size)
    }

    @Test
    fun listSessions_networkFails_returnsCachedSessionsWithOfflineFlag() = runBlocking {
        // Pre-populate vault with cached sessions
        val cachedSummaries = listOf(
            SessionSummary("c1", "Cached Session", "Cached Preview", 50.0, 1, "android")
        )
        vault.saveSessionSummaryCache(SessionSummaryCache(cachedSummaries), partition())

        // Gateway fails
        fakeAdapter.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("Host unreachable"))

        val result = repository.listSessions()
        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertEquals(1, outcome.sessions.size)
        assertTrue("Fallback response must be explicitly labeled as offline cache", outcome.isOfflineCache)
    }

    @Test
    fun listSessions_networkFailsAndNoCache_returnsFailure() = runBlocking {
        fakeAdapter.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("Network error"))

        val result = repository.listSessions()
        assertTrue(result.isFailure)
    }

    // --- CHG-003-S5: expiry-boundary matrix (fresh/stale/absent cache x online/offline) ---

    @Test
    fun listSessions_freshCacheOffline_servedWithOfflineFlag() = runBlocking {
        // Fresh cache (well inside the 7-day window) + gateway failure -> offline fallback
        val cachedSummaries = listOf(
            SessionSummary("c1", "Cached Session", "Cached Preview", 50.0, 1, "android")
        )
        val freshCache = SessionSummaryCache(
            sessions = cachedSummaries,
            syncTimeEpochSeconds = System.currentTimeMillis() / 1000 - (1 * 24 * 3600L) // 1 day old
        )
        assertFalse("1-day-old cache must be fresh (inside 7-day window)", freshCache.isStale())
        assertTrue(freshCache.isFresh())
        vault.saveSessionSummaryCache(freshCache, partition())

        fakeAdapter.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("Host unreachable"))

        val result = repository.listSessions()
        assertTrue("Fresh cache offline must be served as fallback", result.isSuccess)
        val outcome = result.getOrThrow()
        assertEquals(1, outcome.sessions.size)
        assertTrue("Offline fallback must be labeled isOfflineCache=true", outcome.isOfflineCache)
    }

    @Test
    fun listSessions_staleCacheOffline_neverReturnedAsListData() = runBlocking {
        // Cache past the 7-day freshness window + gateway failure -> failure, NOT list data
        val staleCache = SessionSummaryCache(
            sessions = listOf(
                SessionSummary("stale-1", "Stale Session", "Old preview", 10.0, 1, "android")
            ),
            syncTimeEpochSeconds = System.currentTimeMillis() / 1000 - (7 * 24 * 3600L) - 60 // 1 min past expiry
        )
        assertTrue("Cache past 7-day window must be stale", staleCache.isStale())
        assertFalse(staleCache.isFresh())
        vault.saveSessionSummaryCache(staleCache, partition())

        fakeAdapter.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("Host unreachable"))

        val result = repository.listSessions()
        assertTrue(
            "Stale-past-expiry cache must behave as a failure state, not silent truth",
            result.isFailure
        )
        val exception = result.exceptionOrNull()
        assertTrue("Expired-cache offline must surface as LocalCacheException", exception is LocalCacheException)
    }

    @Test
    fun listSessions_absentCacheOffline_returnsFailureWithOfflineState() = runBlocking {
        // No cache at all + gateway failure -> failure (never an unlabeled empty list)
        fakeAdapter.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("Host unreachable"))

        val result = repository.listSessions()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LocalCacheException)
    }

    @Test
    fun listSessions_online_ignoresCacheAndReturnsFreshServerDataNotOffline() = runBlocking {
        // Online path: even with cache present (fresh or stale), server data wins and is NOT offline
        val summaries = listOf(
            SessionSummary("live-1", "Live Session", "Fresh preview", 100.0, 2, "android")
        )
        fakeAdapter.listSessionsResult = GatewayResult.Success(SessionListResult(summaries))

        // Pre-seed a stale cache to prove the online path ignores cache entirely
        val staleCache = SessionSummaryCache(
            sessions = listOf(
                SessionSummary("stale-1", "Stale Session", "Old preview", 10.0, 1, "android")
            ),
            syncTimeEpochSeconds = System.currentTimeMillis() / 1000 - (8 * 24 * 3600L) // 8 days old
        )
        vault.saveSessionSummaryCache(staleCache, partition())

        val result = repository.listSessions()
        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertEquals(1, outcome.sessions.size)
        assertEquals("live-1", outcome.sessions[0].id)
        assertFalse("Online server data must never be labeled isOfflineCache=true", outcome.isOfflineCache)
    }

    @Test
    fun listSessions_offlineFallback_isOfflineFlagSetExactlyOnVaultCacheServing() = runBlocking {
        // Boundary assertion: the ONLY path that sets isOfflineCache=true is the vault-cache fallback.
        // Fresh cache + failure -> true; success -> false; failure w/o usable cache -> failure.
        val freshCache = SessionSummaryCache(
            sessions = listOf(SessionSummary("c1", "C", "P", 0.0, 1, "android")),
            syncTimeEpochSeconds = System.currentTimeMillis() / 1000 - 10
        )
        vault.saveSessionSummaryCache(freshCache, partition())

        fakeAdapter.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("down"))
        val offlineOutcome = repository.listSessions().getOrThrow()
        assertTrue(offlineOutcome.isOfflineCache)

        fakeAdapter.listSessionsResult = GatewayResult.Success(
            SessionListResult(listOf(SessionSummary("live", "L", "P", 0.0, 1, "android")))
        )
        val onlineOutcome = repository.listSessions().getOrThrow()
        assertFalse(onlineOutcome.isOfflineCache)

        vault.clearSessionSummaryCache()
        fakeAdapter.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("down"))
        assertTrue(repository.listSessions().isFailure)
    }

    // --- S2: activity ordering with host-order preservation ---

    @Test
    fun listSessions_sortsByKnownActivity_andPersistsOrder() = runBlocking {
        val summaries = listOf(
            SessionSummary("s-100", "Title 100", "Preview 100", 100.0, 1, "android", lastActive = 300.75),
            SessionSummary("s-200", "Title 200", "Preview 200", 200.0, 1, "android", lastActive = 100.25),
            SessionSummary("s-300", "Title 300", "Preview 300", 300.0, 1, "android", lastActive = 200.5)
        )
        fakeAdapter.listSessionsResult = GatewayResult.Success(SessionListResult(summaries))

        val result = repository.listSessions(forceRefresh = true)
        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertEquals(listOf("s-100", "s-300", "s-200"), outcome.sessions.map { it.id })

        // Verify sorted list was persisted to vault cache
        val cached = readCache()
        assertNotNull(cached)
        assertEquals(listOf("s-100", "s-300", "s-200"), cached!!.sessions.map { it.id })
    }

    @Test
    fun listSessions_equalActivity_preservesInputOrder() = runBlocking {
        val summaries = listOf(
            SessionSummary("s-beta", "Session B", "Preview B", 250.0, 1, "android", lastActive = 300.5),
            SessionSummary("s-alpha", "Session A", "Preview A", 250.0, 1, "android", lastActive = 300.5)
        )
        fakeAdapter.listSessionsResult = GatewayResult.Success(SessionListResult(summaries))

        val result = repository.listSessions(forceRefresh = true)
        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertEquals(listOf("s-beta", "s-alpha"), outcome.sessions.map { it.id })
    }

    @Test
    fun listSessions_missingActivity_preservesHostOrderIncludingNullCreation() = runBlocking {
        val summaries = listOf(
            SessionSummary("s-null-b", "Null B", "Preview Null B", null, 1, "android"),
            SessionSummary("s-100", "Session 100", "Preview 100", 100.0, 1, "android"),
            SessionSummary("s-null-a", "Null A", "Preview Null A", null, 1, "android"),
            SessionSummary("s-300", "Session 300", "Preview 300", 300.0, 1, "android")
        )
        fakeAdapter.listSessionsResult = GatewayResult.Success(SessionListResult(summaries))

        val result = repository.listSessions(forceRefresh = true)
        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertEquals(summaries, outcome.sessions)
    }

    @Test
    fun listSessions_offlineCache_ordersKnownActivityThenStableUnknowns() = runBlocking {
        val unsortedCachedSummaries = listOf(
            SessionSummary("c-100", "Cache 100", "Preview 100", 100.0, 1, "android", lastActive = 400.5),
            SessionSummary("c-null-z", "Cache Null Z", "Preview Null Z", null, 1, "android"),
            SessionSummary("c-300", "Cache 300", "Preview 300", 300.0, 1, "android", lastActive = 300.5),
            SessionSummary("c-null-a", "Cache Null A", "Preview Null A", null, 1, "android"),
            SessionSummary("c-200-b", "Cache 200 B", "Preview 200 B", 200.0, 1, "android", lastActive = 200.5),
            SessionSummary("c-200-a", "Cache 200 A", "Preview 200 A", 200.0, 1, "android", lastActive = 200.5)
        )
        val freshCache = SessionSummaryCache(
            sessions = unsortedCachedSummaries,
            syncTimeEpochSeconds = System.currentTimeMillis() / 1000 - 60
        )
        vault.saveSessionSummaryCache(freshCache, partition())

        fakeAdapter.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("Host unreachable"))

        val result = repository.listSessions()
        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue("Offline fallback must be labeled isOfflineCache=true", outcome.isOfflineCache)
        assertEquals(
            listOf("c-100", "c-300", "c-200-b", "c-200-a", "c-null-z", "c-null-a"),
            outcome.sessions.map { it.id }
        )
    }

    @Test
    fun createDraftSession_persistsSelectionDurableId_andSetsActiveSession() = runBlocking {
        fakeAdapter.createSessionResult = GatewayResult.Success(
            SessionCreateResult(
                runtimeSessionId = "runtime-live-10",
                durableSessionId = "durable-key-10",
                messageCount = 0,
                messages = emptyList()
            )
        )

        val result = repository.createDraftSession("New Conversation")
        assertTrue(result.isSuccess)
        val handle = result.getOrThrow()

        assertEquals("durable-key-10", handle.storedSessionId)
        assertEquals("runtime-live-10", handle.runtimeSessionId)
        assertTrue(handle.isDraft)

        // Verify selection stored in vault
        val selection = vault.getSessionSelection()
        assertNotNull(selection)
        assertEquals("durable-key-10", selection!!.storedSessionId)
        assertEquals("New Conversation", selection.lastKnownTitle)
    }

    @Test
    fun createDraftSession_withoutTitle_doesNotInheritPriorVaultTitle() = runBlocking {
        vault.saveSessionSelection(
            SessionSelection(storedSessionId = "durable-create-1", lastKnownTitle = "Old Title")
        )
        fakeAdapter.createSessionResult = GatewayResult.Success(
            SessionCreateResult(
                runtimeSessionId = "runtime-create-1",
                durableSessionId = "durable-create-1",
                messageCount = 0,
                messages = emptyList()
            )
        )

        val result = repository.createDraftSession()

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow().title)
        assertNull(vault.getSessionSelection()?.lastKnownTitle)
    }

    @Test
    fun resumeSession_persistedSession_replacesStaleRuntimeId() = runBlocking {
        // Prepare active session with a stale runtime ID
        repository.createDraftSession()
        assertEquals("runtime-sid-created-1", repository.activeSession.value?.runtimeSessionId)

        // Resume returns a NEW fresh runtimeSessionId
        fakeAdapter.resumeSessionResult = GatewayResult.Success(
            SessionResumeResult(
                runtimeSessionId = "fresh-runtime-after-resume-99",
                durableSessionId = "durable-key-resumed-1",
                messages = listOf(
                    SessionMessage("user", "Hello"),
                    SessionMessage("assistant", "Hi there")
                ),
                messageCount = 2
            )
        )

        val result = repository.resumeSession("durable-key-resumed-1")
        assertTrue(result.isSuccess)

        // HARD CONTRACT INVARIANT: runtime ID returned by resume replaces stale runtime ID
        val active = repository.activeSession.value
        assertNotNull(active)
        assertEquals("fresh-runtime-after-resume-99", active!!.runtimeSessionId)
        assertEquals("durable-key-resumed-1", active.storedSessionId)

        // Verify history rehydration gate was marked
        assertTrue(fakeAdapter.rehydratedSessions.contains("fresh-runtime-after-resume-99"))

        // Verify messages hydrated
        assertEquals(2, repository.activeMessages.value.size)
    }

    @Test
    fun resumeSession_notFound4007_returnsDistinctSessionNotFoundException() = runBlocking {
        fakeAdapter.resumeSessionResult = GatewayResult.Failure(
            GatewayFailure.Rpc(code = 4007, message = "session not found")
        )

        val result = repository.resumeSession("non-existent-durable-id")
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(
            "4007 session not found must produce distinct SessionNotFoundException",
            exception is SessionNotFoundException
        )
        assertEquals("non-existent-durable-id", (exception as SessionNotFoundException).storedSessionId)
    }

    @Test
    fun restoreSelectedSession_restoresDurableIdWithNullRuntimeId_neverReusesStaleRuntimeId() = runBlocking {
        // Pre-populate vault with selected durable session
        vault.saveSessionSelection(
            SessionSelection(
                storedSessionId = "durable-stored-123",
                lastKnownTitle = "Saved Discussion"
            )
        )

        val restored = repository.restoreSelectedSession()
        assertNotNull(restored)
        assertEquals("durable-stored-123", restored!!.storedSessionId)
        assertEquals("Saved Discussion", restored.title)

        // HARD CONTRACT INVARIANT: runtimeSessionId must be NULL on launch restoration.
        // It must NOT reuse any stale runtime ID for future prompt submissions.
        assertNull("Restored session must have null runtimeSessionId until resumed", restored.runtimeSessionId)
    }

    @Test
    fun listSessions_matchingSummary_propagatesTitleToActiveHandleAndVault() = runBlocking {
        // Step 1: Active session exists with no title (resume path leaves title null)
        fakeAdapter.resumeSessionResult = GatewayResult.Success(
            SessionResumeResult(
                runtimeSessionId = "runtime-t1",
                durableSessionId = "durable-t1",
                messages = emptyList(),
                messageCount = 0
            )
        )
        repository.resumeSession("durable-t1")
        assertNull("Precondition: handle title is null before server list", repository.activeSession.value?.title)
        assertNull(vault.getSessionSelection()?.lastKnownTitle)

        // Step 2: Server list returns a summary whose id matches the active session's storedSessionId
        fakeAdapter.listSessionsResult = GatewayResult.Success(
            SessionListResult(
                sessions = listOf(
                    SessionSummary("durable-t1", "Server Authored Title", "preview", 100.0, 2, "android")
                )
            )
        )
        val result = repository.listSessions(forceRefresh = true)
        assertTrue(result.isSuccess)

        // HARD INVARIANT: active handle reflects the server summary's title
        assertEquals("Server Authored Title", repository.activeSession.value?.title)

        // HARD INVARIANT: vault SessionSelection.lastKnownTitle refreshed from server data
        val selection = vault.getSessionSelection()
        assertNotNull(selection)
        assertEquals("durable-t1", selection!!.storedSessionId)
        assertEquals("Server Authored Title", selection.lastKnownTitle)
    }

    @Test
    fun listSessions_matchingSummaryWithNullTitle_doesNotOverwriteKnownTitle() = runBlocking {
        // Active session with a known title in the vault
        fakeAdapter.createSessionResult = GatewayResult.Success(
            SessionCreateResult(
                runtimeSessionId = "runtime-t2",
                durableSessionId = "durable-t2",
                messageCount = 0,
                messages = emptyList()
            )
        )
        repository.createDraftSession("Known Title")
        assertEquals("Known Title", repository.activeSession.value?.title)

        // Server list returns a summary matching the active session but with no title
        fakeAdapter.listSessionsResult = GatewayResult.Success(
            SessionListResult(
                sessions = listOf(
                    SessionSummary("durable-t2", null, "preview", 100.0, 2, "android")
                )
            )
        )
        val result = repository.listSessions(forceRefresh = true)
        assertTrue(result.isSuccess)

        // Title must NOT be nulled out by a server summary lacking a title
        assertEquals("Known Title", repository.activeSession.value?.title)
        assertEquals("Known Title", vault.getSessionSelection()?.lastKnownTitle)
    }

    @Test
    fun listSessions_matchingSummaryWithEmptyTitle_doesNotOverwriteKnownTitle() = runBlocking {
        fakeAdapter.createSessionResult = GatewayResult.Success(
            SessionCreateResult(
                runtimeSessionId = "runtime-t3",
                durableSessionId = "durable-t3",
                messageCount = 0,
                messages = emptyList()
            )
        )
        repository.createDraftSession("Known Title")

        fakeAdapter.listSessionsResult = GatewayResult.Success(
            SessionListResult(
                sessions = listOf(
                    SessionSummary("durable-t3", "", "preview", 100.0, 2, "android")
                )
            )
        )

        val result = repository.listSessions(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals("Known Title", repository.activeSession.value?.title)
        assertEquals("Known Title", vault.getSessionSelection()?.lastKnownTitle)
    }

    @Test
    fun listSessions_matchingSummaryWithBlankTitle_doesNotOverwriteKnownTitle() = runBlocking {
        fakeAdapter.createSessionResult = GatewayResult.Success(
            SessionCreateResult(
                runtimeSessionId = "runtime-t4",
                durableSessionId = "durable-t4",
                messageCount = 0,
                messages = emptyList()
            )
        )
        repository.createDraftSession("Known Title")

        fakeAdapter.listSessionsResult = GatewayResult.Success(
            SessionListResult(
                sessions = listOf(
                    SessionSummary("durable-t4", "   \t", "preview", 100.0, 2, "android")
                )
            )
        )

        val result = repository.listSessions(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals("Known Title", repository.activeSession.value?.title)
        assertEquals("Known Title", vault.getSessionSelection()?.lastKnownTitle)
    }

    @Test
    fun resumeSession_preservesPreviouslyKnownTitle_neverWritesNullOverKnownTitle() = runBlocking {
        // Step 1: Selection with a known title exists in the vault
        vault.saveSessionSelection(
            SessionSelection(storedSessionId = "durable-r1", lastKnownTitle = "Earlier Title")
        )

        // Step 2: Resume returns no title (per owner contract Q2: session.list is the only title source)
        fakeAdapter.resumeSessionResult = GatewayResult.Success(
            SessionResumeResult(
                runtimeSessionId = "runtime-r1",
                durableSessionId = "durable-r1",
                messages = emptyList(),
                messageCount = 0
            )
        )

        val result = repository.resumeSession("durable-r1")
        assertTrue(result.isSuccess)

        // HARD INVARIANT: handle preserves the previously known title (never null-overwritten)
        assertEquals("Earlier Title", repository.activeSession.value?.title)

        // HARD INVARIANT: vault selection keeps the previously known title
        val selection = vault.getSessionSelection()
        assertNotNull(selection)
        assertEquals("durable-r1", selection!!.storedSessionId)
        assertEquals("Earlier Title", selection.lastKnownTitle)
    }

    @Test
    fun resumeSession_noPriorTitle_leavesTitleNull_untilServerListProvidesIt() = runBlocking {
        // No prior selection/title: resume carries no title, so the handle title stays null
        fakeAdapter.resumeSessionResult = GatewayResult.Success(
            SessionResumeResult(
                runtimeSessionId = "runtime-r2",
                durableSessionId = "durable-r2",
                messages = emptyList(),
                messageCount = 0
            )
        )

        val result = repository.resumeSession("durable-r2")
        assertTrue(result.isSuccess)
        assertNull("No fabricated title: stays null until session.list supplies one", repository.activeSession.value?.title)
        assertNull("No fabricated title persisted", vault.getSessionSelection()?.lastKnownTitle)
    }

    @Test
    fun processRecreation_durableSelectionRestoredWithoutStaleRuntimeId() = runBlocking {
        // Step 1: Create session in original instance
        repository.createDraftSession("Important Task")
        assertEquals("runtime-sid-created-1", repository.activeSession.value?.runtimeSessionId)

        // Step 2: Simulate process recreation with fresh repository instance over same vault
        val freshRepo = DefaultSessionRepository(fakeAdapter, vault)
        val restored = freshRepo.restoreSelectedSession()

        assertNotNull(restored)
        assertEquals("durable-key-created-1", restored!!.storedSessionId)
        assertNull("Restored session across process recreation must have null runtime ID", restored.runtimeSessionId)

        // Step 3: Explicit resume provides fresh runtime ID
        fakeAdapter.resumeSessionResult = GatewayResult.Success(
            SessionResumeResult(
                runtimeSessionId = "new-process-runtime-id-555",
                durableSessionId = "durable-key-created-1",
                messageCount = 0,
                messages = emptyList()
            )
        )
        val resumeResult = freshRepo.resumeSession("durable-key-created-1")
        assertTrue(resumeResult.isSuccess)
        assertEquals("new-process-runtime-id-555", freshRepo.activeSession.value?.runtimeSessionId)
    }

    // =====================================================================
    // F007-S3: replay watermark seeding on resume
    // =====================================================================

    @Test
    fun resumeSession_seedsReplayWatermarkViaReplayEventsSince() = runBlocking {
        // Resume hydration must reach the previously dead-code replayEventsSince
        // path (session.events.since first sweep) so the watermark is seeded from
        // the host's latest_seq. Host shape: a resumed session with prior live
        // activity replays its ring with per-event seq; latest_seq == last seq.
        fakeAdapter.replayEventsResultFactory = {
            GatewayResult.Success(
                ReplayEventsResult(
                    events = listOf(
                        GatewayEvent.MessageStart(sessionId = "runtime-sid-resumed-1", seq = 41L),
                        GatewayEvent.MessageComplete(sessionId = "runtime-sid-resumed-1", seq = 42L, text = "seeded", status = "complete")
                    ),
                    count = 2,
                    epoch = "test-epoch-1",
                    latestSeq = 42L,
                    truncated = false
                )
            )
        }

        val result = repository.resumeSession("durable-key-resumed-1")
        assertTrue(result.isSuccess)

        // Production path reached replayEventsSince with a from-zero first sweep.
        assertEquals(1, fakeAdapter.replayCalls.size)
        val call = fakeAdapter.replayCalls.single()
        assertEquals("runtime-sid-resumed-1", call.sessionId)
        assertEquals(0L, call.lastSeen)
        assertEquals("durable-key-resumed-1", call.durableSessionId)

        // Watermark committed from the returned events (mirrors adapter behavior:
        // commit only after all returned events parse).
        assertEquals(42L, fakeAdapter.getLastSeenSeq("runtime-sid-resumed-1"))
    }

    @Test
    fun resumeSession_failedSeed_keepsResumeResultAndSkipsReSeedUntilEpochChanges() = runBlocking {
        // Deterministic seed failure (e.g. host replay buffer truncated): the
        // fallback -> loadSession() -> resume loop must NOT re-seed and re-fallback
        // forever on the same epoch. The just-completed hydration stays authoritative.
        fakeAdapter.replayEventsResultFactory = {
            GatewayResult.Failure(GatewayFailure.Transport("Replay buffer truncated"))
        }

        val first = repository.resumeSession("durable-key-resumed-1")
        assertTrue("Resume must succeed even when the seed fails", first.isSuccess)
        // F-007 polish: 2 calls = initial attempt + one bounded retry before
        // the deterministic failure is remembered for this epoch.
        assertEquals(2, fakeAdapter.replayCalls.size)

        // Simulate the fallback loop re-resuming within the same epoch.
        val second = repository.resumeSession("durable-key-resumed-1")
        assertTrue(second.isSuccess)
        assertEquals("Failed seed must not be retried within the same epoch", 2, fakeAdapter.replayCalls.size)

        // Host restart announced a fresh epoch: seeding is attempted again.
        fakeAdapter.currentReplayEpoch = "test-epoch-2"
        val third = repository.resumeSession("durable-key-resumed-1")
        assertTrue(third.isSuccess)
        assertEquals("Fresh epoch invalidates the seed-failure memory", 4, fakeAdapter.replayCalls.size)
    }

    @Test
    fun resumeSession_truncatedSeedMarksFailure_clearsOnCleanSeedNextEpoch() = runBlocking {
        // Truncation is a contract fallback (re-hydrate) AND a deterministic
        // seed failure for this epoch.
        fakeAdapter.replayEventsResultFactory = {
            GatewayResult.Success(
                ReplayEventsResult(
                    events = emptyList(),
                    count = 0,
                    epoch = "test-epoch-1",
                    latestSeq = 7L,
                    truncated = true
                )
            )
        }
        val first = repository.resumeSession("durable-key-resumed-1")
        assertTrue(first.isSuccess)
        assertEquals(1, fakeAdapter.replayCalls.size)

        // Same epoch: no re-seed.
        repository.resumeSession("durable-key-resumed-1")
        assertEquals(1, fakeAdapter.replayCalls.size)

        // New epoch + clean batch: memory cleared, watermark committed.
        fakeAdapter.currentReplayEpoch = "test-epoch-2"
        fakeAdapter.replayEventsResultFactory = {
            GatewayResult.Success(
                ReplayEventsResult(
                    events = listOf(
                        GatewayEvent.MessageStart(sessionId = "runtime-sid-resumed-1", seq = 8L),
                        GatewayEvent.MessageComplete(sessionId = "runtime-sid-resumed-1", seq = 9L, text = "seeded", status = "complete")
                    ),
                    count = 2,
                    epoch = "test-epoch-2",
                    latestSeq = 9L,
                    truncated = false
                )
            )
        }
        repository.resumeSession("durable-key-resumed-1")
        assertEquals(2, fakeAdapter.replayCalls.size)
        assertEquals(9L, fakeAdapter.getLastSeenSeq("runtime-sid-resumed-1"))
    }

    @Test
    fun resumeSession_failedResume_neverSeedsReplayWatermark() = runBlocking {
        fakeAdapter.resumeSessionResult = GatewayResult.Failure(
            GatewayFailure.Rpc(code = 4007, message = "session not found")
        )

        val result = repository.resumeSession("durable-key-resumed-1")
        assertTrue(result.isFailure)
        assertTrue("No seed attempt on failed resume", fakeAdapter.replayCalls.isEmpty())
    }

    @Test
    fun resumeSession_transientSeedFailure_retriedOnceBeforeEpochMemory() = runBlocking {
        // F-007 polish: a single transient seed blip must not disable seeding
        // for the whole epoch. One bounded retry happens in the SAME resume;
        // only if the retry also fails is the epoch remembered as seed-failed.
        var calls = 0
        fakeAdapter.replayEventsResultFactory = {
            calls++
            GatewayResult.Failure(GatewayFailure.Transport("transient blip"))
        }

        val first = repository.resumeSession("durable-key-resumed-1")
        assertTrue("Resume must succeed even when the seed fails twice", first.isSuccess)
        assertEquals("Exactly one bounded retry within the same resume", 2, calls)

        // Epoch memory is now marked: a re-resume in the same epoch must NOT
        // re-seed (the fallback loop must not re-trigger the failure forever).
        repository.resumeSession("durable-key-resumed-1")
        assertEquals(2, calls)

        // Fresh epoch invalidates the memory: seeding is attempted again.
        fakeAdapter.currentReplayEpoch = "test-epoch-2"
        repository.resumeSession("durable-key-resumed-1")
        assertEquals(4, calls)
    }

    @Test
    fun resumeSession_transientThenCleanSeed_commitsWatermarkAndClearsMemory() = runBlocking {
        // A blip followed by a clean retry is a successful seed: the watermark
        // commits and no epoch-failure memory is left behind.
        var calls = 0
        fakeAdapter.replayEventsResultFactory = {
            calls++
            if (calls == 1) {
                GatewayResult.Failure(GatewayFailure.Transport("transient blip"))
            } else {
                GatewayResult.Success(
                    ReplayEventsResult(
                        events = listOf(
                            GatewayEvent.MessageComplete(
                                sessionId = "runtime-sid-resumed-1",
                                seq = 42L,
                                text = "seeded",
                                status = "complete"
                            )
                        ),
                        count = 1,
                        epoch = "test-epoch-1",
                        latestSeq = 42L,
                        truncated = false
                    )
                )
            }
        }

        val result = repository.resumeSession("durable-key-resumed-1")
        assertTrue(result.isSuccess)
        assertEquals(2, calls)
        assertEquals(42L, fakeAdapter.getLastSeenSeq("runtime-sid-resumed-1"))

        // No failure memory: a same-epoch re-resume re-seeds cleanly.
        repository.resumeSession("durable-key-resumed-1")
        assertEquals(3, calls)
    }

    @Test
    fun resumeSession_seedFailureMemory_keyedByDurableSessionId() = runBlocking {
        // F-007 polish: the epoch-failure memory must key on durableSessionId
        // (stable across resumes) — a runtime ID change on a later resume of
        // the same durable session must not resurrect a remembered failure.
        fakeAdapter.replayEventsResultFactory = {
            GatewayResult.Failure(GatewayFailure.Transport("deterministic failure"))
        }
        val first = repository.resumeSession("durable-key-resumed-1")
        assertTrue(first.isSuccess)
        assertEquals(2, fakeAdapter.replayCalls.size) // initial + bounded retry

        // Same epoch, SAME durable session but a new runtime ID: the memory
        // must still hold (keyed by durable ID, not runtime ID).
        fakeAdapter.resumeSessionResult = GatewayResult.Success(
            SessionResumeResult(
                runtimeSessionId = "runtime-sid-resumed-2",
                durableSessionId = "durable-key-resumed-1",
                messageCount = 0,
                messages = emptyList()
            )
        )
        repository.resumeSession("durable-key-resumed-1")
        assertEquals(2, fakeAdapter.replayCalls.size)
    }
}
