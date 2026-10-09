package io.github.anshuman2401.hermesmobile.data.session

import io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport
import io.github.anshuman2401.hermesmobile.data.auth.HttpResponse
import io.github.anshuman2401.hermesmobile.data.context.ContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.context.DefaultContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.context.ScopedSessionsResult
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionListResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.local.SessionSummaryCache
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartitionResult
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.feature.sessions.SessionsEmptyStatePolicy
import io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
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

class ProfileScopedSessionFetchTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeTransport: FakeScopedSessionTransport
    private lateinit var contextProfileRepo: ContextProfileRepository
    private lateinit var sessionRepository: DefaultSessionRepository

    private val origin = "http://127.0.0.1:9119"

    class FakeScopedSessionTransport : AuthHttpTransport {
        val recordedRequests = mutableListOf<RecordedRequest>()
        var scopedResponse = HttpResponse(
            200,
            body = """{"sessions":[{"id":"arch-1","title":"Architect Session","started_at":1000.0,"message_count":3,"source":"android"}],"total":1}"""
        )

        data class RecordedRequest(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val body: String?
        )

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?
        ): HttpResponse {
            recordedRequests.add(RecordedRequest(method, url, headers, body))
            return when {
                url.contains("/api/profiles/sessions") -> scopedResponse
                else -> HttpResponse(404, body = "Not Found")
            }
        }
    }

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("scoped_session_fetch_test")
        vault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeTransport = FakeScopedSessionTransport()
        contextProfileRepo = DefaultContextProfileRepository(vaultStorage = vault, httpTransport = fakeTransport)
        sessionRepository = DefaultSessionRepository(
            gatewayAdapter = fakeAdapter,
            vaultStorage = vault,
            contextProfileRepository = contextProfileRepo,
            httpTransport = fakeTransport
        )

        runBlocking {
            vault.saveConnectionProfile(
                ConnectionProfile(
                    localId = "conn-1",
                    normalizedOrigin = origin,
                    displayLabel = "Test Server"
                )
            )
            vault.setActiveProfileId("conn-1")
            vault.saveAuthCredential(
                AuthCredential(
                    driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                    providerId = "password",
                    cookies = mapOf("session_id" to "sess-scoped-token"),
                    canonicalOrigin = origin
                ),
                profileLocalId = "conn-1"
            )
        }
    }

    @Test
    fun listSessions_afterLocalSelection_fetchesScopedSessionsFromHttp() = runBlocking {
        val selection = contextProfileRepo.selectProfile("architect")
        assertTrue("Local selection must succeed", selection is io.github.anshuman2401.hermesmobile.data.context.ContextProfileSelectionResult.Success)
        val unscopedSummary = SessionSummary("unscoped-1", "Unscoped", null, 500.0, 1, null)
        fakeAdapter.listSessionsResult = GatewayResult.Success(SessionListResult(listOf(unscopedSummary)))

        val result = sessionRepository.listSessions()
        assertTrue("listSessions must succeed", result.isSuccess)
        val outcome = result.getOrThrow()

        // Must return the scoped session from HTTP, NOT the unscoped session from adapter
        assertEquals(1, outcome.sessions.size)
        assertEquals("arch-1", outcome.sessions[0].id)
        assertEquals("Architect Session", outcome.sessions[0].title)
        assertEquals("architect", outcome.selectedProfileName)

        // Verify HTTP call was made with profile=architect
        val httpReq = fakeTransport.recordedRequests.firstOrNull { it.url.contains("/api/profiles/sessions") }
        assertNotNull(httpReq)
        assertTrue(httpReq!!.url.contains("profile=architect"))
        assertTrue(httpReq.url.contains("limit="))
        assertEquals("session_id=sess-scoped-token", httpReq.headers["Cookie"])
    }

    @Test
    fun listSessions_whenNoPreferenceSet_usesUnscopedAdapterCall() = runBlocking {
        vault.clearSelectedContextProfile()
        val unscopedSummary = SessionSummary("unscoped-1", "Unscoped", null, 500.0, 1, null)
        fakeAdapter.listSessionsResult = GatewayResult.Success(SessionListResult(listOf(unscopedSummary)))

        val result = sessionRepository.listSessions()
        assertTrue("listSessions must succeed", result.isSuccess)
        val outcome = result.getOrThrow()

        assertEquals(1, outcome.sessions.size)
        assertEquals("unscoped-1", outcome.sessions[0].id)
        assertNull("selectedProfileName must be null when no preference set", outcome.selectedProfileName)

        // HTTP /api/profiles/sessions must NOT have been called
        assertFalse(fakeTransport.recordedRequests.any { it.url.contains("/api/profiles/sessions") })
    }

    @Test
    fun listSessions_scopedFetchFails_neverServesUnscopedCacheAsProfileSessions() = runBlocking {
        // Preference set so listSessions takes the profile-scoped path.
        vault.saveSelectedContextProfile("architect")

        // Seed the vault cache exactly as the UNscoped gatewayAdapter path does
        // (SessionSummaryCache with profileLocalId = active connection id).
        val unscopedSummaries = listOf(
            SessionSummary("unscoped-1", "Unscoped Session", null, 500.0, 1, null)
        )
        // PR-1: the unscoped writer stamps no workspace name, so its records
        // live under the unscoped identity "u" — write exactly that so the
        // scoped reader must refuse to serve it.
        val unscopedPartition = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available)
            .partition.copy(workspaceScopeTag = "u")
        vault.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = unscopedSummaries,
                profileLocalId = "conn-1"
            ),
            unscopedPartition
        )

        // Scoped fetch fails with a network exception
        fakeTransport.scopedResponse = HttpResponse(500, body = "Server Error")

        val result = sessionRepository.listSessions()
        // The unscoped cache entry was never written by the profile-scoped path;
        // it must not be served labeled as the selected profile's sessions.
        if (result.isSuccess) {
            val outcome = result.getOrThrow()
            assertFalse(
                "Unscoped cache must not be served with selectedProfileName set",
                outcome.isOfflineCache && outcome.selectedProfileName != null
            )
        } else {
            // Failure is the acceptable fail-closed outcome for the offline path.
            assertNotNull(result.exceptionOrNull())
        }
    }

    @Test
    fun sessionsViewModel_mapsSelectedProfileName_toUiState() = runBlocking {
        vault.saveSelectedContextProfile("architect")
        assertEquals("architect", sessionRepository.listSessions().getOrThrow().selectedProfileName)
        val viewModel = SessionsViewModel(sessionRepository = sessionRepository)
        try {
            val state = withContext(Dispatchers.Default) {
                withTimeout(5000) { viewModel.uiState.first { !it.isLoading } }
            }
            assertNull(state.error)
            assertEquals("architect", state.selectedProfileName)
            assertEquals(1, state.sessions.size)
            assertEquals("arch-1", state.sessions[0].id)
        } finally { closeViewModel(viewModel) }
    }

    @Test
    fun sessionsViewModel_showsEmptyState_whenScopedSessionsEmpty() = runBlocking {
        vault.saveSelectedContextProfile("empty-profile")
        fakeTransport.scopedResponse = HttpResponse(200, body = """{"sessions":[],"total":0}""")
        val cold = sessionRepository.listSessions().getOrThrow()
        assertEquals("empty-profile", cold.selectedProfileName)
        assertTrue(cold.sessions.isEmpty())
        val viewModel = SessionsViewModel(sessionRepository = sessionRepository)
        try {
            val state = withContext(Dispatchers.Default) {
                withTimeout(5000) { viewModel.uiState.first { !it.isLoading } }
            }
            assertNull(state.error)
            assertEquals("empty-profile", state.selectedProfileName)
            assertTrue(state.sessions.isEmpty())
            assertTrue(SessionsEmptyStatePolicy.isEmptyStateVisible(state.sessions.size, state.isLoading))
        } finally { closeViewModel(viewModel) }
    }

    @Test
    fun sessionsViewModel_terminalFailureCannotPassEmptyProfileSuccessAssertions() = runBlocking {
        vault.saveSelectedContextProfile("empty-profile")
        fakeTransport.scopedResponse = HttpResponse(500, body = "synthetic unavailable")
        assertTrue(sessionRepository.listSessions().isFailure)
        val viewModel = SessionsViewModel(sessionRepository = sessionRepository)
        try {
            val state = withContext(Dispatchers.Default) {
                withTimeout(5000) { viewModel.uiState.first { !it.isLoading } }
            }
            assertNotNull(state.error)
            assertNull(state.selectedProfileName)
        } finally { closeViewModel(viewModel) }
    }

    @Test
    fun legacyScopedParser_retainsFractionalActivityWithoutReordering() = runBlocking {
        fakeTransport.scopedResponse = HttpResponse(200, body = fractionalActivityResponse)
        val rows = contextProfileRepo.getScopedSessions("architect").getOrThrow()
        assertFractionalActivity(rows)
    }

    @Test
    fun capturedScopedParser_retainsFractionalActivityWithoutReordering() = runBlocking {
        fakeTransport.scopedResponse = HttpResponse(200, body = fractionalActivityResponse)
        assertFractionalActivity(capturedSessionRows())
    }

    @Test
    fun typedScopedParser_retainsFractionalActivityWithoutReordering() = runBlocking {
        fakeTransport.scopedResponse = HttpResponse(200, body = fractionalActivityResponse)
        val result = contextProfileRepo.getScopedSessionsTyped("architect")
        assertTrue(result is ScopedSessionsResult.Success)
        assertFractionalActivity((result as ScopedSessionsResult.Success).sessions)
    }

    @Test
    fun legacyScopedParser_missingNullMalformedAndNonfiniteActivityRemainUnknown() = runBlocking {
        fakeTransport.scopedResponse = HttpResponse(200, body = invalidActivityResponse)
        assertUnknownActivity(contextProfileRepo.getScopedSessions("architect").getOrThrow())
    }

    @Test
    fun capturedScopedParser_missingNullMalformedAndNonfiniteActivityRemainUnknown() = runBlocking {
        fakeTransport.scopedResponse = HttpResponse(200, body = invalidActivityResponse)
        assertUnknownActivity(capturedSessionRows())
    }

    private suspend fun capturedSessionRows(): List<SessionSummary> {
        val lease = vault.readAuthCredentialLease(origin)
        assertNotNull(lease)
        val result = contextProfileRepo.getScopedSessionsCaptured("architect", lease!!)
        assertTrue(result is ScopedSessionsResult.Success)
        return (result as ScopedSessionsResult.Success).sessions
    }

    private fun assertFractionalActivity(rows: List<SessionSummary>) {
        assertEquals(listOf("first", "second"), rows.map { it.id })
        assertEquals(listOf(200.0, 100.0), rows.map { it.startedAt })
        assertEquals(listOf(1000.125, 2000.875), rows.map { it.lastActive })
    }

    private fun assertUnknownActivity(rows: List<SessionSummary>) {
        assertEquals(11, rows.size)
        rows.forEach {
            assertEquals(42.5, it.startedAt!!, 0.0)
            assertNull(it.lastActive)
        }
    }

    private suspend fun closeViewModel(viewModel: SessionsViewModel) {
        val job = viewModel.viewModelScope.coroutineContext[Job]!!
        ViewModelStore().apply { put("sessions", viewModel); clear() }
        withContext(Dispatchers.Default) { withTimeout(5000) { job.join() } }
    }
}
