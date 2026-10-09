package com.hermes.bot.data.gateway.okhttp

import com.hermes.bot.data.gateway.GatewayConnectConfig
import com.hermes.bot.data.gateway.GatewayConnectResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OkHttpGatewayTransportRedirectTest {
    private lateinit var source: MockWebServer
    private lateinit var destination: MockWebServer

    @Before
    fun setUp() {
        source = MockWebServer().also { it.start() }
        destination = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        source.shutdown()
        destination.shutdown()
    }

    @Test
    fun defaultClient_rejectsSameOriginRedirectBeforeSecondWebSocketRequest() = runBlocking {
        source.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", source.url("/redirect-target"))
        )
        source.enqueue(MockResponse().setResponseCode(400))

        val result = connect(OkHttpGatewayTransport.defaultClient(), source.url("/api/ws").toString())

        assertRedirectPolicyFailure(result)
        val firstRequest = source.takeRequest()
        assertEquals("/api/ws", firstRequest.path)
        assertTrue(firstRequest.getHeader("Sec-WebSocket-Protocol")!!.contains(TEST_TICKET_SUBPROTOCOL))
        assertNull("A same-origin redirect target must not receive a WebSocket request", source.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test
    fun injectedClient_rejectsCrossOriginRedirectBeforeTicketExposure() = runBlocking {
        source.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", destination.url("/redirect-target"))
        )
        destination.enqueue(MockResponse().setResponseCode(400))
        val injectedClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val result = connect(injectedClient, source.url("/api/ws").toString())

        assertRedirectPolicyFailure(result)
        val firstRequest = source.takeRequest()
        assertTrue(firstRequest.getHeader("Sec-WebSocket-Protocol")!!.contains(TEST_TICKET_SUBPROTOCOL))
        assertNull("A cross-origin redirect target must not receive a ticket-bearing request", destination.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    private suspend fun connect(client: OkHttpClient, url: String): GatewayConnectResult =
        withTimeout(5_000) {
            OkHttpGatewayTransport(client).connect(
                GatewayConnectConfig(
                    url = url,
                    subprotocols = listOf("hermes-gateway-v1", TEST_TICKET_SUBPROTOCOL)
                )
            )
        }

    private fun assertRedirectPolicyFailure(result: GatewayConnectResult) {
        assertTrue("A redirect must fail rather than connect", result is GatewayConnectResult.Failure)
        result as GatewayConnectResult.Failure
        assertEquals(302, result.handshakeStatusCode)
        assertTrue("A redirect must be classified as a policy failure", result.cause is SecurityException)
    }

    private companion object {
        val TEST_TICKET_SUBPROTOCOL = listOf("hermes-gateway-ticket", "test-ticket").joinToString(".")
    }
}
