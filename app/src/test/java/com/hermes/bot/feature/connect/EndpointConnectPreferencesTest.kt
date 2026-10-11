package com.hermes.bot.feature.connect

import androidx.lifecycle.ViewModelStore
import com.hermes.bot.data.auth.*
import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.local.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class EndpointConnectPreferencesTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()
    private val models = ViewModelStore()
    private val endpoint = CanonicalOrigin.parseOrThrow("http://fixture.invalid:9120")
    @After fun close() { models.clear() }
    private fun vm(auth: AuthRepository, vault: VaultStorage? = null): ConnectViewModel =
        ConnectViewModel(authRepository = auth, vaultStorage = vault).also { models.put("connect", it) }
    private fun lookup(read: suspend (String?) -> CanonicalOrigin?) = object : AuthRepository {
        override suspend fun rememberedEndpoint(host: String?) = read(host)
        override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
        override suspend fun notifyAuthExpired() {}
    }
    @Test fun hostnameOnlyEntryRestoresPortAndUnknownHostUsesDefault() {
        val viewModel = vm(lookup { if (it == "fixture.invalid") endpoint else null })
        viewModel.updateHost("fixture.invalid")
        assertEquals("9120", viewModel.uiState.value.portDraft)
        viewModel.updateHost("unknown.invalid")
        assertEquals("9119", viewModel.uiState.value.portDraft)
    }
    @Test fun delayedLookupDoesNotOverwriteExplicitPortEdit() {
        val gate = CompletableDeferred<Unit>()
        val viewModel = vm(lookup { gate.await(); endpoint })
        viewModel.updateHost("fixture.invalid")
        viewModel.updatePort("9220")
        gate.complete(Unit)
        assertEquals("9220", viewModel.uiState.value.portDraft)
    }
    @Test fun explicitPortEnteredBeforeHostAlsoTakesPriority() {
        val viewModel = vm(lookup { endpoint })
        viewModel.updatePort("9220")
        viewModel.updateHost("fixture.invalid")
        assertEquals("9220", viewModel.uiState.value.portDraft)
    }
    @Test fun staleNonCooperativeHostLookupCannotOverrideNewDraft() {
        val gate = CompletableDeferred<Unit>()
        val viewModel = vm(lookup { host ->
            if (host == "fixture.invalid") withContext(NonCancellable) { gate.await(); endpoint }
            else CanonicalOrigin.parseOrThrow("http://other.invalid:9330")
        })
        viewModel.updateHost("fixture.invalid")
        viewModel.updateHost("other.invalid")
        gate.complete(Unit)
        assertEquals("other.invalid", viewModel.uiState.value.hostDraft)
        assertEquals("9330", viewModel.uiState.value.portDraft)
    }
    @Test fun startupRestoresLastSuccessfulEndpointAndStaysSignedOut() = runBlocking {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), main.testDispatcher)
        vault.saveConnectionProfile(ConnectionProfile("first", endpoint.originUrl, "First"))
        vault.noteSuccessfulEndpoint(endpoint.originUrl)
        vault.saveConnectionProfile(ConnectionProfile("other", "http://other.invalid:9119", "Other"))
        val auth = DefaultAuthRepository(vault)
        val viewModel = vm(auth, vault)
        assertEquals("fixture.invalid", viewModel.uiState.value.hostDraft)
        assertEquals("9120", viewModel.uiState.value.portDraft)
        assertEquals(ConnectState.AUTH_REQUIRED, viewModel.uiState.value.state)
        assertEquals("first", vault.getActiveProfileId())
    }
    @Test fun signedOutStartupOffersConnectWithoutAutomaticallyUsingRetainedPassword() = runBlocking {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), main.testDispatcher)
        vault.saveConnectionProfile(ConnectionProfile("fixture", endpoint.originUrl, "Fixture"))
        val owner = vault.captureLoginOwner(endpoint.originUrl)!!
        vault.commitRememberedLogin(owner, AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-cookie"),
            endpoint.originUrl), "synthetic-user", "synthetic-password".toCharArray())
        vault.noteSuccessfulEndpoint(endpoint.originUrl)
        val auth = DefaultAuthRepository(vault)
        auth.signOut(cacheChoice = SignOutCacheChoice.KEEP)
        val viewModel = vm(auth, vault)
        assertEquals(StartupPresentation.SETUP, viewModel.uiState.value.startupPresentation)
        assertEquals(ConnectState.FIRST_LAUNCH, viewModel.uiState.value.state)
        assertEquals("fixture.invalid", viewModel.uiState.value.hostDraft)
        assertEquals("9120", viewModel.uiState.value.portDraft)
        assertNull(auth.getStoredCredential())
    }

    @Test fun addingKnownEndpointAuthenticatesTheNewProfileInsteadOfBorrowingItsCacheOwner() = runBlocking {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), main.testDispatcher)
        vault.saveConnectionProfile(ConnectionProfile("first", endpoint.originUrl, "First"))
        var passwords = 0
        val auth = DefaultAuthRepository(vault, object : AuthHttpTransport {
            override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?) = when {
                url.endsWith("/api/status") -> HttpResponse(200, body =
                    """{"gateway_running":true,"auth_required":true,"auth_providers":["basic"]}""")
                url.endsWith("/auth/password-login") -> {
                    passwords++
                    HttpResponse(200, headers = mapOf("Set-Cookie" to listOf("session=synthetic-cookie; Path=/; HttpOnly")))
                }
                else -> HttpResponse(200, body = """{"ticket":"synthetic-ticket","ttl":30}""")
            }
        })
        assertTrue(auth.login(endpoint, "synthetic-user", "synthetic-password".toCharArray()).isSuccess)
        val original = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        vault.noteSuccessfulEndpoint(endpoint.originUrl)
        val fake = FakeGatewayProtocolAdapter().apply { connectionStatus.value = ConnectionStatus.OFFLINE }
        val adapter = object : GatewayProtocolAdapter by fake {
            override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean {
                fake.connectionStatus.value = ConnectionStatus.CONNECTED
                return true
            }
        }
        val viewModel = ConnectViewModel(authRepository = auth, vaultStorage = vault, gatewayAdapter = adapter,
            recovery = ConnectionRecoveryCoordinator(auth, adapter)).also { models.put("connect", it) }
        viewModel.enterAddProfileMode()
        viewModel.updateHost(endpoint.host)
        viewModel.testConnection()
        assertEquals(ConnectState.CONNECTED_COMPATIBLE, viewModel.uiState.value.state)
        assertNotEquals("first", vault.getActiveProfileId())
        assertNotNull(vault.getAuthCredential())
        assertNotEquals(original, (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition)
        assertEquals(2, passwords)
    }

    @Test fun typingAnotherEndpointPreservesPriorProfileAndBothPasswords() = runBlocking {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), main.testDispatcher)
        vault.saveConnectionProfile(ConnectionProfile("first", endpoint.originUrl, "First"))
        var passwords = 0
        val auth = DefaultAuthRepository(vault, object : AuthHttpTransport {
            override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?) = when {
                url.endsWith("/api/status") -> HttpResponse(200, body =
                    """{"gateway_running":true,"auth_required":true,"auth_providers":["basic"]}""")
                url.endsWith("/auth/password-login") -> {
                    passwords++
                    HttpResponse(200, headers = mapOf("Set-Cookie" to listOf("session=synthetic-cookie; Path=/; HttpOnly")))
                }
                else -> HttpResponse(200, body = """{"ticket":"synthetic-ticket","ttl":30}""")
            }
        })
        assertTrue(auth.login(endpoint, "synthetic-user", "synthetic-password".toCharArray()).isSuccess)
        vault.noteSuccessfulEndpoint(endpoint.originUrl)
        val fake = FakeGatewayProtocolAdapter().apply { connectionStatus.value = ConnectionStatus.OFFLINE }
        val adapter = object : GatewayProtocolAdapter by fake {
            override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean {
                fake.connectionStatus.value = ConnectionStatus.CONNECTED
                return true
            }
        }
        val viewModel = ConnectViewModel(authRepository = auth, vaultStorage = vault, gatewayAdapter = adapter,
            recovery = ConnectionRecoveryCoordinator(auth, adapter)).also { models.put("connect", it) }
        val second = CanonicalOrigin.parseOrThrow("http://other.invalid:9119")
        viewModel.updateHost(second.host)
        viewModel.testConnection()
        assertEquals(ConnectState.AUTH_REQUIRED, viewModel.uiState.value.state)
        assertEquals(endpoint.originUrl, vault.getConnectionProfile("first")!!.normalizedOrigin)
        viewModel.updateUsername("other-synthetic-user")
        viewModel.updatePassword("synthetic-password")
        viewModel.login()
        assertEquals(ConnectState.CONNECTED_COMPATIBLE, viewModel.uiState.value.state)
        assertEquals(second.originUrl, vault.getConnectionProfile()!!.normalizedOrigin)
        viewModel.updateHost(endpoint.host)
        viewModel.testConnection()
        assertEquals(ConnectState.CONNECTED_COMPATIBLE, viewModel.uiState.value.state)
        assertEquals("first", vault.getActiveProfileId())
        assertTrue(auth.hasRememberedLogin(second))
        assertEquals(3, passwords)
    }

    @Test fun disconnectDuringPreferenceWriteCannotPublishReady() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = FakeGatewayProtocolAdapter().apply { connectionStatus.value = ConnectionStatus.OFFLINE }
        var admitted = 0
        val auth = object : AuthRepository {
            override suspend fun checkStatus(origin: CanonicalOrigin) = Result.success(HostStatus("synthetic", true, true))
            override suspend fun hasValidSession(origin: CanonicalOrigin?) = true
            override suspend fun mintWebSocketTicket(origin: CanonicalOrigin) = Result.success(GatewayTicket("synthetic-ticket"))
            override suspend fun noteSuccessfulEndpoint(origin: CanonicalOrigin) { entered.complete(Unit); release.await() }
            override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
            override suspend fun notifyAuthExpired() {}
        }
        val adapter = object : GatewayProtocolAdapter by fake {
            override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean {
                fake.connectionStatus.value = ConnectionStatus.CONNECTED
                return true
            }
        }
        val viewModel = ConnectViewModel(authRepository = auth, gatewayAdapter = adapter,
            recovery = ConnectionRecoveryCoordinator(auth, adapter), onGatewayConnected = { admitted++ })
            .also { models.put("connect", it) }
        viewModel.updateHost(endpoint.host)
        viewModel.testConnection()
        assertTrue(entered.isCompleted)
        assertEquals(ConnectState.PREFLIGHT_LOADING, viewModel.uiState.value.state)
        fake.connectionStatus.value = ConnectionStatus.OFFLINE
        release.complete(Unit)
        assertEquals(ConnectState.HOST_UNREACHABLE, viewModel.uiState.value.state)
        assertEquals(0, admitted)
    }

    @Test fun failedProfilePreparationReturnsToSetupWithoutSendingCredentials() = runBlocking {
        val real = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), main.testDispatcher)
        real.saveConnectionProfile(ConnectionProfile("first", endpoint.originUrl, "First"))
        val vault = object : VaultStorage by real {
            override suspend fun saveConnectionProfileIfCurrent(profile: ConnectionProfile, generation: Long,
                generationGate: ConnectionProfileGenerationGate): ProfileCommitResult = ProfileCommitResult.FAILED("SyntheticIoFailure")
        }
        var recovered = 0
        val auth = object : AuthRepository {
            override suspend fun checkStatus(origin: CanonicalOrigin) = Result.success(HostStatus("synthetic", true, true))
            override suspend fun hasValidSession(origin: CanonicalOrigin?) = false
            override suspend fun recoverSession(origin: CanonicalOrigin, explicitConnect: Boolean): Result<Unit> {
                recovered++
                return Result.failure(AuthSessionRejectedException())
            }
            override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
            override suspend fun notifyAuthExpired() {}
        }
        val adapter = FakeGatewayProtocolAdapter().apply { connectionStatus.value = ConnectionStatus.OFFLINE }
        val viewModel = ConnectViewModel(authRepository = auth, vaultStorage = vault, gatewayAdapter = adapter,
            recovery = ConnectionRecoveryCoordinator(auth, adapter)).also { models.put("connect", it) }
        viewModel.updateHost("other.invalid")
        viewModel.testConnection()
        assertEquals(ConnectState.FIRST_LAUNCH, viewModel.uiState.value.state)
        assertEquals(StartupPresentation.SETUP, viewModel.uiState.value.startupPresentation)
        assertEquals("Connection details could not be saved. Try again.", viewModel.uiState.value.errorMessage)
        assertEquals("first", real.getActiveProfileId())
        assertEquals(0, recovered)
    }

    @Test fun signOutCancelsConnectQueuedBehindANonCooperativePreferenceRead() {
        val release = CompletableDeferred<Unit>()
        var checks = 0
        val auth = object : AuthRepository {
            override suspend fun rememberedEndpoint(host: String?): CanonicalOrigin? =
                withContext(NonCancellable) { release.await(); endpoint }
            override suspend fun checkStatus(origin: CanonicalOrigin): Result<HostStatus> {
                checks++
                return Result.success(HostStatus("synthetic", true, true))
            }
            override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
            override suspend fun notifyAuthExpired() {}
        }
        val viewModel = ConnectViewModel(authRepository = auth, gatewayAdapter = FakeGatewayProtocolAdapter())
            .also { models.put("connect", it) }
        viewModel.updateHost(endpoint.host)
        viewModel.testConnection()
        viewModel.disconnect(SignOutCacheChoice.KEEP)
        release.complete(Unit)
        assertEquals(0, checks)
        assertEquals(ConnectState.FIRST_LAUNCH, viewModel.uiState.value.state)
    }

    @Test fun passwordFormPassesTheUncheckedRememberChoiceAndClearsThePassword() {
        val auth = object : AuthRepository {
            override suspend fun login(origin: CanonicalOrigin, username: String, password: CharArray,
                remember: Boolean, provider: String): Result<AuthCredential> {
                password.fill('\u0000')
                capturedRemember = remember
                return Result.success(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-cookie"), origin.originUrl))
            }
            var capturedRemember: Boolean? = null
            override suspend fun mintWebSocketTicket(origin: CanonicalOrigin): Result<GatewayTicket> = Result.failure(AuthSessionRejectedException())
            override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
            override suspend fun notifyAuthExpired() {}
        }
        val viewModel = ConnectViewModel(authRepository = auth, gatewayAdapter = FakeGatewayProtocolAdapter())
            .also { models.put("connect", it) }
        viewModel.updateHost(endpoint.host)
        viewModel.updatePort(endpoint.port.toString())
        viewModel.transitionForTest(ConnectState.AUTH_REQUIRED)
        viewModel.updateUsername("synthetic-user")
        viewModel.updatePassword("synthetic-password")
        viewModel.updateRememberLogin(false)
        viewModel.login()
        assertEquals(false, auth.capturedRemember)
        assertEquals("", viewModel.uiState.value.passwordDraft)
    }

    @Test fun explicitConnectIntentIsPassedToRecoveryAndUsesRestoredPort() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeGatewayProtocolAdapter().apply { connectionStatus.value = ConnectionStatus.OFFLINE }
        var explicit: Boolean? = null
        var submittedOrigin: CanonicalOrigin? = null
        var valid = false
        val auth = object : AuthRepository {
            override suspend fun rememberedEndpoint(host: String?): CanonicalOrigin? { gate.await(); return endpoint }
            override suspend fun checkStatus(origin: CanonicalOrigin): Result<HostStatus> {
                submittedOrigin = origin
                return Result.success(HostStatus("synthetic", true, true))
            }
            override suspend fun hasValidSession(origin: CanonicalOrigin?) = valid
            override suspend fun recoverSession(origin: CanonicalOrigin, explicitConnect: Boolean): Result<Unit> {
                explicit = explicitConnect
                valid = true
                return Result.success(Unit)
            }
            override suspend fun mintWebSocketTicket(origin: CanonicalOrigin) = Result.success(GatewayTicket("synthetic-ticket"))
            override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
            override suspend fun notifyAuthExpired() {}
        }
        val adapter = object : GatewayProtocolAdapter by fake {
            override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean {
                fake.connectionStatus.value = ConnectionStatus.CONNECTED
                return true
            }
        }
        val viewModel = ConnectViewModel(authRepository = auth, gatewayAdapter = adapter,
            recovery = ConnectionRecoveryCoordinator(auth, adapter)).also { models.put("connect", it) }
        viewModel.updateHost("fixture.invalid")
        viewModel.testConnection()
        assertNull(submittedOrigin)
        gate.complete(Unit)
        assertEquals(endpoint, submittedOrigin)
        assertEquals(true, explicit)
        assertEquals(ConnectState.CONNECTED_COMPATIBLE, viewModel.uiState.value.state)
        assertEquals(0, fake.submitPromptCallCount)
    }
}
