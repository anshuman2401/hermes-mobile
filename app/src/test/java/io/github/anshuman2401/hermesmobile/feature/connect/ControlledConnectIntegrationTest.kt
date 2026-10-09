package io.github.anshuman2401.hermesmobile.feature.connect

import androidx.lifecycle.SavedStateHandle
import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.OkHttpAuthTransport
import io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTransport
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.okhttp.OkHttpGatewayTransport
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ControlledConnectIntegrationTest {
    @get:Rule val tempFolder = TemporaryFolder()
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private lateinit var server: MockWebServer
    private lateinit var vault: EncryptedVault
    private lateinit var repository: DefaultAuthRepository
    private lateinit var adapter: DefaultGatewayProtocolAdapter
    private lateinit var viewModel: ConnectViewModel
    private lateinit var origin: String

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        origin = server.url("").toString().trimEnd('/')
        vault = EncryptedVault(tempFolder.newFolder("vault"), AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        val client = OkHttpClient.Builder().build()
        repository = DefaultAuthRepository(vault, OkHttpAuthTransport(client))
        adapter = DefaultGatewayProtocolAdapter(OkHttpGatewayTransport(client), readyTimeoutMs = 3_000, rpcTimeoutMs = 3_000)
        viewModel = ConnectViewModel(SavedStateHandle(), repository, adapter, vault)
    }

    @After fun tearDown() {
        adapter.disconnect()
        runCatching { server.shutdown() }
    }

    private suspend fun awaitResolved(target: ConnectViewModel = viewModel) = withTimeout(5_000) {
        target.uiState.filter { it.state !in setOf(ConnectState.FIRST_LAUNCH, ConnectState.PREFLIGHT_LOADING) }.first()
    }

    private suspend fun awaitStoredProfileRestore(target: ConnectViewModel) = withTimeout(5_000) {
        target.uiState.filter { it.hostDraft == java.net.URI(origin).host && it.portDraft == java.net.URI(origin).port.toString() }.first()
    }

    private suspend fun awaitPersistedServerName() = withTimeout(5_000) {
        while (true) {
            val profile = vault.getConnectionProfile()
            if (profile?.displayLabel == "Hermes") return@withTimeout
            delay(10)
        }
    }

    @Test fun controlledHttpAndWsPathConnectsOnlyAfterReadyAndCapabilities() = runBlocking {
        vault.saveAuthCredential(AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "fixture",
            cookies = mapOf("session" to "synthetic"),
            canonicalOrigin = origin,
            expiryEpochSeconds = System.currentTimeMillis() / 1000 + 60
        ))
        server.enqueue(MockResponse().setBody("""{"version":"fixture","auth_required":true,"gateway_running":true}"""))
        server.enqueue(MockResponse().setBody("""{"ticket":"synthetic-ticket","ttl_seconds":60}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"fixture"}}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val id = GatewayJson.parseOrNull(text)?.asObjectOrNull()?.get("id")?.asStringOrNull() ?: return
                webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":{"per_session_exclusive_submit":true}}""")
            }
        }))

        val parsed = java.net.URI(origin)
        viewModel.updateServerName("Office gateway")
        viewModel.updateHost(parsed.host)
        viewModel.updatePort(parsed.port.toString())
        assertEquals(origin, viewModel.uiState.value.origin)
        viewModel.testConnection()

        assertEquals(ConnectState.CONNECTED_COMPATIBLE, awaitResolved().state)
        assertEquals("/api/status", server.takeRequest().path)
        assertEquals("/api/auth/ws-ticket", server.takeRequest().path)
        val webSocketRequest = server.takeRequest()
        assertTrue(webSocketRequest.path!!.startsWith("/api/ws"))
        assertTrue(webSocketRequest.getHeader("Sec-WebSocket-Protocol")!!.startsWith("hermes-gateway-v1, hermes-gateway-ticket."))
    }

    @Test fun malformedStructuredInputSendsNoRequest() {
        viewModel.updateHost("http://127.0.0.1")
        viewModel.testConnection()
        assertEquals(ConnectState.FIRST_LAUNCH, viewModel.uiState.value.state)
        assertTrue(viewModel.uiState.value.hostError != null)
        assertEquals(0, server.requestCount)
    }

    @Test fun redirectIsRejectedWithoutFollowingDestination() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.invalid/"))
        val parsed = java.net.URI(origin)
        viewModel.updateHost(parsed.host)
        viewModel.updatePort(parsed.port.toString())
        viewModel.testConnection()
        val state = awaitResolved()
        assertEquals(ConnectState.ORIGIN_POLICY_REJECTED, state.state)
        assertNotEquals(ConnectState.CONNECTED_COMPATIBLE, state.state)
        assertEquals(1, server.requestCount)
    }

    @Test fun hostileStoredProfileServerNamesAreReplacedBeforeUiState() = runBlocking {
        listOf(
            "H".repeat(ConnectViewModel.MAX_SERVER_NAME_LENGTH + 1),
            "Hermes\u0085Admin",
            "Hermes\u202EAdmin"
        ).forEachIndexed { index, hostileName ->
            vault.saveConnectionProfile(ConnectionProfile("profile-$index", origin, hostileName))

            val restored = ConnectViewModel(SavedStateHandle(), repository, adapter, vault)
            val restoredState = awaitStoredProfileRestore(restored)

            assertEquals("Hermes", restoredState.serverNameDraft)
            assertTrue(restoredState.errorMessage!!.startsWith("Server name"))
        }
    }

    @Test fun blankAndOrdinaryStoredProfileServerNamesRemainLocalLabels() = runBlocking {
        listOf("", "Office gateway").forEachIndexed { index, savedName ->
            vault.saveConnectionProfile(ConnectionProfile("profile-valid-$index", origin, savedName))

            val restored = ConnectViewModel(SavedStateHandle(), repository, adapter, vault)
            val restoredState = awaitStoredProfileRestore(restored)

            assertEquals(savedName, restoredState.serverNameDraft)
            assertNull(restoredState.errorMessage)
        }
    }

    @Test fun hostileStoredProfileServerNameIsNotRepersistedAfterSuccessfulConnect() = runBlocking {
        vault.saveConnectionProfile(ConnectionProfile("hostile-profile", origin, "Hermes\u202EAdmin"))
        vault.saveAuthCredential(AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "fixture",
            cookies = mapOf("session" to "synthetic"),
            canonicalOrigin = origin,
            expiryEpochSeconds = System.currentTimeMillis() / 1000 + 60
        ))
        server.enqueue(MockResponse().setBody("""{"version":"fixture","auth_required":true,"gateway_running":true}"""))
        server.enqueue(MockResponse().setBody("""{"ticket":"synthetic-ticket","ttl_seconds":60}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"fixture"}}}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val id = GatewayJson.parseOrNull(text)?.asObjectOrNull()?.get("id")?.asStringOrNull() ?: return
                webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":{"per_session_exclusive_submit":true}}""")
            }
        }))

        val restored = ConnectViewModel(SavedStateHandle(), repository, adapter, vault)
        assertEquals("Hermes", awaitStoredProfileRestore(restored).serverNameDraft)
        restored.testConnection()

        assertEquals(ConnectState.CONNECTED_COMPATIBLE, awaitResolved(restored).state)
        awaitPersistedServerName()
        assertEquals("Hermes", vault.getConnectionProfile()!!.displayLabel)
    }
}
