package io.github.anshuman2401.hermesmobile.data.gateway

import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Slice-1 profile routing tests: verifies that session.create includes a
 * `profile` parameter for every non-blank selection, including literal
 * "default", while session.resume retains its existing omission of the
 * default profile. Both omit null/blank values.
 *
 * These tests inspect the actual sent JSON-RPC payloads captured by FakeGatewayTransport.
 * RED first: these tests must fail before the production code is changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionProfileRpcRoutingTest {

    private lateinit var transport: FakeGatewayTransport
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var adapter: DefaultGatewayProtocolAdapter

    private val validTicket = GatewayTicket("safe-test-ticket-routing-001", 30)

    @Before
    fun setUp() {
        transport = FakeGatewayTransport()
        adapter = DefaultGatewayProtocolAdapter(
            transport = transport,
            dispatcher = testDispatcher,
            readyTimeoutMs = 1_000L,
            rpcTimeoutMs = 1_000L
        )
    }

    // -------------------------------------------------------------------------
    // Helper: extract sent RPC params from the last sent frame for `method`
    // -------------------------------------------------------------------------

    private fun lastSentParamsFor(method: String): JsonObject? {
        val frame = transport.sentFrames.lastOrNull { frame ->
            val parsed = GatewayJson.parseOrNull(frame) as? JsonObject ?: return@lastOrNull false
            parsed["method"]?.asStringOrNull() == method
        } ?: return null
        val parsed = GatewayJson.parseOrNull(frame) as? JsonObject ?: return null
        return parsed["params"] as? JsonObject
    }

    // -------------------------------------------------------------------------
    // session.create — non-default profile
    // -------------------------------------------------------------------------

    @Test
    fun createSession_nonDefaultProfile_includesProfileParam() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(title = null, source = "android", closeOnDisconnect = false, profile = "work")
        advanceUntilIdle()

        val params = lastSentParamsFor("session.create")
        val profileValue = params?.get("profile")?.asStringOrNull()
        assertEquals(
            "session.create must include profile=work for non-default selection",
            "work",
            profileValue
        )
    }

    @Test
    fun createSession_nonDefaultProfileMixedCase_includesProfileParamNormalized() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Profile name is not "default" so it should be included as-is
        adapter.createSession(title = null, source = "android", closeOnDisconnect = false, profile = "Research")
        advanceUntilIdle()

        val params = lastSentParamsFor("session.create")
        val profileValue = params?.get("profile")?.asStringOrNull()
        assertEquals(
            "session.create must include profile for non-default non-blank selection",
            "Research",
            profileValue
        )
    }

    // -------------------------------------------------------------------------
    // session.create — literal default is explicit; null/blank omit param
    // -------------------------------------------------------------------------

    @Test
    fun createSession_nullProfile_omitsProfileParam() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(title = null, source = "android", closeOnDisconnect = false, profile = null)
        advanceUntilIdle()

        val params = lastSentParamsFor("session.create")
        assertNull(
            "session.create must omit profile when profile=null",
            params?.get("profile")
        )
    }

    @Test
    fun createSession_defaultProfile_includesLiteralProfileParam() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(title = null, source = "android", closeOnDisconnect = false, profile = "default")
        advanceUntilIdle()

        val params = lastSentParamsFor("session.create")
        assertEquals(
            "session.create must preserve the literal profile=default",
            "default",
            params?.get("profile")?.asStringOrNull()
        )
    }

    @Test
    fun createSession_defaultProfileUpperCase_includesLiteralProfileParam() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(title = null, source = "android", closeOnDisconnect = false, profile = "DEFAULT")
        advanceUntilIdle()

        val params = lastSentParamsFor("session.create")
        assertEquals(
            "session.create must preserve the existing uppercase profile spelling",
            "DEFAULT",
            params?.get("profile")?.asStringOrNull()
        )
    }

    @Test
    fun createSession_blankProfile_omitsProfileParam() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(title = null, source = "android", closeOnDisconnect = false, profile = "")
        advanceUntilIdle()

        val params = lastSentParamsFor("session.create")
        assertNull(
            "session.create must omit profile when profile is blank",
            params?.get("profile")
        )
    }

    // -------------------------------------------------------------------------
    // Existing session.create params unchanged
    // -------------------------------------------------------------------------

    @Test
    fun createSession_nonDefaultProfile_preservesExistingParams() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(
            title = "Test Session",
            source = "android",
            closeOnDisconnect = false,
            profile = "work"
        )
        advanceUntilIdle()

        val params = lastSentParamsFor("session.create")
        assertEquals("android", params?.get("source")?.asStringOrNull())
        assertFalse(params?.get("close_on_disconnect")?.asBooleanOrNull() ?: true)
        assertEquals("Test Session", params?.get("title")?.asStringOrNull())
        assertEquals("work", params?.get("profile")?.asStringOrNull())
    }

    // -------------------------------------------------------------------------
    // session.resume — non-default profile
    // -------------------------------------------------------------------------

    @Test
    fun resumeSession_nonDefaultProfile_includesProfileParam() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.resumeSession(sessionId = "durable-sess-123", profile = "work")
        advanceUntilIdle()

        val params = lastSentParamsFor("session.resume")
        val profileValue = params?.get("profile")?.asStringOrNull()
        assertEquals(
            "session.resume must include profile=work for non-default selection",
            "work",
            profileValue
        )
        // Verify session_id still present
        assertEquals("durable-sess-123", params?.get("session_id")?.asStringOrNull())
    }

    // -------------------------------------------------------------------------
    // session.resume — default/no profile omits param
    // -------------------------------------------------------------------------

    @Test
    fun resumeSession_nullProfile_omitsProfileParam() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.resumeSession(sessionId = "durable-sess-123", profile = null)
        advanceUntilIdle()

        val params = lastSentParamsFor("session.resume")
        assertNull(
            "session.resume must omit profile when profile=null",
            params?.get("profile")
        )
        assertEquals("durable-sess-123", params?.get("session_id")?.asStringOrNull())
    }

    @Test
    fun resumeSession_defaultProfile_omitsProfileParam() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.resumeSession(sessionId = "durable-sess-123", profile = "default")
        advanceUntilIdle()

        val params = lastSentParamsFor("session.resume")
        assertNull(
            "session.resume must omit profile when profile=default",
            params?.get("profile")
        )
    }

    @Test
    fun resumeSession_defaultProfileMixedCase_omitsProfileParam() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.resumeSession(sessionId = "durable-sess-123", profile = "Default")
        advanceUntilIdle()

        val params = lastSentParamsFor("session.resume")
        assertNull(
            "session.resume must omit profile when profile=Default (case-insensitive)",
            params?.get("profile")
        )
    }

    @Test
    fun resumeSession_blankProfile_omitsProfileParam() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.resumeSession(sessionId = "durable-sess-123", profile = "  ")
        advanceUntilIdle()

        val params = lastSentParamsFor("session.resume")
        assertNull(
            "session.resume must omit profile when profile is blank",
            params?.get("profile")
        )
    }

    // -------------------------------------------------------------------------
    // Existing session.resume params unchanged
    // -------------------------------------------------------------------------

    @Test
    fun resumeSession_nonDefaultProfile_preservesExistingParams() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.resumeSession(sessionId = "durable-sess-456", profile = "research")
        advanceUntilIdle()

        val params = lastSentParamsFor("session.resume")
        assertEquals("durable-sess-456", params?.get("session_id")?.asStringOrNull())
        assertEquals("research", params?.get("profile")?.asStringOrNull())
    }

    // -------------------------------------------------------------------------
    // Backward compat: no-profile call signature still works
    // -------------------------------------------------------------------------

    @Test
    fun createSession_noProfileArgument_omitsProfileParam() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Call with default signature (no profile param)
        adapter.createSession(title = null, source = "android", closeOnDisconnect = false)
        advanceUntilIdle()

        val params = lastSentParamsFor("session.create")
        assertNull(
            "session.create must omit profile when called without profile arg",
            params?.get("profile")
        )
    }

    @Test
    fun resumeSession_noProfileArgument_omitsProfileParam() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Call with default signature (no profile param)
        adapter.resumeSession("durable-sess-789")
        advanceUntilIdle()

        val params = lastSentParamsFor("session.resume")
        assertNull(
            "session.resume must omit profile when called without profile arg",
            params?.get("profile")
        )
    }
}
