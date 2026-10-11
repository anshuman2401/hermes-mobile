package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.fake.FakeGatewayFixtures
import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.gateway.json.JsonObject
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayConnectionLifecycleTest {
    private val origin = "http://example.test:8000"
    private val interval = 1_000L
    private val timeout = 500L

    private class Fixture(test: TestScope, heartbeat: Boolean = true) {
        val fake = FakeGatewayTransport().apply {
            if (!heartbeat) readyFrameOverride = FakeGatewayFixtures.READY_FRAME_NO_HEARTBEAT_NO_CHANGE_EVENTS
        }
        var sendOverride: (suspend (String) -> Boolean?)? = null
        private val transport = object : GatewayTransport by fake {
            override suspend fun send(text: String): Boolean = sendOverride?.invoke(text) ?: fake.send(text)
        }
        val adapter = DefaultGatewayProtocolAdapter(
            transport,
            dispatcher = StandardTestDispatcher(test.testScheduler),
            heartbeatDispatcher = StandardTestDispatcher(test.testScheduler),
            readyTimeoutMs = 500L,
            rpcTimeoutMs = 500L,
            heartbeatIntervalMs = 1_000L
        )
        fun pingCount() = fake.sentFrames.count { method(it) == "gateway.ping" }
        fun assertNoPromptWrites() = assertFalse(fake.sentFrames.any { method(it) == "prompt.submit" })
    }

    private suspend fun TestScope.connected(f: Fixture) {
        val opening = async { f.adapter.connect(origin, GatewayTicket("synthetic-ticket", 30)) }
        runCurrent()
        assertTrue(opening.await())
        assertEquals(ConnectionStatus.CONNECTED, f.adapter.connectionStatus.value)
    }

    @Test fun suspendingBeforeHeartbeatKeepsSocketOpen() = runTest {
        val f = Fixture(this)
        try {
            connected(f)
            f.adapter.suspendHeartbeat()
            advanceTimeBy(interval * 2)
            runCurrent()
            assertEquals(0, f.pingCount())
            assertEquals(ConnectionStatus.CONNECTED, f.adapter.connectionStatus.value)
            assertTrue(f.fake.isConnected)
            f.assertNoPromptWrites()
        } finally { f.adapter.disconnect() }
    }

    @Test fun alreadyCancelledPingDoesNotWriteOrDisconnect() = runTest {
        val f = Fixture(this)
        try {
            connected(f)
            val ping = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                f.adapter.ping()
            }
            runCurrent()
            assertTrue(ping.isCancelled)
            assertEquals(0, f.pingCount())
            assertEquals(ConnectionStatus.CONNECTED, f.adapter.connectionStatus.value)
            assertTrue(f.fake.isConnected)
            f.assertNoPromptWrites()
        } finally { f.adapter.disconnect() }
    }

    @Test fun suspendingUnansweredHeartbeatKeepsSocketAndCanResumeRawPong() = runTest {
        val f = Fixture(this)
        try {
            connected(f)
            f.fake.autoRespondPing = false
            advanceTimeBy(interval)
            runCurrent()
            assertEquals(1, f.pingCount())
            // Foreground can return before the suspended job's cleanup runs.
            f.adapter.suspendHeartbeat()
            f.fake.pingRawAckOnly = true
            f.adapter.resumeHeartbeat()
            runCurrent()
            assertEquals(ConnectionStatus.CONNECTED, f.adapter.connectionStatus.value)
            assertTrue(f.fake.isConnected)
            advanceTimeBy(interval + timeout)
            runCurrent()
            assertEquals(2, f.pingCount())
            assertEquals(ConnectionStatus.CONNECTED, f.adapter.connectionStatus.value)
            assertTrue(f.fake.isConnected)
            f.assertNoPromptWrites()
        } finally { f.adapter.disconnect() }
    }

    @Test fun suspendingHeartbeatDuringTransportWriteKeepsSocketOpen() = runTest {
        val f = Fixture(this)
        val writing = CompletableDeferred<Unit>()
        val blockedWrite = CompletableDeferred<Unit>()
        try {
            connected(f)
            f.sendOverride = { text ->
                if (method(text) == "gateway.ping") { writing.complete(Unit); blockedWrite.await() }
                null
            }
            advanceTimeBy(interval)
            runCurrent()
            assertTrue(writing.isCompleted)
            f.adapter.suspendHeartbeat()
            runCurrent()
            assertEquals(ConnectionStatus.CONNECTED, f.adapter.connectionStatus.value)
            assertTrue(f.fake.isConnected)
        } finally { f.adapter.disconnect() }
    }

    @Test fun unansweredHeartbeatStillFailsConnection() = runTest {
        val f = Fixture(this)
        try {
            connected(f)
            f.fake.autoRespondPing = false
            advanceTimeBy(interval + timeout)
            runCurrent()
            assertEquals(ConnectionStatus.FAILED, f.adapter.connectionStatus.value)
            assertFalse(f.fake.isConnected)
            f.assertNoPromptWrites()
        } finally { f.adapter.disconnect() }
    }

    @Test fun authCloseDuringHeartbeatPreservesAuthenticationRequired() = runTest {
        val f = Fixture(this)
        try {
            connected(f)
            f.fake.autoRespondPing = false
            advanceTimeBy(interval)
            runCurrent()
            f.fake.emitClose(4401, "Synthetic auth rejection")
            runCurrent()
            assertEquals(ConnectionStatus.AUTHENTICATION_REQUIRED, f.adapter.connectionStatus.value)
            assertTrue(f.adapter.terminalFailure.value is GatewayFailure.AuthenticationRequired)
            f.assertNoPromptWrites()
        } finally { f.adapter.disconnect() }
    }

    private suspend fun TestScope.closedDuringAdvertisement(code: Int, expected: ConnectionStatus) {
        val f = Fixture(this, heartbeat = false)
        try {
            f.fake.setRpcHandler("client.capabilities") { _, _ -> f.fake.emitClose(code, "Synthetic close") }
            val opening = async { f.adapter.connect(origin, GatewayTicket("synthetic-ticket", 30)) }
            runCurrent()
            assertFalse(opening.await())
            assertEquals(expected, f.adapter.connectionStatus.value)
            assertFalse(f.fake.isConnected)
            assertFalse(f.adapter.approvalInbox.value.available)
            if (code == 4401 || code == 4403) assertTrue(f.adapter.terminalFailure.value?.isTerminal == true)
            f.assertNoPromptWrites()
        } finally { f.adapter.disconnect() }
    }

    @Test fun normalCloseDuringAdvertisementCannotPublishConnected() = runTest {
        closedDuringAdvertisement(1000, ConnectionStatus.OFFLINE)
    }

    @Test fun abnormalCloseDuringAdvertisementCannotPublishConnected() = runTest {
        closedDuringAdvertisement(1006, ConnectionStatus.FAILED)
    }

    @Test fun authCloseDuringAdvertisementCannotPublishConnected() = runTest {
        closedDuringAdvertisement(4401, ConnectionStatus.AUTHENTICATION_REQUIRED)
    }

    @Test fun policyCloseDuringAdvertisementCannotPublishConnected() = runTest {
        closedDuringAdvertisement(4403, ConnectionStatus.FAILED)
    }

    @Test fun errorDuringAdvertisementCannotPublishConnected() = runTest {
        val f = Fixture(this, heartbeat = false)
        try {
            f.fake.setRpcHandler("client.capabilities") { _, _ -> f.fake.emitError(IllegalStateException("Synthetic transport error")) }
            val opening = async { f.adapter.connect(origin, GatewayTicket("synthetic-ticket", 30)) }
            runCurrent()
            assertFalse(opening.await())
            assertEquals(ConnectionStatus.FAILED, f.adapter.connectionStatus.value)
            assertFalse(f.fake.isConnected)
            assertFalse(f.adapter.approvalInbox.value.available)
        } finally { f.adapter.disconnect() }
    }

    @Test fun advertisementWriteFailureCannotPublishConnected() = runTest {
        val f = Fixture(this, heartbeat = false)
        try {
            f.sendOverride = { text -> if (method(text) == "client.capabilities") false else null }
            val opening = async { f.adapter.connect(origin, GatewayTicket("synthetic-ticket", 30)) }
            runCurrent()
            assertFalse(opening.await())
            assertEquals(ConnectionStatus.FAILED, f.adapter.connectionStatus.value)
            assertFalse(f.fake.isConnected)
        } finally { f.adapter.disconnect() }
    }

    @Test fun advertisementWriteExceptionCannotPublishConnected() = runTest {
        val f = Fixture(this, heartbeat = false)
        try {
            f.sendOverride = { text ->
                if (method(text) == "client.capabilities") throw IllegalStateException("Synthetic write error")
                null
            }
            val opening = async { f.adapter.connect(origin, GatewayTicket("synthetic-ticket", 30)) }
            runCurrent()
            assertFalse(opening.await())
            assertEquals(ConnectionStatus.FAILED, f.adapter.connectionStatus.value)
            assertFalse(f.fake.isConnected)
        } finally { f.adapter.disconnect() }
    }

    @Test fun successfulAdvertisementFollowedByCloseCannotReviveApprovalsOrConnection() = runTest {
        val f = Fixture(this, heartbeat = false)
        try {
            f.fake.setRpcHandler("client.capabilities") { id, _ ->
                f.fake.respondSuccess(id, """{"server_requests":["approval"]}""")
                f.fake.emitClose(1006, "Synthetic close after response")
            }
            val opening = async { f.adapter.connect(origin, GatewayTicket("synthetic-ticket", 30)) }
            runCurrent()
            assertFalse(opening.await())
            assertEquals(ConnectionStatus.FAILED, f.adapter.connectionStatus.value)
            assertFalse(f.adapter.approvalInbox.value.available)
        } finally { f.adapter.disconnect() }
    }

    @Test fun explicitDisconnectDuringAdvertisementKeepsOffline() = runTest {
        val f = Fixture(this, heartbeat = false)
        try {
            f.fake.setRpcHandler("client.capabilities") { _, _ -> f.adapter.disconnect() }
            val opening = async { f.adapter.connect(origin, GatewayTicket("synthetic-ticket", 30)) }
            runCurrent()
            assertFalse(opening.await())
            assertEquals(ConnectionStatus.OFFLINE, f.adapter.connectionStatus.value)
            assertFalse(f.fake.isConnected)
        } finally { f.adapter.disconnect() }
    }

    @Test fun staleCloseDuringAdvertisementCannotInvalidateCurrentAttempt() = runTest {
        val f = Fixture(this, heartbeat = false)
        try {
            connected(f)
            val oldGeneration = f.adapter.connectionGeneration
            f.adapter.disconnect()
            f.fake.setRpcHandler("client.capabilities") { id, _ ->
                f.fake.emitTaggedClose(oldGeneration, 4401, "Synthetic stale auth close")
                f.fake.respondSuccess(id, """{"server_requests":["approval"]}""")
            }
            connected(f)
            assertTrue(f.fake.isConnected)
            assertTrue(f.adapter.approvalInbox.value.available)
            assertNull(f.adapter.terminalFailure.value)
        } finally { f.adapter.disconnect() }
    }

    @Test fun cancelledHeartbeatCleanupCannotClearReplacementRawPongWaiter() = runTest {
        val f = Fixture(this)
        try {
            connected(f)
            f.fake.autoRespondPing = false
            advanceTimeBy(interval)
            runCurrent()
            f.adapter.suspendHeartbeat()
            // Install a new waiter synchronously before the cancelled job resumes.
            val replacement = async(start = CoroutineStart.UNDISPATCHED) { f.adapter.ping() }
            runCurrent()
            f.fake.emitFrame("""{"pong":true}""")
            runCurrent()
            assertTrue(replacement.isCompleted)
            assertTrue(replacement.await() is GatewayResult.Success)
            assertEquals(ConnectionStatus.CONNECTED, f.adapter.connectionStatus.value)
            assertTrue(f.fake.isConnected)
        } finally { f.adapter.disconnect() }
    }

    @Test fun unsupportedOptionalAdvertisementStillAllowsOrdinaryChat() = runTest {
        val f = Fixture(this, heartbeat = false)
        try {
            f.fake.setRpcHandler("client.capabilities") { id, _ -> f.fake.respondError(id, -32601, "Synthetic unsupported method") }
            connected(f)
            assertFalse(f.adapter.approvalInbox.value.available)
            assertNull(f.adapter.terminalFailure.value)
            assertTrue(f.adapter.listSessions() is GatewayResult.Success)
            f.assertNoPromptWrites()
        } finally { f.adapter.disconnect() }
    }

    @Test fun optionalAdvertisementTimeoutOnLiveSocketStillAllowsOrdinaryChat() = runTest {
        val f = Fixture(this, heartbeat = false)
        try {
            f.fake.setRpcHandler("client.capabilities") { _, _ -> }
            val opening = async { f.adapter.connect(origin, GatewayTicket("synthetic-ticket", 30)) }
            runCurrent()
            advanceTimeBy(timeout)
            runCurrent()
            assertTrue(opening.await())
            assertEquals(ConnectionStatus.CONNECTED, f.adapter.connectionStatus.value)
            assertFalse(f.adapter.approvalInbox.value.available)
            assertTrue(f.adapter.listSessions() is GatewayResult.Success)
        } finally { f.adapter.disconnect() }
    }

    @Test fun supersededHeartbeatCannotFailReplacementSocket() = runTest {
        val f = Fixture(this)
        try {
            connected(f)
            f.fake.autoRespondPing = false
            advanceTimeBy(interval)
            runCurrent()
            f.adapter.disconnect()
            f.fake.autoRespondPing = true
            connected(f)
            advanceTimeBy(interval + timeout)
            runCurrent()
            assertEquals(ConnectionStatus.CONNECTED, f.adapter.connectionStatus.value)
            assertTrue(f.fake.isConnected)
        } finally { f.adapter.disconnect() }
    }

    companion object {
        private fun method(text: String) = (GatewayJson.parseOrNull(text) as? JsonObject)?.get("method")?.asStringOrNull()
    }
}
