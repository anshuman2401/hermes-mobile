package io.github.anshuman2401.hermesmobile.feature.connect

import io.github.anshuman2401.hermesmobile.design.AtelierLight
import io.github.anshuman2401.hermesmobile.design.AtelierDark
import io.github.anshuman2401.hermesmobile.design.ProfileStatusBadgePolicy
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.HostStatus
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket
import io.github.anshuman2401.hermesmobile.data.local.SignOutCacheChoice
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic boundary tests: no host, credentials, persistence or real time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectReconnectRecoveryTest {
    @get:Rule val main = MainDispatcherRule()
    private val store = ViewModelStore()
    private val fake = FakeGatewayProtocolAdapter().apply {
        connectionStatus.value = ConnectionStatus.OFFLINE
    }
    private val terminal = MutableStateFlow<GatewayFailure?>(null)
    private var preflight: CompletableDeferred<Result<HostStatus>>? = null
    private var statusResult = Result.success(HostStatus("fixture", true, true))
    private var validSession = true
    private var connectResult = true
    private val adapter = object : GatewayProtocolAdapter by fake {
        override val terminalFailure = terminal
        override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean {
            fake.connectionStatus.value = if (connectResult) ConnectionStatus.CONNECTED else ConnectionStatus.FAILED
            return connectResult
        }
        override fun disconnect() { fake.connectionStatus.value = ConnectionStatus.OFFLINE }
    }
    private val auth = object : AuthRepository {
        override suspend fun checkStatus(origin: CanonicalOrigin) = preflight?.await() ?: statusResult
        override suspend fun hasValidSession(origin: CanonicalOrigin?) = validSession
        override suspend fun mintWebSocketTicket(origin: CanonicalOrigin) = Result.success(GatewayTicket("synthetic-ticket"))
        override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
        override suspend fun notifyAuthExpired() {}
    }
    private fun vm(): ConnectViewModel = ConnectViewModel(SavedStateHandle(), auth, adapter).also {
        store.put("connect", it)
        it.updateHost("fixture.invalid")
        it.updatePort("9119")
    }
    private fun connected() = vm().also {
        it.testConnection()
        assertEquals(ConnectState.CONNECTED_COMPATIBLE, it.uiState.value.state)
    }
    private fun status(value: ConnectionStatus) { fake.connectionStatus.value = value }
    @After fun close() { store.clear() }

    private fun assertDot(vm: ConnectViewModel, connected: Boolean) {
        val isConnected = vm.uiState.value.state == ConnectState.CONNECTED_COMPATIBLE
        for (palette in listOf(AtelierLight, AtelierDark)) {
            assertEquals(if (connected) palette.connected else palette.destructive,
                ProfileStatusBadgePolicy.dotColor(isConnected, palette.connected, palette.destructive))
        }
        assertEquals(if (connected) "Host connected" else "Host not connected",
            ProfileStatusBadgePolicy.statusDescription(isConnected))
    }

    @Test fun failedAndOfflineDropsRecoverWithoutRestartAcrossRepeatedConnections() {
        val vm = connected()
        assertDot(vm, true)
        for (drop in listOf(ConnectionStatus.FAILED, ConnectionStatus.OFFLINE, ConnectionStatus.FAILED)) {
            status(drop)
            assertDot(vm, false)
            assertEquals(ConnectState.HOST_UNREACHABLE, vm.uiState.value.state)
            status(ConnectionStatus.CONNECTING)
            assertDot(vm, false)
            assertEquals(ConnectState.HOST_UNREACHABLE, vm.uiState.value.state)
            status(ConnectionStatus.CONNECTED)
            assertDot(vm, true)
            assertEquals(ConnectState.CONNECTED_COMPATIBLE, vm.uiState.value.state)
            assertEquals(null, vm.uiState.value.errorMessage)
        }
        assertEquals(0, fake.submitPromptCallCount)
    }

    @Test fun lateConnectedAfterExplicitDisconnectCannotRestoreUi() {
        val vm = connected()
        status(ConnectionStatus.FAILED)
        vm.disconnect(SignOutCacheChoice.KEEP)
        status(ConnectionStatus.CONNECTED)
        assertEquals(ConnectState.FIRST_LAUNCH, vm.uiState.value.state)
    }

    @Test fun lateConnectedInAddProfileModeCannotRestoreUi() {
        val vm = connected()
        status(ConnectionStatus.FAILED)
        vm.enterAddProfileMode()
        status(ConnectionStatus.CONNECTED)
        assertEquals(ConnectState.FIRST_LAUNCH, vm.uiState.value.state)
    }

    @Test fun authRequiredAfterDropIsNotClearedByConnectedSignal() {
        val vm = connected()
        status(ConnectionStatus.FAILED)
        status(ConnectionStatus.AUTHENTICATION_REQUIRED)
        status(ConnectionStatus.CONNECTED)
        assertEquals(ConnectState.AUTH_REQUIRED, vm.uiState.value.state)
    }

    @Test fun supersedingPreflightOwnsTransitionsAndItsFailureCannotBeRecoveredByOldSignal() {
        val vm = connected()
        status(ConnectionStatus.FAILED)
        val pending = CompletableDeferred<Result<HostStatus>>()
        preflight = pending
        vm.testConnection()
        status(ConnectionStatus.CONNECTED)
        assertEquals(ConnectState.PREFLIGHT_LOADING, vm.uiState.value.state)
        pending.complete(Result.failure(IllegalStateException("synthetic unavailable")))
        assertEquals(ConnectState.HOST_UNREACHABLE, vm.uiState.value.state)
        status(ConnectionStatus.CONNECTING)
        status(ConnectionStatus.CONNECTED)
        assertEquals(ConnectState.HOST_UNREACHABLE, vm.uiState.value.state)
    }

    @Test fun initialPreflightFailureDoesNotAdmitUnrelatedConnectedSignal() {
        statusResult = Result.failure(IllegalStateException("synthetic unavailable"))
        val vm = vm()
        vm.testConnection()
        status(ConnectionStatus.CONNECTED)
        assertEquals(ConnectState.HOST_UNREACHABLE, vm.uiState.value.state)
    }

    @Test fun failedInitialHandshakeDoesNotAdmitConnectedSignal() {
        connectResult = false
        val vm = vm()
        vm.testConnection()
        status(ConnectionStatus.CONNECTED)
        assertEquals(ConnectState.PROTOCOL_INCOMPATIBLE, vm.uiState.value.state)
    }

    @Test fun terminalProtocolRejectionDoesNotRestoreUi() {
        val vm = connected()
        status(ConnectionStatus.FAILED)
        terminal.value = GatewayFailure.IncompatibleProtocol("synthetic incompatibility")
        status(ConnectionStatus.CONNECTED)
        assertEquals(ConnectState.HOST_UNREACHABLE, vm.uiState.value.state)
    }
}
