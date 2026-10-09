package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.gateway.json.JsonObject
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotProfilesRpcTest {
    private val dispatcher = StandardTestDispatcher()
    private val origin = CanonicalOrigin.parse("https://test.hermes.local").getOrThrow()

    @Test fun rosterIsOrderedIncludesDefaultAndSendsExplicitFalse() = runTest(dispatcher) {
        val fake = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(fake, dispatcher, rpcTimeoutMs = 100)
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
        advanceUntilIdle()
        var params: JsonObject? = null
        fake.setRpcHandler("profiles.list") { id, input ->
            params = input
            fake.respondSuccess(id, """{"profiles":[{"name":"default","display_name":" Display ","ui_meta":{"hermes-bots":{"title":" Title "}},"has_avatar":true,"sessions":["excluded"]},{"name":" exact ","display_name":" Fallback "},{"name":"third","display_name":" ","ui_meta":{"hermes-bots":{"title":" "}}}]}""")
        }
        val result = adapter.listBotProfiles(origin, adapter.connectionGeneration)
        assertTrue(result is GatewayResult.Success)
        val rows = (result as GatewayResult.Success).value
        assertEquals(listOf("default", " exact ", "third"), rows.map { it.name })
        assertEquals(listOf("Title", "Fallback", "third"), rows.map { it.displayName })
        assertEquals(listOf(true, false, false), rows.map { it.hasAvatar })
        assertEquals(setOf("include_sessions"), params?.map?.keys)
        assertEquals(false, params?.get("include_sessions")?.toAny())
        assertEquals(listOf("gateway.capabilities", "client.capabilities", "profiles.list"), fake.sentFrames.map { method(it) })
        adapter.disconnect()
    }

    @Test fun avatarUsesExactIdentityAndOnlyReadParams() = runTest(dispatcher) {
        val fake = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(fake, dispatcher, rpcTimeoutMs = 100)
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
        advanceUntilIdle()
        var params: JsonObject? = null
        fake.setRpcHandler("profiles.get_asset") { id, input ->
            params = input
            fake.respondSuccess(id, """{"found":true,"mime":"image/png","data":"data:image/png;base64,AQID","size":3}""")
        }
        val result = adapter.getBotAvatar(" exact ", origin, adapter.connectionGeneration)
        assertNotNull(result)
        assertArrayEquals(byteArrayOf(1, 2, 3), result!!.bytes)
        assertEquals("image/png", result.mime)
        assertEquals(setOf("name", "asset"), params?.map?.keys)
        assertEquals(" exact ", params?.get("name")?.asStringOrNull())
        assertEquals("avatar", params?.get("asset")?.asStringOrNull())
        adapter.disconnect()
    }

    @Test fun malformedRostersFailInsteadOfSynthesizingRows() = runTest(dispatcher) {
        val fake = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(fake, dispatcher, rpcTimeoutMs = 100)
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
        advanceUntilIdle()
        for (payload in listOf("{}", "{\"profiles\":{}}", "{\"profiles\":[null]}", "{\"profiles\":[{}]}", "{\"profiles\":[{\"name\":\" \"}]}", "{\"profiles\":[{\"name\":\"default\"},{\"name\":\"default\"}]}")) {
            fake.setRpcHandler("profiles.list") { id, _ -> fake.respondSuccess(id, payload) }
            assertTrue(adapter.listBotProfiles(origin, adapter.connectionGeneration) is GatewayResult.Failure)
        }
        fake.setRpcHandler("profiles.list") { id, _ -> fake.respondSuccess(id, "{\"profiles\":[]}") }
        assertEquals(emptyList<BotProfile>(), (adapter.listBotProfiles(origin, adapter.connectionGeneration) as GatewayResult.Success).value)
        adapter.disconnect()
    }

    @Test fun unsupportedTimeoutDisconnectedAndWrongScopeFailClosed() = runTest(dispatcher) {
        val fake = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(fake, dispatcher, rpcTimeoutMs = 100)
        assertTrue(adapter.listBotProfiles(origin, 0) is GatewayResult.Failure)
        assertNull(adapter.getBotAvatar("default", origin, 0))
        assertTrue(fake.sentFrames.isEmpty())
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
        advanceUntilIdle()
        val count = fake.sentFrames.size
        val wrong = CanonicalOrigin.parse("https://other.hermes.local").getOrThrow()
        assertTrue(adapter.listBotProfiles(wrong, adapter.connectionGeneration) is GatewayResult.Failure)
        assertTrue(adapter.listBotProfiles(origin, adapter.connectionGeneration + 1) is GatewayResult.Failure)
        assertNull(adapter.getBotAvatar("default", wrong, adapter.connectionGeneration))
        assertNull(adapter.getBotAvatar("default", origin, adapter.connectionGeneration + 1))
        assertEquals(count, fake.sentFrames.size)
        fake.setRpcHandler("profiles.list") { id, _ -> fake.respondError(id, -32601, "synthetic unsupported") }
        assertTrue(adapter.listBotProfiles(origin, adapter.connectionGeneration) is GatewayResult.Failure)
        fake.setRpcHandler("profiles.list") { _, _ -> Unit }
        assertTrue(adapter.listBotProfiles(origin, adapter.connectionGeneration) is GatewayResult.Failure)
        assertEquals(0, pendingCount(adapter))
        assertEquals(com.hermes.bot.domain.ConnectionStatus.CONNECTED, adapter.connectionStatus.value)
        adapter.disconnect()
    }

    @Test fun assetsAcceptOnlyBoundedSupportedImagesAndFailuresAreOptional() = runTest(dispatcher) {
        val fake = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(fake, dispatcher, rpcTimeoutMs = 100)
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
        advanceUntilIdle()
        for (mime in listOf("image/png", "image/jpeg", "image/webp")) {
            fake.setRpcHandler("profiles.get_asset") { id, _ -> fake.respondSuccess(id, """{"found":true,"mime":"$mime","data":"AQID"}""") }
            assertArrayEquals(byteArrayOf(1, 2, 3), adapter.getBotAvatar("default", origin, adapter.connectionGeneration)!!.bytes)
        }
        val payloads = listOf(
            "{}", "{\"found\":false}",
            """{"found":true,"mime":"image/svg+xml","data":"AQID"}""",
            """{"found":true,"mime":"image/png","data":"%%%"}""",
            """{"found":true,"mime":"image/png","data":""}""",
            """{"found":true,"data":"AQID"}""",
            """{"found":true,"data":"data:image/png,AQID"}""",
            """{"found":true,"mime":"image/jpeg","data":"data:image/png;base64,AQID"}"""
        )
        for (payload in payloads) {
            fake.setRpcHandler("profiles.get_asset") { id, _ -> fake.respondSuccess(id, payload) }
            assertNull(adapter.getBotAvatar("default", origin, adapter.connectionGeneration))
        }
        val oversized = java.util.Base64.getEncoder().encodeToString(ByteArray(2_000_001))
        fake.setRpcHandler("profiles.get_asset") { id, _ -> fake.respondSuccess(id, """{"found":true,"mime":"image/png","data":"$oversized"}""") }
        assertNull(adapter.getBotAvatar("default", origin, adapter.connectionGeneration)) // unchanged frame gate may time out first
        fake.setRpcHandler("profiles.get_asset") { id, _ -> fake.respondError(id, -32601, "synthetic unsupported") }
        assertNull(adapter.getBotAvatar("default", origin, adapter.connectionGeneration))
        fake.setRpcHandler("profiles.get_asset") { _, _ -> Unit }
        assertNull(adapter.getBotAvatar("default", origin, adapter.connectionGeneration))
        assertEquals(0, pendingCount(adapter))
        adapter.disconnect()
    }

    @Test fun cancelledPreCancelledAndLateTaggedReadsCannotLeavePendingOrSendNewFrames() = runTest(dispatcher) {
        val fake = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(fake, dispatcher, rpcTimeoutMs = 1_000)
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
        advanceUntilIdle()
        val generation = adapter.connectionGeneration
        var lateId = ""
        fake.setRpcHandler("profiles.list") { id, _ -> lateId = id }
        val read = launch { adapter.listBotProfiles(origin, generation) }
        runCurrent()
        assertEquals(1, pendingCount(adapter))
        read.cancel(); read.join()
        assertEquals(0, pendingCount(adapter))
        val count = fake.sentFrames.size
        val cancelled = launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) { adapter.listBotProfiles(origin, generation) }
        cancelled.cancel(); cancelled.join()
        assertEquals(count, fake.sentFrames.size)
        assertEquals(0, pendingCount(adapter))
        fake.emitTaggedFrame(generation, """{"jsonrpc":"2.0","id":"$lateId","result":{"profiles":[{"name":"old"}]}}""")
        fake.setRpcHandler("profiles.list") { id, _ -> fake.respondSuccess(id, "{\"profiles\":[{\"name\":\"default\"}]}") }
        val rows = (adapter.listBotProfiles(origin, generation) as GatewayResult.Success).value
        assertEquals(listOf("default"), rows.map { it.name })
        assertEquals(0, pendingCount(adapter))
        adapter.disconnect()
    }

    @Test fun suspendedBotsSendSerializesConnectionSwitchAndRejectsStaleResponse() = runTest(dispatcher) {
        val fake = FakeGatewayTransport()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val transport = object : GatewayTransport by fake {
            override suspend fun send(text: String): Boolean {
                if (method(text) == "profiles.list") { entered.complete(Unit); release.await() }
                return fake.send(text)
            }
        }
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher, rpcTimeoutMs = 1_000)
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
        advanceUntilIdle()
        var result: GatewayResult<List<BotProfile>>? = null
        val read = launch { result = adapter.listBotProfiles(origin, adapter.connectionGeneration) }
        entered.await()
        val connect = launch { adapter.connect("https://other.hermes.local", GatewayTicket("synthetic-ticket", 30)) }
        runCurrent()
        assertFalse(connect.isCompleted)
        assertEquals("wss://test.hermes.local/api/ws", fake.lastConnectConfig?.url)
        release.complete(Unit)
        connect.join(); read.join()
        assertTrue(result is GatewayResult.Failure)
        assertEquals(0, pendingCount(adapter))
        adapter.disconnect()
    }

    private fun pendingCount(adapter: DefaultGatewayProtocolAdapter): Int =
        (adapter.javaClass.getDeclaredField("pendingRequests").apply { isAccessible = true }.get(adapter) as Map<*, *>).size

    private fun method(frame: String) = (GatewayJson.parseOrNull(frame) as? JsonObject)?.get("method")?.asStringOrNull()
}
