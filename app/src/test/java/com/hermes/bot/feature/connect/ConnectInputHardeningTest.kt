package com.hermes.bot.feature.connect

import androidx.lifecycle.SavedStateHandle
import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.auth.HostStatus
import com.hermes.bot.data.local.SignOutCacheChoice
import com.hermes.bot.data.diagnostics.CatalogEvent
import com.hermes.bot.data.diagnostics.DiagnosticField
import com.hermes.bot.data.diagnostics.DiagnosticKey
import com.hermes.bot.data.diagnostics.DiagnosticMetadata
import com.hermes.bot.data.diagnostics.Diagnostics
import com.hermes.bot.data.gateway.GatewayCapabilities
import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.GatewayPingResult
import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.GatewayTicket
import com.hermes.bot.data.gateway.HistoryFallbackSignal
import com.hermes.bot.data.gateway.InterruptSessionResult
import com.hermes.bot.data.gateway.PromptSubmitResult
import com.hermes.bot.data.gateway.ReplayEventsResult
import com.hermes.bot.data.gateway.SessionCreateResult
import com.hermes.bot.data.gateway.SessionListResult
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.origin.ConnectProtocol
import com.hermes.bot.data.origin.StructuredEndpointValidator
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * CHG-004: Connect form input hardening and honest login errors.
 * Acceptance rows A1 (host trim), A2 (port trim), A4 (honest login error vocabulary).
 * Fakes follow the seam pattern used by ControlledConnectIntegrationTest /
 * DefaultAuthRepositoryTest: real ConnectViewModel with fake boundary implementations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectInputHardeningTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val emittedEvents = mutableListOf<Pair<CatalogEvent, DiagnosticMetadata?>>()

    private val recordingDiagnostics = object : Diagnostics {
        override fun emit(event: CatalogEvent, metadata: DiagnosticMetadata) {
            emittedEvents.add(event to metadata)
        }
    }

    private open class FakeAuthRepository : AuthRepository {
        val checkStatusOrigins = mutableListOf<String>()
        var loginException: Throwable? = null

        override suspend fun checkStatus(origin: CanonicalOrigin): Result<HostStatus> {
            checkStatusOrigins.add(origin.originUrl)
            return Result.success(HostStatus(version = "fixture", authRequired = true, gatewayRunning = true))
        }

        override suspend fun hasValidSession(origin: CanonicalOrigin?): Boolean = false

        override suspend fun login(
            origin: CanonicalOrigin,
            username: String,
            password: CharArray,
            provider: String
        ): Result<AuthCredential> {
            val ex = loginException ?: return Result.failure(IllegalStateException("fake default failure"))
            return Result.failure(ex)
        }

        override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
        override suspend fun notifyAuthExpired() {}
    }

    private class FakeGatewayAdapter : GatewayProtocolAdapter {
        override val connectionStatus: StateFlow<ConnectionStatus> =
            MutableStateFlow(ConnectionStatus.OFFLINE)
        override val events: Flow<GatewayEvent> = emptyFlow()
        override val historyFallbackSignals: Flow<HistoryFallbackSignal> = emptyFlow()
        override val currentReplayEpoch: String? = null

        override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean) = false
        override fun disconnect() {}
        override suspend fun listSessions(limit: Int): GatewayResult<SessionListResult> = notConnected()
        override suspend fun listActiveSessions(): GatewayResult<com.hermes.bot.data.gateway.ActiveSessionListResult> = notConnected()
        override suspend fun createSession(title: String?, source: String, closeOnDisconnect: Boolean, profile: String?, model: String?, reasoningEffort: String?): GatewayResult<SessionCreateResult> = notConnected()
        override suspend fun resumeSession(sessionId: String, profile: String?): GatewayResult<SessionResumeResult> = notConnected()
        override suspend fun submitPrompt(sessionId: String, text: String): GatewayResult<PromptSubmitResult> = notConnected()
        override suspend fun interruptSession(sessionId: String): GatewayResult<InterruptSessionResult> = notConnected()
        override suspend fun replayEventsSince(sessionId: String, lastSeen: Long, durableSessionId: String?): GatewayResult<ReplayEventsResult> = notConnected()
        override suspend fun getCapabilities(): GatewayResult<GatewayCapabilities> = notConnected()
        override suspend fun ping(): GatewayResult<GatewayPingResult> = notConnected()
        override fun getLastSeenSeq(sessionId: String): Long? = null
        override fun setLastSeenSeq(sessionId: String, seq: Long) {}
        override fun clearWatermark(sessionId: String) {}
        override fun markHistoryRehydrated(sessionId: String) {}

        private fun <T> notConnected(): GatewayResult<T> =
            GatewayResult.Failure(GatewayFailure.NotConnected())
    }

    private fun makeViewModel(repository: AuthRepository): ConnectViewModel =
        ConnectViewModel(
            savedStateHandle = SavedStateHandle(),
            authRepository = repository,
            gatewayAdapter = FakeGatewayAdapter(),
            diagnostics = recordingDiagnostics
        )

    /** Under MainDispatcherRule the viewModelScope runs eagerly and synchronously. */
    private fun driveToAuthRequired(
        viewModel: ConnectViewModel,
        scope: kotlinx.coroutines.test.TestScope,
        host: String = "192.0.2.10",
        port: String = "9119"
    ) {
        viewModel.updateHost(host)
        viewModel.updatePort(port)
        viewModel.testConnection()
        scope.advanceUntilIdle()
        assertEquals(ConnectState.AUTH_REQUIRED, viewModel.uiState.value.state)
    }

    private fun submitLogin(viewModel: ConnectViewModel, scope: kotlinx.coroutines.test.TestScope) {
        viewModel.updateUsername("admin")
        viewModel.updatePassword("secret")
        viewModel.login()
        scope.advanceUntilIdle()
    }

    private fun loginFailureMetadata(): DiagnosticMetadata? =
        emittedEvents.lastOrNull { it.first == CatalogEvent.LOGIN_FAILED }?.second

    private fun metadataFieldValues(metadata: DiagnosticMetadata?): Map<String, String> {
        assertTrue(metadata != null)
        val field = DiagnosticMetadata::class.java.getDeclaredField("values")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(metadata) as Map<DiagnosticKey, DiagnosticField>
        return map.entries.associate { it.key.wireName to it.value.value }
    }

    private fun assertNoSensitiveLeak(message: String?) {
        assertTrue("loginError must be present", message != null)
        val forbidden = listOf("refused", "malformed response body", "denied by local policy", "took too long", "100.98")
        forbidden.forEach { fragment ->
            assertTrue(
                "Login copy leaked raw exception/host detail ('$fragment'): $message",
                !message!!.contains(fragment, ignoreCase = true)
            )
        }
    }

    // ---------- A1: host draft with surrounding whitespace parses; preflight uses trimmed origin ----------

    @Test
    fun a1_hostDraftWithSurroundingWhitespaceParsesAndPreflightUsesTrimmedOrigin() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = makeViewModel(repository)
        viewModel.updatePort("9119")
        driveToAuthRequired(viewModel, this, host = "  192.0.2.10  ", port = "9119")

        val state = viewModel.uiState.value
        assertNull(state.hostError)
        // Visible draft preserved exactly as typed.
        assertEquals("  192.0.2.10  ", state.hostDraft)
        // Preflight used the trimmed origin.
        val expected = (StructuredEndpointValidator.parse(ConnectProtocol.HTTP, "192.0.2.10", "9119")
            as StructuredEndpointValidator.ValidationResult.Success).origin.originUrl
        assertEquals(listOf(expected), repository.checkStatusOrigins)
    }

    // ---------- A2: port draft with surrounding whitespace parses ----------

    @Test
    fun a2_portDraftWithSurroundingWhitespaceParses() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = makeViewModel(repository)
        viewModel.updateHost("192.0.2.10")
        driveToAuthRequired(viewModel, this, host = "192.0.2.10", port = " 9119 ")

        val state = viewModel.uiState.value
        assertNull(state.portError)
        assertNull(state.hostError)
        assertEquals(" 9119 ", state.portDraft) // visible draft unchanged
        assertEquals(1, repository.checkStatusOrigins.size)
    }

    // ---------- A4: honest login errors (three exception-class variants + catch-all) ----------

    @Test
    fun a4_connectExceptionShowsNetworkErrorParenthetical() = runTest {
        val repository = FakeAuthRepository()
        repository.loginException = java.net.ConnectException("Connection refused")
        val viewModel = makeViewModel(repository)
        driveToAuthRequired(viewModel, this)
        submitLogin(viewModel, this)

        assertEquals("Login failed (network error: ConnectException). Please try again.", viewModel.uiState.value.loginError)
        assertNoSensitiveLeak(viewModel.uiState.value.loginError)
    }

    @Test
    fun a4_securityExceptionShowsSecurityCheckFailedParenthetical() = runTest {
        val repository = FakeAuthRepository()
        repository.loginException = SecurityException("denied by local policy")
        val viewModel = makeViewModel(repository)
        driveToAuthRequired(viewModel, this)
        submitLogin(viewModel, this)

        assertEquals("Login failed (security check failed: SecurityException). Please try again.", viewModel.uiState.value.loginError)
        assertNoSensitiveLeak(viewModel.uiState.value.loginError)
    }

    @Test
    fun a4_illegalStateExceptionShowsHostResponseInvalidParenthetical() = runTest {
        val repository = FakeAuthRepository()
        repository.loginException = IllegalStateException("malformed response body")
        val viewModel = makeViewModel(repository)
        driveToAuthRequired(viewModel, this)
        submitLogin(viewModel, this)

        assertEquals("Login failed (host response invalid: IllegalStateException). Please try again.", viewModel.uiState.value.loginError)
        assertNoSensitiveLeak(viewModel.uiState.value.loginError)
    }

    @Test
    fun a4_otherExceptionShowsUnexpectedErrorParenthetical() = runTest {
        val repository = FakeAuthRepository()
        repository.loginException = java.util.concurrent.TimeoutException("took too long")
        val viewModel = makeViewModel(repository)
        driveToAuthRequired(viewModel, this)
        submitLogin(viewModel, this)

        assertEquals("Login failed (unexpected error: TimeoutException). Please try again.", viewModel.uiState.value.loginError)
        assertNoSensitiveLeak(viewModel.uiState.value.loginError)
    }

    @Test
    fun a4_existingSpecificLoginMessagesAreUnchanged() = runTest {
        data class Case(val exception: Throwable, val expected: String)
        listOf(
            Case(SecurityException("HTTP 401 Unauthorized"), "Invalid username or password."),
            Case(SecurityException("HTTP 403 Forbidden"), "Access denied. Check your credentials."),
            Case(SecurityException("Preflight failed: host unreachable"), "Host became unreachable. Check your connection and try again."),
            Case(SecurityException("Login ok but no valid session cookie returned"), "Authentication succeeded but the host returned no session cookie. Contact your host administrator.")
        ).forEach { case ->
            emittedEvents.clear()
            val repository = FakeAuthRepository().apply { loginException = case.exception }
            val viewModel = makeViewModel(repository)
            driveToAuthRequired(viewModel, this)
            submitLogin(viewModel, this)
            assertEquals(case.expected, viewModel.uiState.value.loginError)
        }
    }

    @Test
    fun a4_loginFailureDiagnosticsCarriesExceptionClassMetadata() = runTest {
        val repository = FakeAuthRepository()
        repository.loginException = java.net.ConnectException("Connection refused")
        val viewModel = makeViewModel(repository)
        driveToAuthRequired(viewModel, this)
        submitLogin(viewModel, this)

        val values = metadataFieldValues(loginFailureMetadata())
        assertEquals("ConnectException", values["exception_class"])
    }
}
