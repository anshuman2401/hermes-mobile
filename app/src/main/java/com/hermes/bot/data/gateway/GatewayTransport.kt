package com.hermes.bot.data.gateway

import kotlinx.coroutines.flow.Flow

/**
 * Transport seam abstracting WebSocket communication.
 * The protocol machine, JSON-RPC correlation, and replay reducer operate over this interface.
 */
interface GatewayTransport {
    val incoming: Flow<GatewayTransportFrame>
    suspend fun connect(config: GatewayConnectConfig): GatewayConnectResult
    suspend fun send(text: String): Boolean
    fun close(code: Int = 1000, reason: String? = null)
}

/**
 * Connection parameters supplied by the adapter to the transport.
 * Diagnostics mask the single-use ticket token.
 */
data class GatewayConnectConfig(
    val url: String,
    val subprotocols: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap()
) {
    override fun toString(): String {
        return "GatewayConnectConfig([REDACTED])"
    }
}

sealed interface GatewayConnectResult {
    data object Success : GatewayConnectResult
    data class Failure(
        val cause: Throwable,
        val handshakeStatusCode: Int? = null
    ) : GatewayConnectResult {
        override fun toString() = "GatewayConnectResult.Failure(handshakeStatusCode=$handshakeStatusCode, [REDACTED])"
    }
}

sealed interface GatewayTransportFrame {
    data class Text(val text: String) : GatewayTransportFrame {
        override fun toString() = "GatewayTransportFrame.Text([REDACTED])"
    }
    data class Close(val code: Int, val reason: String?) : GatewayTransportFrame {
        override fun toString() = "GatewayTransportFrame.Close(code=$code, reason=[REDACTED])"
    }
    data class Error(val cause: Throwable, val handshakeStatusCode: Int? = null) : GatewayTransportFrame {
        override fun toString() = "GatewayTransportFrame.Error(handshakeStatusCode=$handshakeStatusCode, [REDACTED])"
    }
}
