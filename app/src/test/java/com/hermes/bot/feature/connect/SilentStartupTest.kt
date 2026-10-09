package com.hermes.bot.feature.connect

import androidx.lifecycle.SavedStateHandle
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

class SilentStartupTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()
    private val store = ViewModelStore()
    private val gate = CompletableDeferred<Unit>()
    private val fake = FakeGatewayProtocolAdapter().apply { connectionStatus.value = ConnectionStatus.OFFLINE }
    private var rejected = false
    private var valid = false
    private var logins = 0
    private val auth = object : AuthRepository {
        override suspend fun prepareLocalLogin(origin: CanonicalOrigin) = true
        override suspend fun hasValidSession(origin: CanonicalOrigin?) = valid
        override suspend fun recoverSession(origin: CanonicalOrigin): Result<Unit> {
            logins++; gate.await()
            if (rejected) return Result.failure(SavedPasswordRejectedException())
            valid = true
            return Result.success(Unit)
        }
        override suspend fun mintWebSocketTicket(origin: CanonicalOrigin) = Result.success(GatewayTicket("synthetic-ticket"))
        override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
        override suspend fun notifyAuthExpired() {}
    }
    private val adapter = object : GatewayProtocolAdapter by fake {
        override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean {
            fake.connectionStatus.value = ConnectionStatus.CONNECTED
            return true
        }
    }
    private fun vm(): ConnectViewModel {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), main.testDispatcher)
        runBlocking { vault.saveConnectionProfile(ConnectionProfile("synthetic-profile", "http://fixture.invalid:9119", "Fixture")) }
        return ConnectViewModel(SavedStateHandle(), auth, adapter, vault,
            recovery = ConnectionRecoveryCoordinator(auth, adapter)).also { store.put("connect", it) }
    }
    @After fun close() { store.clear() }
    @Test fun expiredCookieDisplaysSessionsThroughoutSilentLogin() {
        val vm = vm()
        assertEquals(StartupPresentation.SESSIONS, vm.uiState.value.startupPresentation)
        assertEquals(ConnectState.PREFLIGHT_LOADING, vm.uiState.value.state)
        gate.complete(Unit)
        assertEquals(ConnectState.CONNECTED_COMPATIBLE, vm.uiState.value.state)
        assertEquals(StartupPresentation.SESSIONS, vm.uiState.value.startupPresentation)
        assertEquals(1, logins)
        assertEquals(0, fake.submitPromptCallCount)
    }
    @Test fun passwordRejectionRetainsSessionsInsteadOfNavigatingToForm() {
        rejected = true
        val vm = vm()
        gate.complete(Unit)
        assertEquals(ConnectState.AUTH_REQUIRED, vm.uiState.value.state)
        assertEquals(StartupPresentation.SESSIONS, vm.uiState.value.startupPresentation)
        assertEquals(1, logins)
        assertEquals(0, fake.submitPromptCallCount)
    }
}
