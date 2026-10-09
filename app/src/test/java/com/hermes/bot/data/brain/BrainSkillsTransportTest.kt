package com.hermes.bot.data.brain

import com.hermes.bot.data.auth.AuthenticatedHttpCall
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainSkillsTransportTest {
    @Test
    fun skillsGetAndToggleUseExactRoutesQueriesMethodsAndBody() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val origin = CanonicalOrigin.parse(server.url("/").toString().removeSuffix("/")).getOrThrow()
            val transport = OkHttpBrainModelReadTransport()
            server.enqueue(MockResponse().setBody("[]"))
            read(transport.openSkillsRead(origin, "/api/skills?profile=worker_alpha", emptyMap(), 5_000))
            server.enqueue(MockResponse().setBody("{\"ok\":true,\"name\":\"demo-one\",\"enabled\":false}"))
            read(
                transport.openSkillsMutation(
                    origin,
                    "/api/skills/toggle?profile=worker_alpha",
                    emptyMap(),
                    "{\"name\":\"demo-one\",\"enabled\":false,\"profile\":\"worker_alpha\"}",
                    5_000
                )
            )

            val get = server.takeRequest()!!
            val put = server.takeRequest()!!
            assertEquals("GET", get.method)
            assertEquals("/api/skills", get.requestUrl?.encodedPath)
            assertEquals("profile=worker_alpha", get.requestUrl?.encodedQuery)
            assertEquals("PUT", put.method)
            assertEquals("/api/skills/toggle", put.requestUrl?.encodedPath)
            assertEquals("profile=worker_alpha", put.requestUrl?.encodedQuery)
            assertEquals(
                "{\"name\":\"demo-one\",\"enabled\":false,\"profile\":\"worker_alpha\"}",
                put.body.readUtf8()
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun contentGetRequiresExactlyOneProfileAndOneNameAndPreservesEncoding() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val origin = CanonicalOrigin.parse(server.url("/").toString().removeSuffix("/")).getOrThrow()
            val transport = OkHttpBrainModelReadTransport()
            server.enqueue(MockResponse().setBody("{\"name\":\"demo one\",\"content\":\"full\",\"path\":\"discard\"}"))
            read(
                transport.openSkillsContentRead(
                    origin,
                    "/api/skills/content?profile=default&name=demo%20one",
                    emptyMap(),
                    5_000
                )
            )
            val request = server.takeRequest()!!
            assertEquals("GET", request.method)
            assertEquals("/api/skills/content", request.requestUrl?.encodedPath)
            assertEquals("profile=default&name=demo%20one", request.requestUrl?.encodedQuery)
            expectIllegalArgument {
                transport.openSkillsContentRead(
                    origin,
                    "/api/skills/content?profile=default&profile=other&name=demo",
                    emptyMap(),
                    1_000
                )
            }
            expectIllegalArgument {
                transport.openSkillsContentRead(
                    origin,
                    "/api/skills/content?profile=default&name=demo&extra=x",
                    emptyMap(),
                    1_000
                )
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun routeAndQueryGuardsRemainFailClosedAndModelMutationPairingIsUnchanged() {
        val server = MockWebServer().apply { start() }
        try {
            val origin = CanonicalOrigin.parse(server.url("/").toString().removeSuffix("/")).getOrThrow()
            val transport = OkHttpBrainModelReadTransport()
            expectIllegalArgument {
                transport.openSkillsRead(origin, "/api/skills/toggle?profile=default", emptyMap(), 1_000)
            }
            expectIllegalArgument {
                transport.openSkillsRead(origin, "/api/skills?profile=default&extra=x", emptyMap(), 1_000)
            }
            expectIllegalArgument {
                transport.openSkillsMutation(
                    origin,
                    "/api/config?profile=default",
                    emptyMap(),
                    "{}",
                    1_000
                )
            }
            expectIllegalArgument {
                transport.openReasoningMutation(
                    origin,
                    "/api/skills/toggle?profile=default",
                    emptyMap(),
                    "{}",
                    1_000
                )
            }
            expectIllegalArgument {
                transport.open(origin, "/api/skills?profile=default", emptyMap(), 1_000)
            }
            assertTrue(true)
        } finally {
            server.shutdown()
        }
    }

    private suspend fun read(call: AuthenticatedHttpCall<BrainModelReadResponse>) {
        val response = call.awaitHeaders()
        response.use { it.readBoundedBody() }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected route guard")
    }
}
