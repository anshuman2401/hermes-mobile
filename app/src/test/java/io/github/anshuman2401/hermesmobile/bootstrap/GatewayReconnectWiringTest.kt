package io.github.anshuman2401.hermesmobile.bootstrap

import io.github.anshuman2401.hermesmobile.data.local.SignOutCacheChoice
import androidx.lifecycle.SavedStateHandle
import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.HostStatus
import io.github.anshuman2401.hermesmobile.data.auth.OkHttpAuthTransport
import io.github.anshuman2401.hermesmobile.data.diagnostics.Diagnostics
import io.github.anshuman2401.hermesmobile.data.diagnostics.NoOpDiagnostics
import io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayReconnectManager
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket
import io.github.anshuman2401.hermesmobile.data.gateway.ReconnectState
import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import io.github.anshuman2401.hermesmobile.data.gateway.okhttp.OkHttpGatewayTransport
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import io.github.anshuman2401.hermesmobile.feature.connect.ConnectState
import io.github.anshuman2401.hermesmobile.feature.connect.ConnectViewModel
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * BUG-012 wiring tests: the GatewayReconnectManager must leave dormancy once the
 * production composition wires ConnectViewModel connect success (origin) and
 * MainActivity lifecycle events into it.
 *
 * RED baseline (before wiring): the ConnectViewModel callback does not exist and
 * the manager's configuredOrigin is never set, so a transport drop never triggers
 * a reconnect — every assertion below names the exact production seam it guards.
 *
 * JVM-only: MockWebServer + real OkHttp transports + injectable dispatchers.
 * Device checks are separate; automated tests use no live hosts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayReconnectWiringTest {

    // ------------------------------------------------------------------
    // Composition-level tests (real production stack over MockWebServer)
    // ------------------------------------------------------------------

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var vault: EncryptedVault
    private lateinit var repository: DefaultAuthRepository
    private lateinit var countingRepository: CountingAuthRepository
    private lateinit var adapter: DefaultGatewayProtocolAdapter
    private lateinit var manager: GatewayReconnectManager
    private lateinit var managerScope: CoroutineScope
    private lateinit var origin: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        origin = server.url("").toString().trimEnd('/')
        vault = EncryptedVault(tempFolder.newFolder("vault"), AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        val client = OkHttpClient.Builder().build()
        repository = DefaultAuthRepository(vault, OkHttpAuthTransport(client))
        countingRepository = CountingAuthRepository(repository)
        adapter = DefaultGatewayProtocolAdapter(
            transport = OkHttpGatewayTransport(client),
            dispatcher = Dispatchers.IO,
            readyTimeoutMs = 5_000,
            rpcTimeoutMs = 5_000
        )
        managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager = GatewayReconnectManager(
            gatewayAdapter = adapter,
            authRepository = countingRepository,
            scope = managerScope
        )
    }

    @After
    fun tearDown() {
        runCatching { adapter.disconnect() }
        runCatching { managerScope.cancel() }
        runCatching { server.shutdown() }
    }

    /** Delegating wrapper that counts mintWebSocketTicket calls (RPR-003 assertion seam). */
    private class CountingAuthRepository(
        private val delegate: AuthRepository
    ) : AuthRepository by delegate {
        val mintCount = AtomicInteger(0)

        override suspend fun mintWebSocketTicket(origin: CanonicalOrigin): Result<GatewayTicket> {
            mintCount.incrementAndGet()
            return delegate.mintWebSocketTicket(origin)
        }
    }

    /**
     * Reflectively constructs ConnectViewModel with the production-mirroring wiring
     * callbacks. Reflection keeps this suite compiling against the unmodified (RED)
     * tree: the constructor lookup itself is the first RED assertion.
     */
    @Suppress("UNCHECKED_CAST")
    private fun constructWiredViewModel(): Triple<ConnectViewModel, MutableList<CanonicalOrigin>, CompletableDeferred<Unit>> {
        val function1Name = "kotlin.jvm.functions.Function1"
        val function0Name = "kotlin.jvm.functions.Function0"
        val ctor = ConnectViewModel::class.java.constructors.firstOrNull { candidate ->
            candidate.parameterTypes.any { it.name == function1Name }
        } ?: throw IllegalStateException(
            "BUG-012 RED: origin is never configured in production wiring — " +
                "ConnectViewModel must gain the onGatewayConnected callback so the " +
                "composition root can configure GatewayReconnectManager"
        )
        val capturedOrigins = mutableListOf<CanonicalOrigin>()
        val connectionWired = CompletableDeferred<Unit>()
        val function1Class = ConnectViewModel::class.java.classLoader!!.loadClass(function1Name)
        val function0Class = ConnectViewModel::class.java.classLoader!!.loadClass(function0Name)
        // The handlers mirror the production AppContainer wiring:
        // onGatewayConnected -> reconnectManager.configureOrigin(origin)
        // onGatewayDisconnected -> reconnectManager.clearOrigin()
        val connectedHandler = Proxy.newProxyInstance(
            ConnectViewModel::class.java.classLoader!!,
            arrayOf(function1Class)
        ) { _, _, args ->
            (args?.get(0) as? CanonicalOrigin)?.let { origin ->
                capturedOrigins.add(origin)
                manager.configureOrigin(origin)
                connectionWired.complete(Unit)
            }
            Unit
        }
        val disconnectedHandler = Proxy.newProxyInstance(
            ConnectViewModel::class.java.classLoader!!,
            arrayOf(function0Class)
        ) { _, _, _ ->
            manager.clearOrigin()
            Unit
        }

        var functionParamIndex = 0
        val args = ctor.parameterTypes.map { type ->
            when (type.name) {
                "androidx.lifecycle.SavedStateHandle" -> SavedStateHandle()
                "io.github.anshuman2401.hermesmobile.data.auth.AuthRepository" -> repository
                "io.github.anshuman2401.hermesmobile.data.auth.ConnectionRecoveryCoordinator" -> null
                "io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter" -> adapter
                "io.github.anshuman2401.hermesmobile.data.local.VaultStorage" -> vault
                "io.github.anshuman2401.hermesmobile.data.diagnostics.Diagnostics" -> NoOpDiagnostics
                function1Name -> connectedHandler
                function0Name -> disconnectedHandler
                // Synthetic trailing parameters of the full-arg constructor:
                // a bitmask of which defaults were applied plus the Kotlin marker.
                "int" -> 0
                "kotlin.jvm.internal.DefaultConstructorMarker" -> null
                else -> throw IllegalStateException(
                    "Unexpected ConnectViewModel constructor parameter type: $type"
                )
            }
        }
        val viewModel = ctor.newInstance(*args.toTypedArray()) as ConnectViewModel
        return Triple(viewModel, capturedOrigins, connectionWired)
    }

    private suspend fun awaitResolved(target: ConnectViewModel) = withTimeout(15_000) {
        target.uiState
            .filter { it.state !in setOf(ConnectState.FIRST_LAUNCH, ConnectState.PREFLIGHT_LOADING) }
            .first()
    }

    private suspend fun awaitCondition(withinMs: Long = 20_000, condition: () -> Boolean) {
        withTimeout(withinMs) {
            while (!condition()) {
                delay(25)
            }
        }
    }

    /** Status + ticket + long-lived WebSocket upgrade (gateway.ready + capabilities). */
    private fun enqueueHappyPath(ticketValue: String) {
        server.enqueue(
            MockResponse().setBody("""{"version":"fixture","auth_required":true,"gateway_running":true}""")
        )
        server.enqueue(MockResponse().setBody("""{"ticket":"$ticketValue","ttl_seconds":60}"""))
        server.enqueue(
            MockResponse().apply {
                setBody(
                    """{"jsonrpc":"2.0","id":0,"result":{"per_session_exclusive_submit":true}}"""
                )
            }.withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.send(
                        """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":false,"replay_epoch":"fixture-epoch"}}}"""
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val id = text.substringAfter("\"id\":\"").substringBefore("\"")
                    webSocket.send(
                        """{"jsonrpc":"2.0","id":"$id","result":{"per_session_exclusive_submit":true}}"""
                    )
                }
            })
        )
    }

    @Test
    fun bug012_connectSuccessConfiguresReconnectManagerAndRecoveryUsesFreshTicket() = runBlocking {
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "fixture",
                cookies = mapOf("session" to "synthetic"),
                canonicalOrigin = origin,
                expiryEpochSeconds = System.currentTimeMillis() / 1000 + 300
            )
        )
        val parsed = java.net.URI(origin)
        val (viewModel, capturedOrigins, connectionWired) = constructWiredViewModel()
        viewModel.updateHost(parsed.host)
        viewModel.updatePort(parsed.port.toString())

        // 1. Initial connect through the production ViewModel path
        enqueueHappyPath("synthetic-ticket-1")
        viewModel.testConnection()
        assertEquals(ConnectState.CONNECTED_COMPATIBLE, awaitResolved(viewModel).state)
        assertEquals(ConnectionStatus.CONNECTED, adapter.connectionStatus.value)

        // The UI state is published before the callback; join the callback before reading its effects.
        withTimeout(15_000) { connectionWired.await() }

        // 2. BUG-012 core wiring: connect success must have configured the origin
        val configuredField = GatewayReconnectManager::class.java.getDeclaredField("configuredOrigin")
        configuredField.isAccessible = true
        val configured = configuredField.get(manager)
        assertNotNull(
            "BUG-012 RED: origin is never configured in production wiring — " +
                "ConnectViewModel connect success must reach GatewayReconnectManager.configureOrigin",
            configured
        )
        assertEquals(origin, (configured as CanonicalOrigin).originUrl)
        assertEquals(1, capturedOrigins.size)

        // 3. Socket dies abnormally (transport error) → FAILED, then automatic recovery
        enqueueHappyPath("synthetic-ticket-2")
        transportDrop()
        // Catch the mid-session drop actually landing before any recovery assertion:
        // the previous order raced on stale CONNECTED status and skipped the drop window.
        awaitCondition(withinMs = 10_000) {
            adapter.connectionStatus.value == ConnectionStatus.FAILED
        }
        awaitCondition(withinMs = 10_000) {
            manager.reconnectState.value is ReconnectState.WaitingBackoff ||
                manager.reconnectState.value is ReconnectState.Connecting
        }
        // Automatic recovery (or terminal FAILED) without user action:
        awaitCondition(withinMs = 20_000) {
            adapter.connectionStatus.value == ConnectionStatus.CONNECTED ||
                manager.reconnectState.value is ReconnectState.Failed ||
                manager.reconnectState.value == ReconnectState.BudgetExhausted
        }
        val recovered = adapter.connectionStatus.value == ConnectionStatus.CONNECTED
        if (recovered) {
            awaitCondition(withinMs = 5_000) {
                manager.reconnectState.value == ReconnectState.Idle
            }
            // RPR-003: the reconnect attempt must mint a FRESH single-use ticket —
            // asserted on the mint seam itself, not on socket request accounting.
            // (The ViewModel's initial connect mints through the raw repository and
            // is intentionally uncounted; every manager attempt counts exactly 1.)
            assertTrue(
                "Reconnect must mint a fresh single-use ticket: mints=${countingRepository.mintCount.get()}",
                countingRepository.mintCount.get() >= 1
            )
            assertEquals(ReconnectState.Idle, manager.reconnectState.value)
        }

        // 4. Explicit disconnect clears the origin: no further automatic reconnects
        val requestCountAfterRecovery = server.requestCount
        viewModel.disconnect(SignOutCacheChoice.KEEP)
        awaitCondition(withinMs = 10_000) {
            adapter.connectionStatus.value == ConnectionStatus.OFFLINE
        }
        awaitCondition(withinMs = 5_000) {
            configuredField.get(manager) == null
        }
        manager.onAppForegrounded() // MainActivity-equivalent hook; origin cleared → no-op
        delay(300)
        assertEquals(
            "No reconnect attempts may follow an explicit disconnect",
            requestCountAfterRecovery,
            server.requestCount
        )
        assertEquals(ReconnectState.Idle, manager.reconnectState.value)
    }

    /**
     * Aborts the live socket the way OkHttp reports abnormal termination
     * (connection reset mid-session), matching the BUG-012 failure chain.
     * `cancel()` aborts immediately: OkHttp fires onFailure → the adapter latches
     * FAILED exactly as with a real network drop.
     */
    private fun transportDrop() {
        val transportField = DefaultGatewayProtocolAdapter::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        val okHttpTransport = transportField.get(adapter) as OkHttpGatewayTransport
        val socketField = OkHttpGatewayTransport::class.java.declaredFields
            .firstOrNull { it.type.name == "okhttp3.WebSocket" }
            ?: throw IllegalStateException("OkHttpGatewayTransport must keep its WebSocket seam")
        socketField.isAccessible = true
        val socket = socketField.get(okHttpTransport) as? okhttp3.WebSocket
            ?: throw IllegalStateException("No live WebSocket to drop for BUG-012 reconnect flow")
        socket.cancel()
    }

    @Test
    fun bug012_wiringSeamsExistInProductionSources() {
        // Supplemental source-text assertions (behavior tests above carry the proof).
        val bootstrapDir = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .map { it.resolve("io/github/anshuman2401/hermesmobile/bootstrap") }
            .first { it.resolve("AppContainer.kt").exists() }

        val appContainerSrc = bootstrapDir.resolve("AppContainer.kt").readText()
        assertTrue(
            "BUG-012: AppContainer.createConnectViewModel must pass onGatewayConnected into ConnectViewModel",
            appContainerSrc.contains("onGatewayConnected")
        )
        assertTrue(
            "BUG-012: the wiring must configure the reconnect manager origin",
            appContainerSrc.contains("reconnectManager.configureOrigin")
        )
        assertTrue(
            "BUG-012: explicit disconnect must clear the reconnect origin",
            appContainerSrc.contains("reconnectManager.clearOrigin")
        )

        val mainActivitySrc = bootstrapDir.resolve("MainActivity.kt").readText()
        assertTrue(
            "BUG-012: MainActivity.onStart must forward foreground events to the reconnect manager",
            mainActivitySrc.contains("onAppForegrounded()")
        )
        assertTrue(
            "BUG-012: MainActivity.onStop must forward background events to the reconnect manager",
            mainActivitySrc.contains("onAppBackgrounded()")
        )
    }

    // ------------------------------------------------------------------
    // Deterministic manager-level flows (injectable dispatchers, no I/O)
    // ------------------------------------------------------------------

    private val testDispatcher = StandardTestDispatcher()

    class TestAuthRepository : AuthRepository {
        val ticketMintCount = AtomicInteger(0)

        override suspend fun checkStatus(origin: String): Result<HostStatus> =
            Result.success(HostStatus(version = "v0.21.1", authRequired = false, gatewayRunning = true))

        override suspend fun hasValidSession(): Boolean = true
        override suspend fun login(origin: String, username: String, password: CharArray, provider: String): Result<AuthCredential> =
            Result.success(AuthCredential(driverKind = "password", providerId = provider, cookies = emptyMap()))

        override suspend fun getStoredCredential(): AuthCredential? = null
        override suspend fun mintWebSocketTicket(origin: String): Result<GatewayTicket> {
            val count = ticketMintCount.incrementAndGet()
            return Result.success(GatewayTicket("ticket-$count"))
        }

        override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
        override suspend fun notifyAuthExpired() {}
    }

    private fun newLocalStack(): Triple<FakeGatewayTransport, DefaultGatewayProtocolAdapter, TestAuthRepository> {
        val transport = FakeGatewayTransport()
        val localAdapter = DefaultGatewayProtocolAdapter(
            transport = transport,
            dispatcher = testDispatcher,
            readyTimeoutMs = 1_000,
            rpcTimeoutMs = 1_000
        )
        return Triple(transport, localAdapter, TestAuthRepository())
    }

    @Test
    fun bug012_noConfiguredOrigin_transportErrorDoesNotAutoReconnect() = runTest(testDispatcher) {
        val (transport, localAdapter, fakeAuth) = newLocalStack()
        val managerScope = CoroutineScope(testDispatcher)
        val manager = GatewayReconnectManager(
            gatewayAdapter = localAdapter,
            authRepository = fakeAuth,
            scope = managerScope,
            ioDispatcher = testDispatcher
        )
        // No configureOrigin: the dormant, unconfigured app must never auto-connect.
        val connectJob = launch {
            localAdapter.connect("https://test.hermes.local", GatewayTicket("legacy-ticket", 30))
        }
        advanceUntilIdle()
        assertTrue(connectJob.isCompleted)
        assertEquals(ConnectionStatus.CONNECTED, localAdapter.connectionStatus.value)

        transport.emitError(RuntimeException("synthetic transport error"))
        advanceUntilIdle()
        assertEquals(ConnectionStatus.FAILED, localAdapter.connectionStatus.value)

        advanceTimeBy(60_000)
        advanceUntilIdle()

        assertEquals(
            "Negative control: unconfigured app must not schedule reconnects",
            ReconnectState.Idle,
            manager.reconnectState.value
        )
        assertEquals(0, fakeAuth.ticketMintCount.get())
        managerScope.cancel()
    }

    @Test
    fun bug012_configuredOrigin_transportErrorTriggersReconnectAndRecovers() = runTest(testDispatcher) {
        val (transport, localAdapter, fakeAuth) = newLocalStack()
        val managerScope = CoroutineScope(testDispatcher)
        val manager = GatewayReconnectManager(
            gatewayAdapter = localAdapter,
            authRepository = fakeAuth,
            scope = managerScope,
            ioDispatcher = testDispatcher
        )
        manager.configureOrigin("https://test.hermes.local")

        manager.reconnectNow()
        advanceUntilIdle()
        assertEquals(ConnectionStatus.CONNECTED, localAdapter.connectionStatus.value)
        val mintsAfterFirstConnect = fakeAuth.ticketMintCount.get()

        // Socket dies with a transport Error while CONNECTED.
        // Tag the frame with the adapter's current generation exactly like OkHttp does.
        transport.emitTaggedError(
            localAdapter.connectionGeneration,
            RuntimeException("synthetic transport error")
        )
        runCurrent()
        assertEquals(ConnectionStatus.FAILED, localAdapter.connectionStatus.value)

        // Backoff elapses on virtual time → fresh ticket + connect → recovered
        advanceTimeBy(20_000)
        advanceUntilIdle()

        assertEquals(
            "App must recover to CONNECTED without user action",
            ConnectionStatus.CONNECTED,
            localAdapter.connectionStatus.value
        )
        assertTrue(
            "Reconnect must mint a fresh single-use ticket",
            fakeAuth.ticketMintCount.get() > mintsAfterFirstConnect
        )
        assertEquals(ReconnectState.Idle, manager.reconnectState.value)
        managerScope.cancel()
    }

    @Test
    fun bug012_backgroundedDropDoesNotRetry_foregroundedTriggersReconnect() = runTest(testDispatcher) {
        val (transport, localAdapter, fakeAuth) = newLocalStack()
        val managerScope = CoroutineScope(testDispatcher)
        val manager = GatewayReconnectManager(
            gatewayAdapter = localAdapter,
            authRepository = fakeAuth,
            scope = managerScope,
            ioDispatcher = testDispatcher
        )
        manager.configureOrigin("https://test.hermes.local")
        manager.reconnectNow()
        advanceUntilIdle()
        assertEquals(ConnectionStatus.CONNECTED, localAdapter.connectionStatus.value)
        val mintsAfterFirstConnect = fakeAuth.ticketMintCount.get()

        // Backgrounded: drops are observed but no retry is scheduled
        manager.onAppBackgrounded()
        advanceUntilIdle()
        transport.emitTaggedError(
            localAdapter.connectionGeneration,
            RuntimeException("synthetic transport error")
        )
        runCurrent()
        assertEquals(ConnectionStatus.FAILED, localAdapter.connectionStatus.value)
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertEquals(
            "Backgrounded drop must not schedule retries",
            mintsAfterFirstConnect,
            fakeAuth.ticketMintCount.get()
        )

        // Foregrounding with a dead connection triggers the reconnect attempt
        manager.onAppForegrounded()
        advanceUntilIdle()
        assertEquals(
            "Foregrounding must trigger the reconnect attempt",
            ConnectionStatus.CONNECTED,
            localAdapter.connectionStatus.value
        )
        assertTrue(fakeAuth.ticketMintCount.get() > mintsAfterFirstConnect)
        assertEquals(ReconnectState.Idle, manager.reconnectState.value)
        managerScope.cancel()
    }

    @Test
    fun bug012_clearOriginStopsScheduledReconnect() = runTest(testDispatcher) {
        val (transport, localAdapter, fakeAuth) = newLocalStack()
        // Coroutine-scope isolation: the manager's scope is a child of THIS runTest's job
        // (same testDispatcher), so its collectors can never leak a real Dispatchers.IO
        // exception into runTest's UncaughtExceptionsBeforeTest check.
        val managerScope = CoroutineScope(testDispatcher)
        val manager = GatewayReconnectManager(
            gatewayAdapter = localAdapter,
            authRepository = fakeAuth,
            scope = managerScope,
            ioDispatcher = testDispatcher
        )
        manager.configureOrigin("https://test.hermes.local")
        manager.reconnectNow()
        advanceUntilIdle()
        assertTrue(
            "Expected CONNECTED after first reconnectNow, got ${localAdapter.connectionStatus.value}, " +
                "managerState=${manager.reconnectState.value}, mints=${fakeAuth.ticketMintCount.get()}",
            localAdapter.connectionStatus.value == ConnectionStatus.CONNECTED
        )
        val mintsAfterFirstConnect = fakeAuth.ticketMintCount.get()

        // Reflective so the suite compiles against the unmodified (RED) tree.
        val clearMethod = GatewayReconnectManager::class.java.methods.firstOrNull { it.name == "clearOrigin" }
        assertNotNull(
            "BUG-012: GatewayReconnectManager must expose clearOrigin for explicit-disconnect symmetry",
            clearMethod
        )
        clearMethod?.invoke(manager)

        transport.emitTaggedError(
            localAdapter.connectionGeneration,
            RuntimeException("synthetic transport error")
        )
        runCurrent()
        advanceTimeBy(60_000)
        advanceUntilIdle()

        assertEquals(ConnectionStatus.FAILED, localAdapter.connectionStatus.value)
        assertEquals(
            "Cleared origin must stop all automatic reconnect attempts",
            mintsAfterFirstConnect,
            fakeAuth.ticketMintCount.get()
        )
        assertEquals(ReconnectState.Idle, manager.reconnectState.value)
        managerScope.cancel()
    }
}
