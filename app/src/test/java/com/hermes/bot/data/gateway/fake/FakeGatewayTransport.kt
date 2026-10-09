package com.hermes.bot.data.gateway.fake

import com.hermes.bot.data.gateway.GatewayConnectConfig
import com.hermes.bot.data.gateway.GatewayConnectResult
import com.hermes.bot.data.gateway.GatewayTransport
import com.hermes.bot.data.gateway.GatewayTransportFrame
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.gateway.json.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic fake WebSocket transport simulating certified Hermes host shapes.
 * Enables zero-network, JVM-reproducible testing of the protocol machine and replay reducer.
 */
class FakeGatewayTransport : GatewayTransport {

    private val channel = kotlinx.coroutines.channels.Channel<GatewayTransportFrame>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    override val incoming: Flow<GatewayTransportFrame> = kotlinx.coroutines.flow.flow {
        for (frame in channel) {
            emit(frame)
        }
    }

    val sentFrames = CopyOnWriteArrayList<String>()
    var lastConnectConfig: GatewayConnectConfig? = null
        private set

    var handshakeStatusCode: Int? = null
    var connectException: Throwable? = null

    var autoEmitReady: Boolean = true
    var readyFrameOverride: String? = null

    var autoRespondCapabilities: Boolean = true
    var capabilitiesExclusiveSubmit: Boolean = true
    var omitCapabilities: Boolean = false

    var autoRespondPing: Boolean = true
    var pingRawAckOnly: Boolean = false
    var isConnected: Boolean = false
        private set

    private val customRpcHandlers = mutableMapOf<String, (id: String, params: JsonObject) -> Unit>()

    override suspend fun connect(config: GatewayConnectConfig): GatewayConnectResult {
        lastConnectConfig = config
        // Drain any leftover frames from prior session
        while (channel.tryReceive().isSuccess) {}

        val failureCode = handshakeStatusCode
        if (failureCode != null) {
            return GatewayConnectResult.Failure(
                cause = RuntimeException("HTTP Handshake failed with status $failureCode"),
                handshakeStatusCode = failureCode
            )
        }

        val ex = connectException
        if (ex != null) {
            return GatewayConnectResult.Failure(cause = ex)
        }

        isConnected = true

        if (autoEmitReady) {
            val readyJson = readyFrameOverride ?: FakeGatewayFixtures.READY_FRAME
            channel.trySend(GatewayTransportFrame.Text(readyJson))
        }

        return GatewayConnectResult.Success
    }

    override suspend fun send(text: String): Boolean {
        if (!isConnected) return false
        sentFrames.add(text)

        val json = GatewayJson.parseOrNull(text) as? JsonObject
        if (json != null) {
            val method = json["method"]?.asStringOrNull()
            val id = json["id"]?.asStringOrNull()
            val params = json["params"] as? JsonObject ?: JsonObject()

            if (method != null && id != null) {
                val customHandler = customRpcHandlers[method]
                if (customHandler != null) {
                    customHandler(id, params)
                } else {
                    handleDefaultRpc(method, id, params)
                }
            }
        }

        return true
    }

    override fun close(code: Int, reason: String?) {
        isConnected = false
    }

    fun emitFrame(text: String) {
        channel.trySend(GatewayTransportFrame.Text(text))
    }

    fun emitTaggedFrame(generation: Long, text: String) {
        channel.trySend(com.hermes.bot.data.gateway.TaggedGatewayTransportFrame(generation, GatewayTransportFrame.Text(text)))
    }

    fun emitClose(code: Int, reason: String? = null) {
        isConnected = false
        channel.trySend(GatewayTransportFrame.Close(code, reason))
    }

    fun emitTaggedClose(generation: Long, code: Int, reason: String? = null) {
        channel.trySend(com.hermes.bot.data.gateway.TaggedGatewayTransportFrame(generation, GatewayTransportFrame.Close(code, reason)))
    }

    fun emitError(cause: Throwable, handshakeStatusCode: Int? = null) {
        isConnected = false
        channel.trySend(GatewayTransportFrame.Error(cause, handshakeStatusCode))
    }

    fun emitTaggedError(generation: Long, cause: Throwable, handshakeStatusCode: Int? = null) {
        channel.trySend(com.hermes.bot.data.gateway.TaggedGatewayTransportFrame(generation, GatewayTransportFrame.Error(cause, handshakeStatusCode)))
    }

    fun respondSuccess(id: String, resultRawJson: String) {
        val frame = """{"jsonrpc":"2.0","id":"$id","result":$resultRawJson}"""
        emitFrame(frame)
    }

    fun respondError(id: String, code: Int, message: String, dataRawJson: String? = null) {
        val dataPart = if (dataRawJson != null) ""","data":$dataRawJson""" else ""
        val frame = """{"jsonrpc":"2.0","id":"$id","error":{"code":$code,"message":"$message"$dataPart}}"""
        emitFrame(frame)
    }

    fun setRpcHandler(method: String, handler: (id: String, params: JsonObject) -> Unit) {
        customRpcHandlers[method] = handler
    }

    private fun handleDefaultRpc(method: String, id: String, params: JsonObject) {
        when (method) {
            "client.capabilities" -> respondSuccess(id, """{"server_requests":["approval"]}""")
            "approval.pending" -> respondSuccess(id, """{"approvals":[]}""")
            "approval.received" -> respondSuccess(id, """{"acknowledged":true}""")
            "gateway.capabilities" -> {
                if (omitCapabilities) return
                if (autoRespondCapabilities) {
                    respondSuccess(
                        id,
                        """{"per_session_exclusive_submit":$capabilitiesExclusiveSubmit}"""
                    )
                }
            }
            "gateway.ping" -> {
                if (pingRawAckOnly) {
                    emitFrame("""{"ok":true}""")
                } else if (autoRespondPing) {
                    respondSuccess(id, """{"ok":true}""")
                }
            }
            "session.list" -> {
                respondSuccess(id, FakeGatewayFixtures.SESSION_LIST_RESPONSE)
            }
            "session.create" -> {
                respondSuccess(id, FakeGatewayFixtures.SESSION_CREATE_RESPONSE)
            }
            "session.resume" -> {
                respondSuccess(id, FakeGatewayFixtures.SESSION_RESUME_PERSISTED)
            }
            "prompt.submit" -> {
                respondSuccess(id, FakeGatewayFixtures.PROMPT_SUBMIT_RESPONSE)
            }
            "session.events.since" -> {
                val reqSid = params["session_id"]?.asStringOrNull()
                val response = if (reqSid != null) {
                    FakeGatewayFixtures.REPLAY_SINCE_RESPONSE.replace(FakeGatewayFixtures.TEST_RUNTIME_SID, reqSid)
                } else {
                    FakeGatewayFixtures.REPLAY_SINCE_RESPONSE
                }
                respondSuccess(id, response)
            }
        }
    }
}
