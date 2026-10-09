package io.github.anshuman2401.hermesmobile.feature.connect

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.github.anshuman2401.hermesmobile.data.auth.*
import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/** Synthetic controlled boundaries; no device, private host or platform-key emulation. */
class ConnectStartupTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()
    private val store = ViewModelStore()
    private lateinit var vault: EncryptedVault
    private val fake = FakeGatewayProtocolAdapter().apply { connectionStatus.value = ConnectionStatus.OFFLINE }
    private var validSession = true
    private var sessionGate: CompletableDeferred<Unit>? = null
    private var ticketGate: CompletableDeferred<Unit>? = null
    private var connectGate: CompletableDeferred<Unit>? = null
    private var ticketError: Exception? = null
    private var tickets = 0
    private var connects = 0
    private val auth = object : AuthRepository {
        override suspend fun hasValidSession(origin: CanonicalOrigin?): Boolean { sessionGate?.await(); return validSession }
        override suspend fun checkStatus(origin: CanonicalOrigin) = Result.success(HostStatus("fixture", true, true))
        override suspend fun mintWebSocketTicket(origin: CanonicalOrigin): Result<GatewayTicket> {
            tickets++
            ticketGate?.await()
            return ticketError?.let { Result.failure(it) } ?: Result.success(GatewayTicket("synthetic-ticket-$tickets"))
        }
        override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
        override suspend fun notifyAuthExpired() {}
    }
    private val adapter = object : GatewayProtocolAdapter by fake {
        override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean {
            connects++
            connectGate?.await()
            fake.connectionStatus.value = ConnectionStatus.CONNECTED
            return true
        }
        override fun disconnect() { fake.connectionStatus.value = ConnectionStatus.OFFLINE }
    }
    @Before fun setup() = runBlocking {
        vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), main.testDispatcher)
        vault.saveConnectionProfile(ConnectionProfile("synthetic-profile", "http://fixture.invalid:9119", "Hermes"))
        vault.setActiveProfileId("synthetic-profile")
        Unit
    }
    @After fun close() { store.clear() }
    private fun vm(storage: VaultStorage = vault, handle: SavedStateHandle = SavedStateHandle()) =
        ConnectViewModel(handle, auth, adapter, storage).also { store.put("connect", it) }

    @Test fun savedLoginAdmitsSessionsBeforeTicketAndHandshakeComplete() {
        ticketGate = CompletableDeferred()
        connectGate = CompletableDeferred()
        val vm = vm()
        assertEquals(StartupPresentation.SESSIONS, vm.uiState.value.startupPresentation)
        assertEquals(ConnectState.PREFLIGHT_LOADING, vm.uiState.value.state)
        assertEquals(0, connects)
        ticketGate!!.complete(Unit)
        assertEquals(ConnectState.PREFLIGHT_LOADING, vm.uiState.value.state)
        connectGate!!.complete(Unit)
        assertEquals(ConnectState.CONNECTED_COMPATIBLE, vm.uiState.value.state)
        assertEquals(StartupPresentation.SESSIONS, vm.uiState.value.startupPresentation)
        assertEquals(1, tickets)
        assertEquals(1, connects)
        assertEquals(0, fake.submitPromptCallCount)
    }
    @Test fun resolvingCredentialNeverExposesHostOrPasswordPresentation() {
        sessionGate = CompletableDeferred()
        ticketGate = CompletableDeferred()
        val vm = vm()
        assertEquals(StartupPresentation.LOADING, vm.uiState.value.startupPresentation)
        sessionGate!!.complete(Unit)
        assertEquals(StartupPresentation.SESSIONS, vm.uiState.value.startupPresentation)
        assertEquals(ConnectState.PREFLIGHT_LOADING, vm.uiState.value.state)
    }
    @Test fun persistedConnectedAndPasswordStatesAreNeverTrusted() {
        sessionGate = CompletableDeferred()
        for (state in listOf(ConnectState.CONNECTED_COMPATIBLE, ConnectState.AUTH_REQUIRED, ConnectState.PREFLIGHT_LOADING)) {
            val vm = vm(handle = SavedStateHandle(mapOf(ConnectViewModel.KEY_STATE to state.name)))
            assertEquals(ConnectState.FIRST_LAUNCH, vm.uiState.value.state)
            assertEquals(StartupPresentation.LOADING, vm.uiState.value.startupPresentation)
        }
    }
    @Test fun missingOrExpiredSessionOpensPasswordWithoutTicketAttempt() {
        validSession = false
        val vm = vm()
        assertEquals(StartupPresentation.SIGN_IN, vm.uiState.value.startupPresentation)
        assertEquals(ConnectState.AUTH_REQUIRED, vm.uiState.value.state)
        assertEquals(0, tickets)
    }
    @Test fun serverRejectionRetainsSessionsWithSignInAction() {
        ticketError = AuthSessionRejectedException(403)
        val vm = vm()
        assertEquals(StartupPresentation.SESSIONS, vm.uiState.value.startupPresentation)
        assertEquals(ConnectState.AUTH_REQUIRED, vm.uiState.value.state)
        assertEquals("Your login expired. Sign in again.", vm.uiState.value.loginError)
        assertEquals(0, connects)
    }
    @Test fun networkErrorRetainsSessionsAndRetryMintsFreshTicket() {
        ticketError = java.io.IOException("synthetic unavailable")
        val vm = vm()
        assertEquals(StartupPresentation.SESSIONS, vm.uiState.value.startupPresentation)
        assertEquals(ConnectState.HOST_UNREACHABLE, vm.uiState.value.state)
        ticketError = null
        vm.retry()
        assertEquals(ConnectState.CONNECTED_COMPATIBLE, vm.uiState.value.state)
        assertEquals(2, tickets)
        assertEquals(1, connects)
        assertEquals(0, fake.submitPromptCallCount)
    }
    @Test fun policyRejectionStaysOnSessionsAndNeverOpensSocket() {
        ticketError = AuthOriginPolicyRejectedException()
        val vm = vm()
        assertEquals(StartupPresentation.SESSIONS, vm.uiState.value.startupPresentation)
        assertEquals(ConnectState.ORIGIN_POLICY_REJECTED, vm.uiState.value.state)
        assertEquals(0, connects)
    }
    @Test fun keystoreFailureShowsRecoverableStorageErrorRatherThanPassword() {
        var unavailable = true
        val storage = object : VaultStorage by vault {
            override suspend fun getConnectionProfile(localId: String?): ConnectionProfile? {
                if (unavailable) throw KeyStoreUnavailableException("synthetic unavailable")
                return vault.getConnectionProfile(localId)
            }
        }
        val vm = vm(storage)
        assertEquals(StartupPresentation.STORAGE_UNAVAILABLE, vm.uiState.value.startupPresentation)
        assertEquals(0, tickets)
        unavailable = false
        vm.reloadSavedConnection()
        assertEquals(ConnectState.CONNECTED_COMPATIBLE, vm.uiState.value.state)
    }
    @Test fun credentialReadFailureFailsClosed() {
        val auth = object : AuthRepository by auth {
            override suspend fun hasValidSession(origin: CanonicalOrigin?): Boolean = throw KeyStoreUnavailableException("synthetic unavailable")
        }
        val vm = ConnectViewModel(SavedStateHandle(), auth, adapter, vault).also { store.put("connect", it) }
        assertEquals(StartupPresentation.STORAGE_UNAVAILABLE, vm.uiState.value.startupPresentation)
        assertEquals(0, tickets)
    }
    @Test fun lateNonCooperativeProfileReadCannotUndoSignOutOrAddMode() {
        for (addMode in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val storage = object : VaultStorage by vault {
                override suspend fun getConnectionProfile(localId: String?): ConnectionProfile? = withContext(NonCancellable) {
                    gate.await(); vault.getConnectionProfile(localId)
                }
            }
            val vm = vm(storage)
            if (addMode) vm.enterAddProfileMode() else vm.disconnect(SignOutCacheChoice.KEEP)
            gate.complete(Unit)
            assertEquals(StartupPresentation.SETUP, vm.uiState.value.startupPresentation)
            assertEquals(ConnectState.FIRST_LAUNCH, vm.uiState.value.state)
        }
        assertEquals(0, tickets)
    }
    @Test fun initialSetupWithNoProfileDoesNotRequestAuthentication() = runBlocking {
        vault.clearConnectionProfile()
        val vm = vm()
        assertEquals(StartupPresentation.SETUP, vm.uiState.value.startupPresentation)
        assertEquals(ConnectState.FIRST_LAUNCH, vm.uiState.value.state)
        assertEquals(0, tickets)
    }
    @Test fun readyPresentationWaitsForProfileCommit() {
        val gate = CompletableDeferred<Unit>()
        val storage = object : VaultStorage by vault {
            override suspend fun saveConnectionProfileIfCurrent(profile: ConnectionProfile, generation: Long,
                generationGate: ConnectionProfileGenerationGate): ProfileCommitResult {
                gate.await()
                return vault.saveConnectionProfileIfCurrent(profile, generation, generationGate)
            }
        }
        val vm = vm(storage)
        assertEquals(ConnectState.PREFLIGHT_LOADING, vm.uiState.value.state)
        assertEquals(StartupPresentation.SESSIONS, vm.uiState.value.startupPresentation)
        gate.complete(Unit)
        assertEquals(ConnectState.CONNECTED_COMPATIBLE, vm.uiState.value.state)
    }
    @Test fun socketLostDuringProfileCommitCannotPublishReady() {
        val gate = CompletableDeferred<Unit>()
        val storage = object : VaultStorage by vault {
            override suspend fun saveConnectionProfileIfCurrent(profile: ConnectionProfile, generation: Long,
                generationGate: ConnectionProfileGenerationGate): ProfileCommitResult {
                gate.await()
                return vault.saveConnectionProfileIfCurrent(profile, generation, generationGate)
            }
        }
        val vm = vm(storage)
        fake.connectionStatus.value = ConnectionStatus.OFFLINE
        assertEquals(ConnectState.PREFLIGHT_LOADING, vm.uiState.value.state)
        gate.complete(Unit)
        assertEquals(ConnectState.HOST_UNREACHABLE, vm.uiState.value.state)
        assertEquals(StartupPresentation.SESSIONS, vm.uiState.value.startupPresentation)
    }
    @Test fun startupRouteIsConsumedOncePerOwnerAndNotRestoredFromSavedPresentation() {
        val handle = SavedStateHandle()
        val vm = vm(handle = handle)
        assertTrue(vm.uiState.value.startupRoutePending)
        vm.onStartupPresented()
        vm.onStartupPresented()
        assertFalse(vm.uiState.value.startupRoutePending)
        val afterProcessDeath = vm(handle = handle)
        assertTrue(afterProcessDeath.uiState.value.startupRoutePending)
        assertEquals(StartupPresentation.SESSIONS, afterProcessDeath.uiState.value.startupPresentation)
    }
    @Test fun gatewayAuthObserverCannotRaceOwnedStartupAttempt() {
        connectGate = CompletableDeferred()
        val vm = vm()
        fake.connectionStatus.value = ConnectionStatus.AUTHENTICATION_REQUIRED
        assertEquals(ConnectState.PREFLIGHT_LOADING, vm.uiState.value.state)
        connectGate!!.complete(Unit)
        assertEquals(ConnectState.CONNECTED_COMPATIBLE, vm.uiState.value.state)
    }
}
