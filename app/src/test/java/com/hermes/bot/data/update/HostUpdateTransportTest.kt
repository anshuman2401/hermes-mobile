package com.hermes.bot.data.update

import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class HostUpdateTransportTest {
    @Test fun slowForcedCheckDoesNotInheritTheOrdinaryReadTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val origin = CanonicalOrigin.parse(server.url("/").toString().removeSuffix("/")).getOrThrow()
            // Scale the ordinary timeout down; this reproduces delayed host headers without a long test.
            val client = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build()
            val transport = OkHttpHostUpdateTransport(client)
            server.enqueue(MockResponse().setHeadersDelay(1, TimeUnit.SECONDS).setBody("{}"))
            val call = transport.open(origin, "/api/hermes/update/check?force=true&profile=default", "GET", emptyMap())
            call.awaitHeaders().use { response ->
                assertEquals(200, response.statusCode)
                assertEquals("{}", response.body().toString(Charsets.UTF_8))
            }
            assertEquals(1, server.requestCount)
        }
    }
}
