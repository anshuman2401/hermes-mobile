package com.hermes.bot.data.auth

import com.hermes.bot.data.local.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/** Synthetic credentials and hosts only; JCE key is an explicit test seam. */
class SilentLoginRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private val origin = CanonicalOrigin.parse("http://fixture.invalid:9119").getOrThrow()
    private val key = AesGcmCipherEngine(InMemoryMasterKeyProvider())
    private lateinit var directory: java.io.File
    private lateinit var vault: EncryptedVault
    private var passwords = 0
    private var tickets = 0
    private var rejectPassword = false
    private var rejectTicket = false
    private var passwordGate: CompletableDeferred<Unit>? = null
    private var entered: CompletableDeferred<Unit>? = null
    private val transport = object : AuthHttpTransport {
        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
            when {
                url.endsWith("/api/status") -> HttpResponse(200, body = """{"gateway_running":true,"auth_required":true,"auth_providers":["basic"]}""")
                url.endsWith("/auth/password-login") -> {
                    passwords++; entered?.complete(Unit); passwordGate?.await()
                    if (rejectPassword) HttpResponse(401)
                    else HttpResponse(200, headers = mapOf("Set-Cookie" to listOf("session=synthetic-$passwords; Path=/; HttpOnly")))
                }
                else -> {
                    tickets++
                    if (rejectTicket) HttpResponse(401) else HttpResponse(200, body = """{"ticket":"synthetic-ticket","ttl":30}""")
                }
            }
    }
    private lateinit var auth: DefaultAuthRepository
    @Before fun setup() = runBlocking {
        directory = temp.newFolder()
        vault = EncryptedVault(directory, key)
        vault.saveConnectionProfile(ConnectionProfile("fixture-profile", origin.originUrl, "Fixture"))
        auth = DefaultAuthRepository(vault, transport)
    }
    private suspend fun login(username: String = "synthetic-user") {
        val password = "synthetic-password".toCharArray()
        assertTrue(auth.login(origin, username, password).isSuccess)
        assertTrue(password.all { it == '0' })
    }
    private suspend fun partition() = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition

    @Test fun remembersEncryptedPasswordAcrossRestartAndExpiryPreservesCacheOwner() = runBlocking {
        login()
        val before = partition()
        assertFalse(directory.walkTopDown().filter { it.isFile }.any { it.readBytes().toString(Charsets.UTF_8).contains("synthetic-password") })
        auth.notifyAuthExpired()
        assertNull(vault.getAuthCredential())
        assertEquals(before, partition())
        vault = EncryptedVault(directory, key)
        auth = DefaultAuthRepository(vault, transport)
        assertTrue(auth.prepareLocalLogin(origin))
        assertTrue(auth.recoverSession(origin).isSuccess)
        assertEquals(2, passwords)
        assertEquals(before, partition())
        assertNotNull(vault.getAuthCredential())
    }
    @Test fun rejectionDisablesAutomaticPasswordUseButRetainsReadOnlyCache() = runBlocking {
        login()
        val before = partition()
        auth.notifyAuthExpired()
        rejectPassword = true
        assertTrue(auth.recoverSession(origin).exceptionOrNull() is SavedPasswordRejectedException)
        assertEquals(before, partition())
        auth = DefaultAuthRepository(EncryptedVault(directory, key), transport)
        assertTrue(auth.recoverSession(origin).exceptionOrNull() is AuthSessionRejectedException)
        assertEquals(2, passwords)
        rejectPassword = false
        login()
        assertEquals(before, partition())
    }
    @Test fun signOutRevokesRememberedPasswordAndCacheAcrossRestart() = runBlocking {
        login()
        auth.signOut(cacheChoice = SignOutCacheChoice.KEEP)
        vault = EncryptedVault(directory, key)
        auth = DefaultAuthRepository(vault, transport)
        assertFalse(auth.prepareLocalLogin(origin))
        assertTrue(vault.activeAccessPartition() is CacheAccessPartitionResult.Unavailable)
        assertTrue(auth.recoverSession(origin).isFailure)
        assertEquals(1, passwords)
    }
    @Test fun accountReplacementDoesNotInheritCacheOwner() = runBlocking {
        login()
        val before = partition()
        login("another-synthetic-user")
        assertNotEquals(before, partition())
    }
    @Test fun nonCooperativeLoginCannotUndoSignOut() = runBlocking {
        login()
        passwordGate = CompletableDeferred()
        entered = CompletableDeferred()
        val pending = async { auth.login(origin, "synthetic-user", "synthetic-password".toCharArray()) }
        entered!!.await()
        auth.signOut(cacheChoice = SignOutCacheChoice.KEEP)
        passwordGate!!.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is StaleAuthenticatedResponseException)
        assertNull(vault.getAuthCredential())
        assertFalse(auth.prepareLocalLogin(origin))
    }
    @Test fun lateLoginCannotCommitToSwitchedProfile() = runBlocking {
        login()
        passwordGate = CompletableDeferred()
        entered = CompletableDeferred()
        val pending = async { auth.login(origin, "synthetic-user", "synthetic-password".toCharArray()) }
        entered!!.await()
        vault.saveConnectionProfile(ConnectionProfile("other-profile", "http://other.invalid:9119", "Other"))
        passwordGate!!.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertNull(vault.getAuthCredential())
    }
    @Test fun legacyCookieAdmitsCacheWithoutInventingPasswordAndSignedOutNeverMigrates() = runBlocking {
        vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-cookie"), origin.originUrl))
        val before = partition()
        assertTrue(auth.prepareLocalLogin(origin))
        auth.notifyAuthExpired()
        assertEquals(before, partition())
        assertTrue(auth.recoverSession(origin).isFailure)
        assertEquals(0, passwords)
        auth.signOut(cacheChoice = SignOutCacheChoice.KEEP)
        assertFalse(auth.prepareLocalLogin(origin))
    }
    @Test fun cookieSuccessorChangesNetworkReceiptWithoutChangingLocalOwner() = runBlocking {
        login()
        val before = partition()
        val lease = vault.readAuthCredentialLease(origin.originUrl)!!
        val mutation = vault.mutateAuthCredentialIfCurrent(lease, lease.credential.copy(cookies = mapOf("session" to "synthetic-next")))
        assertTrue(mutation is AuthCredentialMutationResult.Accepted)
        assertEquals(before, partition())
        assertFalse(auth.isResponseCurrent(lease))
        assertEquals(before.cacheEpoch, (mutation as AuthCredentialMutationResult.Accepted).receipt!!.cacheEpoch)
    }
    @Test fun coordinatorCoalescesRecoveryAndNeverSubmitsPrompts() = runBlocking {
        login()
        auth.notifyAuthExpired()
        passwordGate = CompletableDeferred()
        entered = CompletableDeferred()
        val fake = FakeGatewayProtocolAdapter().apply { connectionStatus.value = ConnectionStatus.OFFLINE }
        var sockets = 0
        val gateway = object : GatewayProtocolAdapter by fake {
            override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean {
                sockets++; fake.connectionStatus.value = ConnectionStatus.CONNECTED; return true
            }
        }
        val coordinator = ConnectionRecoveryCoordinator(auth, gateway)
        val first = async { coordinator.connect(origin) }
        entered!!.await()
        val second = async { coordinator.connect(origin) }
        passwordGate!!.complete(Unit)
        assertTrue(first.await()); assertTrue(second.await())
        assertEquals(2, passwords); assertEquals(1, sockets); assertEquals(1, tickets)
        assertEquals(0, fake.submitPromptCallCount)
    }
    @Test fun ticketStillRejectedAfterOnePasswordLoginStopsRecovery() = runBlocking {
        login()
        rejectTicket = true
        val fake = FakeGatewayProtocolAdapter().apply { connectionStatus.value = ConnectionStatus.OFFLINE }
        val coordinator = ConnectionRecoveryCoordinator(auth, fake)
        assertFalse(coordinator.connect(origin))
        assertEquals(RecoveryPhase.SIGN_IN_REQUIRED, coordinator.phase.value)
        assertEquals(2, passwords)
        assertEquals(2, tickets)
        assertEquals(0, fake.submitPromptCallCount)
    }
    @Test fun cachedRowsRemainReadableAfterExpiryAndCookieRenewal() = runBlocking {
        login()
        val owner = partition()
        val cache = SessionSummaryCache(listOf(SessionSummary("synthetic-session", "Synthetic", "", 0.0, 1, "android")),
            profileLocalId = owner.profileLocalId)
        vault.saveSessionSummaryCache(cache, owner)
        auth.notifyAuthExpired()
        vault = EncryptedVault(directory, key)
        auth = DefaultAuthRepository(vault, transport)
        assertTrue(auth.prepareLocalLogin(origin))
        assertEquals(cache.sessions, (vault.readSessionSummaryCache(partition()) as CacheReadResult.Available).value.sessions)
        assertTrue(auth.recoverSession(origin).isSuccess)
        assertEquals(cache.sessions, (vault.readSessionSummaryCache(partition()) as CacheReadResult.Available).value.sessions)
    }
    @Test fun failedRememberedRecordWriteCannotPublishNetworkOrLocalAccess() = runBlocking {
        var fail = false
        val cipher = object : VaultCipherEngine by key {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
                if (fail && associatedData?.toString(Charsets.UTF_8)?.startsWith("ENDPOINT_LOGIN:") == true &&
                    plaintext.toString(Charsets.UTF_8).contains("synthetic-password"))
                    throw java.io.IOException("Synthetic persistence failure")
                return key.encrypt(plaintext, associatedData)
            }
        }
        vault = EncryptedVault(directory, cipher)
        auth = DefaultAuthRepository(vault, transport)
        fail = true
        assertTrue(auth.login(origin, "synthetic-user", "synthetic-password".toCharArray()).isFailure)
        assertNull(vault.getAuthCredential())
        vault = EncryptedVault(directory, key)
        assertNull(vault.getAuthCredential())
        assertFalse(vault.prepareLocalLogin(origin.originUrl))
    }
    @Test fun keystoreFailureDoesNotEraseRememberedLoginOrFallBack() = runBlocking {
        login()
        val unavailable = object : VaultCipherEngine {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray =
                throw KeyStoreUnavailableException("Synthetic unavailable")
            override fun decrypt(ciphertextWithIv: ByteArray, associatedData: ByteArray?): Result<ByteArray> =
                Result.failure(KeyStoreUnavailableException("Synthetic unavailable"))
        }
        val locked = DefaultAuthRepository(EncryptedVault(directory, unavailable), transport)
        assertTrue(runCatching { locked.prepareLocalLogin(origin) }.exceptionOrNull() is KeyStoreUnavailableException)
        assertEquals(1, passwords)
        assertTrue(auth.prepareLocalLogin(origin))
    }
    @Test fun cacheOnlyResetKeepsRememberedLoginButSignOutNeverReusesIt() = runBlocking {
        login()
        vault.clearCache()
        auth.notifyAuthExpired()
        assertTrue(auth.recoverSession(origin).isSuccess)
        auth.signOut(cacheChoice = SignOutCacheChoice.CLEAR)
        assertTrue(auth.recoverSession(origin).isFailure)
        assertEquals(2, passwords)
    }
    @Test fun policyCloseNeverSendsSavedPassword() = runBlocking {
        login()
        val fake = FakeGatewayProtocolAdapter().apply {
            connectionStatus.value = ConnectionStatus.AUTHENTICATION_REQUIRED
        }
        val adapter = object : GatewayProtocolAdapter by fake {
            override val terminalFailure = kotlinx.coroutines.flow.MutableStateFlow<GatewayFailure?>(
                GatewayFailure.Transport("Synthetic policy", statusCode = 4403))
        }
        assertFalse(ConnectionRecoveryCoordinator(auth, adapter).connect(origin))
        assertEquals(1, passwords)
        assertEquals(0, tickets)
    }
    @Test fun composerRemainsReadOnlyDuringReconnect() {
        val state = com.hermes.bot.feature.chat.ChatUiState(
            composerDraft = "Synthetic draft", runtimeSessionId = "synthetic-runtime",
            isAttachmentConnectionAvailable = false)
        assertFalse(state.isComposerReady)
        assertEquals("Synthetic draft", state.composerDraft)
    }

    @Test fun simultaneousFailedRecoverySharesOnePasswordAttempt() = runBlocking {
        login()
        auth.notifyAuthExpired()
        passwordGate = CompletableDeferred()
        entered = CompletableDeferred()
        rejectPassword = true
        val fake = FakeGatewayProtocolAdapter().apply { connectionStatus.value = ConnectionStatus.OFFLINE }
        val coordinator = ConnectionRecoveryCoordinator(auth, fake)
        val first = async { coordinator.connect(origin) }
        entered!!.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.connect(origin) }
        passwordGate!!.complete(Unit)
        assertFalse(first.await()); assertFalse(second.await())
        assertEquals(2, passwords)
        assertEquals(RecoveryPhase.SIGN_IN_REQUIRED, coordinator.phase.value)
    }
    @Test fun invalidatedInFlightSocketCannotBecomeReady() = runBlocking {
        login()
        val socketEntered = CompletableDeferred<Unit>()
        val socketRelease = CompletableDeferred<Unit>()
        val fake = FakeGatewayProtocolAdapter().apply { connectionStatus.value = ConnectionStatus.OFFLINE }
        val adapter = object : GatewayProtocolAdapter by fake {
            override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean {
                socketEntered.complete(Unit); socketRelease.await()
                fake.connectionStatus.value = ConnectionStatus.CONNECTED
                return true
            }
            override fun disconnect() { fake.connectionStatus.value = ConnectionStatus.OFFLINE }
        }
        val coordinator = ConnectionRecoveryCoordinator(auth, adapter)
        val pending = async { coordinator.connect(origin) }
        socketEntered.await()
        coordinator.invalidate()
        socketRelease.complete(Unit)
        assertFalse(pending.await())
        assertEquals(ConnectionStatus.OFFLINE, fake.connectionStatus.value)
        assertEquals(RecoveryPhase.IDLE, coordinator.phase.value)
    }

    @Test fun manualAccountChangeInvalidatesPresentationButSameAccountRenewalDoesNot() = runBlocking {
        login()
        val first = auth.localAccessRevision.value
        auth.notifyAuthExpired()
        login()
        assertEquals(first, auth.localAccessRevision.value)
        login("another-synthetic-user")
        assertEquals(first + 1, auth.localAccessRevision.value)
    }

}
