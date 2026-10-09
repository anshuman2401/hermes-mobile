package com.hermes.bot.data.auth

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Bounded regression tests for the OkHttpAuthTransport canonical-origin policy.
 *
 * Root cause context (scoped sessions fix): the transport previously rejected
 * EVERY query-bearing URL (`parsedUrl.query != null` clause), which broke the
 * only query-bearing call in the app — the profile-scoped session list
 * `GET /api/profiles/sessions?profile=<name>&limit=<n>` built by
 * ContextProfileRepository. Query parameters cannot change scheme, host, or
 * port, so same-origin is preserved by the remaining scheme check.
 *
 * These tests prove:
 * - A same-origin query-bearing URL passes the policy and reaches the wire.
 * - Cross-origin URLs, fragment-bearing URLs, and HTTP redirects are STILL
 *   rejected fail-closed (no security regression).
 */
class OkHttpAuthTransportOriginPolicyTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun execute_sameOriginQueryBearingUrl_passesPolicyAndDispatchesRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"sessions":[]}"""))

        val transport = OkHttpAuthTransport(OkHttpClient())
        val url = server.url("/api/profiles/sessions?profile=architect&limit=50").toString()

        val response = transport.execute(method = "GET", url = url, headers = mapOf("Accept" to "application/json"))

        assertEquals(200, response.statusCode)
        assertEquals("""{"sessions":[]}""", response.body)

        val recorded = server.takeRequest()
        assertEquals("/api/profiles/sessions?profile=architect&limit=50", recorded.path)
        assertEquals("GET", recorded.method)
    }

    @Test
    fun execute_queryStringCannotMaskNonCanonicalHost_stillRejected() = runBlocking {
        // A query string is now allowed on same-origin URLs, but it cannot
        // rescue a host that fails canonical-origin parsing: the scheme
        // equality check and CanonicalOrigin.parse still reject it.
        val transport = OkHttpAuthTransport(OkHttpClient())
        // ".:" is not a canonical host -> CanonicalOrigin.parse fails.
        val url = "http://.:${server.port}/path?x=1"

        try {
            transport.execute(method = "GET", url = url)
            org.junit.Assert.fail("Non-canonical host with query string must still be policy-rejected")
        } catch (expected: Exception) {
            assertTrue(
                "Rejection must be IllegalArgumentException (parse) or SecurityException (policy), got: ${expected::class.simpleName}",
                expected is IllegalArgumentException || expected is SecurityException
            )
        }
        assertEquals("No request may reach the wire for a rejected URL", 0, server.requestCount)
    }

    @Test
    fun execute_fragmentBearingUrl_stillRejectedBeforeAnyWireTraffic() = runBlocking {
        val transport = OkHttpAuthTransport(OkHttpClient())
        val url = server.url("/api/profiles#section").toString()

        try {
            transport.execute(method = "GET", url = url)
            org.junit.Assert.fail("Fragment-bearing URL must be rejected by the transport policy")
        } catch (expected: SecurityException) {
            assertEquals(
                "Rejected request URL because its origin is not canonical.",
                expected.message
            )
        }
        assertEquals("No request may reach the wire for a rejected URL", 0, server.requestCount)
    }

    @Test
    fun execute_nonHttpSchemeUrl_rejectedAtParseBeforeAnyWireTraffic() = runBlocking {
        val transport = OkHttpAuthTransport(OkHttpClient())
        // Only http/https parse as HttpUrl schemes; any other scheme is
        // rejected at URL-parse time (fail-closed) before the policy check.
        val url = "ftp://127.0.0.1:${server.port}/api/profiles"

        try {
            transport.execute(method = "GET", url = url)
            org.junit.Assert.fail("Non-http(s) scheme URL must be rejected before any wire traffic")
        } catch (expected: Exception) {
            assertTrue(
                "Rejection must be IllegalArgumentException (parse) or SecurityException (policy), got: ${expected::class.simpleName}",
                expected is IllegalArgumentException || expected is SecurityException
            )
        }
        assertEquals("No request may reach the wire for a rejected URL", 0, server.requestCount)
    }

    @Test
    fun execute_crossOriginRedirectLocation_failsClosedWithoutFollowing() = runBlocking {
        // The redirect fail-closed policy is the cross-origin credential-
        // movement guard (ADR-0003/RPR-003): a 302 whose Location points at a
        // different host must never be followed, even though query strings
        // are now allowed on same-origin requests.
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://other-origin.invalid/steal-credentials?x=1")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("should-not-be-fetched"))

        val transport = OkHttpAuthTransport(OkHttpClient())
        val url = server.url("/api/protected").toString()

        try {
            transport.execute(method = "GET", url = url)
            org.junit.Assert.fail("Cross-origin redirect must fail closed under the exact-origin policy")
        } catch (expected: SecurityException) {
            assertEquals("Redirect rejected by exact-origin policy.", expected.message)
        }
        assertEquals("Only the original request may reach the wire", 1, server.requestCount)
    }

    @Test
    fun execute_redirectResponse_failsClosedWithoutFollowing() = runBlocking {
        // Redirect fail-closed policy: even a same-origin redirect must not be
        // followed and must surface the fixed redirect-policy SecurityException.
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/redirect-target").toString())
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("should-not-be-fetched"))

        val transport = OkHttpAuthTransport(OkHttpClient())
        val url = server.url("/api/protected").toString()

        try {
            transport.execute(method = "GET", url = url)
            org.junit.Assert.fail("Redirect must fail closed under the exact-origin policy")
        } catch (expected: SecurityException) {
            assertEquals("Redirect rejected by exact-origin policy.", expected.message)
        }
        assertEquals("Only the original request may reach the wire", 1, server.requestCount)
    }

    @Test
    fun execute_queryOnCanonicalHttpsUrl_reachesWireWithoutPolicyRejection() = runBlocking {
        // https://127.0.0.1:<port> with a query string is a same-origin-canonical
        // URL by construction (CanonicalOrigin.parse accepts it, scheme matches,
        // no fragment). Post-fix it must pass the pre-wire policy; it can only
        // fail later at TLS/connection time against the plain-socket
        // MockWebServer, never with the origin-policy SecurityException. This
        // proves the query clause removal did not silently depend on the http
        // MockWebServer host shape.
        server.enqueue(MockResponse().setResponseCode(200).setBody("unused"))

        val transport = OkHttpAuthTransport(OkHttpClient())
        val url = "https://127.0.0.1:${server.port}/api/profiles/sessions?profile=a&limit=1"

        try {
            transport.execute(method = "GET", url = url)
            org.junit.Assert.fail("TLS to a plain-socket MockWebServer cannot complete; unexpected success")
        } catch (expected: Exception) {
            org.junit.Assert.assertFalse(
                "Same-origin https+query must NOT be policy-rejected",
                expected.message?.contains("origin is not canonical") == true
            )
        }
    }
}