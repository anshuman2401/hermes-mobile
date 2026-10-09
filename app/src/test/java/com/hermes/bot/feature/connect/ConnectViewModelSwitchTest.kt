package com.hermes.bot.feature.connect

import androidx.lifecycle.SavedStateHandle
import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.auth.HostStatus
import com.hermes.bot.data.gateway.ActiveSessionListResult
import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.GatewayTicket
import com.hermes.bot.data.gateway.HistoryFallbackSignal
import com.hermes.bot.data.gateway.SessionListResult
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.SignOutCacheChoice
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.local.VaultStorage
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Plan v1, Test 4: ConnectViewModelSwitchTest
 *
 * Verifies:
 * - Switch ordering: active ID persisted before teardown (gate invalidate -> onGatewayDisconnected -> auth.signOut -> gateway.disconnect).
 * - Gate invalidation cancels stale preflight (generation gate assertions).
 * - Add-mode inserts a new localId while edit-mode preserves it.
 * - Blocked switches (same profile or missing profile) do not tear down connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelSwitchTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var storageDir: File
    private lateinit var rawVault: EncryptedVault
    private lateinit var fakeAuthRepository: TestAuthRepository
    private lateinit var fakeGatewayAdapter: TestGatewayAdapter
    private val eventLog = mutableListOf<String>()

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("connect_vm_switch_test")
        rawVault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        fakeAuthRepository = TestAuthRepository(eventLog, rawVault)
        fakeGatewayAdapter = TestGatewayAdapter(eventLog)
        eventLog.clear()
    }

    private fun createSpyVault(underlying: VaultStorage, log: MutableList<String>): VaultStorage {
        return object : VaultStorage by underlying {
            override suspend fun setActiveProfileId(localId: String): Boolean {
                log.add("vault.setActiveProfileId:$localId")
                return underlying.setActiveProfileId(localId)
            }
        }
    }

    @Test
    fun switchOrdering_persistsActiveIdBeforeTeardown() = runBlocking {
        // Setup two profiles
        val p1 = ConnectionProfile("p1", "http://p1.hermes.internal:9119", "Profile 1")
        val p2 = ConnectionProfile("p2", "http://p2.hermes.internal:9119", "Profile 2")
        rawVault.saveConnectionProfile(p1)
        rawVault.saveConnectionProfile(p2)
        rawVault.setActiveProfileId("p1")

        val spyVault = createSpyVault(rawVault, eventLog)
        val vm = ConnectViewModel(
            savedStateHandle = SavedStateHandle(),
            authRepository = fakeAuthRepository,
            gatewayAdapter = fakeGatewayAdapter,
            vaultStorage = spyVault,
            onGatewayDisconnected = { eventLog.add("onGatewayDisconnected") }
        )
        // Wait for initial profile load
        vm.reloadProfiles()
        eventLog.clear()

        // Execute switch to p2
        val switchJob = vm.switchProfile("p2")
        switchJob.join()

        // Verify order of operations
        val setIndex = eventLog.indexOf("vault.setActiveProfileId:p2")
        val onDisconnectIndex = eventLog.indexOf("onGatewayDisconnected")
        val adapterDisconnectIndex = eventLog.indexOf("gateway.disconnect")

        assertTrue("setActiveProfileId must be logged", setIndex >= 0)
        assertTrue("onGatewayDisconnected must be logged", onDisconnectIndex >= 0)
        assertFalse("auth.signOut must NOT be logged on switchProfile (HIGH-1)", eventLog.contains("auth.signOut"))
        assertTrue("gateway.disconnect must be logged", adapterDisconnectIndex >= 0)

        assertTrue(
            "setActiveProfileId must occur before onGatewayDisconnected",
            setIndex < onDisconnectIndex
        )
        assertTrue(
            "setActiveProfileId must occur before gateway.disconnect",
            setIndex < adapterDisconnectIndex
        )

        // Active profile in storage must be p2
        assertEquals("p2", rawVault.getActiveProfileId())
        assertEquals("p2", vm.profilesUiState.value.activeProfileId)
    }

    @Test
    fun switchProfile_preservesBothProfilesCredentials() = runBlocking {
        val p1 = ConnectionProfile("p1", "http://p1.hermes.internal:9119", "Profile 1")
        val p2 = ConnectionProfile("p2", "http://p2.hermes.internal:9119", "Profile 2")
        rawVault.saveConnectionProfile(p1)
        rawVault.saveConnectionProfile(p2)
        rawVault.setActiveProfileId("p1")

        val cred1 = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "p1-user",
            cookies = mapOf("session" to "p1-token"),
            canonicalOrigin = p1.normalizedOrigin
        )
        val cred2 = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "p2-user",
            cookies = mapOf("session" to "p2-token"),
            canonicalOrigin = p2.normalizedOrigin
        )
        rawVault.saveAuthCredential(cred1, "p1")
        rawVault.saveAuthCredential(cred2, "p2")

        val vm = ConnectViewModel(
            savedStateHandle = SavedStateHandle(),
            authRepository = fakeAuthRepository,
            gatewayAdapter = fakeGatewayAdapter,
            vaultStorage = rawVault
        )
        vm.reloadProfiles()

        val switchJob = vm.switchProfile("p2")
        switchJob.join()

        // Assert old + new AuthCredential records intact after switchProfile
        val loadedP1 = rawVault.getAuthCredential("p1")
        val loadedP2 = rawVault.getAuthCredential("p2")
        assertNotNull("Old profile credential must remain stored after switch", loadedP1)
        assertEquals("p1-token", loadedP1?.cookies?.get("session"))
        assertNotNull("Target profile credential must survive untouched after switch", loadedP2)
        assertEquals("p2-token", loadedP2?.cookies?.get("session"))
    }

    @Test
    fun deleteProfile_nonFirstActiveProfile_preservesRemainingProfilesCredentials() = runBlocking {
        val p1 = ConnectionProfile("p1", "http://p1.hermes.internal:9119", "Profile 1")
        val p2 = ConnectionProfile("p2", "http://p2.hermes.internal:9119", "Profile 2")
        rawVault.saveConnectionProfile(p1)
        rawVault.saveConnectionProfile(p2)
        rawVault.setActiveProfileId("p2") // p2 is non-first

        val cred1 = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "p1-user",
            cookies = mapOf("session" to "p1-secret"),
            canonicalOrigin = p1.normalizedOrigin
        )
        val cred2 = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "p2-user",
            cookies = mapOf("session" to "p2-secret"),
            canonicalOrigin = p2.normalizedOrigin
        )
        rawVault.saveAuthCredential(cred1, "p1")
        rawVault.saveAuthCredential(cred2, "p2")

        val vm = ConnectViewModel(
            savedStateHandle = SavedStateHandle(),
            authRepository = fakeAuthRepository,
            gatewayAdapter = fakeGatewayAdapter,
            vaultStorage = rawVault
        )
        vm.reloadProfiles()

        // Delete active non-first profile p2
        val deleteJob = vm.deleteProfile("p2")
        deleteJob.join()

        // p2 credential must be deleted
        assertNull("Deleted profile's credential must be removed", rawVault.getAuthCredential("p2"))

        // Remaining profile p1's credential must be preserved (HIGH-2)
        val loadedP1 = rawVault.getAuthCredential("p1")
        assertNotNull("Remaining profile's credential must NOT be destroyed by null-fallthrough", loadedP1)
        assertEquals("p1-secret", loadedP1?.cookies?.get("session"))
    }

    @Test
    fun switchProfile_invalidatesGateAndCancelsStalePreflight() = runBlocking {
        val p1 = ConnectionProfile("p1", "http://p1.hermes.internal:9119", "Profile 1")
        val p2 = ConnectionProfile("p2", "http://p2.hermes.internal:9119", "Profile 2")
        rawVault.saveConnectionProfile(p1)
        rawVault.saveConnectionProfile(p2)
        rawVault.setActiveProfileId("p1")

        val preflightDeferred = CompletableDeferred<HostStatus>()
        fakeAuthRepository.customCheckStatus = { _ ->
            Result.success(preflightDeferred.await())
        }

        val vm = ConnectViewModel(
            savedStateHandle = SavedStateHandle(),
            authRepository = fakeAuthRepository,
            gatewayAdapter = fakeGatewayAdapter,
            vaultStorage = rawVault
        )
        vm.reloadProfiles()

        // Start preflight on p1
        vm.updateHost("p1.hermes.internal")
        vm.updatePort("9119")
        vm.testConnection()

        assertEquals(ConnectState.PREFLIGHT_LOADING, vm.uiState.value.state)

        // Current generation during preflight
        val preflightGeneration = vm.profileGenerationGate.currentGeneration()

        // Now trigger switch to p2 while preflight is suspended
        val switchJob = vm.switchProfile("p2")

        // Wait until switchProfile has progressed past gate invalidation
        while (vm.profileGenerationGate.isCurrent(preflightGeneration)) {
            kotlinx.coroutines.yield()
        }

        // Switch invalidates gate
        assertFalse(
            "Preflight generation must be invalidated by profile switch",
            vm.profileGenerationGate.isCurrent(preflightGeneration)
        )

        // Now let preflight complete
        preflightDeferred.complete(
            HostStatus(version = "1.0", authRequired = false, gatewayRunning = true)
        )
        switchJob.join()

        // Verify active profile is p2, not overridden by stale p1 preflight
        assertEquals("p2", rawVault.getActiveProfileId())
        assertEquals("p2", vm.profilesUiState.value.activeProfileId)
    }

    @Test
    fun addMode_insertsNewLocalIdWhileEditModePreservesExisting() = runBlocking {
        val original = ConnectionProfile("prof-original", "http://first.hermes.internal:9119", "First Host")
        rawVault.saveConnectionProfile(original)
        rawVault.setActiveProfileId("prof-original")

        fakeAuthRepository.customCheckStatus = {
            Result.success(HostStatus(version = "1.0", authRequired = false, gatewayRunning = true))
        }
        fakeAuthRepository.customHasSession = { true }
        fakeAuthRepository.customMintTicket = {
            Result.success(GatewayTicket("ticket", 60))
        }
        fakeGatewayAdapter.connectResult = true

        val vm = ConnectViewModel(
            savedStateHandle = SavedStateHandle(),
            authRepository = fakeAuthRepository,
            gatewayAdapter = fakeGatewayAdapter,
            vaultStorage = rawVault
        )
        vm.reloadProfiles()

        // Wait for initial profile restore from init coroutine
        withTimeout(5_000) {
            vm.uiState.filter { it.serverNameDraft == "First Host" }.first()
        }

        // 1. Edit Mode: update label and connect
        assertFalse(vm.isAddMode)
        vm.updateHost("first.hermes.internal")
        vm.updatePort("9119")
        vm.updateServerName("Updated First Host")
        vm.testConnection()
        withTimeout(5_000) {
            while (true) {
                val p = rawVault.getConnectionProfile()
                if (p?.displayLabel == "Updated First Host") break
                kotlinx.coroutines.delay(10)
            }
        }

        // Verify edit mode preserved localId
        var profiles = rawVault.listConnectionProfiles()
        assertEquals(1, profiles.size)
        assertEquals("prof-original", profiles[0].localId)
        assertEquals("Updated First Host", profiles[0].displayLabel)

        // 2. Add Mode: enter add mode and connect to a new host
        vm.enterAddProfileMode()
        assertTrue(vm.isAddMode)
        assertEquals("Hermes", vm.uiState.value.serverNameDraft)
        assertEquals("", vm.uiState.value.hostDraft)

        vm.updateServerName("Second Host")
        vm.updateHost("second.hermes.internal")
        vm.updatePort("9119")
        vm.testConnection()
        withTimeout(5_000) {
            while (true) {
                val list = rawVault.listConnectionProfiles()
                if (list.size == 2 && list.any { it.displayLabel == "Second Host" } && !vm.isAddMode) break
                kotlinx.coroutines.delay(10)
            }
        }

        // Assert add mode is reset and a new profile was inserted with fresh localId
        assertFalse("Add mode must reset after successful connect", vm.isAddMode)
        profiles = rawVault.listConnectionProfiles()
        assertEquals(2, profiles.size)

        val secondProfile = profiles.find { it.displayLabel == "Second Host" }
        assertNotNull("Second profile must be persisted", secondProfile)
        assertNotEquals("prof-original", secondProfile!!.localId)
        assertEquals(secondProfile.localId, rawVault.getActiveProfileId())
        assertEquals(secondProfile.localId, vm.profilesUiState.value.activeProfileId)
    }

    @Test
    fun switchProfile_blockedWhenTargetIsSameOrMissing() = runBlocking {
        val p1 = ConnectionProfile("p1", "http://p1.hermes.internal:9119", "Profile 1")
        val p2 = ConnectionProfile("p2", "http://p2.hermes.internal:9119", "Profile 2")
        rawVault.saveConnectionProfile(p1)
        rawVault.saveConnectionProfile(p2)
        rawVault.setActiveProfileId("p1")

        val vm = ConnectViewModel(
            savedStateHandle = SavedStateHandle(),
            authRepository = fakeAuthRepository,
            gatewayAdapter = fakeGatewayAdapter,
            vaultStorage = rawVault,
            onGatewayDisconnected = { eventLog.add("onGatewayDisconnected") }
        )
        vm.reloadProfiles()
        eventLog.clear()

        // Attempt switch to same profile
        vm.switchProfile("p1").join()
        assertTrue("Switch to same profile must be blocked (no disconnect)", eventLog.isEmpty())
        assertEquals("p1", rawVault.getActiveProfileId())

        // Attempt switch to nonexistent profile
        vm.switchProfile("missing-id").join()
        assertTrue("Switch to missing profile must be blocked (no disconnect)", eventLog.isEmpty())
        assertEquals("p1", rawVault.getActiveProfileId())
    }

    // --- Test Doubles ---

    private class TestAuthRepository(
        private val eventLog: MutableList<String>,
        private val vault: VaultStorage? = null
    ) : AuthRepository {
        var customCheckStatus: (suspend (CanonicalOrigin) -> Result<HostStatus>)? = null
        var customHasSession: (suspend (CanonicalOrigin?) -> Boolean)? = null
        var customMintTicket: (suspend (CanonicalOrigin) -> Result<GatewayTicket>)? = null

        override suspend fun checkStatus(origin: CanonicalOrigin): Result<HostStatus> {
            return customCheckStatus?.invoke(origin)
                ?: Result.success(HostStatus(version = "1.0", authRequired = false, gatewayRunning = true))
        }

        override suspend fun hasValidSession(origin: CanonicalOrigin?): Boolean {
            return customHasSession?.invoke(origin) ?: false
        }

        override suspend fun mintWebSocketTicket(origin: CanonicalOrigin): Result<GatewayTicket> {
            return customMintTicket?.invoke(origin) ?: Result.success(GatewayTicket("ticket", 60))
        }

        override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {
            eventLog.add("auth.signOut")
            vault?.clearAuthCredential(cacheChoice = cacheChoice)
        }

        override suspend fun notifyAuthExpired() {}
    }

    private class TestGatewayAdapter(
        private val eventLog: MutableList<String>,
        private val delegate: FakeGatewayProtocolAdapter = FakeGatewayProtocolAdapter()
    ) : GatewayProtocolAdapter by delegate {
        var connectResult: Boolean = true

        override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean {
            if (connectResult) {
                delegate.connectionStatus.value = ConnectionStatus.CONNECTED
            }
            return connectResult
        }

        override fun disconnect() {
            eventLog.add("gateway.disconnect")
            delegate.connectionStatus.value = ConnectionStatus.OFFLINE
        }
    }
}
