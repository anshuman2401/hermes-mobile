package io.github.anshuman2401.hermesmobile.data.botscreen

import io.github.anshuman2401.hermesmobile.data.bots.BotTarget
import io.github.anshuman2401.hermesmobile.data.bots.DefaultBotRepository
import io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket
import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class BotScreenRepositoryTest {
    private val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
    private val target = BotTarget("local-a", origin.originUrl)
    private val viewer = "synthetic-viewer-id"
    private val hash = MessageDigest.getInstance("SHA-256").digest(viewer.toByteArray())
        .take(6).joinToString("") { "%02x".format(it) }

    private class FakeStream : ScreenStream {
        var opened = 0
        var enabled = false
        var pointers = 0
        var closes = 0
        override fun open(origin: CanonicalOrigin, ticket: String, onFrame: (ScreenFrame) -> Unit, onClosed: () -> Unit) {
            assertEquals("synthetic-display-ticket", ticket)
            opened++
            onFrame(ScreenFrame(2, 1, intArrayOf(0xff000000.toInt(), 0xffffffff.toInt())))
        }
        override fun setInputEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun pointer(x: Int, y: Int, buttons: Int) { if (enabled) pointers++ }
        override fun key(keysym: Int, down: Boolean) = Unit
        override fun close() { closes++; enabled = false }
    }

    @Test fun observeOnceAndGateInputAcrossTakeoverAndHandback() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        var holder = "agent"
        var epoch = 1
        var releases = 0
        fun status() = """{"supported":true,"installed":true,"running":true,"geometry":"1440x900","lease":{"holder":"$holder","viewer_hash":${if (holder == "human") "\"$hash\"" else "null"},"epoch":$epoch}}"""
        transport.setRpcHandler("display.status") { id, _ -> transport.respondSuccess(id, status()) }
        transport.setRpcHandler("display.observe") { id, _ -> transport.respondSuccess(id,
            status().dropLast(1) + """, "ticket":"synthetic-display-ticket","path":"/api/display/ws","viewer_id":"$viewer"}""") }
        transport.setRpcHandler("display.lease.acquire") { id, _ ->
            holder = "human"; epoch++; transport.respondSuccess(id, """{"lease":{"holder":"human","viewer_hash":"$hash","epoch":$epoch}}""")
        }
        transport.setRpcHandler("display.lease.release") { id, _ ->
            releases++; holder = "agent"; epoch++; transport.respondSuccess(id, """{"lease":{"holder":"agent","epoch":$epoch}}""")
        }
        val stream = FakeStream()
        val repo = BotScreenRepository(DefaultBotRepository(adapter), adapter, stream)
        repo.enter(target, "research")
        assertEquals(1, stream.opened)
        repo.refresh(); assertEquals(1, stream.opened) // no duplicate observe before another frame
        repo.pointer(1, 0, 1); assertEquals(0, stream.pointers)
        repo.takeOver(); assertTrue(repo.state.value.holding)
        repo.pointer(1, 0, 1); assertEquals(1, stream.pointers)
        repo.handBack(); assertFalse(repo.state.value.holding)
        repo.pointer(1, 0, 1); assertEquals(1, stream.pointers)
        repo.leave(); assertEquals(1, releases)
    }

    @Test fun leavingAfterLostAcquireReplyReconcilesAndReleasesOwnedLease() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        var holder = "agent"
        var releases = 0
        fun status() = """{"supported":true,"installed":true,"running":true,"geometry":"1440x900","lease":{"holder":"$holder","viewer_hash":${if (holder == "human") "\"$hash\"" else "null"},"epoch":1}}"""
        transport.setRpcHandler("display.status") { id, _ -> transport.respondSuccess(id, status()) }
        transport.setRpcHandler("display.observe") { id, _ -> transport.respondSuccess(id,
            status().dropLast(1) + """, "ticket":"synthetic-display-ticket","path":"/api/display/ws","viewer_id":"$viewer"}""") }
        transport.setRpcHandler("display.lease.release") { id, _ -> releases++; transport.respondSuccess(id,
            """{"lease":{"holder":"agent","epoch":2}}""") }
        val repo = BotScreenRepository(DefaultBotRepository(adapter), adapter, FakeStream())
        repo.enter(target, "research")
        holder = "human" // acquire write reached host, reply did not reach Android
        repo.leave()
        assertEquals(1, releases)
    }

    @Test fun statusStartedBeforeTakeoverCannotRestoreStaleWatchState() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        var statusCalls = 0
        var delayedStatusId: String? = null
        val watchStatus = """{"supported":true,"installed":true,"running":true,"geometry":"1440x900","lease":{"holder":"agent","epoch":1}}"""
        transport.setRpcHandler("display.status") { id, _ ->
            if (++statusCalls == 2) delayedStatusId = id else transport.respondSuccess(id, watchStatus)
        }
        transport.setRpcHandler("display.observe") { id, _ -> transport.respondSuccess(id,
            watchStatus.dropLast(1) + """, "ticket":"synthetic-display-ticket","path":"/api/display/ws","viewer_id":"$viewer"}""") }
        transport.setRpcHandler("display.lease.acquire") { id, _ -> transport.respondSuccess(id,
            """{"lease":{"holder":"human","viewer_hash":"$hash","epoch":2}}""") }
        val repo = BotScreenRepository(DefaultBotRepository(adapter), adapter, FakeStream())
        repo.enter(target, "research")
        launch { repo.refresh() }; runCurrent()
        assertNotNull(delayedStatusId)
        launch { repo.takeOver() }; runCurrent()
        assertTrue(repo.state.value.holding)
        transport.respondSuccess(delayedStatusId!!, watchStatus)
        advanceUntilIdle()
        assertTrue(repo.state.value.holding)
    }
}
