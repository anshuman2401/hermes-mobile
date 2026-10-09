package io.github.anshuman2401.hermesmobile.feature.connect

import io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport
import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.HostStatus
import io.github.anshuman2401.hermesmobile.data.auth.HttpResponse
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.local.SignOutCacheChoice
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * PR-6 TEST-05/TEST-07: explicit choice threading at the ViewModel boundary
 * and the strictly-local nature of both outcomes.
 *
 * - disconnect(cacheChoice) forwards the EXACT explicit choice — no default.
 * - The origin-change preflight reset (ConnectViewModel origin-change site)
 *   passes literal KEEP (no user present → explicit programmatic KEEP).
 * - Both choices make ZERO remote calls: fake REST transport counters stay
 *   at zero across dialog-confirm → VM → repository → vault; no remote
 *   session-delete path exists or is reachable from this slice (ADR-0003).
 */
class ConnectViewModelSignOutChoiceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class RecordingAuthRepository : AuthRepository {
        val signOutChoices = mutableListOf<SignOutCacheChoice>()
        var statusResult: Result<HostStatus> = Result.failure(IOException("offline"))

        override suspend fun checkStatus(origin: CanonicalOrigin): Result<HostStatus> = statusResult
        override suspend fun hasValidSession(origin: CanonicalOrigin?): Boolean = false
        override suspend fun mintWebSocketTicket(origin: CanonicalOrigin): Result<GatewayTicket> =
            Result.failure(UnsupportedOperationException())

        override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {
            signOutChoices += cacheChoice
        }

        override suspend fun notifyAuthExpired() = Unit
        override suspend fun login(
            origin: CanonicalOrigin,
            username: String,
            password: CharArray,
            provider: String
        ): Result<AuthCredential> = Result.failure(UnsupportedOperationException())
    }

    @Test
    fun disconnect_forwardsExactChoiceToRepository() {
        val auth = RecordingAuthRepository()
        val viewModel = ConnectViewModel(
            authRepository = auth,
            gatewayAdapter = FakeGatewayProtocolAdapter()
        )

        viewModel.disconnect(SignOutCacheChoice.CLEAR)
        assertEquals(listOf(SignOutCacheChoice.CLEAR), auth.signOutChoices)

        viewModel.disconnect(SignOutCacheChoice.KEEP)
        assertEquals(
            listOf(SignOutCacheChoice.CLEAR, SignOutCacheChoice.KEEP),
            auth.signOutChoices
        )
    }

    @Test
    fun originChange_signOutPassesExplicitKeep() = runBlocking {
        val auth = RecordingAuthRepository()
        val viewModel = ConnectViewModel(
            authRepository = auth,
            gatewayAdapter = FakeGatewayProtocolAdapter()
        )
        viewModel.updateHost("127.0.0.1")
        viewModel.updatePort("9119")
        viewModel.testConnection()
        delay(100)
        // Endpoint identity change → preflight reset with literal KEEP.
        viewModel.updatePort("9220")
        viewModel.testConnection()
        delay(100)

        assertEquals(
            "origin-change preflight must record explicit KEEP (REQ-07)",
            listOf(SignOutCacheChoice.KEEP),
            auth.signOutChoices
        )
    }

    @Test
    fun clearSignOut_makesZeroRemoteCalls_noRemoteDeletion() = runBlocking {
        val storageDir = tempFolder.newFolder("signout_zero_remote_vault")
        val vault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
        vault.saveConnectionProfile(
            ConnectionProfile(localId = "conn-1", normalizedOrigin = "http://127.0.0.1:9611", displayLabel = "Test Server")
        )
        vault.setActiveProfileId("conn-1")
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok"),
                canonicalOrigin = "http://127.0.0.1:9611"
            ),
            profileLocalId = "conn-1"
        )

        var transportCalls = 0
        val countingTransport = object : AuthHttpTransport {
            override suspend fun execute(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?
            ): HttpResponse {
                transportCalls++
                return HttpResponse(500, body = "unused")
            }
        }
        val auth = DefaultAuthRepository(vaultStorage = vault, httpTransport = countingTransport)

        // CLEAR (dialog confirm path end-to-end at VM boundary)...
        val clearVm = ConnectViewModel(
            authRepository = auth,
            gatewayAdapter = FakeGatewayProtocolAdapter()
        )
        clearVm.disconnect(SignOutCacheChoice.CLEAR)
        delay(150)

        // ...and KEEP — both strictly local; no remote session-delete path exists.
        val keepVm = ConnectViewModel(
            authRepository = auth,
            gatewayAdapter = FakeGatewayProtocolAdapter()
        )
        keepVm.disconnect(SignOutCacheChoice.KEEP)
        delay(150)

        assertEquals(
            "ADR-0003: sign-out never performs any remote HTTP request",
            0,
            transportCalls
        )
        assertEquals(null, vault.getAuthCredential("conn-1"))
    }
}
