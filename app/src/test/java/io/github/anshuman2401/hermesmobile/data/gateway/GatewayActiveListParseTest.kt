package io.github.anshuman2401.hermesmobile.data.gateway

import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.session.DefaultSessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayActiveListParseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var transport: FakeGatewayTransport
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var adapter: DefaultGatewayProtocolAdapter
    private val validTicket = GatewayTicket("safe-test-ticket-xyz", 30)

    @Before
    fun setUp() {
        transport = FakeGatewayTransport()
        adapter = DefaultGatewayProtocolAdapter(
            transport = transport,
            dispatcher = testDispatcher,
            readyTimeoutMs = 1_000L,
            rpcTimeoutMs = 1_000L
        )
    }

    @Test
    fun listActiveSessions_fullRow_parsedSuccessfully() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        transport.setRpcHandler("session.active_list") { id, _ ->
            transport.respondSuccess(
                id,
                """{
                    "sessions": [
                        {
                            "id": "live-1",
                            "session_key": "stored-key-1",
                            "model": "claude-3-5-sonnet",
                            "message_count": 7,
                            "status": "idle",
                            "last_active": 1726830000.5,
                            "started_at": 1726820000.0,
                            "title": "Test Title",
                            "preview": "Test preview",
                            "current": true
                        }
                    ]
                }"""
            )
        }

        val result = adapter.listActiveSessions()
        assertTrue(result is GatewayResult.Success)
        val sessions = (result as GatewayResult.Success<ActiveSessionListResult>).value.sessions
        assertEquals(1, sessions.size)
        val item = sessions[0]
        assertEquals("live-1", item.id)
        assertEquals("stored-key-1", item.sessionKey)
        assertEquals("claude-3-5-sonnet", item.model)
        assertEquals(7, item.messageCount)
        assertEquals("idle", item.status)
        assertEquals(1726830000.5, item.lastActive ?: 0.0, 0.001)
    }

    @Test
    fun listActiveSessions_missingModel_modelIsNull() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        transport.setRpcHandler("session.active_list") { id, _ ->
            transport.respondSuccess(
                id,
                """{
                    "sessions": [
                        {
                            "id": "live-2",
                            "session_key": "stored-key-2",
                            "message_count": 0,
                            "status": "working"
                        }
                    ]
                }"""
            )
        }

        val result = adapter.listActiveSessions()
        assertTrue(result is GatewayResult.Success)
        val sessions = (result as GatewayResult.Success<ActiveSessionListResult>).value.sessions
        assertEquals(1, sessions.size)
        val item = sessions[0]
        assertEquals("live-2", item.id)
        assertEquals("stored-key-2", item.sessionKey)
        assertNull(item.model)
        assertEquals(0, item.messageCount)
        assertEquals("working", item.status)
    }

    @Test
    fun listActiveSessions_emptySessionsArray_returnsEmptyList() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        transport.setRpcHandler("session.active_list") { id, _ ->
            transport.respondSuccess(
                id,
                """{
                    "sessions": []
                }"""
            )
        }

        val result = adapter.listActiveSessions()
        assertTrue(result is GatewayResult.Success)
        val sessions = (result as GatewayResult.Success<ActiveSessionListResult>).value.sessions
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun listActiveSessions_missingSessionsArray_returnsIncompatibleProtocolFailure() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        transport.setRpcHandler("session.active_list") { id, _ ->
            transport.respondSuccess(
                id,
                """{
                    "wrong_field": []
                }"""
            )
        }

        val result = adapter.listActiveSessions()
        assertTrue(result is GatewayResult.Failure)
        val failure = (result as GatewayResult.Failure).failure
        assertTrue(failure is GatewayFailure.IncompatibleProtocol)
        assertEquals("session.active_list missing 'sessions' array", (failure as GatewayFailure.IncompatibleProtocol).reason)
    }

    @Test
    fun repository_gatewayFailure_returnsEmptyMapNoThrow() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        val storageDir = tempFolder.newFolder("sess_repo_fail_test")
        val vault = EncryptedVault(
            storageDir,
            AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
        transport.setRpcHandler("session.active_list") { id, _ ->
            transport.respondError(id, 5000, "Internal gateway failure")
        }
        val repo = DefaultSessionRepository(adapter, vault)
        val result = repo.activeSessionModels()
        assertTrue(result.isSuccess)
        val map = result.getOrThrow()
        assertTrue(map.isEmpty())
    }

    @Test
    fun repository_activeSessionModels_keysBothIdAndSessionKey() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        val storageDir = tempFolder.newFolder("sess_repo_active_test")
        val vault = EncryptedVault(
            storageDir,
            AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
        transport.setRpcHandler("session.active_list") { id, _ ->
            transport.respondSuccess(
                id,
                """{
                    "sessions": [
                        {
                            "id": "runtime-sid-1",
                            "session_key": "durable-key-1",
                            "model": "gpt-4o",
                            "message_count": 3
                        }
                    ]
                }"""
            )
        }
        val repo = DefaultSessionRepository(adapter, vault)
        val result = repo.activeSessionModels()
        assertTrue(result.isSuccess)
        val map = result.getOrThrow()
        assertEquals("gpt-4o", map["runtime-sid-1"])
        assertEquals("gpt-4o", map["durable-key-1"])
    }
}
