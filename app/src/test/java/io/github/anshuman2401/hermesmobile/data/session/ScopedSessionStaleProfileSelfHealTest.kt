package io.github.anshuman2401.hermesmobile.data.session

import io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport
import io.github.anshuman2401.hermesmobile.data.auth.HttpResponse
import io.github.anshuman2401.hermesmobile.data.context.ContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.context.DefaultContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.context.ScopedSessionsResult
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionListResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.viewModelScope
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

/**
 * Scoped-session 404 self-heal (deleg_8333ba4d diagnosis):
 *
 * When the persisted selected workspace name becomes stale (roster change on
 * the host), the scoped fetch GET /api/profiles/sessions 404s with the
 * FastAPI body {"detail":"Profile 'X' does not exist."}. Before this fix the
 * repository mapped that to a generic "service unavailable" banner and the
 * stale preference kept re-failing every refresh. Now:
 *
 * - 404 + profile-not-exist detail -> typed StaleSelection signal:
 *   DefaultSessionRepository clears the persisted preference and falls back
 *   to the unscoped path for THIS load; the user sees the fixed, sanitized
 *   notice string, never the host-controlled 'detail' text.
 * - 400 -> generic fail-closed message preserved.
 * - Hostile detail payloads (IPs, cookie-like tokens) never surface anywhere.
 * - Non-404/400 statuses keep the existing mapping.
 */
class ScopedSessionStaleProfileSelfHealTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeTransport: FakeSelfHealTransport
    private lateinit var contextProfileRepo: ContextProfileRepository
    private lateinit var sessionRepository: DefaultSessionRepository

    private val origin = "http://127.0.0.1:9119"

    class FakeSelfHealTransport : AuthHttpTransport {
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
        storageDir = tempFolder.newFolder("scoped_self_heal_test")
        vault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeTransport = FakeSelfHealTransport()
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
                    cookies = mapOf("session_id" to "sess-selfheal-token"),
                    canonicalOrigin = origin
                ),
                profileLocalId = "conn-1"
            )
        }
    }

    private fun staleDetailBody(hostileDetail: String): String =
        """{"detail":"$hostileDetail"}"""

    @Test
    fun typed_404_profileGone_returnsStaleSelection_withFixedNotice() = runBlocking {
        vault.saveSelectedContextProfile("architect")
        fakeTransport.scopedResponse = HttpResponse(
            404,
            body = staleDetailBody("Profile 'architect' does not exist.")
        )

        val result = contextProfileRepo.getScopedSessionsTyped("architect")

        assertTrue("404 with profile-gone detail must be StaleSelection", result is ScopedSessionsResult.StaleSelection)
        val stale = result as ScopedSessionsResult.StaleSelection
        assertEquals(
            "Notice must be the fixed sanitized constant",
            ScopedSessionsResult.PROFILE_GONE_NOTICE,
            stale.notice
        )
        assertFalse("Notice must never contain raw detail", stale.notice.contains("does not exist", ignoreCase = true))
        assertFalse("Notice must never contain the profile name", stale.notice.contains("architect"))
    }

    @Test
    fun typed_400_mapsToGenericError_preservingFailClosedMessage() = runBlocking {
        vault.saveSelectedContextProfile("architect")
        fakeTransport.scopedResponse = HttpResponse(
            400,
            body = staleDetailBody("Profile 'architect' does not exist.")
        )

        val result = contextProfileRepo.getScopedSessionsTyped("architect")

        assertTrue("400 must stay an Error", result is ScopedSessionsResult.Error)
        val error = result as ScopedSessionsResult.Error
        assertEquals(
            "400 must preserve the existing generic fail-closed message",
            "Context profiles service unavailable (HTTP 400)",
            error.exception.message
        )
    }

    @Test
    fun typed_hostileDetail_neverSurfacesRawText() = runBlocking {
        vault.saveSelectedContextProfile("architect")
        val hostileDetail = "session_id=sess-hijack-cookie; connect to 10.1.2.3 failed; ticket hermes-gateway-ticket.ABCDEF"
        fakeTransport.scopedResponse = HttpResponse(
            404,
            body = staleDetailBody(hostileDetail)
        )

        val result = contextProfileRepo.getScopedSessionsTyped("architect")

        val surfaced = when (result) {
            is ScopedSessionsResult.StaleSelection -> result.notice
            is ScopedSessionsResult.Error -> result.exception.message ?: ""
            is ScopedSessionsResult.Success -> ""
        }
        assertFalse("Cookie-like token must never surface", surfaced.contains("sess-hijack-cookie"))
        assertFalse("IP must never surface", surfaced.contains("10.1.2.3"))
        assertFalse("Ticket-like value must never surface", surfaced.contains("hermes-gateway-ticket"))
        assertFalse("Raw detail text must never surface verbatim", surfaced.contains("connect to"))
    }

    @Test
    fun typed_404_genericDetail_keepsFailClosedError() = runBlocking {
        vault.saveSelectedContextProfile("architect")
        fakeTransport.scopedResponse = HttpResponse(
            404,
            body = """{"detail":"Route not found"}"""
        )

        val result = contextProfileRepo.getScopedSessionsTyped("architect")

        assertTrue("404 without profile-gone detail must stay fail-closed", result is ScopedSessionsResult.Error)
        val error = result as ScopedSessionsResult.Error
        assertEquals(
            "Generic 404 mapping must be preserved",
            "Context profiles service unavailable (HTTP 404)",
            error.exception.message
        )
    }

    @Test
    fun typed_500_keepsExistingFailClosedMapping() = runBlocking {
        vault.saveSelectedContextProfile("architect")
        fakeTransport.scopedResponse = HttpResponse(500, body = "Server Error")

        val result = contextProfileRepo.getScopedSessionsTyped("architect")

        assertTrue("500 must stay an Error", result is ScopedSessionsResult.Error)
        val error = result as ScopedSessionsResult.Error
        assertTrue("500 must remain IllegalStateException", error.exception is IllegalStateException)
        assertEquals(
            "500 must keep the existing generic message",
            "Context profiles service unavailable (HTTP 500)",
            error.exception.message
        )
    }

    @Test
    fun typed_401_keepsSecurityExceptionMapping() = runBlocking {
        vault.saveSelectedContextProfile("architect")
        fakeTransport.scopedResponse = HttpResponse(401, body = "Unauthorized")

        val result = contextProfileRepo.getScopedSessionsTyped("architect")

        assertTrue("401 must stay an Error", result is ScopedSessionsResult.Error)
        val err = result as ScopedSessionsResult.Error
        assertTrue("401 must remain SecurityException", err.exception is SecurityException)
        assertEquals(
            "Authentication expired or unauthorized (401)",
            err.exception.message
        )
    }

    @Test
    fun listSessions_404StaleProfile_clearsPreference_andFallsBackToUnscoped() = runBlocking {
        vault.saveSelectedContextProfile("architect")
        val unscopedSummary = SessionSummary("unscoped-1", "Unscoped", null, 500.0, 1, null)
        fakeAdapter.listSessionsResult = GatewayResult.Success(SessionListResult(listOf(unscopedSummary)))
        fakeTransport.scopedResponse = HttpResponse(
            404,
            body = staleDetailBody("Profile 'architect' does not exist.")
        )

        val result = listWithShellRecovery()

        assertTrue("Self-heal must recover via unscoped fallback", result.isSuccess)
        val outcome = result.getOrThrow()
        assertEquals("Fallback must serve unscoped sessions", listOf("unscoped-1"), outcome.sessions.map { it.id })
        assertNull("Outcome must be unscoped (no profile selected)", outcome.selectedProfileName)

        // The stale preference must be cleared (self-heal).
        assertNull("Stale selected profile must be cleared", vault.getSelectedContextProfile())

        // Both requests dispatched: scoped (404) then unscoped adapter path.
        assertTrue(
            "Scoped fetch must have been attempted first",
            fakeTransport.recordedRequests.any { it.url.contains("/api/profiles/sessions") }
        )
    }

    @Test
    fun listSessions_404StaleProfile_exposesDistinctSafeNotice() = runBlocking {
        vault.saveSelectedContextProfile("architect")
        fakeAdapter.listSessionsResult = GatewayResult.Success(
            SessionListResult(listOf(SessionSummary("unscoped-1", "Unscoped", null, 500.0, 1, null)))
        )
        fakeTransport.scopedResponse = HttpResponse(
            404,
            body = staleDetailBody("Profile 'architect' does not exist.")
        )

        val result = listWithShellRecovery()

        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertEquals(
            "Stale-profile recovery must surface the fixed distinct notice",
            ScopedSessionsResult.PROFILE_GONE_NOTICE,
            outcome.staleProfileNotice
        )
    }

    @Test
    fun listSessions_400StaysFailing_withGenericMessage() = runBlocking {
        vault.saveSelectedContextProfile("architect")
        fakeAdapter.listSessionsResult = GatewayResult.Success(
            SessionListResult(listOf(SessionSummary("unscoped-1", "Unscoped", null, 500.0, 1, null)))
        )
        fakeTransport.scopedResponse = HttpResponse(
            400,
            body = staleDetailBody("Profile 'architect' does not exist.")
        )

        val result = sessionRepository.listSessions()

        assertTrue("400 must keep the fail-closed failure", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(
            "400 must preserve the generic scoped-fetch message",
            "Context profiles service unavailable (HTTP 400)",
            ex!!.message
        )
        // Fail-closed: the preference is NOT cleared on 400.
        assertEquals("Preference must not be cleared on 400", "architect", vault.getSelectedContextProfile())
    }

    @Test
    fun listSessions_non404400Errors_unchangedFailClosed() = runBlocking {
        vault.saveSelectedContextProfile("architect")
        fakeTransport.scopedResponse = HttpResponse(500, body = "Server Error")

        val result = sessionRepository.listSessions()

        assertTrue("500 must keep failing closed", result.isFailure)
        assertEquals(
            "Preference must not be cleared on non-404/400 failures",
            "architect",
            vault.getSelectedContextProfile()
        )
        assertTrue(
            "Unscoped adapter path must NOT run for non-self-heal failures",
            fakeTransport.recordedRequests.none { it.url.contains("/api/gateway") }
        )
    }

    @Test
    fun listSessions_404StaleProfile_doesNotRecordHostDetailInDiagnostics() = runBlocking {
        vault.saveSelectedContextProfile("architect")
        fakeAdapter.listSessionsResult = GatewayResult.Success(
            SessionListResult(listOf(SessionSummary("unscoped-1", "Unscoped", null, 500.0, 1, null)))
        )
        // Profile-gone semantics PLUS hostile payload (cookie-like token, IP,
        // ticket-like value). The self-heal must fire on the typed signal while
        // the hostile text never surfaces in the notice or recorded metadata.
        val hostileDetail = "Profile 'architect' does not exist. (session_id=sess-leak-cookie, 10.9.8.7, ticket hermes-gateway-ticket.ZZZ)"
        fakeTransport.scopedResponse = HttpResponse(
            404,
            body = staleDetailBody(hostileDetail)
        )

        val result = listWithShellRecovery()

        assertTrue("Self-heal must still recover despite hostile detail", result.isSuccess)
        val outcome = result.getOrThrow()
        // The surfaced notice is the fixed constant — none of the hostile text.
        assertEquals(ScopedSessionsResult.PROFILE_GONE_NOTICE, outcome.staleProfileNotice)
        assertFalse(outcome.staleProfileNotice!!.contains("sess-leak-cookie"))
        assertFalse(outcome.staleProfileNotice!!.contains("10.9.8.7"))
        assertFalse(outcome.staleProfileNotice!!.contains("hermes-gateway-ticket"))
        // The full hostile body must not be recoverable from any recorded
        // request metadata.
        val allRequests = fakeTransport.recordedRequests.joinToString { "${it.url} ${it.headers} ${it.body}" }
        assertFalse(allRequests.contains(hostileDetail))
    }

    /** Same terminal fallback contract, now through the required shared shell reset. */
    private suspend fun listWithShellRecovery(): Result<SessionListOutcome> {
        val initial = sessionRepository.listSessions()
        val request = initial.getOrThrow().recoveryRequirement ?: return initial
        assertEquals("architect", vault.getSelectedContextProfile())
        assertTrue(initial.getOrThrow().sessions.isEmpty())
        assertNull(initial.getOrThrow().staleProfileNotice)
        val sessions = io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel(sessionRepository)
        val owner = io.github.anshuman2401.hermesmobile.navigation.ChatRouteOwnerViewModel { io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel(it) }
        owner.get("synthetic-old")
        val job = sessions.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!
        try {
            val result = io.github.anshuman2401.hermesmobile.navigation.performWorkspaceRecovery(request, sessions.captureWorkspaceIntent(), contextProfileRepo,
                owner, sessions, { sessions.resetWorkspace() })
            assertEquals(WorkspaceRecoveryApplyResult.Applied, result)
            assertTrue(owner.activeKeys().isEmpty())
            val state = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5000) { sessions.uiState.first { !it.isLoading } }
            }
            assertNull(state.error)
            return Result.success(SessionListOutcome(state.sessions, state.isOfflineCache, state.selectedProfileName, state.staleProfileNotice))
        } finally {
            androidx.lifecycle.ViewModelStore().apply { put("sessions", sessions); clear() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.withTimeout(5000) { job.join() } }
            owner.activeKeys().forEach(owner::clear)
        }
    }
}