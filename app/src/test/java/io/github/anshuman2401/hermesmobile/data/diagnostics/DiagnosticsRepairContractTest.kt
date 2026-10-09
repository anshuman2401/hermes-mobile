package io.github.anshuman2401.hermesmobile.data.diagnostics

import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.HostStatus
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.SignOutCacheChoice
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.feature.connect.ConnectState
import io.github.anshuman2401.hermesmobile.feature.connect.ConnectViewModel
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Executes production ViewModel and diagnostics behavior with fakes only at external boundaries. */
class DiagnosticsRepairContractTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private class RecordingDiagnostics : Diagnostics {
        val events = mutableListOf<CatalogEvent>()
        override fun emit(event: CatalogEvent, metadata: DiagnosticMetadata) { events += event }
    }

    private class TestAuthRepository : AuthRepository {
        var checkStatusCalls = 0
        var delayMs: Long = 0
        var sessionDelayMs: Long = 0
        var loginCalls = 0
        var statusResult: Result<HostStatus> = Result.success(HostStatus("1.0", false, true))
        var hasSession: Boolean = false
        var loginStarted: CountDownLatch? = null
        var releaseLogin: CountDownLatch? = null
        var loginResult: Result<AuthCredential> = Result.failure(UnsupportedOperationException())
        var ticketResult: Result<GatewayTicket> = Result.success(GatewayTicket("fake-ticket", 60))
        var ticketCalls = 0

        override suspend fun checkStatus(origin: CanonicalOrigin): Result<HostStatus> {
            checkStatusCalls++
            if (delayMs > 0) delay(delayMs)
            return statusResult
        }
        override suspend fun hasValidSession(origin: CanonicalOrigin?): Boolean {
            if (sessionDelayMs > 0) delay(sessionDelayMs)
            return hasSession
        }
        override suspend fun mintWebSocketTicket(origin: CanonicalOrigin): Result<GatewayTicket> {
            ticketCalls++
            return ticketResult
        }
        override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) = Unit
        override suspend fun notifyAuthExpired() = Unit
        override suspend fun login(origin: CanonicalOrigin, username: String, password: CharArray, provider: String): Result<AuthCredential> {
            loginCalls++
            loginStarted?.countDown()
            releaseLogin?.let { release ->
                return suspendCoroutine { continuation ->
                    Thread {
                        release.await(2, TimeUnit.SECONDS)
                        continuation.resume(loginResult)
                    }.start()
                }
            }
            return loginResult
        }
    }

    @Test fun structuredDisplay_parsesEscapedValues_andRejectsMalformedRecords() {
        val escaped = "{\"v\":1,\"ts\":\"2026-09-16T12:00:00Z\",\"severity\":\"INFO\",\"code\":\"test_code\",\"component\":\"test\",\"message\":\"Said \\\"hello\\\"\"}"
        assertTrue(DiagnosticRedactor.structuredDisplay(escaped).contains("Said \"hello\""))
        assertEquals("[malformed record]", DiagnosticRedactor.structuredDisplay("{not json}"))
    }

    @Test fun lifecycleDisconnect_emitsGatewayDisconnected() {
        val diagnostics = RecordingDiagnostics()
        val viewModel = ConnectViewModel(diagnostics = diagnostics, gatewayAdapter = FakeGatewayProtocolAdapter())
        viewModel.disconnect(SignOutCacheChoice.KEEP)
        assertTrue(diagnostics.events.contains(CatalogEvent.GATEWAY_DISCONNECTED))
    }

    @Test fun repeatedConnectionSubmit_doesNotOverlapPreflightWork() = runBlocking {
        val auth = TestAuthRepository().apply { delayMs = 100 }
        val viewModel = ConnectViewModel(authRepository = auth, gatewayAdapter = FakeGatewayProtocolAdapter())
        viewModel.updateHost("127.0.0.1")
        viewModel.updatePort("8080")
        viewModel.testConnection()
        viewModel.testConnection()
        delay(250)
        assertEquals(1, auth.checkStatusCalls)
    }

    @Test fun preflightFailure_publishesFailureStateInsteadOfLeavingLoading() = runBlocking {
        val auth = TestAuthRepository().apply { statusResult = Result.failure(IllegalStateException("offline")) }
        val viewModel = ConnectViewModel(authRepository = auth, gatewayAdapter = FakeGatewayProtocolAdapter())
        viewModel.updateHost("127.0.0.1")
        viewModel.updatePort("8080")
        viewModel.testConnection()
        delay(100)
        assertEquals(ConnectState.HOST_UNREACHABLE, viewModel.uiState.value.state)
        assertFalse(viewModel.uiState.value.errorMessage.isNullOrBlank())
    }

    @Test fun login_doesNotOverlapActiveSessionAuthentication() = runBlocking {
        val auth = TestAuthRepository().apply { sessionDelayMs = 200 }
        val viewModel = ConnectViewModel(authRepository = auth, gatewayAdapter = FakeGatewayProtocolAdapter())
        viewModel.updateHost("127.0.0.1")
        viewModel.updatePort("8080")
        viewModel.updateUsername("alice")
        viewModel.updatePassword("secret")
        viewModel.transitionForTest(ConnectState.AUTH_REQUIRED)

        viewModel.authenticate()
        delay(30)
        viewModel.login()
        delay(100)

        assertEquals(0, auth.loginCalls)
    }

    @Test fun disconnectedNonCooperativeLogin_cannotStartGatewayTicketOrPublishLoginState() = runBlocking {
        val loginStarted = CountDownLatch(1)
        val releaseLogin = CountDownLatch(1)
        val auth = TestAuthRepository().apply {
            this.loginStarted = loginStarted
            this.releaseLogin = releaseLogin
            this.loginResult = Result.success(AuthCredential("cookie_session", "basic", emptyMap()))
        }
        val viewModel = ConnectViewModel(authRepository = auth, gatewayAdapter = FakeGatewayProtocolAdapter())
        viewModel.updateHost("127.0.0.1")
        viewModel.updatePort("8080")
        viewModel.updateUsername("alice")
        viewModel.updatePassword("secret")
        viewModel.transitionForTest(ConnectState.AUTH_REQUIRED)

        viewModel.login()
        assertTrue(loginStarted.await(2, TimeUnit.SECONDS))
        viewModel.disconnect(SignOutCacheChoice.KEEP)
        releaseLogin.countDown()
        delay(150)

        assertEquals(0, auth.ticketCalls)
        assertEquals(ConnectState.FIRST_LAUNCH, viewModel.uiState.value.state)
        assertFalse(viewModel.uiState.value.loginLoading)
    }

    @Test fun successfulAuthenticatedConnection_recordsAuthTicketGatewayCapabilitiesAndProfileLifecycle() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val auth = TestAuthRepository().apply {
            statusResult = Result.success(HostStatus("1.0", authRequired = true, gatewayRunning = true))
            loginResult = Result.success(AuthCredential("cookie_session", "basic", emptyMap()))
        }
        val viewModel = ConnectViewModel(
            authRepository = auth,
            gatewayAdapter = FakeGatewayProtocolAdapter(),
            diagnostics = diagnostics
        )
        viewModel.updateHost("127.0.0.1")
        viewModel.updatePort("8080")
        viewModel.testConnection()
        delay(100)
        viewModel.updateUsername("alice")
        viewModel.updatePassword("secret")
        viewModel.login()
        delay(150)

        assertTrue(
            diagnostics.events.containsAll(
                listOf(
                    CatalogEvent.CONNECT_SUBMIT,
                    CatalogEvent.PREFLIGHT_START,
                    CatalogEvent.PREFLIGHT_OK,
                    CatalogEvent.AUTH_REQUIRED,
                    CatalogEvent.LOGIN_START,
                    CatalogEvent.LOGIN_OK,
                    CatalogEvent.TICKET_START,
                    CatalogEvent.TICKET_OK,
                    CatalogEvent.GATEWAY_CONNECT_START,
                    CatalogEvent.GATEWAY_READY,
                    CatalogEvent.GATEWAY_CAPABILITIES_OK,
                    CatalogEvent.PROFILE_SAVE_OK
                )
            )
        )
    }

    @Test fun ticketFailure_recordsFailureWithoutLeavingGatewayWorkActive() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val auth = TestAuthRepository().apply {
            hasSession = true
            ticketResult = Result.failure(IllegalStateException("ticket unavailable"))
        }
        val viewModel = ConnectViewModel(
            authRepository = auth,
            gatewayAdapter = FakeGatewayProtocolAdapter(),
            diagnostics = diagnostics
        )
        viewModel.updateHost("127.0.0.1")
        viewModel.updatePort("8080")
        viewModel.testConnection()
        delay(150)

        assertTrue(diagnostics.events.contains(CatalogEvent.TICKET_FAILED))
        assertEquals(ConnectState.HOST_UNREACHABLE, viewModel.uiState.value.state)
    }
}
