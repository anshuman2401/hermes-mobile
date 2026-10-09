package io.github.anshuman2401.hermesmobile.data.gateway.okhttp

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayConnectConfig
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayConnectResult
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTransport
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTransportFrame
import io.github.anshuman2401.hermesmobile.data.gateway.TaggedGatewayTransportFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Production OkHttp-backed WebSocket transport for the certified Hermes Gateway.
 */
class OkHttpGatewayTransport(
    suppliedClient: OkHttpClient = defaultClient()
) : GatewayTransport {

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for long-lived WebSocket
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // Apply the redirect boundary to injected clients as well as the default client.
    private val client: OkHttpClient = suppliedClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val _incoming = MutableSharedFlow<GatewayTransportFrame>(extraBufferCapacity = 512)
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val currentGeneration = AtomicLong(0)

    var activeGeneration: Long
        get() = currentGeneration.get()
        set(value) {
            currentGeneration.set(value)
        }

    override val incoming: Flow<GatewayTransportFrame> = _incoming.asSharedFlow()

    override suspend fun connect(config: GatewayConnectConfig): GatewayConnectResult {
        if (currentGeneration.get() == 0L) {
            currentGeneration.incrementAndGet()
        }
        val generation = currentGeneration.get()
        // Reset socket state for new connection attempt
        try {
            webSocket?.close(1000, "Reconnecting")
        } catch (_: Exception) {}
        isConnected.set(false)

        val wsUrl = when {
            config.url.startsWith("https://", ignoreCase = true) ->
                "wss://" + config.url.substring(8)
            config.url.startsWith("http://", ignoreCase = true) ->
                "ws://" + config.url.substring(7)
            config.url.startsWith("wss://", ignoreCase = true) || config.url.startsWith("ws://", ignoreCase = true) ->
                config.url
            else -> return GatewayConnectResult.Failure(
                IllegalArgumentException("Unsupported protocol in URL: ${config.url}")
            )
        }

        val requestBuilder = Request.Builder().url(wsUrl)

        if (config.subprotocols.isNotEmpty()) {
            requestBuilder.header("Sec-WebSocket-Protocol", config.subprotocols.joinToString(", "))
        }

        for ((headerName, headerValue) in config.headers) {
            requestBuilder.header(headerName, headerValue)
        }

        val connectDeferred = CompletableDeferred<GatewayConnectResult>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != currentGeneration.get()) return
                isConnected.set(true)
                connectDeferred.complete(GatewayConnectResult.Success)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != currentGeneration.get()) return
                _incoming.tryEmit(TaggedGatewayTransportFrame(generation, GatewayTransportFrame.Text(text)))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != currentGeneration.get()) return
                _incoming.tryEmit(TaggedGatewayTransportFrame(generation, GatewayTransportFrame.Close(code, reason)))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != currentGeneration.get()) return
                isConnected.set(false)
                _incoming.tryEmit(TaggedGatewayTransportFrame(generation, GatewayTransportFrame.Close(code, reason)))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != currentGeneration.get()) return
                val statusCode = response?.code
                val cause = if (statusCode in 300..399) {
                    SecurityException("WebSocket redirect rejected by gateway policy.", t)
                } else {
                    t
                }
                isConnected.set(false)
                if (!connectDeferred.isCompleted) {
                    connectDeferred.complete(
                        GatewayConnectResult.Failure(cause = cause, handshakeStatusCode = statusCode)
                    )
                }
                _incoming.tryEmit(TaggedGatewayTransportFrame(generation, GatewayTransportFrame.Error(cause = cause, handshakeStatusCode = statusCode)))
            }
        }

        try {
            webSocket = client.newWebSocket(requestBuilder.build(), listener)
        } catch (e: Exception) {
            return GatewayConnectResult.Failure(cause = e)
        }

        return connectDeferred.await()
    }

    override suspend fun send(text: String): Boolean {
        if (!isConnected.get()) return false
        return webSocket?.send(text) ?: false
    }

    override fun close(code: Int, reason: String?) {
        val gen = currentGeneration.get()
        isConnected.set(false)
        try {
            webSocket?.close(code, reason)
        } catch (_: Exception) {}
        _incoming.tryEmit(TaggedGatewayTransportFrame(gen, GatewayTransportFrame.Close(code, reason ?: "Closed")))
    }
}
